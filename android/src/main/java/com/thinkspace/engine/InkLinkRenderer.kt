package com.thinkspace.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.max
import kotlin.math.min

/**
 * Zero-allocation, hardware-accelerated renderer for elastic margin tethers (Ink-Links).
 * Implements smooth cubic Bézier splines, dynamic margin projection, and glowing anchor pins.
 */
class InkLinkRenderer(density: Float = 1.0f) {

  // Reusable Path to eliminate allocations on the 120 FPS draw path
  private val tetherPath = Path()

  // Pre-allocated DashPathEffects
  private val defaultDashEffect = DashPathEffect(floatArrayOf(12f * density, 8f * density), 0f)
  private val activeDashEffect = DashPathEffect(floatArrayOf(14f * density, 6f * density), 0f)

  // Pre-allocated Paint instances
  private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 6f * density
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }

  private val cordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 1.8f * density
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
    pathEffect = defaultDashEffect
  }

  private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
  }

  private val innerPinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
    color = Color.WHITE
  }

  /**
   * Draws a cubic Bézier tether from source anchor [startX, startY] to card anchor [endX, endY].
   */
  fun drawTether(
    canvas: Canvas,
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    color: Int,
    isHeld: Boolean = false
  ) {
    tetherPath.reset()
    tetherPath.moveTo(startX, startY)

    val spanX = max(24f, endX - startX)
    val spanY = endY - startY

    // Cubic Bézier control points for natural elastic cord curvature
    val cp1X = startX + spanX * 0.35f
    val cp1Y = startY + spanY * 0.15f + (if (isHeld) 16f else 8f)
    val cp2X = startX + spanX * 0.68f
    val cp2Y = endY - (if (isHeld) 14f else 8f)

    tetherPath.cubicTo(cp1X, cp1Y, cp2X, cp2Y, endX, endY)

    // 1. Halo Glow (rendered when card is held or active)
    if (isHeld) {
      haloPaint.color = color
      haloPaint.alpha = 75
      haloPaint.strokeWidth = 6.5f
      canvas.drawPath(tetherPath, haloPaint)
    }

    // 2. Dotted Elastic Cord
    cordPaint.color = color
    cordPaint.alpha = if (isHeld) 245 else 170
    cordPaint.strokeWidth = if (isHeld) 2.6f else 1.8f
    cordPaint.pathEffect = if (isHeld) activeDashEffect else defaultDashEffect
    canvas.drawPath(tetherPath, cordPaint)

    // 3. Anchor Node Pins
    val outerRadius = if (isHeld) 5.5f else 4.0f
    pinPaint.color = color
    canvas.drawCircle(startX, startY, outerRadius, pinPaint)
    canvas.drawCircle(endX, endY, outerRadius, pinPaint)

    // Center white dot on anchor pins for high-contrast visibility
    if (isHeld) {
      canvas.drawCircle(startX, startY, 2.0f, innerPinPaint)
      canvas.drawCircle(endX, endY, 2.0f, innerPinPaint)
    }
  }

  /**
   * Draws an elastic tether dynamically projected towards the left margin border (§8 of spec).
   */
  fun drawProjectedTether(
    canvas: Canvas,
    cardWorldX: Float,
    cardWorldY: Float,
    color: Int,
    isHeld: Boolean,
    camera: CameraState
  ) {
    val endX = cardWorldX + 14f
    val endY = cardWorldY + 16f

    // Dynamic off-screen projection past the left visible screen border
    val viewLeftWorldX = camera.screenToWorldX(-60f)
    val startX = min(viewLeftWorldX, endX - 70f)
    val startY = endY - 6f

    drawTether(canvas, startX, startY, endX, endY, color, isHeld)
  }
}
