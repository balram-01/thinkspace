import Foundation
import CoreGraphics
import UIKit

@objc public class BezierCalculus: NSObject {

    public struct BezierCurve {
        public let p0: CGPoint
        public let p1: CGPoint
        public let p2: CGPoint
        public let p3: CGPoint

        public func point(at t: CGFloat) -> CGPoint {
            let mt = 1.0 - t
            let mt2 = mt * mt
            let mt3 = mt2 * mt
            let t2 = t * t
            let t3 = t2 * t

            let x = mt3 * p0.x + 3 * mt2 * t * p1.x + 3 * mt * t2 * p2.x + t3 * p3.x
            let y = mt3 * p0.y + 3 * mt2 * t * p1.y + 3 * mt * t2 * p2.y + t3 * p3.y
            return CGPoint(x: x, y: y)
        }
    }

    /**
     * Calculates the cubic Bézier curve connecting source anchor to target anchor.
     */
    @objc public static func calculateTetherCurve(
        start: CGPoint,
        end: CGPoint,
        isHeld: BooleanLiteralType = false
    ) -> UIBezierPath {
        let spanX = max(24.0, end.x - start.x)
        let spanY = end.y - start.y

        let cp1X = start.x + spanX * 0.35
        let cp1Y = start.y + spanY * 0.15 + (isHeld ? 16.0 : 8.0)
        let cp2X = start.x + spanX * 0.68
        let cp2Y = end.y - (isHeld ? 14.0 : 8.0)

        let path = UIBezierPath()
        path.move(to: start)
        path.addCurve(to: end, controlPoint1: CGPoint(x: cp1X, y: cp1Y), controlPoint2: CGPoint(x: cp2X, y: cp2Y))
        return path
    }

    /**
     * Finds the nearest anchor point on the perimeter of a rectangle to a given external target point.
     */
    @objc public static func nearestPerimeterPoint(rect: CGRect, target: CGPoint) -> CGPoint {
        let clampedX = min(max(target.x, rect.minX), rect.maxX)
        let clampedY = min(max(target.y, rect.minY), rect.maxY)

        // Project to the closest edge
        let dl = abs(clampedX - rect.minX)
        let dr = abs(clampedX - rect.maxX)
        let dt = abs(clampedY - rect.minY)
        let db = abs(clampedY - rect.maxY)

        let minDistance = min(min(dl, dr), min(dt, db))

        if minDistance == dl {
            return CGPoint(x: rect.minX, y: clampedY)
        } else if minDistance == dr {
            return CGPoint(x: rect.maxX, y: clampedY)
        } else if minDistance == dt {
            return CGPoint(x: clampedX, y: rect.minY)
        } else {
            return CGPoint(x: clampedX, y: rect.maxY)
        }
    }
}
