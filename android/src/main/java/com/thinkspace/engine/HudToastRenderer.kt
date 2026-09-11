package com.thinkspace.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import kotlin.math.min

/**
 * Renders the bottom floating toast notifications matching the video:
 *  - "Extracted figure placed neatly with live Ink-Link!"
 *  - "Excerpt placed without overlap with live Ink-Link!"
 *  - "Magnetically stacked with nearby card!"
 */
class HudToastRenderer(private val density: Float) {

  private var currentMessage: String? = null
  private var showStartTime: Long = 0L
  private val displayDurationMs: Long = 2800L
  private val fadeDurationMs: Long = 300L

  private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#0F172A")
    style = Paint.Style.FILL
  }

  private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#00ADB5")
    strokeWidth = 1.5f * density
    style = Paint.Style.STROKE
  }

  private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#00ADB5")
    strokeWidth = 4f * density
    style = Paint.Style.STROKE
    alpha = 60
  }

  private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#00ADB5")
    style = Paint.Style.FILL
  }

  private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    textSize = 13f * density
    isFakeBoldText = true
  }

  private val toastRect = RectF()

  fun show(message: String) {
    currentMessage = message
    showStartTime = System.currentTimeMillis()
  }

  val isShowing: Boolean
    get() {
      if (currentMessage == null) return false
      val elapsed = System.currentTimeMillis() - showStartTime
      return elapsed < displayDurationMs
    }

  fun draw(canvas: Canvas, viewWidth: Float, bottomAnchorY: Float) {
    val message = currentMessage ?: return
    val elapsed = System.currentTimeMillis() - showStartTime
    if (elapsed >= displayDurationMs) {
      currentMessage = null
      return
    }

    // Calculate alpha (fade in -> solid -> fade out)
    val alpha = when {
      elapsed < fadeDurationMs -> (elapsed.toFloat() / fadeDurationMs).coerceIn(0f, 1f)
      elapsed > (displayDurationMs - fadeDurationMs) -> {
        val remaining = displayDurationMs - elapsed
        (remaining.toFloat() / fadeDurationMs).coerceIn(0f, 1f)
      }
      else -> 1f
    }

    val alphaInt = (alpha * 255).toInt()
    bgPaint.alpha = (alpha * 230).toInt()
    borderPaint.alpha = alphaInt
    glowPaint.alpha = (alpha * 60).toInt()
    dotPaint.alpha = alphaInt
    textPaint.alpha = alphaInt

    val textW = textPaint.measureText(message)
    val toastH = 38f * density
    val toastW = min(viewWidth - 32f * density, textW + 52f * density)
    val left = (viewWidth - toastW) / 2f
    val top = bottomAnchorY - toastH - 12f * density

    toastRect.set(left, top, left + toastW, top + toastH)
    val cornerRadius = toastH / 2f

    // Outer glow
    canvas.drawRoundRect(toastRect, cornerRadius, cornerRadius, glowPaint)
    // Background
    canvas.drawRoundRect(toastRect, cornerRadius, cornerRadius, bgPaint)
    // Border
    canvas.drawRoundRect(toastRect, cornerRadius, cornerRadius, borderPaint)

    // Glowing cyan status indicator dot
    val dotRadius = 4f * density
    val dotX = left + 18f * density
    val dotY = top + toastH / 2f
    canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)

    // Text
    val textX = dotX + 12f * density
    val textY = top + (toastH / 2f) + (textPaint.textSize / 3f)
    canvas.drawText(message, textX, textY, textPaint)
  }
}
