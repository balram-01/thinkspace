package com.thinkspace.engine

import android.graphics.RectF
import com.thinkspace.NativeCard
import kotlin.math.hypot

data class GroupedExcerpt(
  val id: String,
  val text: String,
  val pageNumber: Int,
  val color: Int,
  val isImage: Boolean = false,
  val imageUrl: String? = null
)

/**
 * Handles magnetic snapping & grouping of excerpt cards into stacked piles.
 * Matches the video interaction:
 *  - "SNAP & STACK ON CARD"
 *  - "Release thumb to group into stacked pile (+1)"
 *  - Badge showing "p. 1, p. 3" and "2 grouped"
 */
object MagneticStackingEngine {

  const val SNAP_RADIUS_DP: Float = 75f

  /**
   * Finds any card whose center is within [snapRadius] of [dragWorldX], [dragWorldY].
   */
  fun findSnapTarget(
    dragWorldX: Float,
    dragWorldY: Float,
    existingCards: List<NativeCard>,
    ignoreCardId: String? = null,
    snapRadius: Float = SNAP_RADIUS_DP
  ): NativeCard? {
    return existingCards.firstOrNull { card ->
      if (card.id == ignoreCardId) return@firstOrNull false
      val cardCenterX = card.x + card.width / 2f
      val cardCenterY = card.y + card.getHeight() / 2f
      val dist = hypot(cardCenterX - dragWorldX, cardCenterY - dragWorldY)
      dist <= snapRadius
    }
  }

  /**
   * Stacks an excerpt into an existing card's pile.
   */
  fun stackIntoCard(
    targetCard: NativeCard,
    newExcerpt: GroupedExcerpt
  ) {
    if (targetCard.groupedItems == null) {
      targetCard.groupedItems = mutableListOf()
      // Add existing card's base item first
      targetCard.groupedItems?.add(
        GroupedExcerpt(
          id = targetCard.id,
          text = targetCard.text,
          pageNumber = targetCard.pageNumber,
          color = targetCard.color,
          isImage = targetCard.isImage,
          imageUrl = targetCard.imageUrl
        )
      )
    }

    targetCard.groupedItems?.add(newExcerpt)
    targetCard.stackCount = targetCard.groupedItems?.size ?: (targetCard.stackCount + 1)
  }

  /**
   * Returns a formatted page string for stacked cards e.g. "p. 1, p. 3"
   */
  fun formatStackedPages(card: NativeCard): String {
    val items = card.groupedItems
    return if (!items.isNullOrEmpty()) {
      val pages = items.map { it.pageNumber }.distinct().sorted()
      pages.joinToString(", ") { "p. $it" }
    } else {
      "p. ${card.pageNumber}"
    }
  }
}
