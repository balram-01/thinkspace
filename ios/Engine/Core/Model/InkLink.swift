import Foundation
import CoreGraphics

@objc public class InkLink: NSObject, Codable {
    @objc public let id: String
    @objc public var sourceExcerptId: String
    @objc public var targetDocumentId: String
    @objc public var targetPageNumber: Int
    @objc public var targetRelativeY: CGFloat
    @objc public var color: String

    @objc public init(
        id: String,
        sourceExcerptId: String,
        targetDocumentId: String = "",
        targetPageNumber: Int = 1,
        targetRelativeY: CGFloat = 0.5,
        color: String = "#00ADB5"
    ) {
        self.id = id
        self.sourceExcerptId = sourceExcerptId
        self.targetDocumentId = targetDocumentId
        self.targetPageNumber = targetPageNumber
        self.targetRelativeY = targetRelativeY
        self.color = color
        super.init()
    }
}
