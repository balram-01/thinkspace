import Foundation
import CoreGraphics

@objc public class MagneticStackingEngine: NSObject {

    @objc public static let snapRadius: CGFloat = 75.0

    /**
     * Finds any card whose center is within snapRadius of the drag coordinates.
     */
    @objc public static func findSnapTarget(
        dragWorldX: CGFloat,
        dragWorldY: CGFloat,
        existingCards: [ExcerptModel],
        ignoreCardId: String? = nil,
        radius: CGFloat = snapRadius
    ) -> ExcerptModel? {
        for card in existingCards {
            if let ignore = ignoreCardId, card.id == ignore {
                continue
            }
            let cardCenterX = card.x + card.width / 2.0
            let cardCenterY = card.y + card.estimatedHeight() / 2.0
            let distance = hypot(cardCenterX - dragWorldX, cardCenterY - dragWorldY)
            if distance <= radius {
                return card
            }
        }
        return nil
    }

    /**
     * Stacks a new excerpt into an existing card pile.
     */
    @objc public static func stackIntoCard(
        targetCard: ExcerptModel,
        newExcerpt: GroupedExcerpt
    ) {
        if targetCard.groupedItems == nil {
            targetCard.groupedItems = [
                GroupedExcerpt(
                    id: targetCard.id,
                    text: targetCard.text,
                    pageNumber: targetCard.pageNumber,
                    color: targetCard.color,
                    isImage: targetCard.isImage,
                    imageUrl: targetCard.imageUrl
                )
            ]
        }

        targetCard.groupedItems?.append(newExcerpt)
        targetCard.stackCount = targetCard.groupedItems?.count ?? (targetCard.stackCount + 1)
    }

    /**
     * Returns formatted page string e.g. "p. 1, p. 3"
     */
    @objc public static func formatStackedPages(card: ExcerptModel) -> String {
        guard let items = card.groupedItems, !items.isEmpty else {
            return "p. \(card.pageNumber)"
        }
        let uniquePages = Array(Set(items.map { $0.pageNumber })).sorted()
        return uniquePages.map { "p. \($0)" }.joined(separator: ", ")
    }
}
