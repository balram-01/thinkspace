package com.thinkspace

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.animation.DecelerateInterpolator
import com.thinkspace.engine.*
import com.thinkspace.engine.models.*
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.PageSize
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Extension functions for ThinkspaceView: Excerpt extraction to canvas, instant crop cards,
 * card tidy, annotation creation/erasure, stroke erasing, and smooth document scrolling.
 */
  // ---------------------------------------------------------------------------
  // Excerpt & Annotation Helpers with Collision-Free Drop & Toast Feedback
  // ---------------------------------------------------------------------------
internal fun ThinkspaceView.extractExcerptToCanvas(text: String, pageNumber: Int, color: Int, pdfRects: List<RectF> = emptyList()) {
    val newId = "card-${System.currentTimeMillis()}"
    val (dropWx, dropWy) = canvasScreenToWorld(width / 2f, height * splitRatio + 80f, height * splitRatio + 14f)

    val cardW = 220f
    val cardH = if (text.length > 70) 130f else 105f
    val resolved = CollisionPlacementSolver.findNonOverlappingPosition(
      desiredX = dropWx - cardW / 2f,
      desiredY = dropWy - cardH / 2f,
      cardWidth = cardW,
      cardHeight = cardH,
      existingCards = cards
    )

    val card = NativeCard(
      id = newId,
      x = resolved.x,
      y = resolved.y,
      width = cardW,
      text = text,
      color = color,
      pageNumber = pageNumber,
      comment = null,
      clusterId = null,
      stackCount = 1,
      isImage = false,
      imageUrl = null,
      isTable = false,
      tableRows = null,
      groupedItems = null,
      sourceRects = pdfRects,
      documentId = activeDocumentId
    )
    cards.add(card)

    val newLink = NativeLink("link-${System.currentTimeMillis()}", newId, color)
    links.add(newLink)
    undoRedoManager.record(CreateCardAction(
      card = card,
      link = newLink,
      cardsList = cards,
      linksList = links,
      onUndoDispatched = { c ->
        dispatchCardDeleteEvent(c.id)
        invalidate()
      },
      onRedoDispatched = { c, _ ->
        dispatchExtractExcerptEvent(
          c.text, c.pageNumber, c.color, c.isImage, c.imageUrl, c.x, c.y, c.id, c.sourceRects
        )
        invalidate()
      }
    ))
    triggerShockwave(resolved.x + cardW / 2f, resolved.y + cardH / 2f, color)
    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    dispatchExtractExcerptEvent(
      text = text,
      pageNumber = pageNumber,
      color = color,
      isImg = false,
      x = resolved.x,
      y = resolved.y,
      cardId = newId,
      sourceRects = pdfRects
    )
    hudToast.show("Excerpt placed without overlap with live Ink-Link!")
    invalidate()
  }

internal fun ThinkspaceView.extractCropToCanvas(cropSel: NativeCropSelection) {
    val newId = "card-${System.currentTimeMillis()}"
    val (dropWx, dropWy) = canvasScreenToWorld(width / 2f, height * splitRatio + 80f, height * splitRatio + 14f)

    val bmp = generateCropBitmap(cropSel.pageIndex, cropSel.pageBounds)
    val imgPath = if (bmp != null) {
      val p = saveCropToFile(bmp)
      cardBitmapCache.put(p, bmp)
      cardBitmapCache.put(newId, bmp)
      cardBitmapCache.put("page_${cropSel.pageIndex + 1}_image", bmp)
      p
    } else null

    val cardW = 240f
    val cardH = 160f
    val resolved = CollisionPlacementSolver.findNonOverlappingPosition(
      desiredX = dropWx - cardW / 2f,
      desiredY = dropWy - cardH / 2f,
      cardWidth = cardW,
      cardHeight = cardH,
      existingCards = cards
    )

    val cropSourceRects = listOf(RectF(cropSel.pageBounds.left, cropSel.pageBounds.top, cropSel.pageBounds.right, cropSel.pageBounds.bottom))
    val card = NativeCard(
      id = newId,
      x = resolved.x,
      y = resolved.y,
      width = cardW,
      text = "[Photo Excerpt]",
      color = cropSel.color,
      pageNumber = cropSel.pageIndex + 1,
      comment = null,
      clusterId = null,
      stackCount = 1,
      isImage = true,
      imageUrl = imgPath,
      isTable = false,
      tableRows = null,
      groupedItems = null,
      sourceRects = cropSourceRects,
      documentId = activeDocumentId
    )
    cards.add(card)

    // Reset docMode and dismiss crop selector so user can scroll immediately
    activeCropSelection = null
    docMode = "text"

    val newLink = NativeLink("link-${System.currentTimeMillis()}", newId, cropSel.color)
    links.add(newLink)
    undoRedoManager.record(CreateCardAction(
      card = card,
      link = newLink,
      cardsList = cards,
      linksList = links,
      onUndoDispatched = { c ->
        dispatchCardDeleteEvent(c.id)
        invalidate()
      },
      onRedoDispatched = { c, _ ->
        dispatchExtractExcerptEvent(
          c.text, c.pageNumber, c.color, c.isImage, c.imageUrl, c.x, c.y, c.id, c.sourceRects
        )
        invalidate()
      }
    ))
    triggerShockwave(resolved.x + cardW / 2f, resolved.y + cardH / 2f, cropSel.color)
    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    dispatchExtractExcerptEvent(
      text = card.text,
      pageNumber = card.pageNumber,
      color = card.color,
      isImg = card.isImage,
      imageUrl = card.imageUrl,
      x = card.x,
      y = card.y,
      cardId = card.id,
      sourceRects = cropSourceRects
    )
    hudToast.show("Extracted figure placed neatly with live Ink-Link!")
    invalidate()
  }

internal fun ThinkspaceView.tidyCards() {
    if (cards.isEmpty()) {
      hudToast.show("No cards to tidy")
      invalidate()
      return
    }
    var curX = 60f
    var curY = 40f
    for (c in cards) {
      c.x = curX
      c.y = curY
      curX += c.width + 24f
      if (curX > 600f) {
        curX = 60f
        curY += c.getHeight() + 24f
      }
    }
    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    hudToast.show("✨ Workspace tidied neatly!")
    invalidate()
  }

internal fun ThinkspaceView.addAnnotation(text: String, pageNumber: Int, color: Int, rects: List<RectF> = emptyList()) {
    val annId = "ann-${System.currentTimeMillis()}"
    val ann = NativeAnnotation(annId, "page-$pageNumber", 0, pageNumber, color, text, rects)
    annotations.add(ann)
    undoRedoManager.record(AddAnnotationAction(
      annotation = ann,
      annotationsList = annotations,
      onUndoDispatched = { invalidate() },
      onRedoDispatched = { invalidate() }
    ))
    invalidate()
  }

internal fun ThinkspaceView.eraseAnnotationNear(sx: Float, sy: Float): Boolean {
    val threshold = 28f * density
    val targetAnn = annotations.findLast { ann ->
      val pl = pageLayouts.find { it.pageNumber == ann.pageNumber }
      if (pl != null && !pl.isFolded) {
        val pSize = runCatching { activePdfDoc?.getPage(pl.pageIndex)?.size }.getOrNull()
          ?: com.thinkspace.pdfengine.model.PageSize.LETTER
        val pW = pSize.width
        val pH = pSize.height
        ann.rects.any { r ->
          val l = pl.boundsOnScreen.left + (r.left / pW) * pl.boundsOnScreen.width()
          val t = pl.boundsOnScreen.top + (r.top / pH) * pl.boundsOnScreen.height()
          val right = pl.boundsOnScreen.left + (r.right / pW) * pl.boundsOnScreen.width()
          val b = pl.boundsOnScreen.top + (r.bottom / pH) * pl.boundsOnScreen.height()
          val hRect = RectF(l - threshold, t - threshold, right + threshold, b + threshold)
          hRect.contains(sx, sy)
        }
      } else false
    }

    if (targetAnn != null) {
      annotations.remove(targetAnn)
      undoRedoManager.record(DeleteAnnotationAction(
        annotation = targetAnn,
        annotationsList = annotations,
        onUndoDispatched = { invalidate() },
        onRedoDispatched = { invalidate() }
      ))
      hudToast.show("🗑️ Highlight erased")
      performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
      invalidate()
      return true
    }
    return false
  }

internal fun ThinkspaceView.copyToClipboard(text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = ClipData.newPlainText("Excerpt", text)
    clipboard?.setPrimaryClip(clip)
    copiedToastText = "✓ Copied!"
    postDelayed({
      copiedToastText = null
      invalidate()
    }, 1500)
    invalidate()
  }

internal fun ThinkspaceView.eraseStrokesNear(wx: Float, wy: Float) {
    val threshold = 28f
    val toRemove = strokes.filter { s ->
      s.points.any { hypot(it.x - wx, it.y - wy) <= threshold }
    }
    if (toRemove.isNotEmpty()) {
      strokes.removeAll(toRemove)
      undoRedoManager.record(EraseStrokesAction(
        erasedStrokes = toRemove,
        strokesList = strokes,
        onUndoDispatched = { restored ->
          for (s in restored) {
            dispatchAddStrokeEvent(s)
          }
          invalidate()
        },
        onRedoDispatched = { removed ->
          for (s in removed) {
            dispatchEraseStrokeEvent(s.id)
          }
          invalidate()
        }
      ))
      for (s in toRemove) {
        dispatchEraseStrokeEvent(s.id)
      }
      invalidate()
    }

    val curCanvasTopY = if ((activePdfDoc != null || activeDocument != null) && effectiveSplitRatio > 0f) height * effectiveSplitRatio + 14f else 0f
    val erasedLinks = semanticInkLinks.filter { link ->
      val card = cards.find { it.id == link.targetCardId }
      if (card == null) false
      else {
        val srcWorldX: Float
        val srcWorldY: Float
        val pl = pageLayouts.find { it.pageIndex == link.sourcePageIndex }
        if (pl != null && link.sourceDocId == activeDocumentId) {
          val pSize = runCatching { activePdfDoc?.getPage(pl.pageIndex)?.size }.getOrNull()
            ?: com.thinkspace.pdfengine.model.PageSize.LETTER
          val rawSx = pl.boundsOnScreen.left + (link.sourcePdfPoint.x / pSize.width) * pl.boundsOnScreen.width()
          val rawSy = pl.boundsOnScreen.top + (link.sourcePdfPoint.y / pSize.height) * pl.boundsOnScreen.height()
          srcWorldX = camera.screenToWorldX(rawSx)
          srcWorldY = camera.screenToWorldY(rawSy, curCanvasTopY)
        } else {
          srcWorldX = card.x
          srcWorldY = card.y - 120f
        }
        val edge = getCardEdgeAnchor(card, srcWorldX, srcWorldY)
        val d = distToSegment(wx, wy, srcWorldX, srcWorldY, edge.x, edge.y)
        d <= threshold || hypot(edge.x - wx, edge.y - wy) <= threshold * 1.4f
      }
    }
    if (erasedLinks.isNotEmpty()) {
      semanticInkLinks.removeAll(erasedLinks)
      for (el in erasedLinks) {
        undoRedoManager.record(DeleteSemanticInkLinkAction(
          link = el,
          linksList = semanticInkLinks,
          onUndoDispatched = { l ->
            dispatchInkLinkCreateEvent(l)
            persistSemanticInkLinksLocally()
            invalidate()
          },
          onRedoDispatched = { l ->
            dispatchInkLinkDeleteEvent(l.id)
            persistSemanticInkLinksLocally()
            invalidate()
          }
        ))
        dispatchInkLinkDeleteEvent(el.id)
      }
      persistSemanticInkLinksLocally()
      invalidate()
    }
  }

internal fun ThinkspaceView.eraseDocStrokesNear(sx: Float, sy: Float): Boolean {
    var erasedAny = false
    for (pl in pageLayouts) {
      if (!pl.boundsOnScreen.contains(sx, sy)) continue
      val pSize = runCatching { activePdfDoc?.getPage(pl.pageIndex)?.size }.getOrNull()
        ?: com.thinkspace.pdfengine.model.PageSize.LETTER
      val pW = pSize.width
      val pH = pSize.height
      val px = ((sx - pl.boundsOnScreen.left) / pl.boundsOnScreen.width()) * pW
      val py = ((sy - pl.boundsOnScreen.top) / pl.boundsOnScreen.height()) * pH

      val list = pageStrokes[pl.pageIndex] ?: continue
      val threshold = 28f * (pW / pl.boundsOnScreen.width().coerceAtLeast(1f))
      val toRemove = list.filter { s ->
        s.points.any { hypot(it.x - px, it.y - py) <= threshold }
      }
      if (toRemove.isNotEmpty()) {
        list.removeAll(toRemove)
        undoRedoManager.record(ErasePageStrokesAction(
          erasedStrokes = toRemove,
          pageStrokesMap = pageStrokes,
          onUndoDispatched = { invalidate() },
          onRedoDispatched = { invalidate() }
        ))
        erasedAny = true
        invalidate()
      }

      val erasedDocLinks = semanticInkLinks.filter { link ->
        link.sourceDocId == activeDocumentId &&
        link.sourcePageIndex == pl.pageIndex &&
        hypot(link.sourcePdfPoint.x - px, link.sourcePdfPoint.y - py) <= threshold
      }
      if (erasedDocLinks.isNotEmpty()) {
        semanticInkLinks.removeAll(erasedDocLinks)
        for (el in erasedDocLinks) {
          undoRedoManager.record(DeleteSemanticInkLinkAction(
            link = el,
            linksList = semanticInkLinks,
            onUndoDispatched = { l ->
              dispatchInkLinkCreateEvent(l)
              persistSemanticInkLinksLocally()
              invalidate()
            },
            onRedoDispatched = { l ->
              dispatchInkLinkDeleteEvent(l.id)
              persistSemanticInkLinksLocally()
              invalidate()
            }
          ))
          dispatchInkLinkDeleteEvent(el.id)
        }
        persistSemanticInkLinksLocally()
        erasedAny = true
        invalidate()
      }
    }
    return erasedAny
  }

  private var docScrollAnimator: ValueAnimator? = null
  private var pulseAnimator: ValueAnimator? = null
  internal var canvasAnimator: ValueAnimator? = null

fun ThinkspaceView.scrollToPage(targetPageNum: Int) {
    post {
      scrollToDocumentPage(targetPageNum)
      invalidate()
    }
  }

internal fun ThinkspaceView.scrollToDocumentPage(targetPageNum: Int, sourceRects: List<RectF> = emptyList()) {
    val viewW = width.toFloat().coerceAtLeast(100f)
    val viewH = height.toFloat().coerceAtLeast(100f)
    val docBottomY = viewH * splitRatio
    val viewportH = max(100f, docBottomY - subheaderH)

    val targetScrollY: Float
    val safePageNum: Int

    if (activePdfDoc != null) {
      val pCount = activePdfDoc?.pageCount ?: 1
      safePageNum = targetPageNum.coerceIn(1, max(1, pCount))
      val pageIdx = safePageNum - 1

      val paperMargin = 10f * density
      val basePaperW = viewW - paperMargin * 2f
      val paperW = basePaperW * pdfScaleFactor
      val pSize = runCatching { activePdfDoc?.getPage(pageIdx)?.size }.getOrNull() ?: com.thinkspace.pdfengine.model.PageSize.LETTER
      val docAspectRatio = if (pSize.width > 0f) pSize.height / pSize.width else 1.294f
      val standardPageH = paperW * docAspectRatio
      val standardGap = 16f * pdfScaleFactor
      val totalDocH = compressionEngine.getTotalDocHeight(standardPageH, standardGap)
      val maxScroll = max(0f, totalDocH - (docBottomY - subheaderH) + 60f)

      val pageTopDocY = compressionEngine.getPageTopDocY(pageIdx, standardPageH, standardGap)
      val displayedH = compressionEngine.getDisplayedPageHeight(pageIdx, standardPageH)

      targetScrollY = if (sourceRects.isNotEmpty()) {
        val minY = sourceRects.minOf { it.top }
        val maxY = sourceRects.maxOf { it.bottom }
        val centerPdfY = (minY + maxY) / 2f
        val pdfH = pSize.height
        val scale = if (pdfH > 0f) displayedH / pdfH else 1f
        val sourceOffset = centerPdfY * scale
        val sourceDocY = pageTopDocY + sourceOffset
        (sourceDocY - viewportH / 2f + 16f).coerceIn(0f, maxScroll)
      } else {
        pageTopDocY.coerceIn(0f, maxScroll)
      }
    } else {
      // Structured document mode
      safePageNum = targetPageNum
      val targetP = paragraphLayouts.find { it.pageNumber == targetPageNum }
      targetScrollY = if (targetP != null) {
        (docScrollY + targetP.topY - subheaderH - 16f).coerceIn(0f, maxDocScrollY)
      } else {
        0f
      }
    }

    docScrollAnimator?.cancel()
    val animator = ValueAnimator.ofFloat(docScrollY, targetScrollY).apply {
      duration = 420
      interpolator = DecelerateInterpolator()
      addUpdateListener {
        docScrollY = it.animatedValue as Float
        invalidate()
      }
      start()
    }
    docScrollAnimator = animator

    pulsePageNumber = safePageNum
    pulseSourceRects = sourceRects
    pulseAlpha = 240

    pulseAnimator?.cancel()
    val pulseAnim = ValueAnimator.ofInt(240, 0).apply {
      duration = 1400
      addUpdateListener {
        pulseAlpha = it.animatedValue as Int
        invalidate()
      }
      start()
    }
    pulseAnimator = pulseAnim

    // Center horizontally if zoomed in
    if (activePdfDoc != null && sourceRects.isNotEmpty() && maxDocScrollX > 0f) {
      val minX = sourceRects.minOf { it.left }
      val maxX = sourceRects.maxOf { it.right }
      val centerPdfX = (minX + maxX) / 2f
      val pSize = runCatching { activePdfDoc?.getPage(safePageNum - 1)?.size }.getOrNull() ?: com.thinkspace.pdfengine.model.PageSize.LETTER
      val pdfW = pSize.width
      val paperMargin = 10f * density
      val basePaperW = viewW - paperMargin * 2f
      val paperW = basePaperW * pdfScaleFactor
      val scaleX = if (pdfW > 0f) paperW / pdfW else 1f
      val sourceX = centerPdfX * scaleX
      docScrollX = (sourceX - basePaperW / 2f).coerceIn(0f, maxDocScrollX)
    }

    invalidate()
  }
