package com.thinkspace

import android.graphics.Color
import android.graphics.RectF
import com.facebook.react.bridge.Arguments
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.EventDispatcher
import com.thinkspace.engine.models.NativeAnnotation
import com.thinkspace.engine.models.NativeCard
import com.thinkspace.engine.models.NativeDoc
import com.thinkspace.engine.models.NativeLink
import com.thinkspace.engine.models.NativePoint
import com.thinkspace.engine.models.NativeSection
import com.thinkspace.engine.models.NativeStroke
import com.thinkspace.engine.models.ThinkspaceEvent
import com.thinkspace.engine.models.WorkspaceDocumentEntry
import com.thinkspace.engine.models.WorkspaceFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Extension functions for ThinkspaceView: Document configuration, multi-document switching,
 * folder management, and hydration from JSON (annotations, strokes, cards, links).
 */
  // ---------------------------------------------------------------------------
  // Document Configuration
  // ---------------------------------------------------------------------------

fun ThinkspaceView.setDocumentFromJson(json: String?) {
    if (json.isNullOrEmpty()) {
      activePdfDoc = null
      activeDocument = null
      invalidate()
      return
    }
    try {
      val obj = JSONObject(json)
      val id = obj.optString("id", obj.optString("documentId", "default-doc")).ifEmpty { "default-doc" }
      activeDocumentId = id
      val uri = obj.optString("uri", "")
      val title = obj.optString("title", "Document")
      val pageCount = obj.optInt("pageCount", 1)

      // 1. Try to find an already opened PDF in PdfEngineModule
      val existingPdf = PdfEngineModule.openDocuments[id]
      if (existingPdf != null) {
        activePdfDoc = existingPdf
        activeDocument = null
        pageBitmaps.evictAll()
        pageWordsCache.clear()
        docScrollY = 0f
        invalidate()
        return
      }

      // 2. If URI provided, open via DefaultPdfDocumentEngine asynchronously
      if (uri.isNotEmpty()) {
        renderScope.launch(Dispatchers.IO) {
          try {
            val engine = PdfEngineModule.getOrCreateEngine(context)
            val source = PdfEngineModule.resolveSource(context, uri)
            val doc = engine.open(source)
            PdfEngineModule.openDocuments[id] = doc
            withContext(Dispatchers.Main) {
              activePdfDoc = doc
              activeDocument = null
              pageBitmaps.evictAll()
              pageWordsCache.clear()
              docScrollY = 0f
              invalidate()
            }
          } catch (e: Exception) {
            e.printStackTrace()
          }
        }
      }

      // 3. Fallback to structured document sections (for demo/preloaded text)
      val secList = mutableListOf<NativeSection>()
      val secArr = obj.optJSONArray("sections")
      if (secArr != null) {
        for (i in 0 until secArr.length()) {
          val sObj = secArr.getJSONObject(i)
          val sId = sObj.optString("id", "sec-$i")
          val pageNumber = sObj.optInt("pageNumber", i + 1)
          val heading = sObj.optString("heading", "Chapter $i")
          val pArr = sObj.optJSONArray("paragraphs")
          val pList = mutableListOf<String>()
          if (pArr != null) {
            for (j in 0 until pArr.length()) pList.add(pArr.getString(j))
          }
          secList.add(NativeSection(sId, pageNumber, heading, pList, null, null))
        }
      }
      activeDocument = NativeDoc(id, title, pageCount, secList)
    } catch (e: Exception) {
      e.printStackTrace()
    }
    invalidate()
  }

  // ── Multi-Document Engine Methods ────────────────────────────────────────────

  /**
   * Called when the React Native layer passes a new workspace documents list.
   * Registers all document entries in workspaceDocumentEntries.
   * Does NOT immediately open all PDFs — only the active one is opened eagerly;
   * others are opened lazily when switchToDocument() is called.
   *
   * @param json JSON array of WorkspaceDocumentEntry objects.
   */
fun ThinkspaceView.setWorkspaceDocumentsFromJson(json: String?) {
    if (json.isNullOrEmpty()) return
    try {
      val arr = JSONArray(json)
      val incoming = mutableListOf<WorkspaceDocumentEntry>()
      val palette = docColorPalette

      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("id", "doc-$i")
        val title = obj.optString("title", "Document")
        val pageCount = obj.optInt("pageCount", 1)
        val uri = obj.optString("uri", "")
        // Assign a color from palette if not provided, cycling by index
        val existingAccent = workspaceDocumentEntries.find { it.id == id }?.colorAccent
        val colorHex = obj.optString("colorAccent", "").takeIf { it.isNotEmpty() }
          ?: existingAccent
          ?: "#%06X".format(palette[i % palette.size] and 0xFFFFFF)
        val folderId = obj.optString("folderId", "").takeIf { it.isNotEmpty() }
        incoming.add(WorkspaceDocumentEntry(id, title, pageCount, uri, colorHex, folderId))
      }

      // Preserve ordering: update existing entries, add new ones
      workspaceDocumentEntries.clear()
      workspaceDocumentEntries.addAll(incoming)

      // For each entry, check if already in PdfEngineModule registry
      for (entry in incoming) {
        val existing = PdfEngineModule.openDocuments[entry.id]
        if (existing != null) {
          documentRegistry[entry.id] = existing
        }
      }

      // If no active doc is set yet, activate the first one
      if (activeDocumentId.isEmpty() && incoming.isNotEmpty()) {
        switchToDocument(incoming[0].id)
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  /**
   * Called when the React Native layer passes workspace folders for hierarchical organization.
   *
   * @param json JSON array of WorkspaceFolder objects.
   */
fun ThinkspaceView.setWorkspaceFoldersFromJson(json: String?) {
    if (json.isNullOrEmpty()) return
    try {
      val arr = JSONArray(json)
      workspaceFolders.clear()
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("id", "folder-$i")
        val name = obj.optString("name", "New Folder")
        val parentId = obj.optString("parentId", "").takeIf { it.isNotEmpty() }
        val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
        workspaceFolders.add(WorkspaceFolder(id, name, parentId, createdAt))
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  /**
   * Switch the active PDF/document viewport to the given document ID.
   * The workspace canvas (cards, strokes, ink, notes) is NOT affected.
   * Only the document pane changes.
   *
   * If the document is already opened in the registry, switching is instant.
   * If not yet opened, opens it asynchronously from the stored URI.
   *
   * @param docId The document ID to activate.
   */
fun ThinkspaceView.switchToDocument(docId: String) {
    if (docId == activeDocumentId && activePdfDoc != null) return // Already active

    activeDocumentId = docId

    // Check registry first (instant switch)
    val cached = documentRegistry[docId] ?: PdfEngineModule.openDocuments[docId]
    if (cached != null) {
      documentRegistry[docId] = cached
      activePdfDoc = cached
      activeDocument = null
      // Clear per-page caches for the previous document's bitmaps
      pageBitmaps.evictAll()
      pageWordsCache.clear()
      compressionEngine.resetAllToNormal(animate = false) {}
      docScrollY = 0f
      val targetPage = pendingScrollToPage
      val targetRects = pendingPulseRects
      pendingScrollToPage = null
      pendingPulseRects = null
      if (targetPage != null) {
        post { scrollToDocumentPage(targetPage, targetRects ?: emptyList()) }
      }
      invalidate()
      return
    }

    // Not yet open — find the entry and open from URI asynchronously
    val entry = workspaceDocumentEntries.find { it.id == docId }
    if (entry != null && entry.uri.isNotEmpty()) {
      renderScope.launch(Dispatchers.IO) {
        try {
          val engine = PdfEngineModule.getOrCreateEngine(context)
          val source = PdfEngineModule.resolveSource(context, entry.uri)
          val doc = engine.open(source)
          PdfEngineModule.openDocuments[docId] = doc
          documentRegistry[docId] = doc
          withContext(Dispatchers.Main) {
            if (activeDocumentId == docId) {
              activePdfDoc = doc
              activeDocument = null
              pageBitmaps.evictAll()
              pageWordsCache.clear()
              compressionEngine.resetAllToNormal(animate = false) {}
              docScrollY = 0f
              val targetPage = pendingScrollToPage
              val targetRects = pendingPulseRects
              pendingScrollToPage = null
              pendingPulseRects = null
              if (targetPage != null) {
                post { scrollToDocumentPage(targetPage, targetRects ?: emptyList()) }
              }
              invalidate()
            }
          }
        } catch (e: Exception) {
          e.printStackTrace()
          withContext(Dispatchers.Main) {
            hudToast.show("Document could not be opened")
          }
        }
      }
    }
  }

  /**
   * Dispatch a React Native event requesting the RN layer to switch to a specific
   * document and page. Called when the user taps the source badge on a card.
   *
   * @param cardId   The card whose source badge was tapped.
   * @param docId    The source document ID.
   * @param pageNum  The source page number (1-based).
   */
internal fun ThinkspaceView.dispatchRequestDocumentSwitchEvent(cardId: String, docId: String, pageNum: Int) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val dispatcher: EventDispatcher? = getEventDispatcher()
    val data = Arguments.createMap().apply {
      putString("documentId", docId)
      putInt("sourcePageNumber", pageNum)
      putString("cardId", cardId)
    }
    dispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topRequestDocumentSwitch", data))
  }

  // ── End Multi-Document Engine Methods ────────────────────────────────────────

fun ThinkspaceView.setAnnotationsFromJson(json: String?) {
    if (json.isNullOrEmpty()) {
      return
    }
    try {
      val arr = JSONArray(json)
      if (arr.length() == 0 && annotations.isNotEmpty()) {
        return
      }
      val incoming = mutableListOf<NativeAnnotation>()
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("id", "ann-$i")
        val sectionId = obj.optString("sectionId", "")
        val pIdx = obj.optInt("paragraphIndex", 0)
        val pageNumber = obj.optInt("pageNumber", 1)
        val colorHex = obj.optString("color", "#00ADB5")
        val color = try { Color.parseColor(colorHex) } catch (e: Exception) { Color.YELLOW }
        val text = obj.optString("text", "")
        val rects = mutableListOf<RectF>()
        val rectsArr = obj.optJSONArray("rects")
        if (rectsArr != null) {
          for (rIdx in 0 until rectsArr.length()) {
            val ro = rectsArr.getJSONObject(rIdx)
            rects.add(RectF(
              ro.optDouble("left", 0.0).toFloat(),
              ro.optDouble("top", 0.0).toFloat(),
              ro.optDouble("right", 0.0).toFloat(),
              ro.optDouble("bottom", 0.0).toFloat()
            ))
          }
        }
        incoming.add(NativeAnnotation(id, sectionId, pIdx, pageNumber, color, text, rects))
      }
      val incomingIds = incoming.map { it.id }.toSet()
      val localOnly = annotations.filter { !incomingIds.contains(it.id) }
      annotations.clear()
      annotations.addAll(incoming)
      annotations.addAll(localOnly)
    } catch (e: Exception) {
      e.printStackTrace()
    }
    invalidate()
  }

fun ThinkspaceView.setStrokesFromJson(json: String?) {
    strokes.clear()
    if (json.isNullOrEmpty()) {
      invalidate()
      return
    }
    try {
      val arr = JSONArray(json)
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("id", "stroke-$i")
        val colorHex = obj.optString("color", "#00ADB5")
        val color = try { Color.parseColor(colorHex) } catch (e: Exception) { Color.WHITE }
        val strokeWidth = obj.optDouble("strokeWidth", 3.5).toFloat()
        val isHighlighter = obj.optBoolean("isHighlighter", false)

        val ptsArr = obj.optJSONArray("points")
        val pts = mutableListOf<NativePoint>()
        if (ptsArr != null) {
          for (j in 0 until ptsArr.length()) {
            val ptObj = ptsArr.getJSONObject(j)
            pts.add(NativePoint(ptObj.getDouble("x").toFloat(), ptObj.getDouble("y").toFloat()))
          }
        }
        strokes.add(NativeStroke(id, pts, color, strokeWidth, isHighlighter))
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
    invalidate()
  }

fun ThinkspaceView.setCardsFromJson(json: String?) {
    if (json.isNullOrEmpty()) {
      return
    }
    try {
      val arr = JSONArray(json)
      if (arr.length() == 0 && cards.isNotEmpty()) {
        return
      }

      val updatedList = mutableListOf<NativeCard>()
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("id", "card-$i")
        var existing = cards.find { it.id == id }
        val isImage = obj.optBoolean("isImage", existing?.isImage ?: false)
        val pageNumber = obj.optInt("pageNumber", existing?.pageNumber ?: 1)
        if (existing == null && isImage) {
          existing = cards.find { it.isImage && it.pageNumber == pageNumber && !it.imageUrl.isNullOrEmpty() }
        }
        val text = obj.optString("text", existing?.text ?: "")
        if (existing == null && text.isNotEmpty()) {
          existing = cards.find { it.pageNumber == pageNumber && it.text == text }
        }

        val x = if (obj.has("x") && obj.getDouble("x") != 0.0) obj.getDouble("x").toFloat() else (existing?.x ?: 60f)
        val y = if (obj.has("y") && obj.getDouble("y") != 0.0) obj.getDouble("y").toFloat() else (existing?.y ?: 60f)
        val width = obj.optDouble("width", (existing?.width ?: 220f).toDouble()).toFloat()
        val colorHex = obj.optString("color", "#00ADB5")
        val color = try { Color.parseColor(colorHex) } catch (e: Exception) { existing?.color ?: Color.WHITE }
        val comment = if (obj.has("comment") && !obj.isNull("comment")) obj.getString("comment") else existing?.comment
        val clusterId = if (obj.has("clusterId") && !obj.isNull("clusterId")) obj.getString("clusterId") else existing?.clusterId
        val stackCount = obj.optInt("stackCount", existing?.stackCount ?: 1)
        val imageUrl = if (obj.has("imageUrl") && !obj.isNull("imageUrl") && obj.getString("imageUrl").isNotEmpty()) {
          obj.getString("imageUrl")
        } else {
          existing?.imageUrl
        }
        val isTable = obj.optBoolean("isTable", existing?.isTable ?: false)

        // Cache association for image card bitmap
        if (isImage) {
          val cachedBmp = (if (!imageUrl.isNullOrEmpty()) cardBitmapCache.get(imageUrl) else null)
            ?: (if (existing != null) cardBitmapCache.get(existing.id) else null)
            ?: cardBitmapCache.get("page_${pageNumber}_image")
          if (cachedBmp != null) {
            cardBitmapCache.put(id, cachedBmp)
            if (!imageUrl.isNullOrEmpty()) {
              cardBitmapCache.put(imageUrl, cachedBmp)
            }
          }
        }

        val parsedSourceRects = mutableListOf<RectF>()
        if (obj.has("sourceRects") && !obj.isNull("sourceRects")) {
          val sArr = obj.getJSONArray("sourceRects")
          for (rIdx in 0 until sArr.length()) {
            val rObj = sArr.getJSONObject(rIdx)
            val l = rObj.optDouble("left", 0.0).toFloat()
            val t = rObj.optDouble("top", 0.0).toFloat()
            val r = rObj.optDouble("right", 0.0).toFloat()
            val b = rObj.optDouble("bottom", 0.0).toFloat()
            parsedSourceRects.add(RectF(l, t, r, b))
          }
        } else if (existing != null && existing.sourceRects.isNotEmpty()) {
          parsedSourceRects.addAll(existing.sourceRects)
        }

        // Multi-document: parse documentId, falling back to existing or activeDocumentId
        val cardDocumentId = obj.optString(
          "documentId",
          existing?.documentId?.takeIf { it.isNotEmpty() } ?: activeDocumentId
        )

        updatedList.add(
          NativeCard(
            id, x, y, width, text, color, pageNumber, comment, clusterId, stackCount,
            isImage, imageUrl, isTable, existing?.tableRows,
            existing?.groupedItems,
            parsedSourceRects,
            documentId = cardDocumentId
          )
        )
      }

      cards.clear()
      cards.addAll(updatedList)
    } catch (e: Exception) {
      e.printStackTrace()
    }
    invalidate()
  }

fun ThinkspaceView.setLinksFromJson(json: String?) {
    if (json.isNullOrEmpty()) {
      return
    }
    try {
      val arr = JSONArray(json)
      if (arr.length() == 0 && links.isNotEmpty()) {
        return
      }
      links.clear()
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("id", "link-$i")
        val sourceExcerptId = obj.optString("sourceExcerptId", "")
        val colorHex = obj.optString("color", "#00ADB5")
        val color = try { Color.parseColor(colorHex) } catch (e: Exception) { Color.CYAN }
        links.add(NativeLink(id, sourceExcerptId, color))
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
    invalidate()
  }
