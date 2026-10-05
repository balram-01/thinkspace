import Foundation
import CoreGraphics
import UIKit

@objc public class GroupedExcerpt: NSObject, Codable {
    @objc public let id: String
    @objc public let text: String
    @objc public let pageNumber: Int
    @objc public let color: String
    @objc public let isImage: Bool
    @objc public let imageUrl: String?

    @objc public init(
        id: String,
        text: String,
        pageNumber: Int,
        color: String,
        isImage: Bool = false,
        imageUrl: String? = nil
    ) {
        self.id = id
        self.text = text
        self.pageNumber = pageNumber
        self.color = color
        self.isImage = isImage
        self.imageUrl = imageUrl
        super.init()
    }
}

@objc public class ExcerptModel: NSObject, Codable {
    @objc public let id: String
    @objc public var documentId: String?
    @objc public var pageNumber: Int
    @objc public var text: String
    @objc public var color: String
    @objc public var x: CGFloat
    @objc public var y: CGFloat
    @objc public var width: CGFloat
    @objc public var comment: String?
    @objc public var tags: [String]
    @objc public var clusterId: String?
    @objc public var stackCount: Int
    @objc public var imageUrl: String?
    @objc public var isTable: Bool
    @objc public var isImage: Bool
    @objc public var groupedItems: [GroupedExcerpt]?
    @objc public var sourceRects: [BoundingBox]

    @objc public init(
        id: String,
        documentId: String? = nil,
        pageNumber: Int = 1,
        text: String,
        color: String = "#FFEB3B",
        x: CGFloat = 0,
        y: CGFloat = 0,
        width: CGFloat = 200,
        comment: String? = nil,
        tags: [String] = [],
        clusterId: String? = nil,
        stackCount: Int = 1,
        imageUrl: String? = nil,
        isTable: Bool = false,
        isImage: Bool = false,
        groupedItems: [GroupedExcerpt]? = nil,
        sourceRects: [BoundingBox] = []
    ) {
        self.id = id
        self.documentId = documentId
        self.pageNumber = pageNumber
        self.text = text
        self.color = color
        self.x = x
        self.y = y
        self.width = width
        self.comment = comment
        self.tags = tags
        self.clusterId = clusterId
        self.stackCount = stackCount
        self.imageUrl = imageUrl
        self.isTable = isTable
        self.isImage = isImage
        self.groupedItems = groupedItems
        self.sourceRects = sourceRects
        super.init()
    }

    @objc public func estimatedHeight() -> CGFloat {
        let baseHeight: CGFloat = isImage ? 140 : 80
        let textPadding: CGFloat = CGFloat(min(text.count / 30, 8)) * 16.0
        return baseHeight + textPadding
    }
}
