import Foundation
import CoreGraphics

@objc public class BoundingBox: NSObject, Codable {
    @objc public let x: CGFloat
    @objc public let y: CGFloat
    @objc public let width: CGFloat
    @objc public let height: CGFloat

    @objc public init(x: CGFloat, y: CGFloat, width: CGFloat, height: CGFloat) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
        super.init()
    }

    @objc public var rect: CGRect {
        CGRect(x: x, y: y, width: width, height: height)
    }

    @objc public func contains(_ point: CGPoint) -> Bool {
        rect.contains(point)
    }
}
