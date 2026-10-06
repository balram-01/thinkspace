package com.thinkspace

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import com.thinkspace.engine.models.NativeCard
import kotlin.math.max
import kotlin.math.min

/**
 * Extension functions for ThinkspaceView: Selection Callout bar, Card Action Bar,
 * Typography Bar, Style Sheet Popover, and Color Palettes rendering.
 */
  /**
   * Draws the sleek, minimalist LiquidText-style selection callout bar matching competitor screenshot:
   * - Rounded slate-gray pill background (#5A6B82) with subtle crisp border (#72849B) and layered drop shadows
   * - Row 1: "Comment" | "AutoExcerpt" | "Bookmark" | "•••" (cyan dots)
   * - Row 2: 5 bright color dots | Clear dot (white with slash) | Rainbow spectrum dot | "|" | "Tags"
   */
internal fun ThinkspaceView.drawCompetitorSelectionCallout(
    canvas: Canvas,
    calloutR: RectF,
    commentBtn: RectF,
    excerptBtn: RectF,
    bookmarkBtn: RectF,
    moreBtn: RectF,
    colorBtns: List<Pair<RectF, Int>>,
    clearBtn: RectF,
    rainbowBtn: RectF,
    tagsBtn: RectF,
    activeColor: Int
  ) {
    val d = density

    // 1. Layered soft drop shadows
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    shadowPaint.color = Color.argb(35, 0, 0, 0)
    canvas.drawRoundRect(calloutR.left - 1f * d, calloutR.top + 2f * d, calloutR.right + 1f * d, calloutR.bottom + 8f * d, 16f * d, 16f * d, shadowPaint)
    shadowPaint.color = Color.argb(45, 0, 0, 0)
    canvas.drawRoundRect(calloutR.left, calloutR.top + 1f * d, calloutR.right, calloutR.bottom + 4f * d, 14f * d, 14f * d, shadowPaint)

    // 2. Slate-gray Card Background & Crisp Border
    val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#5A6B82")
      style = Paint.Style.FILL
    }
    val cardBrdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#72849B")
      strokeWidth = 1f * d
      style = Paint.Style.STROKE
    }
    canvas.drawRoundRect(calloutR, 14f * d, 14f * d, cardBgPaint)
    canvas.drawRoundRect(calloutR, 14f * d, 14f * d, cardBrdPaint)

    // 3. Row 1: Actions (Comment, AutoExcerpt, Bookmark, •••)
    val actionTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 12.5f * d
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textAlign = Paint.Align.CENTER
    }
    val actFm = actionTextPaint.fontMetrics
    val actShift = (actFm.descent + actFm.ascent) / 2f

    // "Comment"
    canvas.drawText("Comment", commentBtn.centerX(), commentBtn.centerY() - actShift, actionTextPaint)

    // "AutoExcerpt"
    canvas.drawText("AutoExcerpt", excerptBtn.centerX(), excerptBtn.centerY() - actShift, actionTextPaint)

    // "Bookmark"
    canvas.drawText("Bookmark", bookmarkBtn.centerX(), bookmarkBtn.centerY() - actShift, actionTextPaint)

    // "•••" (More) in light cyan / blue dots matching reference screenshot
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#38BDF8")
      style = Paint.Style.FILL
    }
    val moreCx = moreBtn.centerX()
    val moreCy = moreBtn.centerY()
    val dotR = 2.2f * d
    val dotSpacing = 5f * d
    canvas.drawCircle(moreCx - dotSpacing, moreCy, dotR, dotPaint)
    canvas.drawCircle(moreCx, moreCy, dotR, dotPaint)
    canvas.drawCircle(moreCx + dotSpacing, moreCy, dotR, dotPaint)

    // 4. Row 2: Color Swatches
    val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val swatchRad = 9.5f * d

    for (cb in colorBtns) {
      val cx = cb.first.centerX()
      val cy = cb.first.centerY()
      val col = cb.second
      val isSelected = (col == activeColor)

      // Active selection ring
      if (isSelected) {
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.WHITE
          strokeWidth = 2f * d
          style = Paint.Style.STROKE
        }
        canvas.drawCircle(cx, cy, swatchRad + 3f * d, haloPaint)
      }

      circlePaint.color = col
      canvas.drawCircle(cx, cy, swatchRad, circlePaint)
    }

    // 5. Clear / Remove Highlight Swatch (White circle with diagonal slash)
    val clearCx = clearBtn.centerX()
    val clearCy = clearBtn.centerY()
    circlePaint.color = Color.WHITE
    canvas.drawCircle(clearCx, clearCy, swatchRad, circlePaint)
    val slashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#64748B")
      strokeWidth = 1.8f * d
      strokeCap = Paint.Cap.ROUND
    }
    val slashLen = 5.5f * d
    canvas.drawLine(clearCx - slashLen, clearCy + slashLen, clearCx + slashLen, clearCy - slashLen, slashPaint)

    // 6. Rainbow Spectrum Swatch
    val rainCx = rainbowBtn.centerX()
    val rainCy = rainbowBtn.centerY()
    val rainbowShader = android.graphics.SweepGradient(
      rainCx, rainCy,
      intArrayOf(
        Color.parseColor("#EF4444"),
        Color.parseColor("#F59E0B"),
        Color.parseColor("#10B981"),
        Color.parseColor("#3B82F6"),
        Color.parseColor("#8B5CF6"),
        Color.parseColor("#EC4899"),
        Color.parseColor("#EF4444")
      ),
      null
    )
    val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      shader = rainbowShader
      style = Paint.Style.FILL
    }
    canvas.drawCircle(rainCx, rainCy, swatchRad, rainPaint)

    // 7. Vertical Divider Line `|` before Tags
    val divX = (rainCx + swatchRad + tagsBtn.left) / 2f
    val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#78889B")
      strokeWidth = 1f * d
    }
    canvas.drawLine(divX, clearCy - 9f * d, divX, clearCy + 9f * d, divPaint)

    // 8. Tags Button: 🏷️ Tags
    val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      strokeWidth = 1.3f * d
      strokeCap = Paint.Cap.ROUND
      strokeJoin = Paint.Join.ROUND
      style = Paint.Style.STROKE
    }
    val tagCy = clearCy
    val tagLeft = tagsBtn.left + 2f * d
    val tagPath = Path().apply {
      moveTo(tagLeft + 2.5f * d, tagCy - 4.5f * d)
      lineTo(tagLeft + 7.5f * d, tagCy - 4.5f * d)
      lineTo(tagLeft + 11.5f * d, tagCy)
      lineTo(tagLeft + 5.5f * d, tagCy + 5.5f * d)
      lineTo(tagLeft + 2.5f * d, tagCy + 1.5f * d)
      close()
    }
    canvas.drawPath(tagPath, tagPaint)
    val tagHole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      style = Paint.Style.FILL
    }
    canvas.drawCircle(tagLeft + 4.8f * d, tagCy - 1.8f * d, 0.9f * d, tagHole)

    val tagsTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 12f * d
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val tagFm = tagsTextPaint.fontMetrics
    canvas.drawText("Tags", tagLeft + 15f * d, tagCy - (tagFm.descent + tagFm.ascent) / 2f, tagsTextPaint)
  }


  // ── Vector Icon Drawing Helpers for Apple Selection Toolbar ────────────────
internal fun ThinkspaceView.drawCommentIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
    val r = size * 0.46f
    val rect = RectF(cx - r, cy - r * 0.8f, cx + r, cy + r * 0.65f)
    val corner = 4.5f * density
    val path = Path().apply {
      addRoundRect(rect, corner, corner, Path.Direction.CW)
      moveTo(cx - r * 0.4f, cy + r * 0.65f)
      lineTo(cx - r * 0.75f, cy + r * 1.15f)
      lineTo(cx - r * 0.1f, cy + r * 0.65f)
      close()
    }
    val fillPaint = Paint(paint).apply { style = Paint.Style.FILL; color = Color.WHITE }
    canvas.drawPath(path, fillPaint)
  }

internal fun ThinkspaceView.drawEditIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
    val s = size * 0.44f
    val path = Path().apply {
      moveTo(cx + s * 0.65f, cy - s * 0.85f)
      lineTo(cx + s * 0.88f, cy - s * 0.62f)
      lineTo(cx - s * 0.35f, cy + s * 0.62f)
      lineTo(cx - s * 0.88f, cy + s * 0.88f)
      lineTo(cx - s * 0.62f, cy + s * 0.35f)
      close()
    }
    val fillPaint = Paint(paint).apply { style = Paint.Style.FILL; color = Color.WHITE }
    canvas.drawPath(path, fillPaint)
  }

internal fun ThinkspaceView.drawCopyIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint, bgCol: Int) {
    val s = size * 0.42f
    val corner = 2.5f * density
    val strokeP = Paint(paint).apply {
      style = Paint.Style.STROKE
      strokeWidth = 1.8f * density
      color = Color.WHITE
    }
    // Back doc
    canvas.drawRoundRect(RectF(cx - s * 0.35f, cy - s * 0.85f, cx + s * 0.85f, cy + s * 0.35f), corner, corner, strokeP)
    // Front doc background erase
    val eraseP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgCol; style = Paint.Style.FILL }
    val frontRect = RectF(cx - s * 0.85f, cy - s * 0.35f, cx + s * 0.35f, cy + s * 0.85f)
    canvas.drawRoundRect(frontRect, corner, corner, eraseP)
    // Front doc stroke
    canvas.drawRoundRect(frontRect, corner, corner, strokeP)
  }

internal fun ThinkspaceView.drawDeleteIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
    val s = size * 0.44f
    val strokeP = Paint(paint).apply {
      style = Paint.Style.STROKE
      strokeWidth = 1.8f * density
      strokeCap = Paint.Cap.ROUND
      color = Color.parseColor("#FF6B6B")
    }
    // Lid
    canvas.drawLine(cx - s * 0.85f, cy - s * 0.5f, cx + s * 0.85f, cy - s * 0.5f, strokeP)
    // Handle
    canvas.drawRoundRect(RectF(cx - s * 0.35f, cy - s * 0.85f, cx + s * 0.35f, cy - s * 0.5f), 1.8f * density, 1.8f * density, strokeP)
    // Can body
    val body = Path().apply {
      moveTo(cx - s * 0.65f, cy - s * 0.5f)
      lineTo(cx - s * 0.5f, cy + s * 0.85f)
      lineTo(cx + s * 0.5f, cy + s * 0.85f)
      lineTo(cx + s * 0.65f, cy - s * 0.5f)
    }
    canvas.drawPath(body, strokeP)
    // Vertical slats
    canvas.drawLine(cx - s * 0.22f, cy - s * 0.2f, cx - s * 0.18f, cy + s * 0.6f, strokeP)
    canvas.drawLine(cx + s * 0.22f, cy - s * 0.2f, cx + s * 0.18f, cy + s * 0.6f, strokeP)
  }

internal fun ThinkspaceView.drawTagsIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
    val s = size * 0.44f
    val strokeP = Paint(paint).apply {
      style = Paint.Style.STROKE
      strokeWidth = 1.8f * density
      strokeCap = Paint.Cap.ROUND
      strokeJoin = Paint.Join.ROUND
      color = Color.WHITE
    }
    val tagPath = Path().apply {
      moveTo(cx - s * 0.85f, cy)
      lineTo(cx - s * 0.22f, cy - s * 0.68f)
      lineTo(cx + s * 0.8f, cy + s * 0.32f)
      lineTo(cx + s * 0.18f, cy + s * 1.0f)
      close()
    }
    canvas.drawPath(tagPath, strokeP)
    // Eyelet hole
    val fillP = Paint(paint).apply { style = Paint.Style.FILL; color = Color.WHITE }
    canvas.drawCircle(cx - s * 0.38f, cy, 1.8f * density, fillP)
  }

internal fun ThinkspaceView.drawBackMenuIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
    val fillPaint = Paint(paint).apply {
      style = Paint.Style.FILL
      color = Color.WHITE
    }
    val w = size
    val h = size * 0.85f
    val path = Path().apply {
      // Arrowhead tip pointing left
      moveTo(cx - 0.48f * w, cy)
      // Top barb
      lineTo(cx - 0.08f * w, cy - 0.49f * h)
      // Top notch at junction with arrow body
      lineTo(cx - 0.08f * w, cy - 0.23f * h)
      // Upper curve arching smoothly right and downwards
      cubicTo(
        cx + 0.20f * w, cy - 0.23f * h,
        cx + 0.48f * w, cy - 0.05f * h,
        cx + 0.48f * w, cy + 0.26f * h
      )
      // Rounded bottom tail tip
      cubicTo(
        cx + 0.48f * w, cy + 0.40f * h,
        cx + 0.45f * w, cy + 0.49f * h,
        cx + 0.42f * w, cy + 0.49f * h
      )
      // Inner curve returning back towards arrowhead junction
      cubicTo(
        cx + 0.40f * w, cy + 0.28f * h,
        cx + 0.20f * w, cy + 0.19f * h,
        cx - 0.08f * w, cy + 0.19f * h
      )
      // Bottom barb
      lineTo(cx - 0.08f * w, cy + 0.49f * h)
      close()
    }
    canvas.drawPath(path, fillPaint)
  }

internal fun ThinkspaceView.drawCardActionBar(canvas: Canvas, card: NativeCard, viewW: Float, viewH: Float, canvasTopY: Float) {
    val isDocked = isKeyboardActive()
    val kbH = getKeyboardHeight()
    val isEditing = editingCardId != null

    // Safe view bounds ensuring toolbar is never clipped by edges, split line, or bottom navigation
    val safeTop = canvasTopY + 12f * density
    val safeBottom = viewH - 72f * density
    val safeLeft = 12f * density
    val safeRight = viewW - 12f * density

    // Height 58dp for generous, comfortable touch targets and easily readable labels
    val abH = 58f * density
    val maxAvailableW = safeRight - safeLeft
    val isTablet = viewW >= 600f * density
    val abW = if (isTablet) min(maxAvailableW, 460f * density) else min(maxAvailableW, 400f * density)

    val (scLeft, scTop) = canvasWorldToScreen(card.x, card.y, canvasTopY)
    val (scRight, scBottom) = canvasWorldToScreen(card.x + card.width, card.y + card.getHeight(), canvasTopY)
    val cardCenterX = (scLeft + scRight) / 2f

    // Horizontally centered on card, clamped to screen margins
    val abLeft = (cardCenterX - abW / 2f).coerceIn(safeLeft, safeRight - abW)

    // Intelligently position above or below card, or dock near workspace top if card fills viewport
    val abTop = if (isDocked) {
      (viewH - kbH - abH - 10f * density).coerceIn(safeTop, safeBottom - abH)
    } else {
      val margin = 12f * density
      val spaceAbove = scTop - safeTop
      val spaceBelow = safeBottom - scBottom

      when {
        spaceAbove >= abH + margin -> scTop - abH - margin
        spaceBelow >= abH + margin -> scBottom + margin
        else -> {
          if (scTop - safeTop >= 20f * density) {
            (scTop - abH - 6f * density).coerceIn(safeTop, safeBottom - abH)
          } else {
            safeTop + 8f * density
          }
        }
      }
    }.coerceIn(safeTop, safeBottom - abH)

    cardActionBarRect.set(abLeft, abTop, abLeft + abW, abTop + abH)
    val cornerRadius = abH / 2f

    // Theme detection: Dark Slate Glass vs Frosted Light Slate
    val isNightMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val barBgColor = if (isNightMode) Color.parseColor("#1E2534") else Color.parseColor("#5A6B82")
    val barBorderColor = if (isNightMode) Color.parseColor("#475569") else Color.parseColor("#72849B")

    // Ambient Apple Drop Shadow (Dual layer)
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    shadowPaint.color = Color.argb(45, 0, 0, 0)
    canvas.drawRoundRect(RectF(abLeft, abTop + 4f * density, abLeft + abW, abTop + abH + 4f * density), cornerRadius, cornerRadius, shadowPaint)
    shadowPaint.color = Color.argb(35, 0, 0, 0)
    canvas.drawRoundRect(RectF(abLeft, abTop + 1f * density, abLeft + abW, abTop + abH + 1f * density), cornerRadius, cornerRadius, shadowPaint)

    // Capsule Background & Border
    val abBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = barBgColor
      style = Paint.Style.FILL
    }
    val abBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = barBorderColor
      strokeWidth = 1.3f * density
      style = Paint.Style.STROKE
    }
    canvas.drawRoundRect(cardActionBarRect, cornerRadius, cornerRadius, abBgPaint)
    canvas.drawRoundRect(cardActionBarRect, cornerRadius, cornerRadius, abBorderPaint)

    val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 12.5f * density
      isFakeBoldText = true
      textAlign = Paint.Align.CENTER
    }

    val deleteLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#FF6B6B")
      textSize = 12.5f * density
      isFakeBoldText = true
      textAlign = Paint.Align.CENTER
    }

    val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
    }
    val deleteIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#FF6B6B")
    }

    val innerLeft = abLeft + 8f * density
    val innerRight = abLeft + abW - 8f * density
    val innerW = innerRight - innerLeft

    val wColor = 34f * density
    val wDiv = 8f * density
    val wTypo = 40f * density

    val iconCenterY = abTop + 20f * density
    val iconSize = 20f * density
    val labelBaselineY = abTop + 47.5f * density

    var curX = innerLeft

    if (isEditing) {
      // Edit Mode: Comment, Copy, Delete, Tags, Color, Tt
      val remainingW = innerW - wColor - wDiv - wTypo
      val btnW = remainingW / 4f

      // 1. Comment
      btnCardCommentRect.set(curX, abTop, curX + btnW, abTop + abH)
      drawCommentIcon(canvas, btnCardCommentRect.centerX(), iconCenterY, iconSize, iconPaint)
      canvas.drawText("Comment", btnCardCommentRect.centerX(), labelBaselineY, labelPaint)
      curX += btnW

      // 2. Copy
      btnCardCopyRect.set(curX, abTop, curX + btnW, abTop + abH)
      drawCopyIcon(canvas, btnCardCopyRect.centerX(), iconCenterY, iconSize, iconPaint, barBgColor)
      canvas.drawText("Copy", btnCardCopyRect.centerX(), labelBaselineY, labelPaint)
      curX += btnW

      // 3. Delete
      btnCardDeleteRect.set(curX, abTop, curX + btnW, abTop + abH)
      drawDeleteIcon(canvas, btnCardDeleteRect.centerX(), iconCenterY, iconSize, deleteIconPaint)
      canvas.drawText("Delete", btnCardDeleteRect.centerX(), labelBaselineY, deleteLabelPaint)
      curX += btnW

      // 4. Tags
      btnCardTagsRect.set(curX, abTop, curX + btnW, abTop + abH)
      drawTagsIcon(canvas, btnCardTagsRect.centerX(), iconCenterY, iconSize, iconPaint)
      canvas.drawText("Tags", btnCardTagsRect.centerX(), labelBaselineY, labelPaint)
      curX += btnW

      // 5. Color Swatch
      btnCardColorWheelRect.set(curX, abTop, curX + wColor, abTop + abH)
      drawRainbowSwatch(canvas, btnCardColorWheelRect, card.color)
      curX += wColor

      // 6. Hairline Divider |
      val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = barBorderColor
        strokeWidth = 1.2f * density
      }
      val divX = curX + wDiv / 2f
      canvas.drawLine(divX, abTop + 14f * density, divX, abTop + abH - 14f * density, divPaint)
      curX += wDiv

      // 7. Typography Button [Tt]
      btnCardTypographyRect.set(curX, abTop, innerRight, abTop + abH)
      drawTypographyGlyph(canvas, btnCardTypographyRect, density, isTypographyBarVisible)

      btnCardEditRect.setEmpty()
    } else {
      // Normal Card Selection Bar: Comment, Edit, Copy, Delete, Tags, Color, Tt
      val remainingW = innerW - wColor - wDiv - wTypo
      val btnW = remainingW / 5f

      // 1. Comment
      btnCardCommentRect.set(curX, abTop, curX + btnW, abTop + abH)
      drawCommentIcon(canvas, btnCardCommentRect.centerX(), iconCenterY, iconSize, iconPaint)
      canvas.drawText("Comment", btnCardCommentRect.centerX(), labelBaselineY, labelPaint)
      curX += btnW

      // 2. Edit
      btnCardEditRect.set(curX, abTop, curX + btnW, abTop + abH)
      drawEditIcon(canvas, btnCardEditRect.centerX(), iconCenterY, iconSize, iconPaint)
      canvas.drawText("Edit", btnCardEditRect.centerX(), labelBaselineY, labelPaint)
      curX += btnW

      // 3. Copy
      btnCardCopyRect.set(curX, abTop, curX + btnW, abTop + abH)
      drawCopyIcon(canvas, btnCardCopyRect.centerX(), iconCenterY, iconSize, iconPaint, barBgColor)
      canvas.drawText("Copy", btnCardCopyRect.centerX(), labelBaselineY, labelPaint)
      curX += btnW

      // 4. Delete
      btnCardDeleteRect.set(curX, abTop, curX + btnW, abTop + abH)
      drawDeleteIcon(canvas, btnCardDeleteRect.centerX(), iconCenterY, iconSize, deleteIconPaint)
      canvas.drawText("Delete", btnCardDeleteRect.centerX(), labelBaselineY, deleteLabelPaint)
      curX += btnW

      // 5. Tags
      btnCardTagsRect.set(curX, abTop, curX + btnW, abTop + abH)
      drawTagsIcon(canvas, btnCardTagsRect.centerX(), iconCenterY, iconSize, iconPaint)
      canvas.drawText("Tags", btnCardTagsRect.centerX(), labelBaselineY, labelPaint)
      curX += btnW

      // 6. Color Swatch
      btnCardColorWheelRect.set(curX, abTop, curX + wColor, abTop + abH)
      drawRainbowSwatch(canvas, btnCardColorWheelRect, card.color)
      curX += wColor

      // 7. Hairline Divider |
      val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = barBorderColor
        strokeWidth = 1.2f * density
      }
      val divX = curX + wDiv / 2f
      canvas.drawLine(divX, abTop + 14f * density, divX, abTop + abH - 14f * density, divPaint)
      curX += wDiv

      // 8. Typography Button [Tt]
      btnCardTypographyRect.set(curX, abTop, innerRight, abTop + abH)
      drawTypographyGlyph(canvas, btnCardTypographyRect, density, isTypographyBarVisible)
    }
  }

internal fun ThinkspaceView.drawRainbowSwatch(canvas: Canvas, rect: RectF, cardColor: Int) {
    val cwCenter = rect.centerX()
    val cwY = rect.centerY()
    val cwRad = 13.5f * density

    val rainbowShader = android.graphics.SweepGradient(
      cwCenter, cwY,
      intArrayOf(
        Color.parseColor("#EF4444"),
        Color.parseColor("#F59E0B"),
        Color.parseColor("#10B981"),
        Color.parseColor("#3B82F6"),
        Color.parseColor("#8B5CF6"),
        Color.parseColor("#EC4899"),
        Color.parseColor("#EF4444")
      ),
      null
    )
    val rainbowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      shader = rainbowShader
      style = Paint.Style.FILL
    }
    canvas.drawCircle(cwCenter, cwY, cwRad, rainbowPaint)

    val cwRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#A0FFFFFF")
      strokeWidth = 1.4f * density
      style = Paint.Style.STROKE
    }
    canvas.drawCircle(cwCenter, cwY, cwRad, cwRing)

    val innerDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = cardColor
      style = Paint.Style.FILL
    }
    canvas.drawCircle(cwCenter, cwY, 4.2f * density, innerDot)
  }

internal fun ThinkspaceView.drawTypographyGlyph(canvas: Canvas, rect: RectF, density: Float, isActive: Boolean) {
    if (isActive) {
      val activePill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
        style = Paint.Style.FILL
      }
      canvas.drawRoundRect(
        RectF(rect.centerX() - 17f * density, rect.centerY() - 17f * density, rect.centerX() + 17f * density, rect.centerY() + 17f * density),
        9f * density, 9f * density, activePill
      )
    }

    val tBigPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 18f * density
      typeface = Typeface.create("serif", Typeface.BOLD)
    }
    val tSmallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 13f * density
      typeface = Typeface.create("serif", Typeface.BOLD)
    }

    val wBigT = tBigPaint.measureText("T")
    val wSmallT = tSmallPaint.measureText("T")
    val totalW = wBigT + wSmallT + 1.2f * density
    val startX = rect.centerX() - totalW / 2f

    val fmBig = tBigPaint.fontMetrics
    val bigY = rect.centerY() - (fmBig.ascent + fmBig.descent) / 2f
    val fmSmall = tSmallPaint.fontMetrics
    val smallY = rect.centerY() - (fmSmall.ascent + fmSmall.descent) / 2f + 2.8f * density

    canvas.drawText("T", startX, bigY, tBigPaint)
    canvas.drawText("T", startX + wBigT + 1.2f * density, smallY, tSmallPaint)
  }

internal fun ThinkspaceView.drawTypographyBar(canvas: Canvas, card: NativeCard, viewW: Float, viewH: Float, canvasTopY: Float) {
    val isDocked = isKeyboardActive()
    val kbH = getKeyboardHeight()

    val safeTop = canvasTopY + 12f * density
    val safeBottom = viewH - 72f * density
    val safeLeft = 12f * density
    val safeRight = viewW - 12f * density

    val typoH = 58f * density
    val maxAvailableW = safeRight - safeLeft
    val isTablet = viewW >= 600f * density
    val typoW = if (isTablet) min(maxAvailableW, 460f * density) else min(maxAvailableW, 400f * density)

    val (scLeft, scTop) = canvasWorldToScreen(card.x, card.y, canvasTopY)
    val (scRight, scBottom) = canvasWorldToScreen(card.x + card.width, card.y + card.getHeight(), canvasTopY)
    val cardCenterX = (scLeft + scRight) / 2f

    val typoLeft = (cardCenterX - typoW / 2f).coerceIn(safeLeft, safeRight - typoW)
    val typoTop = if (isDocked) {
      (viewH - kbH - typoH - 10f * density).coerceIn(safeTop, safeBottom - typoH)
    } else {
      val margin = 12f * density
      val spaceAbove = scTop - safeTop
      val spaceBelow = safeBottom - scBottom

      when {
        spaceAbove >= typoH + margin -> scTop - typoH - margin
        spaceBelow >= typoH + margin -> scBottom + margin
        else -> {
          if (scTop - safeTop >= 20f * density) {
            (scTop - typoH - 6f * density).coerceIn(safeTop, safeBottom - typoH)
          } else {
            safeTop + 8f * density
          }
        }
      }
    }.coerceIn(safeTop, safeBottom - typoH)

    typographyBarRect.set(typoLeft, typoTop, typoLeft + typoW, typoTop + typoH)
    val cornerRadius = typoH / 2f

    val isNightMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val barBgColor = if (isNightMode) Color.parseColor("#1E2534") else Color.parseColor("#5A6B82")
    val barBorderColor = if (isNightMode) Color.parseColor("#475569") else Color.parseColor("#72849B")

    // Ambient Apple Drop Shadow (Dual layer)
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    shadowPaint.color = Color.argb(45, 0, 0, 0)
    canvas.drawRoundRect(RectF(typoLeft, typoTop + 4f * density, typoLeft + typoW, typoTop + typoH + 4f * density), cornerRadius, cornerRadius, shadowPaint)
    shadowPaint.color = Color.argb(35, 0, 0, 0)
    canvas.drawRoundRect(RectF(typoLeft, typoTop + 1f * density, typoLeft + typoW, typoTop + typoH + 1f * density), cornerRadius, cornerRadius, shadowPaint)

    // Capsule Background & Border
    val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = barBgColor
      style = Paint.Style.FILL
    }
    val barBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = barBorderColor
      strokeWidth = 1.3f * density
      style = Paint.Style.STROKE
    }
    canvas.drawRoundRect(typographyBarRect, cornerRadius, cornerRadius, barBg)
    canvas.drawRoundRect(typographyBarRect, cornerRadius, cornerRadius, barBorder)

    val itemPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 15f * density
      isFakeBoldText = true
      typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = barBorderColor
      strokeWidth = 1.2f * density
    }
    val activePillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#2563EB")
      style = Paint.Style.FILL
    }

    val innerLeft = typoLeft + 10f * density
    val innerRight = typoLeft + typoW - 10f * density
    val btnTop = typoTop
    val btnBottom = typoTop + typoH

    val wUndo = 38f * density
    val wDiv1 = 6f * density
    val wStyle = 62f * density
    val wDiv2 = 6f * density
    val wBold = 34f * density
    val wItalic = 34f * density
    val wUnderline = 34f * density
    val wStrike = 34f * density
    val wDiv3 = 6f * density
    val wSize = 32f * density
    val wColor = 34f * density
    val wMore = 34f * density
    val fixedTotal = wUndo + wDiv1 + wStyle + wDiv2 + wBold + wItalic + wUnderline + wStrike + wDiv3 + wSize + wColor + wMore
    val gap = ((innerRight - innerLeft - fixedTotal) / 11f).coerceAtLeast(1.5f * density)

    val fm = itemPaint.fontMetrics
    val centerY = typographyBarRect.centerY() - (fm.ascent + fm.descent) / 2f
    var curX = innerLeft

    // 1. Back to main card selection menu [ ↩ ]
    btnTypoBackRect.set(curX, btnTop, curX + wUndo, btnBottom)
    drawBackMenuIcon(canvas, btnTypoBackRect.centerX(), typographyBarRect.centerY(), 22f * density, itemPaint)
    curX += wUndo + gap

    // Divider 1
    val d1X = curX + wDiv1 / 2f
    canvas.drawLine(d1X, typoTop + 14f * density, d1X, typoTop + typoH - 14f * density, divPaint)
    curX += wDiv1 + gap

    // 2. Style
    btnTypoStyleRect.set(curX, btnTop, curX + wStyle, btnBottom)
    if (isStyleSheetOpen) {
      val stylePill = RectF(btnTypoStyleRect.centerX() - 29f * density, btnTypoStyleRect.centerY() - 16f * density, btnTypoStyleRect.centerX() + 29f * density, btnTypoStyleRect.centerY() + 16f * density)
      canvas.drawRoundRect(stylePill, 8f * density, 8f * density, activePillPaint)
    }
    val styleLabel = if (card.textStyleName != "Default") card.textStyleName else "Style"
    val stylePaint = TextPaint(itemPaint).apply { textSize = 15f * density }
    canvas.drawText(styleLabel, btnTypoStyleRect.centerX() - stylePaint.measureText(styleLabel) / 2f, centerY, stylePaint)
    curX += wStyle + gap

    // Divider 2
    val d2X = curX + wDiv2 / 2f
    canvas.drawLine(d2X, typoTop + 14f * density, d2X, typoTop + typoH - 14f * density, divPaint)
    curX += wDiv2 + gap

    // 3. Bold [ B ]
    btnTypoBoldRect.set(curX, btnTop, curX + wBold, btnBottom)
    if (card.isBold) {
      val bPill = RectF(btnTypoBoldRect.centerX() - 15f * density, btnTypoBoldRect.centerY() - 15f * density, btnTypoBoldRect.centerX() + 15f * density, btnTypoBoldRect.centerY() + 15f * density)
      canvas.drawRoundRect(bPill, 8f * density, 8f * density, activePillPaint)
    }
    val boldPaint = TextPaint(itemPaint).apply { isFakeBoldText = true; textSize = 18f * density }
    canvas.drawText("B", btnTypoBoldRect.centerX() - boldPaint.measureText("B") / 2f, centerY, boldPaint)
    curX += wBold + gap

    // 4. Italic [ I ]
    btnTypoItalicRect.set(curX, btnTop, curX + wItalic, btnBottom)
    if (card.isItalic) {
      val iPill = RectF(btnTypoItalicRect.centerX() - 15f * density, btnTypoItalicRect.centerY() - 15f * density, btnTypoItalicRect.centerX() + 15f * density, btnTypoItalicRect.centerY() + 15f * density)
      canvas.drawRoundRect(iPill, 8f * density, 8f * density, activePillPaint)
    }
    val italicPaint = TextPaint(itemPaint).apply { textSkewX = -0.22f; textSize = 18f * density }
    canvas.drawText("I", btnTypoItalicRect.centerX() - italicPaint.measureText("I") / 2f, centerY, italicPaint)
    curX += wItalic + gap

    // 5. Underline [ U ]
    btnTypoUnderlineRect.set(curX, btnTop, curX + wUnderline, btnBottom)
    if (card.isUnderline) {
      val uPill = RectF(btnTypoUnderlineRect.centerX() - 15f * density, btnTypoUnderlineRect.centerY() - 15f * density, btnTypoUnderlineRect.centerX() + 15f * density, btnTypoUnderlineRect.centerY() + 15f * density)
      canvas.drawRoundRect(uPill, 8f * density, 8f * density, activePillPaint)
    }
    val ulPaint = TextPaint(itemPaint).apply { isUnderlineText = true; textSize = 18f * density }
    canvas.drawText("U", btnTypoUnderlineRect.centerX() - ulPaint.measureText("U") / 2f, centerY, ulPaint)
    curX += wUnderline + gap

    // 6. Strikethrough [ S ]
    btnTypoStrikeRect.set(curX, btnTop, curX + wStrike, btnBottom)
    if (card.isStrikethrough) {
      val sPill = RectF(btnTypoStrikeRect.centerX() - 15f * density, btnTypoStrikeRect.centerY() - 15f * density, btnTypoStrikeRect.centerX() + 15f * density, btnTypoStrikeRect.centerY() + 15f * density)
      canvas.drawRoundRect(sPill, 8f * density, 8f * density, activePillPaint)
    }
    val strikePaint = TextPaint(itemPaint).apply { isStrikeThruText = true; textSize = 18f * density }
    canvas.drawText("S", btnTypoStrikeRect.centerX() - strikePaint.measureText("S") / 2f, centerY, strikePaint)
    curX += wStrike + gap

    // Divider 3
    val d3X = curX + wDiv3 / 2f
    canvas.drawLine(d3X, typoTop + 14f * density, d3X, typoTop + typoH - 14f * density, divPaint)
    curX += wDiv3 + gap

    // 7. Size indicator '0'
    btnTypoFontSizeRect.set(curX, btnTop, curX + wSize, btnBottom)
    val sizeText = "0"
    val sizePaint = TextPaint(itemPaint).apply { textSize = 16f * density }
    canvas.drawText(sizeText, btnTypoFontSizeRect.centerX() - sizePaint.measureText(sizeText) / 2f, centerY, sizePaint)
    curX += wSize + gap

    // 8. Text Color [ A_ ]
    btnTypoTextColorRect.set(curX, btnTop, curX + wColor, btnBottom)
    if (isTypoTextColorPaletteOpen) {
      val aPill = RectF(btnTypoTextColorRect.centerX() - 15f * density, btnTypoTextColorRect.centerY() - 15f * density, btnTypoTextColorRect.centerX() + 15f * density, btnTypoTextColorRect.centerY() + 15f * density)
      canvas.drawRoundRect(aPill, 8f * density, 8f * density, activePillPaint)
    }
    val aPaint = TextPaint(itemPaint).apply { textSize = 17.5f * density; isFakeBoldText = true }
    val fmA = aPaint.fontMetrics
    val aY = typographyBarRect.centerY() - (fmA.ascent + fmA.descent) / 2f - 2f * density
    canvas.drawText("A", btnTypoTextColorRect.centerX() - aPaint.measureText("A") / 2f, aY, aPaint)
    val colorBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = card.textColor
      strokeWidth = 3.5f * density
      strokeCap = Paint.Cap.ROUND
    }
    val aBarY = typographyBarRect.centerY() + 9f * density
    canvas.drawLine(btnTypoTextColorRect.centerX() - 8f * density, aBarY, btnTypoTextColorRect.centerX() + 8f * density, aBarY, colorBarPaint)
    curX += wColor + gap

    // 9. More Options ···
    btnTypoMoreRect.set(curX, btnTop, innerRight, btnBottom)
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      style = Paint.Style.FILL
    }
    val dotR = 2.8f * density
    val dotSpacing = 6.5f * density
    val moreCx = btnTypoMoreRect.centerX()
    val moreCy = typographyBarRect.centerY()
    canvas.drawCircle(moreCx - dotSpacing, moreCy, dotR, dotPaint)
    canvas.drawCircle(moreCx, moreCy, dotR, dotPaint)
    canvas.drawCircle(moreCx + dotSpacing, moreCy, dotR, dotPaint)
  }

internal fun ThinkspaceView.drawStyleSheetPopover(canvas: Canvas, card: NativeCard, viewW: Float, viewH: Float) {
    val popW = min(viewW - 24f * density, 300f * density)
    val rowH = 44f * density
    val options = listOf(
      Pair("Title", 20f),
      Pair("Subtitle", 16f),
      Pair("Heading 1", 18f),
      Pair("Heading 2", 16f),
      Pair("Heading 3", 14f),
      Pair("Default Style for New Excerpts", 13f)
    )
    val popH = options.size * rowH

    val popLeft = (typographyBarRect.left + 10f * density).coerceIn(10f * density, viewW - popW - 10f * density)
    val isDocked = isKeyboardActive()
    val popTop = if (isDocked) {
      typographyBarRect.top - popH - 8f * density
    } else {
      (typographyBarRect.bottom + 6f * density).coerceAtMost(viewH - popH - 12f * density)
    }
    styleSheetRect.set(popLeft, popTop, popLeft + popW, popTop + popH)

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#4D000000")
      style = Paint.Style.FILL
    }
    canvas.drawRoundRect(
      RectF(popLeft, popTop + 4f * density, popLeft + popW, popTop + popH + 4f * density),
      14f * density, 14f * density, shadowPaint
    )

    val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#1C2331")
      style = Paint.Style.FILL
    }
    val cardBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#384457")
      strokeWidth = 1.2f * density
      style = Paint.Style.STROKE
    }
    canvas.drawRoundRect(styleSheetRect, 14f * density, 14f * density, cardBg)
    canvas.drawRoundRect(styleSheetRect, 14f * density, 14f * density, cardBorder)

    styleOptionRects.clear()
    val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#2D3748")
      strokeWidth = 1f * density
    }
    val dotsPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#64748B")
      textSize = 14f * density
      isFakeBoldText = true
    }

    for (i in options.indices) {
      val name = options[i].first
      val rTop = popTop + i * rowH
      val rowRect = RectF(popLeft, rTop, popLeft + popW, rTop + rowH)
      val menuRect = RectF(rowRect.right - 28f * density, rTop, rowRect.right, rTop + rowH)
      styleOptionRects.add(Triple(rowRect, name, menuRect))

      val isSelected = card.textStyleName == name || (name == "Default Style for New Excerpts" && card.textStyleName == "Default")
      if (isSelected) {
        val selRowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#2563EB")
          alpha = 75
          style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rowRect, 8f * density, 8f * density, selRowPaint)
      }

      val rowTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isSelected) Color.parseColor("#60A5FA") else Color.parseColor("#F1F5F9")
        textSize = when (name) {
          "Title" -> 18f * density
          "Subtitle" -> 14.5f * density
          "Heading 1" -> 16.5f * density
          "Heading 2" -> 15f * density
          "Heading 3" -> 13.5f * density
          else -> 12.5f * density
        }
        isFakeBoldText = name != "Subtitle" && name != "Default Style for New Excerpts"
        if (name == "Subtitle") textSkewX = -0.15f
      }

      canvas.drawText(name, rowRect.left + 14f * density, rowRect.centerY() + 4.5f * density, rowTextPaint)
      canvas.drawText("⋮", rowRect.right - 18f * density, rowRect.centerY() + 4.5f * density, dotsPaint)

      if (i < options.size - 1) {
        canvas.drawLine(popLeft + 10f * density, rowRect.bottom, popLeft + popW - 10f * density, rowRect.bottom, divPaint)
      }
    }
  }

internal fun ThinkspaceView.drawCardColorPalette(canvas: Canvas, card: NativeCard) {
    val paletteColors = listOf(
      Color.parseColor("#EAB308"), // Yellow
      Color.parseColor("#3B82F6"), // Blue
      Color.parseColor("#22C55E"), // Green
      Color.parseColor("#8B5CF6"), // Purple
      Color.parseColor("#EC4899"), // Pink
      Color.parseColor("#EF4444")  // Red
    )
    val swatchSize = 24f * density
    val spacing = 8f * density
    val pW = paletteColors.size * (swatchSize + spacing) + 14f * density
    val pH = swatchSize + 14f * density
    val pLeft = (btnCardColorWheelRect.centerX() - pW / 2f).coerceIn(10f * density, width - pW - 10f * density)
    val isDocked = isKeyboardActive()
    val anchorTop = if (isTypographyBarVisible) typographyBarRect.top else cardActionBarRect.top
    val anchorBottom = if (isTypographyBarVisible) typographyBarRect.bottom else cardActionBarRect.bottom
    val pTop = if (isDocked) {
      anchorTop - pH - 8f * density
    } else {
      (anchorBottom + 6f * density).coerceAtMost(height - pH - 12f * density)
    }
    val pRect = RectF(pLeft, pTop, pLeft + pW, pTop + pH)

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#4D000000")
      style = Paint.Style.FILL
    }
    canvas.drawRoundRect(
      RectF(pLeft, pTop + 3f * density, pLeft + pW, pTop + pH + 3f * density),
      14f * density, 14f * density, shadowPaint
    )

    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1C2331"); style = Paint.Style.FILL }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#384457"); strokeWidth = 1.2f * density; style = Paint.Style.STROKE }
    canvas.drawRoundRect(pRect, 14f * density, 14f * density, bg)
    canvas.drawRoundRect(pRect, 14f * density, 14f * density, border)

    cardColorPaletteRects.clear()
    for (i in paletteColors.indices) {
      val col = paletteColors[i]
      val sLeft = pLeft + 7f * density + i * (swatchSize + spacing)
      val sTop = pTop + 7f * density
      val sRect = RectF(sLeft, sTop, sLeft + swatchSize, sTop + swatchSize)
      cardColorPaletteRects.add(Pair(sRect, col))

      val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.FILL }
      canvas.drawCircle(sRect.centerX(), sRect.centerY(), swatchSize / 2f, sp)
      if (col == card.color) {
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2.5f * density; style = Paint.Style.STROKE }
        canvas.drawCircle(sRect.centerX(), sRect.centerY(), swatchSize / 2f + 2f * density, ring)
      }
    }
  }

internal fun ThinkspaceView.drawTypoTextColorPalette(canvas: Canvas, card: NativeCard) {
    val textColors = listOf(
      Color.parseColor("#FFFFFF"), // White
      Color.parseColor("#94A3B8"), // Slate
      Color.parseColor("#000000"), // Black
      Color.parseColor("#2563EB"), // Blue
      Color.parseColor("#16A34A"), // Green
      Color.parseColor("#DC2626"), // Red
      Color.parseColor("#D97706")  // Amber
    )
    val swatchSize = 24f * density
    val spacing = 8f * density
    val pW = textColors.size * (swatchSize + spacing) + 14f * density
    val pH = swatchSize + 14f * density
    val pLeft = (btnTypoTextColorRect.centerX() - pW / 2f).coerceIn(10f * density, width - pW - 10f * density)
    val isDocked = isKeyboardActive()
    val pTop = if (isDocked) {
      typographyBarRect.top - pH - 8f * density
    } else {
      (typographyBarRect.bottom + 6f * density).coerceAtMost(height - pH - 12f * density)
    }
    val pRect = RectF(pLeft, pTop, pLeft + pW, pTop + pH)

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#4D000000")
      style = Paint.Style.FILL
    }
    canvas.drawRoundRect(
      RectF(pLeft, pTop + 3f * density, pLeft + pW, pTop + pH + 3f * density),
      14f * density, 14f * density, shadowPaint
    )

    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1C2331"); style = Paint.Style.FILL }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#384457"); strokeWidth = 1.2f * density; style = Paint.Style.STROKE }
    canvas.drawRoundRect(pRect, 14f * density, 14f * density, bg)
    canvas.drawRoundRect(pRect, 14f * density, 14f * density, border)

    typoTextColorPaletteRects.clear()
    for (i in textColors.indices) {
      val col = textColors[i]
      val sLeft = pLeft + 7f * density + i * (swatchSize + spacing)
      val sTop = pTop + 7f * density
      val sRect = RectF(sLeft, sTop, sLeft + swatchSize, sTop + swatchSize)
      typoTextColorPaletteRects.add(Pair(sRect, col))

      val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.FILL }
      canvas.drawCircle(sRect.centerX(), sRect.centerY(), swatchSize / 2f, sp)
      if (col == card.textColor) {
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2.5f * density; style = Paint.Style.STROKE }
        canvas.drawCircle(sRect.centerX(), sRect.centerY(), swatchSize / 2f + 2f * density, ring)
      }
    }
  }
