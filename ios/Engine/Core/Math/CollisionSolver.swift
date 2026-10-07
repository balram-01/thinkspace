import Foundation
import CoreGraphics
import UIKit

@objc public class CollisionSolver: NSObject {

    public static let gap: CGFloat = 18.0

    /**
     * Finds the closest non-overlapping position to (desiredX, desiredY)
     * by testing 5 proximity slot candidates around colliding cards:
     * [Right, Below, Diagonal, Left, Above].
     */
    @objc public static func findNonOverlappingPosition(
        desiredX: CGFloat,
        desiredY: CGFloat,
        cardWidth: CGFloat,
        cardHeight: CGFloat,
        existingCards: [ExcerptModel],
        ignoreCardId: String? = nil,
        canvasPadding: CGFloat = gap
    ) -> CGPoint {
        let pool = existingCards.filter { card in
            if let ignore = ignoreCardId, card.id == ignore {
                return false
            }
            return true
        }

        if pool.isEmpty {
            return CGPoint(x: desiredX, y: desiredY)
        }

        func isOverlapping(x: CGFloat, y: CGFloat) -> Bool {
            let right = x + cardWidth
            let bottom = y + cardHeight
            for other in pool {
                let otherRight = other.x + other.width
                let otherBottom = other.y + other.estimatedHeight()
                if x < otherRight + canvasPadding && right + canvasPadding > other.x &&
                   y < otherBottom + canvasPadding && bottom + canvasPadding > other.y {
                    return true
                }
            }
            return false
        }

        // 1. If desired spot is clear, use it immediately
        if !isOverlapping(x: desiredX, y: desiredY) {
            return CGPoint(x: desiredX, y: desiredY)
        }

        // 2. Evaluate 5 Proximity Slot Candidates per card:
        // [Right, Below, Diagonal, Left, Above]
        var candidates: [CGPoint] = []
        for c in pool {
            let cw = c.width
            let ch = c.estimatedHeight()

            let slots = [
                CGPoint(x: c.x + cw + canvasPadding, y: c.y),                               // Right
                CGPoint(x: c.x, y: c.y + ch + canvasPadding),                               // Below
                CGPoint(x: c.x + cw + canvasPadding, y: c.y + ch + canvasPadding),          // Diagonal
                CGPoint(x: max(15.0, c.x - cardWidth - canvasPadding), y: c.y),             // Left
                CGPoint(x: c.x, y: max(15.0, c.y - cardHeight - canvasPadding))              // Above
            ]

            for slot in slots {
                if !isOverlapping(x: slot.x, y: slot.y) {
                    candidates.append(slot)
                }
            }
        }

        // Choose slot with minimal Euclidean distance to user's desired drop point
        if let bestSlot = candidates.min(by: { hypot($0.x - desiredX, $0.y - desiredY) < hypot($1.x - desiredX, $1.y - desiredY) }) {
            return bestSlot
        }

        // 3. Fallback: Shift slightly offset
        return CGPoint(x: desiredX + 24.0, y: desiredY + 24.0)
    }

    /**
     * Auto-flow placement across 8 balanced masonry columns when excerpts are added
     * without an explicit canvas drop coordinate.
     */
    @objc public static func findAutoFlowPosition(
        cardWidth: CGFloat,
        cardHeight: CGFloat,
        existingCards: [ExcerptModel],
        startX: CGFloat = 25.0,
        startY: CGFloat = 60.0,
        colWidth: CGFloat = 205.0
    ) -> CGPoint {
        if existingCards.isEmpty {
            return CGPoint(x: startX, y: startY)
        }

        for col in 0...7 {
            let colX = startX + CGFloat(col) * (colWidth + gap)
            let cardsInCol = existingCards.filter { abs($0.x - colX) < colWidth * 0.75 }
            if cardsInCol.isEmpty {
                return CGPoint(x: colX, y: startY)
            }

            let lowestY = cardsInCol.map { $0.y + $0.estimatedHeight() }.max() ?? startY
            if lowestY + cardHeight + gap <= 1200.0 {
                return CGPoint(x: colX, y: lowestY + gap)
            }
        }

        // If all columns full, start new row below lowest overall card
        let lowestOverallY = existingCards.map { $0.y + $0.estimatedHeight() }.max() ?? startY
        return CGPoint(x: startX, y: lowestOverallY + gap)
    }
}
