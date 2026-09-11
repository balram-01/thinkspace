package com.thinkspace

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.thinkspace.pdfengine.api.*
import com.thinkspace.pdfengine.core.DefaultPdfDocumentEngine
import com.thinkspace.pdfengine.errors.PdfEngineError
import com.thinkspace.pdfengine.model.*
import com.thinkspace.pdfengine.rendering.RenderedPage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@ReactModule(name = PdfEngineModule.NAME)
class PdfEngineModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  companion object {
    const val NAME = "PdfEngineModule"
    private const val PICK_PDF_REQUEST = 9921

    val openDocuments = ConcurrentHashMap<String, PdfDocument>()
    @Volatile
    var sharedEngine: DefaultPdfDocumentEngine? = null

    fun getOrCreateEngine(context: Context): DefaultPdfDocumentEngine {
      return sharedEngine ?: synchronized(this) {
        sharedEngine ?: DefaultPdfDocumentEngine(context = context.applicationContext).also {
          sharedEngine = it
        }
      }
    }

    fun resolveSource(context: Context, uriOrPath: String, password: String? = null): PdfSource {
      return when {
        uriOrPath.startsWith("content://") -> {
          val uri = Uri.parse(uriOrPath)
          val cr: ContentResolver = context.contentResolver
          PdfSource.FromUri(uri = uri, contentResolver = cr, password = password)
        }
        uriOrPath.startsWith("file://") -> {
          val file = File(Uri.parse(uriOrPath).path ?: uriOrPath.removePrefix("file://"))
          PdfSource.FromFile(file = file, password = password)
        }
        else -> PdfSource.FromFile(file = File(uriOrPath), password = password)
      }
    }
  }

  // ── File picker promise (single-use) ──────────────────────────────────────
  private var pickPdfPromise: Promise? = null

  private val activityEventListener = object : BaseActivityEventListener() {
    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
      if (requestCode != PICK_PDF_REQUEST) return
      val p = pickPdfPromise ?: return
      pickPdfPromise = null
      if (resultCode == Activity.RESULT_OK && data?.data != null) {
        val uri = data.data!!.toString()
        val result = Arguments.createMap().apply {
          putString("uri", uri)
          // Try to get a display name
          val displayName = runCatching {
            val cursor = reactContext.contentResolver.query(data.data!!, arrayOf("_display_name"), null, null, null)
            cursor?.use { c ->
              if (c.moveToFirst()) c.getString(0) else null
            }
          }.getOrNull() ?: uri.substringAfterLast('/')
          putString("name", displayName)
        }
        p.resolve(result)
      } else {
        p.reject("PICK_CANCELLED", "User cancelled the file picker")
      }
    }
  }

  init {
    reactContext.addActivityEventListener(activityEventListener)
  }

  // ── Singleton engine + document registry ──────────────────────────────────

  private val engine: DefaultPdfDocumentEngine
    get() = getOrCreateEngine(reactContext)

  private val docIdCounter = AtomicInteger(0)

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // ── Module name ────────────────────────────────────────────────────────────

  override fun getName(): String = NAME

  override fun invalidate() {
    scope.cancel()
    openDocuments.values.forEach { runCatching { engine.close(it) } }
    openDocuments.clear()
    runCatching { engine.shutdown() }
    super.invalidate()
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private fun resolveSource(uriOrPath: String, password: String?): PdfSource {
    return Companion.resolveSource(reactContext, uriOrPath, password)
  }

  private fun getDoc(docId: String, promise: Promise): PdfDocument? {
    val doc = openDocuments[docId]
    if (doc == null) {
      promise.reject("DOC_NOT_FOUND", "No open document with id: $docId")
    }
    return doc
  }

  private fun boundsToMap(b: BoundingBox): WritableMap = Arguments.createMap().apply {
    putDouble("left", b.left.toDouble())
    putDouble("top", b.top.toDouble())
    putDouble("right", b.right.toDouble())
    putDouble("bottom", b.bottom.toDouble())
    putDouble("width", (b.right - b.left).toDouble())
    putDouble("height", (b.bottom - b.top).toDouble())
  }

  private fun quadToMap(q: Quad): WritableMap = Arguments.createMap().apply {
    fun pointMap(p: Point) = Arguments.createMap().apply {
      putDouble("x", p.x.toDouble())
      putDouble("y", p.y.toDouble())
    }
    putMap("topLeft", pointMap(q.topLeft))
    putMap("topRight", pointMap(q.topRight))
    putMap("bottomRight", pointMap(q.bottomRight))
    putMap("bottomLeft", pointMap(q.bottomLeft))
  }

  // ── 1. openDocument ───────────────────────────────────────────────────────

  @ReactMethod
  fun openDocument(uriOrPath: String, password: String?, promise: Promise) {
    scope.launch {
      try {
        val source = resolveSource(uriOrPath, password.takeIf { !it.isNullOrEmpty() })
        val document = engine.open(source)
        val docId = "pdf_doc_${docIdCounter.incrementAndGet()}"
        openDocuments[docId] = document

        val result = Arguments.createMap().apply {
          putString("documentId", docId)
          putInt("pageCount", document.pageCount)
          putString("title", document.metadata.title ?: "")
          putString("author", document.metadata.author ?: "")
          putString("subject", document.metadata.subject ?: "")
          putBoolean("isEncrypted", document.metadata.isEncrypted)
          putString("pdfVersion", document.metadata.pdfVersion ?: "")
        }
        promise.resolve(result)
      } catch (e: PdfEngineError) {
        promise.reject("PDF_ENGINE_ERROR", e.message ?: "Failed to open document", e)
      } catch (e: Exception) {
        promise.reject("OPEN_FAILED", e.message ?: "Unexpected error", e)
      }
    }
  }

  // ── 2. extractText ────────────────────────────────────────────────────────

  @ReactMethod
  fun extractText(docId: String, pageIndex: Int, promise: Promise) {
    scope.launch {
      val doc = getDoc(docId, promise) ?: return@launch
      try {
        val words: List<TextWord> = engine.extractText(doc, pageIndex).filterIsInstance<TextWord>()
        val wordsArr = Arguments.createArray()
        for (word in words) {
          val wMap = Arguments.createMap().apply {
            putString("text", word.text)
            putMap("bounds", boundsToMap(word.bounds))
            putDouble("fontSize", word.fontSize.toDouble())
            putString("fontName", word.fontName)
            putBoolean("isBold", word.fontStyle == FontStyle.BOLD || word.fontStyle == FontStyle.BOLD_ITALIC)
            putBoolean("isItalic", word.fontStyle == FontStyle.ITALIC || word.fontStyle == FontStyle.BOLD_ITALIC)
            putDouble("baseline", word.baseline.toDouble())
            putInt("orderIndex", word.orderIndex)
            putInt("pageIndex", word.pageIndex)
          }
          wordsArr.pushMap(wMap)
        }
        val result = Arguments.createMap().apply {
          putInt("pageIndex", pageIndex)
          putArray("words", wordsArr)
        }
        promise.resolve(result)
      } catch (e: Exception) {
        promise.reject("EXTRACT_FAILED", e.message ?: "Text extraction failed", e)
      }
    }
  }

  // ── 3. analyzePage ────────────────────────────────────────────────────────

  @ReactMethod
  fun analyzePage(docId: String, pageIndex: Int, promise: Promise) {
    scope.launch {
      val doc = getDoc(docId, promise) ?: return@launch
      try {
        val structure: PageStructure = engine.analyzePage(doc, pageIndex)

        fun wordToMap(w: TextWord) = Arguments.createMap().apply {
          putString("text", w.text)
          putMap("bounds", boundsToMap(w.bounds))
          putDouble("fontSize", w.fontSize.toDouble())
          putInt("orderIndex", w.orderIndex)
        }

        fun lineToMap(line: TextLine) = Arguments.createMap().apply {
          putString("text", line.text)
          putMap("bounds", boundsToMap(line.bounds))
          putDouble("averageFontSize", line.averageFontSize.toDouble())
          putInt("orderIndex", line.orderIndex)
          val wa = Arguments.createArray()
          line.words.forEach { wa.pushMap(wordToMap(it)) }
          putArray("words", wa)
        }

        fun blockToMap(block: TextBlock) = Arguments.createMap().apply {
          putString("id", block.id)
          putString("text", block.text)
          putMap("bounds", boundsToMap(block.bounds))
          putBoolean("isHeading", block.isHeading)
          putInt("columnIndex", block.columnIndex)
          putInt("orderIndex", block.orderIndex)
          val la = Arguments.createArray()
          block.lines.forEach { la.pushMap(lineToMap(it)) }
          putArray("lines", la)
        }

        val blocksArr = Arguments.createArray()
        structure.blocks.forEach { blocksArr.pushMap(blockToMap(it)) }

        val linesArr = Arguments.createArray()
        structure.lines.forEach { linesArr.pushMap(lineToMap(it)) }

        val result = Arguments.createMap().apply {
          putInt("pageIndex", pageIndex)
          putInt("columnCount", structure.columnCount)
          putArray("blocks", blocksArr)
          putArray("lines", linesArr)
          putDouble("pageWidth", structure.bounds.width.toDouble())
          putDouble("pageHeight", structure.bounds.height.toDouble())
          putBoolean("isScanned", structure.isScanned)
        }
        promise.resolve(result)
      } catch (e: Exception) {
        promise.reject("ANALYZE_FAILED", e.message ?: "Page analysis failed", e)
      }
    }
  }

  // ── 4. searchDocument ─────────────────────────────────────────────────────

  @ReactMethod
  fun searchDocument(docId: String, query: String, promise: Promise) {
    scope.launch {
      val doc = getDoc(docId, promise) ?: return@launch
      try {
        val results: List<SearchResult> = engine.search(doc, query)
        val arr = Arguments.createArray()
        for (r in results) {
          val quadsArr = Arguments.createArray()
          r.quads.forEach { quadsArr.pushMap(quadToMap(it)) }
          val rMap = Arguments.createMap().apply {
            putInt("pageIndex", r.pageIndex)
            putString("matchedText", r.matchedText)
            putMap("bounds", boundsToMap(r.bounds))
            putString("context", r.context)
            putInt("startOffset", r.startOffset)
            putInt("endOffset", r.endOffset)
            putArray("quads", quadsArr)
            putBoolean("isFromOcr", r.isFromOcr)
          }
          arr.pushMap(rMap)
        }
        promise.resolve(arr)
      } catch (e: Exception) {
        promise.reject("SEARCH_FAILED", e.message ?: "Search failed", e)
      }
    }
  }

  // ── 5. renderPage ─────────────────────────────────────────────────────────
  // Returns a temp file URI — avoids large base64 payload on the bridge.

  @ReactMethod
  fun renderPage(docId: String, pageIndex: Int, scale: Double, promise: Promise) {
    scope.launch {
      val doc = getDoc(docId, promise) ?: return@launch
      var renderedPage: RenderedPage? = null
      try {
        val options = RenderOptions(scale = scale.toFloat())
        renderedPage = engine.renderPage(doc, pageIndex, options)
        val bitmap = renderedPage.bitmap

        // Write bitmap to a temp PNG file in app cache dir
        val cacheDir = reactContext.cacheDir
        val tempFile = File(cacheDir, "pdf_page_${docId}_${pageIndex}_${System.currentTimeMillis()}.png")
        FileOutputStream(tempFile).use { fos ->
          bitmap.compress(Bitmap.CompressFormat.PNG, 95, fos)
        }

        val result = Arguments.createMap().apply {
          putString("uri", "file://${tempFile.absolutePath}")
          putInt("width", bitmap.width)
          putInt("height", bitmap.height)
          putDouble("pageWidth", renderedPage.renderedBounds.width.toDouble())
          putDouble("pageHeight", renderedPage.renderedBounds.height.toDouble())
          putDouble("scale", scale)
          putInt("pageIndex", pageIndex)
        }
        promise.resolve(result)
      } catch (e: Exception) {
        promise.reject("RENDER_FAILED", e.message ?: "Page rendering failed", e)
      } finally {
        // Return bitmap to pool (deterministic cleanup)
        renderedPage?.close()
      }
    }
  }

  // ── 5b. renderPageRegion (Crop / Figure extraction) ───────────────────────

  @ReactMethod
  fun renderPageRegion(
    docId: String,
    pageIndex: Int,
    left: Double,
    top: Double,
    right: Double,
    bottom: Double,
    scale: Double,
    promise: Promise
  ) {
    scope.launch {
      val doc = getDoc(docId, promise) ?: return@launch
      var renderedPage: RenderedPage? = null
      try {
        val vp = BoundingBox(
          left = minOf(left, right).toFloat(),
          top = minOf(top, bottom).toFloat(),
          right = maxOf(left, right).toFloat(),
          bottom = maxOf(top, bottom).toFloat()
        )
        val options = RenderOptions(scale = scale.toFloat(), viewport = vp)
        renderedPage = engine.renderPage(doc, pageIndex, options)
        val bitmap = renderedPage.bitmap

        val cacheDir = reactContext.cacheDir
        val tempFile = File(cacheDir, "pdf_crop_${docId}_${pageIndex}_${System.currentTimeMillis()}.png")
        FileOutputStream(tempFile).use { fos ->
          bitmap.compress(Bitmap.CompressFormat.PNG, 95, fos)
        }

        val result = Arguments.createMap().apply {
          putString("uri", "file://${tempFile.absolutePath}")
          putInt("width", bitmap.width)
          putInt("height", bitmap.height)
          putInt("pageIndex", pageIndex)
        }
        promise.resolve(result)
      } catch (e: Exception) {
        promise.reject("RENDER_FAILED", e.message ?: "Page region rendering failed", e)
      } finally {
        renderedPage?.close()
      }
    }
  }

  // ── 6. getSelectionGeometry ───────────────────────────────────────────────

  @ReactMethod
  fun getSelectionGeometry(
    docId: String,
    pageIndex: Int,
    startX: Double,
    startY: Double,
    endX: Double,
    endY: Double,
    promise: Promise
  ) {
    scope.launch {
      val doc = getDoc(docId, promise) ?: return@launch
      try {
        val request = TextSelectionRequest.PointRange(
          pageIndex = pageIndex,
          startPoint = Point(startX.toFloat(), startY.toFloat()),
          endPoint = Point(endX.toFloat(), endY.toFloat())
        )
        val selection: TextSelection = engine.getSelectionGeometry(doc, request)

        val quadsArr = Arguments.createArray()
        selection.quads.forEach { quadsArr.pushMap(quadToMap(it)) }

        val pageSelectionsArr = Arguments.createArray()
        selection.pageSelections.forEach { ps ->
          val psMap = Arguments.createMap().apply {
            putInt("pageIndex", ps.pageIndex)
            putString("text", ps.text)
            putMap("bounds", boundsToMap(ps.bounds))
            val pq = Arguments.createArray()
            ps.quads.forEach { pq.pushMap(quadToMap(it)) }
            putArray("quads", pq)
          }
          pageSelectionsArr.pushMap(psMap)
        }

        val result = Arguments.createMap().apply {
          putString("text", selection.text)
          putInt("startPage", selection.startPage)
          putInt("endPage", selection.endPage)
          putMap("bounds", boundsToMap(selection.bounds))
          putArray("quads", quadsArr)
          putArray("pageSelections", pageSelectionsArr)
        }
        promise.resolve(result)
      } catch (e: Exception) {
        promise.reject("SELECTION_FAILED", e.message ?: "Selection geometry failed", e)
      }
    }
  }

  // ── 7. processDocument (full batch processing with options) ────────────────

  @ReactMethod
  fun processDocument(docId: String, options: ReadableMap?, promise: Promise) {
    scope.launch {
      val doc = getDoc(docId, promise) ?: return@launch
      try {
        val enableOcr = options?.getBoolean("enableOcr") ?: false
        val extractImages = options?.getBoolean("extractImages") ?: false
        val startPage = options?.getInt("startPageIndex") ?: 0
        val pageLimit = if (options?.hasKey("pageLimit") == true) options.getInt("pageLimit") else null

        val processingOptions = ProcessingOptions(
          extractImages = extractImages,
          performLayoutAnalysis = true,
          enableOcr = enableOcr,
          indexForSearch = true,
          startPageIndex = startPage,
          pageLimit = pageLimit
        )

        val processingResult: ProcessingResult = engine.process(doc, processingOptions)

        val result = Arguments.createMap().apply {
          putInt("totalPages", processingResult.totalPages)
          putInt("processedPages", processingResult.successfulPages)
          putLong("totalDurationMs", processingResult.totalDurationMs)
          putBoolean("isFullyIndexed", processingResult.isFullyIndexed)
        }
        promise.resolve(result)
      } catch (e: Exception) {
        promise.reject("PROCESS_FAILED", e.message ?: "Document processing failed", e)
      }
    }
  }

  // ── 8. pickPdfFile ────────────────────────────────────────────────────────

  @ReactMethod
  fun pickPdfFile(promise: Promise) {
    val activity = reactContext.currentActivity
    if (activity == null) {
      promise.reject("NO_ACTIVITY", "No Activity available")
      return
    }
    if (pickPdfPromise != null) {
      promise.reject("PICK_IN_PROGRESS", "A file pick is already in progress")
      return
    }
    pickPdfPromise = promise
    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
      type = "application/pdf"
      addCategory(Intent.CATEGORY_OPENABLE)
      putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "application/octet-stream"))
    }
    activity.startActivityForResult(
      Intent.createChooser(intent, "Select PDF"),
      PICK_PDF_REQUEST
    )
  }

  // ── 9. closeDocument ──────────────────────────────────────────────────────


  @ReactMethod
  fun closeDocument(docId: String, promise: Promise) {
    scope.launch {
      val doc = openDocuments.remove(docId)
      if (doc == null) {
        promise.reject("DOC_NOT_FOUND", "No open document with id: $docId")
        return@launch
      }
      try {
        engine.close(doc)
        promise.resolve(null)
      } catch (e: Exception) {
        promise.reject("CLOSE_FAILED", e.message ?: "Failed to close document", e)
      }
    }
  }

  // ── 9. shutdown ───────────────────────────────────────────────────────────

  @ReactMethod
  fun shutdown(promise: Promise) {
    scope.launch {
      try {
        openDocuments.values.forEach { runCatching { engine.close(it) } }
        openDocuments.clear()
        engine.shutdown()
        promise.resolve(null)
      } catch (e: Exception) {
        promise.reject("SHUTDOWN_FAILED", e.message ?: "Shutdown failed", e)
      }
    }
  }
}
