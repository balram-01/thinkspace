import Foundation
import CoreGraphics

@objc public class CollisionSolver: NSObject {

    @objc public static func checkOverlap(r1: CGRect, r2: CGRect) -> Bool {
        return r1.intersects(r2)
    }

    /**
     * Resolves a non-overlapping position for a newly dropped card near desired point.
     */
    @objc public static func findAvailableSlot(
        desiredOrigin: CGPoint,
        cardSize: CGSize,
        existingRects: [CGRect],
        spacing: CGFloat = 16.0
    ) -> CGPoint {
        var candidate = CGRect(origin: desiredOrigin, size: cardSize)
        var hasCollision = true
        var attempts = 0
        let maxAttempts = 20

        while hasCollision && attempts < maxAttempts {
            hasCollision = false
            for rect in existingRects {
                if candidate.intersects(rect.insetBy(dx: -spacing, dy: -spacing)) {
                    hasCollision = true
                    // Displace downwards or to the right
                    candidate.origin.y = rect.maxY + spacing
                    break
                }
            }
            attempts += 1
        }

        return candidate.origin
    }
}
