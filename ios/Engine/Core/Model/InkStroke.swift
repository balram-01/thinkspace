import Foundation
import CoreGraphics

@objc public class InkPoint: NSObject, Codable {
    @objc public let x: CGFloat
    @objc public let y: CGFloat
    @objc public let pressure: CGFloat
    @objc public let timestamp: TimeInterval

    @objc public init(x: CGFloat, y: CGFloat, pressure: CGFloat = 1.0, timestamp: TimeInterval = Date().timeIntervalSince1970) {
        self.x = x
        self.y = y
        self.pressure = pressure
        self.timestamp = timestamp
        super.init()
    }
}

@objc public class InkStroke: NSObject, Codable {
    @objc public let id: String
    @objc public var points: [InkPoint]
    @objc public var color: String
    @objc public var strokeWidth: CGFloat
    @objc public var isHighlighter: Bool

    @objc public init(
        id: String,
        points: [InkPoint] = [],
        color: String = "#000000",
        strokeWidth: CGFloat = 2.0,
        isHighlighter: Bool = false
    ) {
        self.id = id
        self.points = points
        self.color = color
        self.strokeWidth = strokeWidth
        self.isHighlighter = isHighlighter
        super.init()
    }
}
