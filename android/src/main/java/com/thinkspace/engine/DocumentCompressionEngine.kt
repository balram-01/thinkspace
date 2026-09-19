package com.thinkspace.engine

import android.animation.ValueAnimator
import android.graphics.RectF
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * State for a single PDF page in the compression layout.
 */
data class PageCompressionState(
  val pageIndex: Int,
  var compressionFactor: Float = 1.0f,     // 1.0 = full height, ~0.08 = compressed
  var targetCompressionFactor: Float = 1.0f,
  var hasSearchMatch: Boolean = false,
  var matchCount: Int = 0,
  var hasAnnotations: Boolean = false,
  val annotationColors: MutableList<Int> = mutableListOf()
)

/**
 * Real PDF Document Compression Engine (LiquidText style).
 * Dynamically calculates displayed page heights and positions for all PDF pages.
 * Supports Search-driven auto-compression, highlight-preserving pinch, and two-finger manual pinch compression.
 */
class DocumentCompressionEngine(private val density: Float) {

  // Compression factor tuning parameters
  val minCompressionFactor: Float = 0.08f // Non-matching / non-annotated page height ratio (~40-60dp)
  val maxCompressionFactor: Float = 1.0f  // Fully expanded matching / annotated / normal page

  // Per-page compression states (indexed by pageIndex 0 until pageCount)
  private val pageStates = mutableListOf<PageCompressionState>()

  // Manual two-finger pinch compression tracking
  var isManualPinching: Boolean = false
    private set
  private var pinchAnchorTopPageIndex: Int = -1
  private var pinchAnchorBottomPageIndex: Int = -1
  private var isDocumentWideHighlightPinch: Boolean = false
  private var pinchInitialFingerDistance: Float = 0f
  private var pinchInitialFactors = mutableMapOf<Int, Float>()

  // Animation controller
  private var compressionAnimator: ValueAnimator? = null

  /**
   * Initializes or updates page count.
   */
  fun ensurePageCount(pCount: Int) {
    if (pageStates.size == pCount) return
    pageStates.clear()
    for (i in 0 until pCount) {
      pageStates.add(PageCompressionState(pageIndex = i))
    }
  }

  fun getPageCount(): Int = pageStates.size

  fun getCompressionFactor(pageIndex: Int): Float {
    return pageStates.getOrNull(pageIndex)?.compressionFactor ?: 1.0f
  }

  fun isPageCompressed(pageIndex: Int): Boolean {
    return (pageStates.getOrNull(pageIndex)?.compressionFactor ?: 1.0f) < 0.55f
  }

  fun hasSearchMatch(pageIndex: Int): Boolean {
    return pageStates.getOrNull(pageIndex)?.hasSearchMatch ?: false
  }

  fun hasAnnotations(pageIndex: Int): Boolean {
    return pageStates.getOrNull(pageIndex)?.hasAnnotations ?: false
  }

  fun getAnnotationColors(pageIndex: Int): List<Int> {
    return pageStates.getOrNull(pageIndex)?.annotationColors ?: emptyList()
  }

  fun isAnyPageCompressed(): Boolean {
    return pageStates.any { it.compressionFactor < 0.85f }
  }

  /**
   * Updates annotation / highlight metadata for pages across the document.
   */
  fun updateAnnotationData(annotatedPageIndices: Set<Int>, colorsByPage: Map<Int, List<Int>> = emptyMap()) {
    for (state in pageStates) {
      val isAnnotated = annotatedPageIndices.contains(state.pageIndex)
      state.hasAnnotations = isAnnotated
      state.annotationColors.clear()
      colorsByPage[state.pageIndex]?.let {
        state.annotationColors.addAll(it)
      }
    }
  }

  /**
   * Calculates displayed height for a given page.
   */
  fun getDisplayedPageHeight(pageIndex: Int, standardPageH: Float): Float {
    val factor = getCompressionFactor(pageIndex)
    return max(34f * density, standardPageH * factor)
  }

  /**
   * Calculates page gap.
   */
  fun getPageGap(pageIndex: Int, standardGap: Float): Float {
    val factor = getCompressionFactor(pageIndex)
    return max(4f * density, standardGap * factor)
  }

  /**
   * Computes the cumulative uncollapsed or compressed Y position of a page top.
   */
  fun getPageTopDocY(pageIndex: Int, standardPageH: Float, standardGap: Float): Float {
    var accumY = 0f
    val limit = min(pageIndex, pageStates.size)
    for (i in 0 until limit) {
      accumY += getDisplayedPageHeight(i, standardPageH) + getPageGap(i, standardGap)
    }
    return accumY
  }

  /**
   * Computes the total height of the document with all current compression factors applied.
   */
  fun getTotalDocHeight(standardPageH: Float, standardGap: Float): Float {
    return getPageTopDocY(pageStates.size, standardPageH, standardGap)
  }

  // ---------------------------------------------------------------------------
  // 1. Search Mode: Automatic Page Compression
  // ---------------------------------------------------------------------------

  /**
   * Applies search matches across the document.
   * Pages with matches remain expanded (factor = 1.0f).
   * Pages without matches are compressed down to minCompressionFactor (~0.08f).
   */
  fun applySearchMatches(
    matchingPageIndices: Set<Int>,
    animate: Boolean = true,
    onUpdate: () -> Unit
  ) {
    if (pageStates.isEmpty()) return

    for (state in pageStates) {
      val isMatch = matchingPageIndices.contains(state.pageIndex)
      state.hasSearchMatch = isMatch
      state.targetCompressionFactor = if (isMatch) maxCompressionFactor else minCompressionFactor
    }

    if (animate) {
      animateCompressionTransition(onUpdate)
    } else {
      for (state in pageStates) {
        state.compressionFactor = state.targetCompressionFactor
      }
      onUpdate()
    }
  }

  /**
   * Collapses all pages that do NOT have highlights or annotations.
   * Pages with highlights stay expanded to compare notes across 100+ pages.
   */
  fun collapseUnhighlightedPages(
    annotatedPageIndices: Set<Int>,
    animate: Boolean = true,
    onUpdate: () -> Unit
  ) {
    if (pageStates.isEmpty()) return

    for (state in pageStates) {
      val keepExpanded = annotatedPageIndices.contains(state.pageIndex) || state.hasSearchMatch
      state.targetCompressionFactor = if (keepExpanded) maxCompressionFactor else minCompressionFactor
    }

    if (animate) {
      animateCompressionTransition(onUpdate)
    } else {
      for (state in pageStates) {
        state.compressionFactor = state.targetCompressionFactor
      }
      onUpdate()
    }
  }

  /**
   * Resets all pages to normal uncompressed view (factor = 1.0f).
   */
  fun resetAllToNormal(animate: Boolean = true, onUpdate: () -> Unit) {
    if (pageStates.isEmpty()) return

    for (state in pageStates) {
      state.hasSearchMatch = false
      state.matchCount = 0
      state.targetCompressionFactor = maxCompressionFactor
    }

    if (animate) {
      animateCompressionTransition(onUpdate)
    } else {
      for (state in pageStates) {
        state.compressionFactor = maxCompressionFactor
      }
      onUpdate()
    }
  }

  /**
   * Expands an individual page (e.g. when user taps on a compressed page).
   */
  fun expandPage(pageIndex: Int, animate: Boolean = true, onUpdate: () -> Unit) {
    val state = pageStates.getOrNull(pageIndex) ?: return
    state.targetCompressionFactor = maxCompressionFactor
    if (animate) {
      animateCompressionTransition(onUpdate)
    } else {
      state.compressionFactor = maxCompressionFactor
      onUpdate()
    }
  }

  /**
   * Smoothly animates all page compression factors toward targetCompressionFactor.
   */
  private fun animateCompressionTransition(onUpdate: () -> Unit) {
    compressionAnimator?.cancel()

    val startFactors = pageStates.map { it.compressionFactor }.toFloatArray()
    val targetFactors = pageStates.map { it.targetCompressionFactor }.toFloatArray()

    compressionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = 280L
      interpolator = DecelerateInterpolator(1.8f)
      addUpdateListener { anim ->
        val fraction = anim.animatedValue as Float
        for (i in pageStates.indices) {
          pageStates[i].compressionFactor = startFactors[i] + (targetFactors[i] - startFactors[i]) * fraction
        }
        onUpdate()
      }
      start()
    }
  }

  // ---------------------------------------------------------------------------
  // 2. Manual Two-Finger Pinch Compression
  // ---------------------------------------------------------------------------

  /**
   * Begins manual pinch compression.
   * Accurately distinguishes between range pinch (between two distant fingers)
   * and highlight-seeking document pinch (pinching anywhere on a 100+ page PDF to bring notes together).
   */
  fun onManualPinchBegin(
    screenY1: Float,
    screenY2: Float,
    pageBounds: List<RectF>,
    pageIndices: List<Int>,
    annotatedPageIndices: Set<Int>
  ): Boolean {
    val topScreenY = min(screenY1, screenY2)
    val bottomScreenY = max(screenY1, screenY2)
    val fingerDist = max(30f * density, bottomScreenY - topScreenY)

    // Find anchor pages under finger 1 and finger 2
    var topPage = -1
    var bottomPage = -1

    for (idx in pageBounds.indices) {
      val r = pageBounds[idx]
      val pIdx = pageIndices.getOrElse(idx) { idx }
      if (topPage == -1 && topScreenY <= r.bottom) {
        topPage = pIdx
      }
      if (bottomScreenY <= r.bottom) {
        bottomPage = pIdx
        break
      }
    }

    if (bottomPage == -1 && pageIndices.isNotEmpty()) {
      bottomPage = pageIndices.last()
    }

    pinchInitialFingerDistance = fingerDist
    pinchInitialFactors.clear()

    if (topPage != -1 && bottomPage != -1 && bottomPage > topPage + 1) {
      // Local range pinch: compress non-annotated pages strictly between finger A and finger B
      isDocumentWideHighlightPinch = false
      pinchAnchorTopPageIndex = topPage
      pinchAnchorBottomPageIndex = bottomPage
      for (i in (topPage + 1) until bottomPage) {
        pinchInitialFactors[i] = pageStates.getOrNull(i)?.compressionFactor ?: 1.0f
      }
    } else {
      // Document-wide highlight pinch (LiquidText signature interaction for 100+ pages)
      // When pinching anywhere, all non-annotated / non-matching pages collapse so the user
      // sees all highlighted colors and sections come together!
      isDocumentWideHighlightPinch = true
      pinchAnchorTopPageIndex = -1
      pinchAnchorBottomPageIndex = -1
      for (i in pageStates.indices) {
        pinchInitialFactors[i] = pageStates[i].compressionFactor
      }
    }

    isManualPinching = true
    return true
  }

  /**
   * Called as fingers move closer or farther apart.
   * Progressively compresses or expands the content.
   * Pages with user highlights or search matches stay expanded and clearly visible!
   */
  fun onManualPinchMove(screenY1: Float, screenY2: Float): Boolean {
    if (!isManualPinching || pinchInitialFingerDistance <= 0f) return false

    val currentDist = abs(screenY1 - screenY2)
    val distanceRatio = (currentDist / pinchInitialFingerDistance).coerceIn(0.06f, 1.4f)

    if (isDocumentWideHighlightPinch) {
      // Document-wide pinch:
      // Pages with annotations or search matches remain fully expanded (1.0x) so the user
      // can see what and where they highlighted!
      // Unannotated pages compress smoothly down to minCompressionFactor (~0.08x).
      for (state in pageStates) {
        val hasContentToRead = state.hasAnnotations || state.hasSearchMatch
        if (hasContentToRead) {
          state.compressionFactor = maxCompressionFactor
          state.targetCompressionFactor = maxCompressionFactor
        } else {
          val initial = pinchInitialFactors[state.pageIndex] ?: 1.0f
          val newFactor = (initial * distanceRatio).coerceIn(minCompressionFactor, maxCompressionFactor)
          state.compressionFactor = newFactor
          state.targetCompressionFactor = newFactor
        }
      }
    } else {
      // Range pinch between anchor fingers
      for (i in (pinchAnchorTopPageIndex + 1) until pinchAnchorBottomPageIndex) {
        val state = pageStates.getOrNull(i) ?: continue
        val hasContentToRead = state.hasAnnotations || state.hasSearchMatch
        if (hasContentToRead) {
          state.compressionFactor = maxCompressionFactor
          state.targetCompressionFactor = maxCompressionFactor
        } else {
          val initial = pinchInitialFactors[i] ?: 1.0f
          val newFactor = (initial * distanceRatio).coerceIn(minCompressionFactor, maxCompressionFactor)
          state.compressionFactor = newFactor
          state.targetCompressionFactor = newFactor
        }
      }
    }

    return true
  }

  /**
   * Called when fingers are lifted.
   * The compressed state remains so the user can compare distant sections and notes.
   */
  fun onManualPinchEnd(onUpdate: () -> Unit, onHaptic: () -> Unit) {
    if (!isManualPinching) return

    val anyCompressed = pageStates.any { it.compressionFactor < 0.85f }
    if (anyCompressed) {
      onHaptic()
    }

    isManualPinching = false
    pinchAnchorTopPageIndex = -1
    pinchAnchorBottomPageIndex = -1
    isDocumentWideHighlightPinch = false
    pinchInitialFingerDistance = 0f
    pinchInitialFactors.clear()

    onUpdate()
  }
}
