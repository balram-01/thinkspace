package com.thinkspace

// ---------------------------------------------------------------------------
// Notebook Page — Rendering, Public API, Persistence & Event Dispatchers
// Extension functions for ThinkspaceView — extracted from ThinkspaceView.kt
// ---------------------------------------------------------------------------

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.text.TextPaint
import android.view.animation.DecelerateInterpolator
import com.facebook.react.bridge.Arguments
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.EventDispatcher
import com.thinkspace.engine.CreateNotebookPageAction
import com.thinkspace.engine.models.NativeNotebookPage
import com.thinkspace.engine.models.NativeStroke
import com.thinkspace.engine.models.ThinkspaceEvent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

// ---------------------------------------------------------------------------
// Notebook Page \u2014 Rendering
// ---------------------------------------------------------------------------
/**
 * Draws all notebook pages on the canvas (called inside the world-transform save/restore block).
 * Pages are drawn in list order (first = bottom), with the selected page always highlighted.
 */
internal fun ThinkspaceView.drawNotebookPages(canvas: Canvas) {
  nbPageDeleteRects.clear()
  nbPageStyleBtnRects.clear()
  nbPageResizeRects.clear()

  for (page in notebookPages) {
    val isSelected = page.id == selectedNotebookPageId
    val r = RectF(page.x, page.y, page.x + page.width, page.y + page.height)

    // 1. Drop shadow
    val shadowR = RectF(r.left + 5f, r.top + 5f, r.right + 5f, r.bottom + 5f)
    canvas.drawRoundRect(shadowR, 10f, 10f, nbPageShadowPaint)

    // 2. Paper background
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = page.backgroundColor; style = Paint.Style.FILL
    }
    canvas.drawRoundRect(r, 10f, 10f, bgPaint)

    // 3. Paper pattern (clipped inside page bounds)
    canvas.save()
    canvas.clipRect(r)
    drawNotebookPagePattern(canvas, page, r)
    canvas.restore()

    // 4. Title (only when there is one and page is large enough)
    if (page.title.isNotEmpty() && page.height > 60f) {
      val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#374151")
        textSize = (page.height * 0.055f).coerceIn(14f, 22f)
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
      }
      canvas.drawText(page.title, r.left + 18f, r.top + 28f, titlePaint)
    }

    // 5. Page border
    canvas.drawRoundRect(r, 10f, 10f,
      if (isSelected) nbPageSelectedBorderPaint else nbPageNormalBorderPaint)

    // 6. Toolbar + controls when selected
    if (isSelected) {
      val tbH = 28f
      val tbW = page.width.coerceAtMost(260f)
      val tbLeft = r.left + (page.width - tbW) / 2f
      val tbTop = r.top - tbH - 6f
      val tbRect = RectF(tbLeft, tbTop, tbLeft + tbW, tbTop + tbH)

      // Toolbar shadow
      val toolShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#30000000"); style = Paint.Style.FILL
      }
      canvas.drawRoundRect(RectF(tbLeft, tbTop + 2f, tbLeft + tbW, tbTop + tbH + 2f),
        12f, 12f, toolShadow)
      // Toolbar bg + border
      canvas.drawRoundRect(tbRect, 12f, 12f, nbPageToolbarBgPaint)
      canvas.drawRoundRect(tbRect, 12f, 12f, nbPageToolbarBorderPaint)

      val iconPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1"); textSize = 11.5f
      }

      val btnW = tbW / 4f
      val midY = tbRect.centerY() + 4f

      // [✥ Move] btn (visual header drag indicator)
      val moveRect = RectF(tbLeft, tbTop, tbLeft + btnW, tbTop + tbH)
      val moveLabel = "✥ Move"
      canvas.drawText(moveLabel, moveRect.centerX() - iconPaint.measureText(moveLabel) / 2f, midY, iconPaint)

      // [≡ Style] btn
      val styleRect = RectF(tbLeft + btnW, tbTop, tbLeft + btnW * 2f, tbTop + tbH)
      nbPageStyleBtnRects[page.id] = styleRect
      val styleLabel = when (page.pageStyle) {
        "blank" -> "Blank"
        "ruled" -> "Ruled"
        "grid"  -> "Grid"
        "dotted"-> "Dots"
        "sketch"-> "Sketch"
        "cornell"-> "Cornell"
        "squared"-> "Squared"
        else    -> "Custom"
      }
      val styleFull = "≡ $styleLabel"
      canvas.drawText(styleFull, styleRect.centerX() - iconPaint.measureText(styleFull) / 2f, midY, iconPaint)

      // [⧉ Copy] btn
      val dupRect = RectF(tbLeft + btnW * 2f, tbTop, tbLeft + btnW * 3f, tbTop + tbH)
      nbPageDuplicateRects[page.id] = dupRect
      val dupLabel = "⧉ Copy"
      canvas.drawText(dupLabel, dupRect.centerX() - iconPaint.measureText(dupLabel) / 2f, midY, iconPaint)

      // [🗑 Delete] btn
      val deleteRect = RectF(tbLeft + btnW * 3f, tbTop, tbLeft + tbW, tbTop + tbH)
      nbPageDeleteRects[page.id] = deleteRect
      val delPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FCA5A5"); textSize = 11.5f
      }
      val delLabel = "🗑 Delete"
      canvas.drawText(delLabel, deleteRect.centerX() - delPaint.measureText(delLabel) / 2f, midY, delPaint)

      // Dividers
      val divPaint = Paint().apply { color = Color.parseColor("#334155"); strokeWidth = 1f }
      canvas.drawLine(tbLeft + btnW, tbTop + 5f, tbLeft + btnW, tbTop + tbH - 5f, divPaint)
      canvas.drawLine(tbLeft + btnW * 2f, tbTop + 5f, tbLeft + btnW * 2f, tbTop + tbH - 5f, divPaint)
      canvas.drawLine(tbLeft + btnW * 3f, tbTop + 5f, tbLeft + btnW * 3f, tbTop + tbH - 5f, divPaint)

      // 7. Four circular selection handles on corners (matching design reference)
      val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6"); style = Paint.Style.FILL
      }
      val cornerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f
      }
      val corners = listOf(
        Pair(r.left, r.top),
        Pair(r.right, r.top),
        Pair(r.left, r.bottom),
        Pair(r.right, r.bottom)
      )
      for (pt in corners) {
        canvas.drawCircle(pt.first, pt.second, 5.5f, cornerPaint)
        canvas.drawCircle(pt.first, pt.second, 5.5f, cornerStroke)
      }

      // Bottom-right corner resize hit area
      val handleSz = 22f
      val handleRect = RectF(r.right - handleSz, r.bottom - handleSz, r.right, r.bottom)
      nbPageResizeRects[page.id] = handleRect

      // 8. Style picker overlay (if open for this page)
      if (isNotebookStylePickerOpen && stylePickerForPageId == page.id) {
        val styles = listOf(
          "blank" to "Blank",
          "ruled" to "Ruled",
          "grid" to "Grid",
          "dotted" to "Dotted",
          "sketch" to "Sketch",
          "cornell" to "Cornell",
          "squared" to "Squared",
          "custom" to "Custom"
        )
        val rowH = 34f
        val popW = 125f
        val popLeft = (nbPageStyleBtnRects[page.id]?.left ?: r.left)
        val popTop = tbTop + tbH + 4f
        val popRect = RectF(popLeft, popTop, popLeft + popW, popTop + styles.size * rowH)
        nbPageStylePickerRect.set(popRect)

        // Picker shadow + bg
        canvas.drawRoundRect(RectF(popLeft, popTop + 2f, popLeft + popW, popTop + styles.size * rowH + 2f),
          8f, 8f, toolShadow)
        val popBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#0F172A"); style = Paint.Style.FILL
        }
        canvas.drawRoundRect(popRect, 8f, 8f, popBg)
        canvas.drawRoundRect(popRect, 8f, 8f, nbPageToolbarBorderPaint)

        val rowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          textSize = 12f
        }
        for (i in styles.indices) {
          val (styleKey, styleLabel2) = styles[i]
          val rowTop = popTop + i * rowH
          val isActive = page.pageStyle == styleKey
          if (isActive) {
            val activeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.parseColor("#1E3A5F"); style = Paint.Style.FILL
            }
            canvas.drawRect(popLeft, rowTop, popLeft + popW, rowTop + rowH, activeBg)
          }
          rowPaint.color = if (isActive) Color.parseColor("#3B82F6") else Color.parseColor("#CBD5E1")
          canvas.drawText(styleLabel2, popLeft + 12f, rowTop + rowH * 0.65f, rowPaint)
        }
      }
    }
  }
}

/** Draws the paper texture/pattern lines inside the notebook page's clipped region. */
private fun ThinkspaceView.drawNotebookPagePattern(canvas: Canvas, page: NativeNotebookPage, r: RectF) {
  val contentTop = r.top + 40f  // leave room for title
  when (page.pageStyle) {
    "ruled" -> {
      val lineSpacing = (page.height * 0.065f).coerceIn(22f, 36f)
      var y = contentTop + lineSpacing
      while (y < r.bottom - 10f) {
        canvas.drawLine(r.left + 10f, y, r.right - 10f, y, nbPageRuledLinePaint)
        y += lineSpacing
      }
      // Pink margin line ~15% from left
      val marginX = r.left + page.width * 0.15f
      canvas.drawLine(marginX, contentTop, marginX, r.bottom - 10f, nbPageMarginLinePaint)
    }
    "grid" -> {
      val step = (page.width * 0.08f).coerceIn(20f, 40f)
      var x = r.left + step
      while (x < r.right - 5f) {
        canvas.drawLine(x, contentTop, x, r.bottom - 5f, nbPageGridLinePaint)
        x += step
      }
      var y = contentTop + step
      while (y < r.bottom - 5f) {
        canvas.drawLine(r.left + 5f, y, r.right - 5f, y, nbPageGridLinePaint)
        y += step
      }
    }
    "dotted" -> {
      val stepX = (page.width * 0.1f).coerceIn(20f, 38f)
      val stepY = (page.height * 0.065f).coerceIn(20f, 36f)
      var x = r.left + stepX
      while (x < r.right - 5f) {
        var y = contentTop + stepY
        while (y < r.bottom - 5f) {
          canvas.drawCircle(x, y, 1.8f, nbPageDotPaint)
          y += stepY
        }
        x += stepX
      }
    }
    "sketch" -> {
      val step = (page.width * 0.12f).coerceIn(24f, 46f)
      val sketchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D8D5BE"); strokeWidth = 0.6f; style = Paint.Style.STROKE
      }
      var startX = r.left - page.height
      while (startX < r.right + page.height) {
        canvas.drawLine(startX, contentTop, startX + page.height, r.bottom, sketchPaint)
        startX += step
      }
    }
    "cornell" -> {
      val cueX = r.left + page.width * 0.28f
      val summaryY = r.bottom - page.height * 0.22f
      val cornellSepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4444"); strokeWidth = 1.2f
      }
      // Vertical cue column
      canvas.drawLine(cueX, contentTop, cueX, summaryY, cornellSepPaint)
      // Horizontal summary line
      canvas.drawLine(r.left + 10f, summaryY, r.right - 10f, summaryY, cornellSepPaint)
      // Ruled lines in main notes section
      val lineSpacing = (page.height * 0.065f).coerceIn(22f, 36f)
      var y = contentTop + lineSpacing
      while (y < summaryY - 8f) {
        canvas.drawLine(cueX + 4f, y, r.right - 10f, y, nbPageRuledLinePaint)
        y += lineSpacing
      }
    }
    "squared" -> {
      val step = (page.width * 0.05f).coerceIn(14f, 26f)
      val sqMajorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8"); strokeWidth = 0.8f
      }
      val sqMinorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1"); strokeWidth = 0.4f
      }
      var count = 0
      var x = r.left + step
      while (x < r.right - 5f) {
        canvas.drawLine(x, contentTop, x, r.bottom - 5f, if (count % 5 == 0) sqMajorPaint else sqMinorPaint)
        x += step
        count++
      }
      count = 0
      var y = contentTop + step
      while (y < r.bottom - 5f) {
        canvas.drawLine(r.left + 5f, y, r.right - 5f, y, if (count % 5 == 0) sqMajorPaint else sqMinorPaint)
        y += step
        count++
      }
    }
    "custom" -> {
      val lineSpacing = (page.height * 0.07f).coerceIn(24f, 38f)
      val customLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E7FF"); strokeWidth = 0.8f
      }
      var y = contentTop + lineSpacing
      while (y < r.bottom - 10f) {
        canvas.drawLine(r.left + 15f, y, r.right - 15f, y, customLinePaint)
        y += lineSpacing
      }
    }
    // "blank" -> clean paper background without guide lines
  }
}

// ---------------------------------------------------------------------------
// Notebook Page — Public API & Persistence
// ---------------------------------------------------------------------------
/**
 * Creates a new notebook page centered in the current viewport and adds it to the canvas.
 * Called from React Native via the 'addNotebookPage' command, or directly from Kotlin.
 */
fun ThinkspaceView.addNotebookPage(style: String = "ruled", title: String = "") {
  val viewW = width.toFloat()
  val viewH = height.toFloat()
  val hasDoc = activePdfDoc != null || activeDocument != null
  val splitY = if (hasDoc) viewH * effectiveSplitRatio else 0f
  val canvasTopY = if (hasDoc && effectiveSplitRatio > 0f) splitY + 14f else 0f

  // Center in the visible canvas viewport
  val centerScreenX = viewW / 2f
  val centerScreenY = (canvasTopY + viewH) / 2f
  val (centerWx, centerWy) = canvasScreenToWorld(centerScreenX, centerScreenY, canvasTopY)

  val pageW = (280f / scaleFactor).coerceIn(200f, 600f)
  val pageH = (380f / scaleFactor).coerceIn(260f, 800f)

  val newPage = NativeNotebookPage(
    id = "nbpage-${System.currentTimeMillis()}",
    x = centerWx - pageW / 2f,
    y = centerWy - pageH / 2f,
    width = pageW,
    height = pageH,
    pageStyle = style,
    title = if (title.isEmpty()) "Notebook Page" else title
  )

  notebookPages.add(newPage)
  selectedNotebookPageId = newPage.id

  undoRedoManager.record(CreateNotebookPageAction(
    page = newPage,
    pagesList = notebookPages,
    onUndoDispatched = { invalidate() },
    onRedoDispatched = { invalidate() }
  ))

  dispatchNotebookPageAddedEvent(newPage)
  persistNotebookPagesLocally()
  hudToast.show("📄 Notebook page added — drag header to move")
  performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
  invalidate()
}

/**
 * Duplicates an existing notebook page, offsetting it slightly and adding it to the canvas.
 */
fun ThinkspaceView.duplicateNotebookPage(pageId: String) {
  val orig = notebookPages.find { it.id == pageId } ?: return
  val copy = NativeNotebookPage(
    id = "nbpage-${System.currentTimeMillis()}",
    x = orig.x + 30f,
    y = orig.y + 30f,
    width = orig.width,
    height = orig.height,
    pageStyle = orig.pageStyle,
    title = if (orig.title.endsWith("(Copy)")) orig.title else "${orig.title} (Copy)",
    backgroundColor = orig.backgroundColor
  )
  notebookPages.add(copy)
  selectedNotebookPageId = copy.id
  undoRedoManager.record(CreateNotebookPageAction(
    page = copy,
    pagesList = notebookPages,
    onUndoDispatched = { invalidate() },
    onRedoDispatched = { invalidate() }
  ))
  dispatchNotebookPageAddedEvent(copy)
  persistNotebookPagesLocally()
  hudToast.show("📋 Page duplicated")
  performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
  invalidate()
}

/**
 * Persists the notebook pages to a local app-private JSON file so they survive restarts.
 */
fun ThinkspaceView.persistNotebookPagesLocally() {
  try {
    val file = java.io.File(context.filesDir, "thinkspace_notebook_pages.json")
    val arr = org.json.JSONArray()
    for (page in notebookPages) {
      val obj = org.json.JSONObject().apply {
        put("id", page.id)
        put("x", page.x.toDouble())
        put("y", page.y.toDouble())
        put("width", page.width.toDouble())
        put("height", page.height.toDouble())
        put("pageStyle", page.pageStyle)
        put("title", page.title)
        put("backgroundColor", String.format("#%06X", 0xFFFFFF and page.backgroundColor))
      }
      arr.put(obj)
    }
    file.writeText(arr.toString())
  } catch (e: Exception) {
    e.printStackTrace()
  }
}

/**
 * Restores notebook pages from the local app-private JSON file if currently empty.
 */
fun ThinkspaceView.loadPersistedNotebookPages() {
  try {
    val file = java.io.File(context.filesDir, "thinkspace_notebook_pages.json")
    if (file.exists() && notebookPages.isEmpty()) {
      val json = file.readText()
      setNotebookPagesFromJson(json)
    }
  } catch (e: Exception) {
    e.printStackTrace()
  }
}

/**
 * Loads notebook pages from a JSON array string (used by the 'notebookPagesJson' ReactProp).
 */
fun ThinkspaceView.setNotebookPagesFromJson(json: String?) {
  if (json.isNullOrBlank()) return
  try {
    val arr = org.json.JSONArray(json)
    notebookPages.clear()
    for (i in 0 until arr.length()) {
      val obj = arr.getJSONObject(i)
      notebookPages.add(NativeNotebookPage(
        id = obj.optString("id", "nbpage-$i"),
        x = obj.optDouble("x", 0.0).toFloat(),
        y = obj.optDouble("y", 0.0).toFloat(),
        width = obj.optDouble("width", 280.0).toFloat(),
        height = obj.optDouble("height", 380.0).toFloat(),
        pageStyle = obj.optString("pageStyle", "ruled"),
        title = obj.optString("title", "Notebook Page"),
        backgroundColor = Color.parseColor(obj.optString("backgroundColor", "#FFFEF0")
          .let { if (it.startsWith("#")) it else "#FFFEF0" })
      ))
    }
    persistNotebookPagesLocally()
    invalidate()
  } catch (e: Exception) {
    e.printStackTrace()
  }
}

// ---------------------------------------------------------------------------
// Notebook Page \u2014 React Native Event Dispatchers
// ---------------------------------------------------------------------------
internal fun ThinkspaceView.getEventDispatcher(): EventDispatcher? {
  val reactContext = UIManagerHelper.getReactContext(this) ?: return null
  return UIManagerHelper.getEventDispatcherForReactTag(reactContext, id)
}

internal fun ThinkspaceView.dispatchNotebookPageAddedEvent(page: NativeNotebookPage) {
  val surfaceId = UIManagerHelper.getSurfaceId(this)
  val eventDispatcher = getEventDispatcher()
  val data = Arguments.createMap().apply {
    putString("id", page.id)
    putDouble("x", page.x.toDouble())
    putDouble("y", page.y.toDouble())
    putDouble("width", page.width.toDouble())
    putDouble("height", page.height.toDouble())
    putString("pageStyle", page.pageStyle)
    putString("title", page.title)
  }
  eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topNotebookPageAdded", data))
}

internal fun ThinkspaceView.dispatchNotebookPageMovedEvent(pageId: String, x: Float, y: Float) {
  val surfaceId = UIManagerHelper.getSurfaceId(this)
  val eventDispatcher = getEventDispatcher()
  val data = Arguments.createMap().apply {
    putString("id", pageId)
    putDouble("x", x.toDouble())
    putDouble("y", y.toDouble())
  }
  eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topNotebookPageMoved", data))
}

internal fun ThinkspaceView.dispatchNotebookPageDeletedEvent(pageId: String) {
  val surfaceId = UIManagerHelper.getSurfaceId(this)
  val eventDispatcher = getEventDispatcher()
  val data = Arguments.createMap().apply { putString("id", pageId) }
  eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topNotebookPageDeleted", data))
}


internal fun ThinkspaceView.dispatchTransformEvent() {
  val surfaceId = UIManagerHelper.getSurfaceId(this)
  val eventDispatcher = getEventDispatcher()
  val data = Arguments.createMap().apply {
    putDouble("panX", panX.toDouble())
    putDouble("panY", panY.toDouble())
    putDouble("scale", scaleFactor.toDouble())
  }
  eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topTransformChange", data))
}

internal fun ThinkspaceView.dispatchSplitRatioEvent(ratio: Float) {
  val surfaceId = UIManagerHelper.getSurfaceId(this)
  val eventDispatcher = getEventDispatcher()
  val data = Arguments.createMap().apply {
    putDouble("ratio", ratio.toDouble())
  }
  eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topSplitRatioChange", data))
}

internal fun ThinkspaceView.dispatchToggleSqueezeEvent(squeezed: Boolean) {
  val surfaceId = UIManagerHelper.getSurfaceId(this)
  val eventDispatcher = getEventDispatcher()
  val data = Arguments.createMap().apply {
    putBoolean("isSqueezed", squeezed)
  }
  eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topToggleSqueeze", data))
}

internal fun ThinkspaceView.dispatchExtractExcerptEvent(
  text: String,
  pageNumber: Int,
  color: Int,
  isImg: Boolean,
  imageUrl: String? = null,
  x: Float = 0f,
  y: Float = 0f,
  cardId: String? = null,
  sourceRects: List<RectF> = emptyList(),
  documentId: String = activeDocumentId
) {
  val surfaceId = UIManagerHelper.getSurfaceId(this)
  val eventDispatcher = getEventDispatcher()
  val data = Arguments.createMap().apply {
    putString("text", text)
    putDouble("pageNumber", pageNumber.toDouble())
    putString("color", String.format("#%06X", 0xFFFFFF and color))
    putBoolean("isTable", false)
    putBoolean("isImage", isImg)
    if (!imageUrl.isNullOrEmpty()) {
      putString("imageUrl", imageUrl)
    }
    if (!cardId.isNullOrEmpty()) {
      putString("id", cardId)
    }
    putDouble("x", x.toDouble())
    putDouble("y", y.toDouble())
    // Multi-document: always include the source document ID
    putString("documentId", documentId)
    if (sourceRects.isNotEmpty()) {
      val arr = Arguments.createArray()
      for (r in sourceRects) {
        val m = Arguments.createMap().apply {
          putDouble("left", r.left.toDouble())
          putDouble("top", r.top.toDouble())
          putDouble("right", r.right.toDouble())
          putDouble("bottom", r.bottom.toDouble())
        }
        arr.pushMap(m)
      }
      putArray("sourceRects", arr)
    }
  }
  eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topExtractExcerpt", data))
}

internal fun ThinkspaceView.dispatchExcerptPressEvent(cardId: String) {
  val surfaceId = UIManagerHelper.getSurfaceId(this)
  val eventDispatcher = getEventDispatcher()
  val data = Arguments.createMap().apply {
    putString("id", cardId)
  }
  eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topExcerptPress", data))
}

internal fun ThinkspaceView.dispatchExcerptMoveEndEvent(cardId: String, x: Float, y: Float) {
  val surfaceId = UIManagerHelper.getSurfaceId(this)
  val eventDispatcher = getEventDispatcher()
  val data = Arguments.createMap().apply {
    putString("id", cardId)
    putDouble("x", x.toDouble())
    putDouble("y", y.toDouble())
  }
  eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topExcerptMoveEnd", data))
}

internal fun ThinkspaceView.dispatchAddStrokeEvent(stroke: NativeStroke) {
  val surfaceId = UIManagerHelper.getSurfaceId(this)
  val eventDispatcher = getEventDispatcher()
  val json = JSONObject().apply {
    put("id", stroke.id)
    put("color", String.format("#%06X", 0xFFFFFF and stroke.color))
    put("strokeWidth", stroke.strokeWidth.toDouble())
    put("isHighlighter", stroke.isHighlighter)
    val ptsArr = JSONArray()
    for (p in stroke.points) {
      ptsArr.put(JSONObject().apply {
        put("x", p.x.toDouble())
        put("y", p.y.toDouble())
      })
    }
    put("points", ptsArr)
  }
  val data = Arguments.createMap().apply {
    putString("strokeJson", json.toString())
  }
  eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topAddStroke", data))
}

internal fun ThinkspaceView.dispatchEraseStrokeEvent(strokeId: String) {
  val surfaceId = UIManagerHelper.getSurfaceId(this)
  val eventDispatcher = getEventDispatcher()
  val data = Arguments.createMap().apply {
    putString("id", strokeId)
  }
  eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topEraseStroke", data))
}

fun ThinkspaceView.zoomToFitCards() {
  if (width <= 0 || height <= 0) {
    post { zoomToFitCards() }
    return
  }

  val viewW = width.toFloat()
  val viewH = height.toFloat()
  val hasDoc = activePdfDoc != null || activeDocument != null
  val splitY = if (hasDoc) viewH * effectiveSplitRatio else 0f
  val canvasTopY = if (hasDoc && effectiveSplitRatio > 0f) splitY + 14f else 0f
  val viewportH = (viewH - canvasTopY).coerceAtLeast(100f)
  val viewportW = viewW.coerceAtLeast(100f)

  var minX = Float.MAX_VALUE
  var maxX = -Float.MAX_VALUE
  var minY = Float.MAX_VALUE
  var maxY = -Float.MAX_VALUE
  var hasContent = false

  for (c in cards) {
    minX = minOf(minX, c.x)
    maxX = maxOf(maxX, c.x + c.width)
    minY = minOf(minY, c.y)
    maxY = maxOf(maxY, c.y + c.getHeight())
    hasContent = true
  }

  for (s in strokes) {
    for (p in s.points) {
      minX = minOf(minX, p.x)
      maxX = maxOf(maxX, p.x)
      minY = minOf(minY, p.y)
      maxY = maxOf(maxY, p.y)
      hasContent = true
    }
  }

  for (pg in notebookPages) {
    minX = minOf(minX, pg.x)
    maxX = maxOf(maxX, pg.x + pg.width)
    minY = minOf(minY, pg.y)
    maxY = maxOf(maxY, pg.y + pg.height)
    hasContent = true
  }

  val targetPanX: Float
  val targetPanY: Float
  val targetScale: Float

  if (!hasContent) {
    targetPanX = 0f
    targetPanY = 0f
    targetScale = 1.0f
  } else {
    val contentW = (maxX - minX).coerceAtLeast(80f)
    val contentH = (maxY - minY).coerceAtLeast(80f)
    val contentCenterX = (minX + maxX) / 2f
    val contentCenterY = (minY + maxY) / 2f

    val padding = 56f * density
    val availW = (viewportW - 2 * padding).coerceAtLeast(80f)
    val availH = (viewportH - 2 * padding).coerceAtLeast(80f)

    val fitScale = minOf(availW / contentW, availH / contentH)
    targetScale = fitScale.coerceIn(camera.minScale, 1.05f)

    targetPanX = (viewportW / 2f) - (contentCenterX * targetScale)
    targetPanY = (viewportH / 2f) - (contentCenterY * targetScale)
  }

  val startPanX = panX
  val startPanY = panY
  val startScale = scaleFactor

  canvasAnimator?.cancel()
  val anim = ValueAnimator.ofFloat(0f, 1f).apply {
    duration = 380
    interpolator = DecelerateInterpolator()
    addUpdateListener { animator ->
      val fraction = animator.animatedFraction
      panX = startPanX + (targetPanX - startPanX) * fraction
      panY = startPanY + (targetPanY - startPanY) * fraction
      scaleFactor = startScale + (targetScale - startScale) * fraction
      dispatchTransformEvent()
      invalidate()
    }
  }
  canvasAnimator = anim
  anim.start()

  performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
  if (hasContent) {
    hudToast.show("Zoomed to Cards")
  } else {
    hudToast.show("Workspace Centered")
  }
}

fun ThinkspaceView.undo(): Boolean {
  val action = undoRedoManager.undo()
  if (action != null) {
    hudToast.show("↩ Undone: ${action.description}")
    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    invalidate()
    return true
  } else {
    hudToast.show("Nothing to undo")
    return false
  }
}

fun ThinkspaceView.redo(): Boolean {
  val action = undoRedoManager.redo()
  if (action != null) {
    hudToast.show("↪ Redone: ${action.description}")
    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    invalidate()
    return true
  } else {
    hudToast.show("Nothing to redo")
    return false
  }
}

fun ThinkspaceView.canUndo(): Boolean = undoRedoManager.canUndo
fun ThinkspaceView.canRedo(): Boolean = undoRedoManager.canRedo

internal fun ThinkspaceView.dispatchUndoStateChangeEvent(canUndo: Boolean, canRedo: Boolean) {
  val surfaceId = UIManagerHelper.getSurfaceId(this)
  val eventDispatcher = getEventDispatcher()
  val data = Arguments.createMap().apply {
    putBoolean("canUndo", canUndo)
    putBoolean("canRedo", canRedo)
  }
  eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topUndoStateChange", data))
}

internal fun ThinkspaceView.dispatchCardDeleteEvent(cardId: String) {
  val surfaceId = UIManagerHelper.getSurfaceId(this)
  val eventDispatcher = getEventDispatcher()
  val data = Arguments.createMap().apply {
    putString("id", cardId)
  }
  eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topCardDelete", data))
}

internal fun ThinkspaceView.dispatchChangeCardColorEvent(cardId: String, color: Int) {
  val surfaceId = UIManagerHelper.getSurfaceId(this)
  val eventDispatcher = getEventDispatcher()
  val data = Arguments.createMap().apply {
    putString("id", cardId)
    putString("color", String.format("#%06X", 0xFFFFFF and color))
  }
  eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topChangeCardColor", data))
}

fun ThinkspaceView.clearSelection() {
  activeCropSelection = null
  activePdfSelection = null
  activeSelection = null
  invalidate()
}

fun ThinkspaceView.deleteCard(cardId: String) {
  val card = cards.find { it.id == cardId } ?: return
  cards.remove(card)
  semanticInkLinks.removeAll { it.targetCardId == cardId }
  dispatchCardDeleteEvent(cardId)
  persistSemanticInkLinksLocally()
  invalidate()
}

fun ThinkspaceView.deleteInkLink(linkId: String) {
  val link = semanticInkLinks.find { it.id == linkId } ?: return
  semanticInkLinks.remove(link)
  dispatchInkLinkDeleteEvent(linkId)
  persistSemanticInkLinksLocally()
  invalidate()
}

fun ThinkspaceView.clearAllCards() {
  cards.clear()
  semanticInkLinks.clear()
  persistSemanticInkLinksLocally()
  invalidate()
}

fun ThinkspaceView.clearAllStrokes() {
  strokes.clear()
  invalidate()
}

fun ThinkspaceView.setViewport(x: Float, y: Float, scale: Float) {
  panX = x
  panY = y
  scaleFactor = scale.coerceIn(camera.minScale, camera.maxScale)
  dispatchTransformEvent()
  invalidate()
}

fun ThinkspaceView.setSplitRatioProgrammatic(ratio: Float, dispatch: Boolean = true) {
  val clamped = ratio.coerceIn(0.18f, 0.82f)
  if (abs(splitRatio - clamped) < 0.001f) return
  splitRatio = clamped
  if (dispatch) {
    dispatchSplitRatioEvent(clamped)
  }
  invalidate()
}

fun ThinkspaceView.toggleSqueezeMode() {
  isSqueezed = !isSqueezed
  dispatchToggleSqueezeEvent(isSqueezed)
  invalidate()
}

