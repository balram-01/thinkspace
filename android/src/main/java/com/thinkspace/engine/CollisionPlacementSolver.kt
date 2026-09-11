package com.thinkspace.engine

import android.graphics.PointF
import android.graphics.RectF
import com.thinkspace.NativeCard
import kotlin.math.max

/**
 * Intelligent auto-placement solver that prevents cards from overlapping
 * when dropped onto the infinite workspace.
 */
object CollisionPlacementSolver {

  fun findNonOverlappingPosition(
    desiredX: Float,
    desiredY: Float,
    cardWidth: Float,
    cardHeight: Float,
    existingCards: List<NativeCard>,
    canvasPadding: Float = 16f
  ): PointF {
    if (existingCards.isEmpty()) {
      return PointF(desiredX, desiredY)
    }

    val proposed = RectF(desiredX, desiredY, desiredX + cardWidth, desiredY + cardHeight)
    val hasCollision = existingCards.any { other ->
      val otherRect = RectF(other.x, other.y, other.x + other.width, other.y + other.getHeight())
      RectF.intersects(proposed, otherRect)
    }

    if (!hasCollision) {
      return PointF(desiredX, desiredY)
    }

    // Step downwards or diagonally to find clear canvas space
    var testX = desiredX
    var testY = desiredY
    var attempts = 0
    val maxAttempts = 30

    while (attempts < maxAttempts) {
      // Find what it collides with
      val collidingCard = existingCards.firstOrNull { other ->
        val otherRect = RectF(other.x, other.y, other.x + other.width, other.y + other.getHeight())
        RectF.intersects(RectF(testX, testY, testX + cardWidth, testY + cardHeight), otherRect)
      }

      if (collidingCard == null) {
        return PointF(testX, testY)
      }

      // Shift downwards below the colliding card
      testY = collidingCard.y + collidingCard.getHeight() + canvasPadding

      // If pushed too far down, shift right and reset Y
      if (testY - desiredY > 600f) {
        testY = desiredY
        testX += cardWidth + canvasPadding + 10f
      }

      attempts++
    }

    // Fallback: slightly offset to remain visible
    return PointF(desiredX + 24f, desiredY + 24f)
  }
}
