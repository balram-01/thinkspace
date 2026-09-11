package com.thinkspace.engine

import android.graphics.PointF

/**
 * Encapsulates the 2D infinite canvas camera viewport state and coordinate transformations.
 * Designed for zero-allocation performance on high-frequency gesture and draw hot paths (120 FPS).
 */
class CameraState(
  initialPanX: Float = 0f,
  initialPanY: Float = 0f,
  initialScale: Float = 1.0f,
  val minScale: Float = 0.1f,
  val maxScale: Float = 5.0f
) {
  var panX: Float = initialPanX
  var panY: Float = initialPanY
  var scaleFactor: Float = initialScale

  // ---------------------------------------------------------------------------
  // Zero-Allocation Coordinate Conversions (Scalars)
  // ---------------------------------------------------------------------------

  @Suppress("NOTHING_TO_INLINE")
  inline fun screenToWorldX(sx: Float): Float {
    return (sx - panX) / scaleFactor
  }

  @Suppress("NOTHING_TO_INLINE")
  inline fun screenToWorldY(sy: Float, canvasTopY: Float): Float {
    return (sy - canvasTopY - panY) / scaleFactor
  }

  @Suppress("NOTHING_TO_INLINE")
  inline fun worldToScreenX(wx: Float): Float {
    return wx * scaleFactor + panX
  }

  @Suppress("NOTHING_TO_INLINE")
  inline fun worldToScreenY(wy: Float, canvasTopY: Float): Float {
    return wy * scaleFactor + panY + canvasTopY
  }

  /**
   * Transforms screen viewport coordinates to world canvas coordinates into a reusable [outPoint].
   */
  fun screenToWorld(sx: Float, sy: Float, canvasTopY: Float, outPoint: PointF): PointF {
    outPoint.x = screenToWorldX(sx)
    outPoint.y = screenToWorldY(sy, canvasTopY)
    return outPoint
  }

  /**
   * Transforms world canvas coordinates to screen viewport coordinates into a reusable [outPoint].
   */
  fun worldToScreen(wx: Float, wy: Float, canvasTopY: Float, outPoint: PointF): PointF {
    outPoint.x = worldToScreenX(wx)
    outPoint.y = worldToScreenY(wy, canvasTopY)
    return outPoint
  }

  // ---------------------------------------------------------------------------
  // Viewport Transformations
  // ---------------------------------------------------------------------------

  /**
   * Applies focal-aware pinch zoom anchoring directly around the user's fingers.
   * Prevents viewport jump during multi-touch scaling.
   */
  fun applyPinch(focalX: Float, focalY: Float, zoomFactor: Float) {
    val newScale = (scaleFactor * zoomFactor).coerceIn(minScale, maxScale)
    val ratio = newScale / scaleFactor
    panX = focalX - (focalX - panX) * ratio
    panY = focalY - (focalY - panY) * ratio
    scaleFactor = newScale
  }

  /**
   * Pans the canvas by delta pixels.
   */
  fun applyPan(dx: Float, dy: Float) {
    panX += dx
    panY += dy
  }

  /**
   * Resets viewport to default origin and scale.
   */
  fun reset() {
    panX = 0f
    panY = 0f
    scaleFactor = 1.0f
  }
}
