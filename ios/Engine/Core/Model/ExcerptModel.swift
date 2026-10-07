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

    // Typography styling properties matching Android NativeCard
    @objc public var fontSize: CGFloat = 13.0
    @objc public var isBold: Bool = false
    @objc public var isItalic: Bool = false
    @objc public var isUnderline: Bool = false
    @objc public var isStrikethrough: Bool = false
    @objc public var textColor: String = "#1E293B"
    @objc public var textStyleName: String = "Default"

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
        sourceRects: [BoundingBox] = [],
        fontSize: CGFloat = 13.0,
        isBold: Bool = false,
        isItalic: Bool = false,
        isUnderline: Bool = false,
        isStrikethrough: Bool = false,
        textColor: String = "#1E293B",
        textStyleName: String = "Default"
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
        self.fontSize = fontSize
        self.isBold = isBold
        self.isItalic = isItalic
        self.isUnderline = isUnderline
        self.isStrikethrough = isStrikethrough
        self.textColor = textColor
        self.textStyleName = textStyleName
        super.init()
    }

    @objc public func estimatedHeight() -> CGFloat {
        if let items = groupedItems, items.count > 1 {
            let baseH: CGFloat = items.contains(where: { $0.isImage }) ? 175.0 : 140.0
            return baseH + CGFloat(items.count - 1) * 8.0
        }
        if isTable {
            return 170.0
        }
        if isImage {
            return 160.0
        }

        // Dynamic text layout calculation matching Android StaticLayout
        let textW = max(20.0, width - 28.0)
        var fontDescriptor = UIFont.systemFont(ofSize: max(12.0, fontSize)).fontDescriptor
        var traits: UIFontDescriptor.SymbolicTraits = []
        if isBold { traits.insert(.traitBold) }
        if isItalic { traits.insert(.traitItalic) }
        if let desc = fontDescriptor.withSymbolicTraits(traits) {
            fontDescriptor = desc
        }
        let font = UIFont(descriptor: fontDescriptor, size: max(12.0, fontSize))

        let textToMeasure = text.isEmpty ? " " : text
        let attrString = NSAttributedString(
            string: textToMeasure,
            attributes: [.font: font]
        )
        let constraintRect = CGSize(width: textW, height: .greatestFiniteMagnitude)
        let boundingBox = attrString.boundingRect(
            with: constraintRect,
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            context: nil
        )

        return max(105.0, ceil(boundingBox.height) + 56.0)
    }
}
