package com.thinkspace

import android.graphics.*
import android.os.ParcelFileDescriptor
import android.view.HapticFeedbackConstants
import com.thinkspace.engine.models.*
import com.thinkspace.pdfengine.api.PdfDocument
import com.thinkspace.pdfengine.api.RenderOptions
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.PageSize
import com.thinkspace.pdfengine.model.TextWord
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Extension functions for ThinkspaceView: Document tap selection handling,
 * PDF text word selection engine, line clustering, and selection synchronization.
 */
  // ---------------------------------------------------------------------------
  // Document Tap Selection Handling
  // ---------------------------------------------------------------------------
internal fun ThinkspaceView.handleDocTap(tapX: Float, tapY: Float) {
    // 0. If user tapped outside an active selection, dismiss it and return!
    if (activeCropSelection != null || activePdfSelection != null) {
      activeCropSelection = null
      activePdfSelection = null
      docMode = "text"
      invalidate()
      return
    }

    // 1. Real PDF Word Selection or InkLink Tap
    if (activePdfDoc != null) {
      for (pl in pageLayouts) {
        if (pl.boundsOnScreen.contains(tapX, tapY)) {
          val px = (tapX - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pl.pageSize.width
          val py = (tapY - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pl.pageSize.height

          // Check if tapped on or near an InkLink anchor pin in the PDF
          val matchingLink = semanticInkLinks.find {
            it.sourceDocId == activeDocumentId &&
            it.sourcePageIndex == pl.pageIndex &&
            hypot(it.sourcePdfPoint.x - px, it.sourcePdfPoint.y - py) < 28f
          }
          if (matchingLink != null) {
            val targetCard = cards.find { it.id == matchingLink.targetCardId }
            if (targetCard != null) {
              val targetCx = targetCard.x + targetCard.width / 2f
              val targetCy = targetCard.y + targetCard.getHeight() / 2f
              val hasDoc = activePdfDoc != null || activeDocument != null
              val curCanvasTopY = if (hasDoc && effectiveSplitRatio > 0f) height * effectiveSplitRatio + 14f else 0f
              val viewW = width.toFloat()
              val viewH = height - curCanvasTopY
              camera.panX = -targetCx + (viewW / 2f) / camera.scaleFactor
              camera.panY = -targetCy + (viewH / 2f) / camera.scaleFactor
              selectedCardId = targetCard.id
              performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              hudToast.show("Jumped to Linked Card in Workspace")
              invalidate()
              return
            }
          }

          if (pl.isFolded) {
            compressionEngine.expandPage(pl.pageIndex, animate = true) { invalidate() }
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            return
          }
          val words = pageWordsCache[pl.pageIndex]
          if (words != null && words.isNotEmpty() && activeTool == "select") {
            val px = (tapX - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pl.pageSize.width
            val py = (tapY - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pl.pageSize.height
            val hitWord = words.find { w ->
              val b = w.bounds
              px >= b.left - 6f && px <= b.right + 6f && py >= b.top - 8f && py <= b.bottom + 8f
            } ?: words.minByOrNull { w ->
              val b = w.bounds
              val cx = (b.left + b.right) / 2f
              val cy = (b.top + b.bottom) / 2f
              (cx - px) * (cx - px) + (cy - py) * (cy - py)
            }?.takeIf { w ->
              val b = w.bounds
              val cx = (b.left + b.right) / 2f
              val cy = (b.top + b.bottom) / 2f
              val distSq = (cx - px) * (cx - px) + (cy - py) * (cy - py)
              distSq < 36f * 36f
            }

            if (hitWord != null) {
              updatePdfSelection(pl, hitWord, hitWord)
              invalidate()
              return
            }
          }

          // If no text word was hit:
          // ONLY create a Figure Crop selection if the user explicitly switched to "crop" mode!
          if (docMode == "crop") {
            val cropW = min(pl.boundsOnScreen.width() * 0.85f, 320f * density)
            val cropH = min(pl.boundsOnScreen.height() * 0.45f, 220f * density)
            val sRect = RectF(
              (tapX - cropW / 2f).coerceIn(pl.boundsOnScreen.left + 8f * density, pl.boundsOnScreen.right - cropW - 8f * density),
              (tapY - cropH / 2f).coerceIn(pl.boundsOnScreen.top + 8f * density, pl.boundsOnScreen.bottom - cropH - 8f * density),
              (tapX + cropW / 2f).coerceIn(pl.boundsOnScreen.left + cropW + 8f * density, pl.boundsOnScreen.right - 8f * density),
              (tapY + cropH / 2f).coerceIn(pl.boundsOnScreen.top + cropH + 8f * density, pl.boundsOnScreen.bottom - 8f * density)
            )

            val pW = pl.pageSize.width
            val pH = pl.pageSize.height
            val pageLeft = (sRect.left - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
            val pageTop = (sRect.top - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH
            val pageRight = (sRect.right - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
            val pageBottom = (sRect.bottom - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH

            val cW = 200f * density
            val cH = 44f * density
            val cLeft = sRect.centerX() - cW / 2f
            val cTop = if (sRect.top - cH - 12f * density > subheaderH) sRect.top - cH - 12f * density else sRect.bottom + 12f * density
            val calloutR = RectF(cLeft, cTop, cLeft + cW, cTop + cH)
            val excerptBtn = RectF(cLeft + 8f * density, cTop + 4f * density, cLeft + cW - 44f * density, cTop + cH - 4f * density)
            val closeBtn = RectF(cLeft + cW - 40f * density, cTop + 4f * density, cLeft + cW - 4f * density, cTop + cH - 4f * density)

            activeCropSelection = createCropSelection(
              pageIndex = pl.pageIndex,
              sRect = sRect,
              pageBounds = BoundingBox(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom)),
              color = selectedColor,
              dimensionsText = "Page ${pl.pageIndex + 1} (${pW.toInt()}x${pH.toInt()} pt)"
            )
            activePdfSelection = null
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            invalidate()
            return
          } else {
            // Normal tap on blank whitespace in text mode: toggle immersive mode!
            activeCropSelection = null
            activePdfSelection = null
            toggleImmersiveMode()
            invalidate()
            return
          }
        }
      }
    }

    // 2. Structured Sections Document
    if (activeDocument != null && activePdfDoc == null && activeTool == "select") {
      for (pInfo in paragraphLayouts) {
        val paraH = pInfo.layout.height.toFloat() + 16f
        val pRect = RectF(pInfo.paperX, pInfo.topY, pInfo.paperX + pInfo.width + 56f, pInfo.topY + paraH)
        if (pRect.contains(tapX, tapY)) {
          val hlLeft = pInfo.paperX + 24f
          val hlTop = pInfo.topY - 4f
          val hlRight = pInfo.paperX + pInfo.width + 32f
          val hlBottom = pInfo.topY + pInfo.layout.height + 4f
          val highlightR = RectF(hlLeft, hlTop, hlRight, hlBottom)

          val layout = computeSelectionCalloutLayout(listOf(highlightR))

          activePdfSelection = NativePdfSelection(
            pageIndex = pInfo.pageNumber - 1,
            text = pInfo.text,
            highlightRects = listOf(highlightR),
            pdfRects = emptyList(),
            startHandle = RectF(hlLeft - 12f * density, hlTop - 20f * density, hlLeft + 12f * density, hlBottom),
            endHandle = RectF(hlRight - 12f * density, hlTop, hlRight + 12f * density, hlBottom + 20f * density),
            calloutRect = layout.calloutRect,
            calloutExcerptBtn = layout.excerptBtn,
            calloutCopyBtn = layout.copyBtn,
            calloutHighlightBtn = layout.highlightBtn,
            calloutCloseBtn = layout.closeBtn,
            startWordIndex = 0,
            endWordIndex = 0,
            calloutAddWordLeftBtn = layout.addWordLeftBtn,
            calloutAddWordRightBtn = layout.addWordRightBtn,
            calloutSelectAllBtn = layout.selectAllBtn,
            charCountText = "${pInfo.text.length} chars",
            calloutColorBtns = layout.colorBtns,
            calloutMoreBtn = layout.moreBtn,
            calloutTagsBtn = layout.tagsBtn,
            calloutSubCardRect = layout.subCardRect,
            calloutMainCardRect = layout.mainCardRect,
            calloutRainbowBtn = layout.rainbowBtn,
            calloutCommentBtn = layout.commentBtn,
            calloutBookmarkBtn = layout.bookmarkBtn,
            calloutClearBtn = layout.clearBtn
          )
          invalidate()
          return
        }
      }
    }

    // 3. Tapped outside -> Dismiss selection or toggle immersive mode
    val hadSelection = activePdfSelection != null || activeCropSelection != null
    activePdfSelection = null
    activeCropSelection = null
    if (!hadSelection) {
      toggleImmersiveMode()
    }
    invalidate()
  }

  // ---------------------------------------------------------------------------
  // Real PDF Word Selection Engine matching LiquidText
  // ---------------------------------------------------------------------------

  /**
   * LiquidText-Style Line Clustering:
   * Groups selected consecutive words belonging to the same horizontal text line and
   * merges them into single continuous bounding rectangles covering all spaces between words.
   */
internal fun ThinkspaceView.clusterWordsIntoLineRects(
    words: List<TextWord>,
    pl: PdfPageLayout
  ): Pair<List<RectF>, List<RectF>> {
    if (words.isEmpty()) return Pair(emptyList(), emptyList())

    val lineGroups = mutableListOf<MutableList<TextWord>>()
    for (word in words) {
      if (lineGroups.isEmpty()) {
        lineGroups.add(mutableListOf(word))
      } else {
        val currentGroup = lineGroups.last()
        val prev = currentGroup.last()

        val overlapTop = max(prev.bounds.top, word.bounds.top)
        val overlapBottom = min(prev.bounds.bottom, word.bounds.bottom)
        val overlapH = overlapBottom - overlapTop
        val minH = min(prev.bounds.height, word.bounds.height)

        val baselineMatch = if (prev.baseline > 0f && word.baseline > 0f) {
          abs(prev.baseline - word.baseline) <= max(prev.fontSize, word.fontSize) * 0.45f
        } else false

        val isSameLine = baselineMatch ||
          (minH > 0f && overlapH >= minH * 0.45f) ||
          (abs(prev.bounds.top - word.bounds.top) <= max(prev.bounds.height, word.bounds.height) * 0.35f)

        if (isSameLine) {
          currentGroup.add(word)
        } else {
          lineGroups.add(mutableListOf(word))
        }
      }
    }

    val screenRects = mutableListOf<RectF>()
    val pdfRects = mutableListOf<RectF>()

    for (group in lineGroups) {
      if (group.isEmpty()) continue
      val sorted = group.sortedBy { it.bounds.left }
      val lineLeft = sorted.minOf { it.bounds.left }
      val lineRight = sorted.maxOf { it.bounds.right }
      val lineTop = sorted.minOf { it.bounds.top }
      val lineBottom = sorted.maxOf { it.bounds.bottom }

      val pRect = RectF(lineLeft, lineTop, lineRight, lineBottom)
      pdfRects.add(pRect)

      val pSize = runCatching { activePdfDoc?.getPage(pl.pageIndex)?.size }.getOrNull() ?: pl.pageSize
      val pW = pSize.width
      val pH = pSize.height
      val l = pl.boundsOnScreen.left + (lineLeft / pW) * pl.boundsOnScreen.width()
      val t = pl.boundsOnScreen.top + (lineTop / pH) * pl.boundsOnScreen.height()
      val r = pl.boundsOnScreen.left + (lineRight / pW) * pl.boundsOnScreen.width()
      val b = pl.boundsOnScreen.top + (lineBottom / pH) * pl.boundsOnScreen.height()
      screenRects.add(RectF(l, t, r, b))
    }

    return Pair(screenRects, pdfRects)
  }

internal fun ThinkspaceView.buildPdfSelectionForLayout(
    pl: PdfPageLayout,
    startIdx: Int,
    endIdx: Int
  ): NativePdfSelection? {
    val words = pageWordsCache[pl.pageIndex] ?: return null
    if (words.isEmpty()) return null
    val clampedStart = startIdx.coerceIn(0, words.size - 1)
    val clampedEnd = endIdx.coerceIn(clampedStart, words.size - 1)
    val selWords = words.subList(clampedStart, clampedEnd + 1)
    val combinedText = selWords.joinToString(" ") { it.text }

    val (rects, pRects) = clusterWordsIntoLineRects(selWords, pl)
    if (rects.isEmpty()) return null

    val firstR = rects.first()
    val lastR = rects.last()
    val startHandle = RectF(firstR.left - 14f * density, firstR.bottom - 4f * density, firstR.left + 14f * density, firstR.bottom + 22f * density)
    val endHandle = RectF(lastR.right - 14f * density, lastR.bottom - 4f * density, lastR.right + 14f * density, lastR.bottom + 22f * density)

    val charCount = combinedText.length
    val previewSnippet = if (combinedText.length > 22) combinedText.substring(0, 20) + "..." else combinedText
    val charCountText = "$charCount chars selected \"$previewSnippet\""

    val layout = computeSelectionCalloutLayout(rects)

    return NativePdfSelection(
      pageIndex = pl.pageIndex,
      text = combinedText,
      highlightRects = rects,
      pdfRects = pRects,
      startHandle = startHandle,
      endHandle = endHandle,
      calloutRect = layout.calloutRect,
      calloutExcerptBtn = layout.excerptBtn,
      calloutCopyBtn = layout.copyBtn,
      calloutHighlightBtn = layout.highlightBtn,
      calloutCloseBtn = layout.closeBtn,
      startWordIndex = clampedStart,
      endWordIndex = clampedEnd,
      calloutAddWordLeftBtn = layout.addWordLeftBtn,
      calloutAddWordRightBtn = layout.addWordRightBtn,
      calloutSelectAllBtn = layout.selectAllBtn,
      charCountText = "${combinedText.length} chars",
      calloutColorBtns = layout.colorBtns,
      calloutMoreBtn = layout.moreBtn,
      calloutTagsBtn = layout.tagsBtn,
      calloutSubCardRect = layout.subCardRect,
      calloutMainCardRect = layout.mainCardRect,
      calloutRainbowBtn = layout.rainbowBtn,
      calloutCommentBtn = layout.commentBtn,
      calloutBookmarkBtn = layout.bookmarkBtn,
      calloutClearBtn = layout.clearBtn
    )
  }

internal fun ThinkspaceView.syncPdfSelectionWithLayout(
    sel: NativePdfSelection,
    pl: PdfPageLayout
  ): NativePdfSelection {
    val words = pageWordsCache[pl.pageIndex]
    if (!words.isNullOrEmpty() && sel.startWordIndex >= 0 && sel.endWordIndex < words.size) {
      val built = buildPdfSelectionForLayout(pl, sel.startWordIndex, sel.endWordIndex)
      if (built != null) return built
    }

    // Fallback projection using sel.pdfRects directly
    val pSize = runCatching { activePdfDoc?.getPage(pl.pageIndex)?.size }.getOrNull() ?: pl.pageSize
    val pW = pSize.width
    val pH = pSize.height
    val screenRects = sel.pdfRects.map { pRect ->
      val l = pl.boundsOnScreen.left + (pRect.left / pW) * pl.boundsOnScreen.width()
      val t = pl.boundsOnScreen.top + (pRect.top / pH) * pl.boundsOnScreen.height()
      val r = pl.boundsOnScreen.left + (pRect.right / pW) * pl.boundsOnScreen.width()
      val b = pl.boundsOnScreen.top + (pRect.bottom / pH) * pl.boundsOnScreen.height()
      RectF(l, t, r, b)
    }

    if (screenRects.isEmpty()) return sel

    val firstR = screenRects.first()
    val lastR = screenRects.last()
    val startHandle = RectF(firstR.left - 14f * density, firstR.bottom - 4f * density, firstR.left + 14f * density, firstR.bottom + 22f * density)
    val endHandle = RectF(lastR.right - 14f * density, lastR.bottom - 4f * density, lastR.right + 14f * density, lastR.bottom + 22f * density)

    val layout = computeSelectionCalloutLayout(screenRects, sel.calloutColorBtns.map { it.second })

    return sel.copy(
      highlightRects = screenRects,
      startHandle = startHandle,
      endHandle = endHandle,
      calloutRect = layout.calloutRect,
      calloutExcerptBtn = layout.excerptBtn,
      calloutCopyBtn = layout.copyBtn,
      calloutHighlightBtn = layout.highlightBtn,
      calloutCloseBtn = layout.closeBtn,
      calloutAddWordLeftBtn = layout.addWordLeftBtn,
      calloutAddWordRightBtn = layout.addWordRightBtn,
      calloutSelectAllBtn = layout.selectAllBtn,
      calloutColorBtns = layout.colorBtns,
      calloutMoreBtn = layout.moreBtn,
      calloutTagsBtn = layout.tagsBtn,
      calloutSubCardRect = layout.subCardRect,
      calloutMainCardRect = layout.mainCardRect,
      calloutRainbowBtn = layout.rainbowBtn,
      calloutCommentBtn = layout.commentBtn,
      calloutBookmarkBtn = layout.bookmarkBtn,
      calloutClearBtn = layout.clearBtn
    )
  }

internal fun ThinkspaceView.updatePdfSelectionByIndex(pl: PdfPageLayout, startIdx: Int, endIdx: Int) {
    val sel = buildPdfSelectionForLayout(pl, startIdx, endIdx) ?: return
    activePdfSelection = sel
    invalidate()
  }

internal fun ThinkspaceView.updatePdfSelection(pl: PdfPageLayout, w1: TextWord, w2: TextWord) {
    val words = pageWordsCache[pl.pageIndex] ?: return
    val idx1 = words.indexOf(w1)
    val idx2 = words.indexOf(w2)
    if (idx1 == -1 || idx2 == -1) return
    val startIdx = min(idx1, idx2)
    val endIdx = max(idx1, idx2)
    updatePdfSelectionByIndex(pl, startIdx, endIdx)
  }


// ── Crop & Structured Selection, Long Press & Crop Generation ──
internal fun ThinkspaceView.createCropSelection(
    pageIndex: Int,
    sRect: RectF,
    pageBounds: BoundingBox,
    color: Int = selectedColor,
    dimensionsText: String = ""
  ): NativeCropSelection {
    val sel = NativeCropSelection(
      pageIndex = pageIndex,
      pageBounds = pageBounds,
      screenRect = sRect,
      color = color,
      dimensionsText = dimensionsText
    )
    recomputeCropCalloutRects(sel)
    return sel
  }

internal fun ThinkspaceView.recomputeCropCalloutRects(sel: NativeCropSelection) {
    val d = density
    val maxAvailW = width - 24f * d
    val cW = min(maxAvailW, 316f * d)
    val cH = 66f * d
    val cLeft = (sel.screenRect.centerX() - cW / 2f).coerceIn(12f * d, max(12f * d, width - cW - 12f * d))
    val splitY = if (activePdfDoc != null || activeDocument != null) height.toFloat() * splitRatio else 0f
    val cTop = if (sel.screenRect.top - cH - 12f * d >= subheaderH) {
      sel.screenRect.top - cH - 12f * d
    } else {
      min(sel.screenRect.bottom + 12f * d, (splitY - cH - 14f * d).coerceAtLeast(subheaderH))
    }
    sel.calloutRect.set(cLeft, cTop, cLeft + cW, cTop + cH)

    // Row 1: Comment | AutoExcerpt | Bookmark | •••
    val r1Top = cTop + 4f * d
    val r1Bottom = cTop + 32f * d
    val moreBtnW = 32f * d
    sel.calloutMoreBtn.set(cLeft + cW - 12f * d - moreBtnW, r1Top, cLeft + cW - 10f * d, r1Bottom)

    val r1Left = cLeft + 12f * d
    val commentBtnW = 70f * d
    val excerptBtnW = 92f * d
    val bookmarkBtnW = 76f * d

    sel.calloutCommentBtn.set(r1Left, r1Top, r1Left + commentBtnW, r1Bottom)
    sel.calloutExcerptBtn.set(sel.calloutCommentBtn.right + 4f * d, r1Top, sel.calloutCommentBtn.right + 4f * d + excerptBtnW, r1Bottom)
    sel.calloutBookmarkBtn.set(sel.calloutExcerptBtn.right + 4f * d, r1Top, min(sel.calloutMoreBtn.left - 4f * d, sel.calloutExcerptBtn.right + 4f * d + bookmarkBtnW), r1Bottom)

    // Row 2: Color swatches (5) + Clear swatch + Rainbow swatch + Divider + Tags
    val r2CenterY = cTop + 48f * d
    val touchRad = 13f * d
    val pitch = ((cW - 130f * d) / 7f).coerceIn(21f * d, 25f * d)
    val swatchStartX = cLeft + 18f * d

    sel.calloutColorBtns.clear()
    val palette = listOf(
      Color.parseColor("#EF4444"), // Red
      Color.parseColor("#22C55E"), // Green
      Color.parseColor("#3B82F6"), // Blue
      Color.parseColor("#F59E0B"), // Yellow
      Color.parseColor("#EC4899")  // Pink
    )
    for (i in palette.indices) {
      val cx = swatchStartX + i * pitch
      sel.calloutColorBtns.add(Pair(RectF(cx - touchRad, r2CenterY - touchRad, cx + touchRad, r2CenterY + touchRad), palette[i]))
    }
    val clearCx = swatchStartX + palette.size * pitch
    sel.calloutClearBtn.set(clearCx - touchRad, r2CenterY - touchRad, clearCx + touchRad, r2CenterY + touchRad)

    val rainbowCx = clearCx + pitch
    sel.calloutRainbowBtn.set(rainbowCx - touchRad, r2CenterY - touchRad, rainbowCx + touchRad, r2CenterY + touchRad)

    val tagsLeft = rainbowCx + touchRad + 8f * d
    sel.calloutTagsBtn.set(tagsLeft, cTop + 34f * d, cLeft + cW - 10f * d, cTop + 62f * d)

    sel.holdAndDragRect.set(sel.screenRect.left, sel.screenRect.top - 24f * d, sel.screenRect.left + 115f * d, sel.screenRect.top - 4f * d)
  }




internal fun ThinkspaceView.triggerLongPressSelect(x: Float, y: Float) {
    val splitY = if (activePdfDoc != null || activeDocument != null) height.toFloat() * splitRatio else 0f
    if (y < splitY - 14f && y >= subheaderH) {
      isScrollingDoc = false

      val sel = activePdfSelection
      if (sel != null) {
        if (sel.calloutRect.contains(x, y)) {
          return // Ignore long presses on the callout menu
        }

        val inSelection = sel.highlightRects.any { it.contains(x, y) }
        if (inSelection) {
          isLiftingExcerpt = true
          liftCandidateText = sel.text
          liftCandidatePage = sel.pageIndex + 1
          liftCandidateColor = android.graphics.Color.parseColor("#3B82F6") // Blue default for text excerpt
          liftCandidateIsImage = false
          liftCandidateImagePath = null
          liftCandidateBitmap = null
          liftCandidateSourceRects = sel.pdfRects
          
          liftAnchorScreenX = x
          liftAnchorScreenY = y
          liftGhostX = x
          liftGhostY = y
          
          activePdfSelection = null
          parent?.requestDisallowInterceptTouchEvent(true)
          performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
          invalidate()
          return
        }
      }

      val crop = activeCropSelection
      if (crop != null) {
        if (crop.calloutRect.contains(x, y)) {
          return // Ignore long presses on the callout menu
        }
        if (crop.screenRect.contains(x, y)) {
          // Touching inside selection keeps selection active (allows moving or adjusting handles)
          return
        }
      }

      // 1. Real PDF document - ONLY select text when activeTool is explicitly "select"
      if (activePdfDoc != null) {
        for (pl in pageLayouts) {
          if (!pl.isFolded && pl.boundsOnScreen.contains(x, y)) {
            val words = pageWordsCache[pl.pageIndex]
            if (!words.isNullOrEmpty() && activeTool == "select" && docMode != "crop") {
              val px = (x - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pl.pageSize.width
              val py = (y - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pl.pageSize.height
              val hitWord = words.find { w ->
                val b = w.bounds
                px >= b.left - 4f && px <= b.right + 4f && py >= b.top - 6f && py <= b.bottom + 6f
              }

              if (hitWord != null) {
                isSelectingPdfText = true
                pdfSelectStartWord = hitWord
                pdfSelectPageIndex = pl.pageIndex
                updatePdfSelection(pl, hitWord, hitWord)
                isScrollingDoc = false
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                invalidate()
                return
              }
            }

            // LiquidText Area Selection:
            // Create a handsome, clearly visible selection box centered on touch point
            isDraggingCrop = true
            cropStartX = x
            cropStartY = y
            cropPageIndex = pl.pageIndex

            val selW = min(pl.boundsOnScreen.width() * 0.70f, 220f * density)
            val selH = min(pl.boundsOnScreen.height() * 0.35f, 150f * density)
            val sRect = RectF(
              (x - selW / 2f).coerceIn(pl.boundsOnScreen.left + 8f * density, pl.boundsOnScreen.right - selW - 8f * density),
              (y - selH / 2f).coerceIn(pl.boundsOnScreen.top + 8f * density, pl.boundsOnScreen.bottom - selH - 8f * density),
              (x + selW / 2f).coerceIn(pl.boundsOnScreen.left + selW + 8f * density, pl.boundsOnScreen.right - 8f * density),
              (y + selH / 2f).coerceIn(pl.boundsOnScreen.top + selH + 8f * density, pl.boundsOnScreen.bottom - 8f * density)
            )
            val pW = pl.pageSize.width
            val pH = pl.pageSize.height
            val pageLeft = (sRect.left - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
            val pageTop = (sRect.top - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH
            val pageRight = (sRect.right - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
            val pageBottom = (sRect.bottom - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH

            val newSel = createCropSelection(
              pageIndex = pl.pageIndex,
              sRect = sRect,
              pageBounds = BoundingBox(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom)),
              color = selectedColor,
              dimensionsText = "Page ${pl.pageIndex + 1} (${pW.toInt()}x${pH.toInt()} pt)"
            )
            recomputeCropCalloutRects(newSel)
            activeCropSelection = newSel
            activePdfSelection = null
            isScrollingDoc = false
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
            return
          }
        }
      }

      // 2. Structured Sections Document - ONLY select text when activeTool is explicitly "select"
      if (activeDocument != null && activeTool == "select") {
        for (pInfo in paragraphLayouts) {
          val paraH = pInfo.layout.height.toFloat() + 16f
          val pRect = RectF(pInfo.paperX, pInfo.topY, pInfo.paperX + pInfo.width + 56f, pInfo.topY + paraH)
          if (pRect.contains(x, y)) {
            updateStructuredSelection(pInfo, x, y)
            isDraggingEndHandle = true
            isDraggingStartHandle = false
            isScrollingDoc = false
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
            return
          }
        }

        // Long-pressed outside paragraph text in structured doc: start area selection!
        if (activeCropSelection == null) {
          val splitYVal = height.toFloat() * splitRatio
          if (y in subheaderH..(splitYVal - 14f)) {
            isDraggingCrop = true
            cropStartX = x
            cropStartY = y
            cropPageIndex = 0

            val initialW = min((width - 32f).coerceAtLeast(100f), 220f * density)
            val initialH = min((splitYVal - subheaderH - 32f).coerceAtLeast(80f), 150f * density)
            val sRect = RectF(
              (x - initialW / 2f).coerceIn(16f, width - initialW - 16f),
              (y - initialH / 2f).coerceIn(subheaderH + 4f, splitYVal - initialH - 14f),
              (x + initialW / 2f).coerceIn(16f + initialW, width - 16f),
              (y + initialH / 2f).coerceIn(subheaderH + initialH + 4f, splitYVal - 14f)
            )
            val newSel = createCropSelection(
              pageIndex = 0,
              sRect = sRect,
              pageBounds = BoundingBox(sRect.left, sRect.top, sRect.right, sRect.bottom),
              color = selectedColor,
              dimensionsText = "Document Section"
            )
            recomputeCropCalloutRects(newSel)
            activeCropSelection = newSel
            activePdfSelection = null
            isScrollingDoc = false
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
            return
          }
        }
      }
    }
  }

internal fun ThinkspaceView.updateStructuredSelection(pInfo: ParagraphLayoutInfo, touchX: Float, touchY: Float) {
    val relY = (touchY - pInfo.topY).coerceIn(0f, pInfo.layout.height.toFloat() - 1f)
    val line = pInfo.layout.getLineForVertical(relY.toInt())
    val relX = touchX - pInfo.paperX
    val offset = pInfo.layout.getOffsetForHorizontal(line, relX).coerceIn(0, pInfo.text.length)

    var start = offset
    var end = offset
    while (start > 0 && !pInfo.text[start - 1].isWhitespace()) {
      start--
    }
    while (end < pInfo.text.length && !pInfo.text[end].isWhitespace()) {
      end++
    }
    if (start >= end) {
      start = 0
      end = min(pInfo.text.length, 20)
    }

    updateStructuredSelectionByOffsets(pInfo, start, end)
  }

internal fun ThinkspaceView.computeSelectionCalloutLayout(
    rects: List<RectF>,
    customColors: List<Int>? = null
  ): CalloutLayoutResult {
    val d = density
    val colors = customColors ?: listOf(
      Color.parseColor("#EF4444"), // Red
      Color.parseColor("#22C55E"), // Green
      Color.parseColor("#3B82F6"), // Blue
      Color.parseColor("#F59E0B"), // Yellow
      Color.parseColor("#EC4899")  // Pink
    )

    val maxAvailW = width - 24f * d
    val cW = min(maxAvailW, 316f * d)
    val cH = 66f * d

    val minX = rects.minOfOrNull { it.left } ?: (width / 2f)
    val maxX = rects.maxOfOrNull { it.right } ?: (width / 2f)
    val firstR = rects.firstOrNull() ?: RectF(minX, 100f * d, maxX, 120f * d)
    val lastR = rects.lastOrNull() ?: firstR

    val idealLeft = (minX + maxX) / 2f - cW / 2f
    val cLeft = idealLeft.coerceIn(12f * d, max(12f * d, width - cW - 12f * d))

    val gapY = 12f * d
    val minTop = subheaderH + 6f * d
    val maxBottom = height * splitRatio - cH - 10f * d
    val cTop = (if (firstR.top - cH - gapY > subheaderH) firstR.top - cH - gapY else lastR.bottom + gapY).coerceIn(minTop, max(minTop, maxBottom))

    val calloutR = RectF(cLeft, cTop, cLeft + cW, cTop + cH)

    // Row 1: Comment | AutoExcerpt | Bookmark | •••
    val r1Top = cTop + 4f * d
    val r1Bottom = cTop + 32f * d
    val moreBtnW = 32f * d
    val moreBtn = RectF(cLeft + cW - 12f * d - moreBtnW, r1Top, cLeft + cW - 10f * d, r1Bottom)

    val r1Left = cLeft + 12f * d
    val commentBtnW = 70f * d
    val excerptBtnW = 92f * d
    val bookmarkBtnW = 76f * d

    val commentBtn = RectF(r1Left, r1Top, r1Left + commentBtnW, r1Bottom)
    val excerptBtn = RectF(commentBtn.right + 4f * d, r1Top, commentBtn.right + 4f * d + excerptBtnW, r1Bottom)
    val bookmarkBtn = RectF(excerptBtn.right + 4f * d, r1Top, min(moreBtn.left - 4f * d, excerptBtn.right + 4f * d + bookmarkBtnW), r1Bottom)

    // Row 2: Color swatches (5) + Clear swatch + Rainbow swatch + Divider + Tags
    val r2CenterY = cTop + 48f * d
    val touchRad = 13f * d
    val pitch = ((cW - 130f * d) / 7f).coerceIn(21f * d, 25f * d)
    val swatchStartX = cLeft + 18f * d

    val colorBtns = mutableListOf<Pair<RectF, Int>>()
    for (i in colors.indices) {
      val cx = swatchStartX + i * pitch
      colorBtns.add(Pair(RectF(cx - touchRad, r2CenterY - touchRad, cx + touchRad, r2CenterY + touchRad), colors[i]))
    }

    val clearCx = swatchStartX + colors.size * pitch
    val clearBtn = RectF(clearCx - touchRad, r2CenterY - touchRad, clearCx + touchRad, r2CenterY + touchRad)

    val rainbowCx = clearCx + pitch
    val rainbowBtn = RectF(rainbowCx - touchRad, r2CenterY - touchRad, rainbowCx + touchRad, r2CenterY + touchRad)

    val tagsLeft = rainbowCx + touchRad + 8f * d
    val tagsBtn = RectF(tagsLeft, cTop + 34f * d, cLeft + cW - 10f * d, cTop + 62f * d)

    return CalloutLayoutResult(
      calloutRect = calloutR,
      closeBtn = RectF(),
      excerptBtn = excerptBtn,
      copyBtn = moreBtn,
      highlightBtn = colorBtns.firstOrNull()?.first ?: RectF(),
      addWordLeftBtn = RectF(),
      addWordRightBtn = RectF(),
      selectAllBtn = RectF(),
      colorBtns = colorBtns,
      moreBtn = moreBtn,
      tagsBtn = tagsBtn,
      subCardRect = RectF(),
      mainCardRect = calloutR,
      rainbowBtn = rainbowBtn,
      commentBtn = commentBtn,
      bookmarkBtn = bookmarkBtn,
      clearBtn = clearBtn
    )
  }

internal fun ThinkspaceView.updateStructuredSelectionByOffsets(pInfo: ParagraphLayoutInfo, startOffset: Int, endOffset: Int) {
    val clampedStart = startOffset.coerceIn(0, pInfo.text.length)
    val clampedEnd = max(clampedStart + 1, endOffset).coerceIn(clampedStart, pInfo.text.length)
    val rawText = pInfo.text.substring(clampedStart, clampedEnd)
    val selText = if (rawText.trim().isNotEmpty()) rawText.trim() else rawText

    val startLine = pInfo.layout.getLineForOffset(clampedStart)
    val endLine = pInfo.layout.getLineForOffset(clampedEnd)

    val rects = mutableListOf<RectF>()
    for (l in startLine..endLine) {
      val lStart = if (l == startLine) clampedStart else pInfo.layout.getLineStart(l)
      val lEnd = if (l == endLine) clampedEnd else pInfo.layout.getLineEnd(l)
      if (lStart < lEnd) {
        val lX1 = pInfo.paperX + pInfo.layout.getPrimaryHorizontal(lStart)
        val lX2 = pInfo.paperX + pInfo.layout.getPrimaryHorizontal(lEnd)
        val lTop = pInfo.topY + pInfo.layout.getLineTop(l)
        val lBottom = pInfo.topY + pInfo.layout.getLineBottom(l)
        rects.add(RectF(min(lX1, lX2), lTop, max(lX1, lX2), lBottom))
      }
    }
    if (rects.isEmpty()) {
      val lTop = pInfo.topY + pInfo.layout.getLineTop(startLine)
      val lBottom = pInfo.topY + pInfo.layout.getLineBottom(startLine)
      rects.add(RectF(pInfo.paperX, lTop, pInfo.paperX + pInfo.width, lBottom))
    }

    val firstR = rects.first()
    val lastR = rects.last()
    val startHandle = RectF(firstR.left - 14f * density, firstR.bottom - 4f * density, firstR.left + 14f * density, firstR.bottom + 22f * density)
    val endHandle = RectF(lastR.right - 14f * density, lastR.bottom - 4f * density, lastR.right + 14f * density, lastR.bottom + 22f * density)

    val layout = computeSelectionCalloutLayout(rects)

    activeStructuredPInfo = pInfo
    activeStructuredStartOffset = clampedStart
    activeStructuredEndOffset = clampedEnd

    activePdfSelection = NativePdfSelection(
      pageIndex = pInfo.pageNumber - 1,
      text = selText,
      highlightRects = rects,
      pdfRects = emptyList(),
      startHandle = startHandle,
      endHandle = endHandle,
      calloutRect = layout.calloutRect,
      calloutExcerptBtn = layout.excerptBtn,
      calloutCopyBtn = layout.copyBtn,
      calloutHighlightBtn = layout.highlightBtn,
      calloutCloseBtn = layout.closeBtn,
      startWordIndex = 0,
      endWordIndex = 0,
      calloutAddWordLeftBtn = layout.addWordLeftBtn,
      calloutAddWordRightBtn = layout.addWordRightBtn,
      calloutSelectAllBtn = layout.selectAllBtn,
      charCountText = "${selText.length} chars",
      calloutColorBtns = layout.colorBtns,
      calloutMoreBtn = layout.moreBtn,
      calloutTagsBtn = layout.tagsBtn,
      calloutSubCardRect = layout.subCardRect,
      calloutMainCardRect = layout.mainCardRect,
      calloutRainbowBtn = layout.rainbowBtn,
      calloutCommentBtn = layout.commentBtn,
      calloutBookmarkBtn = layout.bookmarkBtn,
      calloutClearBtn = layout.clearBtn
    )
    invalidate()
  }


internal fun ThinkspaceView.generateCropBitmap(pageIndex: Int, bounds: BoundingBox): Bitmap? {
    if (activePdfDoc != null) {
      var pageBmp = pageBitmaps.get(pageIndex)
      if (pageBmp == null || pageBmp.isRecycled) {
        val wrapper = activePdfDoc as? com.thinkspace.pdfengine.parser.PdfBoxDocumentWrapper
        if (wrapper != null && wrapper.file.exists()) {
          try {
            val pfd = ParcelFileDescriptor.open(wrapper.file, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd.use { desc ->
              val nativeRenderer = android.graphics.pdf.PdfRenderer(desc)
              val page = nativeRenderer.openPage(pageIndex)
              val scale = 2.0f
              val targetW = max(1, (page.width * scale).toInt())
              val targetH = max(1, (page.height * scale).toInt())
              val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
              bmp.eraseColor(Color.WHITE)
              page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
              page.close()
              nativeRenderer.close()
              pageBmp = bmp
              pageBitmaps.put(pageIndex, bmp)
            }
          } catch (e: Exception) {
            e.printStackTrace()
          }
        }
      }

      // Secondary fallback if nativeRenderer failed or wasn't loaded:
      if (pageBmp == null || pageBmp.isRecycled) {
        try {
          val engine = PdfEngineModule.getOrCreateEngine(context)
          val rendered = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            engine.renderPage(activePdfDoc!!, pageIndex, RenderOptions(scale = 2.0f))
          }
          pageBmp = rendered.bitmap
          if (pageBmp != null) {
            pageBitmaps.put(pageIndex, pageBmp)
          }
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }

      if (pageBmp != null && !pageBmp!!.isRecycled) {
        val bmp = pageBmp!!
        val pl = pageLayouts.find { it.pageIndex == pageIndex }
        val pW = pl?.pageSize?.width ?: (bmp.width / 2.0f)
        val pH = pl?.pageSize?.height ?: (bmp.height / 2.0f)

        val normL = minOf(bounds.left, bounds.right).coerceIn(0f, pW)
        val normR = maxOf(bounds.left, bounds.right).coerceIn(0f, pW)
        val normT = minOf(bounds.top, bounds.bottom).coerceIn(0f, pH)
        val normB = maxOf(bounds.top, bounds.bottom).coerceIn(0f, pH)

        var srcL = ((normL / pW) * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        var srcT = ((normT / pH) * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        var srcR = ((normR / pW) * bmp.width).toInt().coerceIn(0, bmp.width)
        var srcB = ((normB / pH) * bmp.height).toInt().coerceIn(0, bmp.height)

        var w = srcR - srcL
        var h = srcB - srcT

        if (w < 20) {
          srcL = maxOf(0, srcL - 40)
          srcR = minOf(bmp.width, srcL + 120)
          w = srcR - srcL
        }
        if (h < 20) {
          srcT = maxOf(0, srcT - 40)
          srcB = minOf(bmp.height, srcT + 120)
          h = srcB - srcT
        }

        if (srcL + w > bmp.width) w = bmp.width - srcL
        if (srcT + h > bmp.height) h = bmp.height - srcT

        if (w > 0 && h > 0) {
          return try {
            Bitmap.createBitmap(bmp, srcL, srcT, w, h)
          } catch (e: Exception) {
            null
          }
        }
      }
    }

    if (activeDocument != null) {
      val cropBmp = Bitmap.createBitmap(400, 260, Bitmap.Config.ARGB_8888)
      val cropCanvas = Canvas(cropBmp)
      cropCanvas.drawColor(Color.WHITE)
      val borderP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        strokeWidth = 2f
        style = Paint.Style.STROKE
      }
      cropCanvas.drawRect(0f, 0f, 400f, 260f, borderP)

      val headerP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      }
      val bodyP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        textSize = 14f
      }

      val sec = activeDocument?.sections?.getOrNull(pageIndex) ?: activeDocument?.sections?.firstOrNull()
      val title = sec?.heading ?: activeDocument?.title ?: "Document"
      cropCanvas.drawText("📖 $title", 20f, 36f, headerP)

      val lines = sec?.paragraphs ?: listOf("Historical excerpt and excerpted diagram.")
      var y = 70f
      for (line in lines) {
        val sub = if (line.length > 45) line.substring(0, 42) + "..." else line
        cropCanvas.drawText(sub, 20f, y, bodyP)
        y += 26f
        if (y > 230f) break
      }
      return cropBmp
    }

    return null
  }

internal fun ThinkspaceView.saveCropToFile(bmp: Bitmap): String {
    val file = File(context.cacheDir, "crop_${System.currentTimeMillis()}_${(1000..9999).random()}.png")
    try {
      FileOutputStream(file).use { out ->
        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
    return file.absolutePath
  }
