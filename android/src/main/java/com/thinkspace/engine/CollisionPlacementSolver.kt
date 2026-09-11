package com.thinkspace.engine

import android.graphics.PointF
import android.graphics.RectF
import com.thinkspace.NativeCard
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Intelligent auto-placement solver (§9 of Kotlin Workspace Engine Spec).
 * Prevents cards from overlapping when dropped or placed on the infinite workspace:
 *  1. Checks for direct overlap.
 *  2. Evaluates 5 proximity candidate slots per card: [Right, Below, Diagonal, Left, Above].
 *  3. Fallbacks to balanced 8-column masonry auto-flow tiling for unpositioned excerpts.
 */
object CollisionPlacementSolver {

  private const val GAP: Float = 18f

  /**
   * Finds the closest non-overlapping position to [desiredX, desiredY]
   * by testing proximity slot candidates around colliding cards.
   */
  fun findNonOverlappingPosition(
    desiredX: Float,
    desiredY: Float,
    cardWidth: Float,
    cardHeight: Float,
    existingCards: List<NativeCard>,
    ignoreCardId: String? = null,
    canvasPadding: Float = GAP
  ): PointF {
    val pool = if (ignoreCardId != null) existingCards.filter { it.id != ignoreCardId } else existingCards
    if (pool.isEmpty()) {
      return PointF(desiredX, desiredY)
    }

    fun isOverlapping(x: Float, y: Float): Boolean {
      val right = x + cardWidth
      val bottom = y + cardHeight
      for (other in pool) {
        val otherRight = other.x + other.width
        val otherBottom = other.y + other.getHeight()
        if (x < otherRight + canvasPadding && right + canvasPadding > other.x &&
            y < otherBottom + canvasPadding && bottom + canvasPadding > other.y) {
          return true
        }
      }
      return false
    }

    // 1. If desired spot is already clear, use it immediately
    if (!isOverlapping(desiredX, desiredY)) {
      return PointF(desiredX, desiredY)
    }

    // 2. Evaluate 5 Proximity Slot Candidates per card:
    // [Right, Below, Diagonal, Left, Above]
    val candidates = ArrayList<PointF>(pool.size * 5)
    for (c in pool) {
      val cw = c.width
      val ch = c.getHeight()

      val slots = arrayOf(
        PointF(c.x + cw + canvasPadding, c.y),                          // Right
        PointF(c.x, c.y + ch + canvasPadding),                          // Below
        PointF(c.x + cw + canvasPadding, c.y + ch + canvasPadding),     // Diagonal
        PointF(max(15f, c.x - cardWidth - canvasPadding), c.y),         // Left
        PointF(c.x, max(15f, c.y - cardHeight - canvasPadding))         // Above
      )

      for (slot in slots) {
        if (!isOverlapping(slot.x, slot.y)) {
          candidates.add(slot)
        }
      }
    }

    // Choose the slot with minimal distance to the user's desired drop point
    val bestSlot = candidates.minByOrNull { hypot(it.x - desiredX, it.y - desiredY) }
    if (bestSlot != null) {
      return bestSlot
    }

    // 3. Fallback: Shift slightly offset
    return PointF(desiredX + 24f, desiredY + 24f)
  }

  /**
   * Auto-flow placement across 8 balanced masonry columns when excerpts are added
   * without an explicit canvas drop coordinate.
   */
  fun findAutoFlowPosition(
    cardWidth: Float,
    cardHeight: Float,
    existingCards: List<NativeCard>,
    startX: Float = 25f,
    startY: Float = 60f,
    colWidth: Float = 205f
  ): PointF {
    if (existingCards.isEmpty()) {
      return PointF(startX, startY)
    }

    for (col in 0..7) {
      val colX = startX + col * (colWidth + GAP)
      val cardsInCol = existingCards.filter { abs(it.x - colX) < colWidth * 0.75f }
      if (cardsInCol.isEmpty()) {
        return PointF(colX, startY)
      }

      val lowestY = cardsInCol.maxOf { it.y + it.getHeight() }
      if (lowestY + cardHeight + GAP <= 1200f) {
        return PointF(colX, lowestY + GAP)
      }
    }

    // If all columns full, start new row below lowest overall card
    val lowestOverallY = existingCards.maxOfOrNull { it.y + it.getHeight() } ?: startY
    return PointF(startX, lowestOverallY + GAP)
  }
}
