import Foundation
import CoreGraphics

@objc public class WorkspaceDocumentEntry: NSObject, Codable {
    @objc public let id: String
    @objc public let title: String
    @objc public let pageCount: Int
    @objc public let uri: String
    @objc public let colorAccent: String?
    @objc public let author: String?

    @objc public init(
        id: String,
        title: String,
        pageCount: Int,
        uri: String,
        colorAccent: String? = nil,
        author: String? = nil
    ) {
        self.id = id
        self.title = title
        self.pageCount = pageCount
        self.uri = uri
        self.colorAccent = colorAccent
        self.author = author
        super.init()
    }
}

@objc public class NotebookPageModel: NSObject, Codable {
    @objc public let id: String
    @objc public var x: CGFloat
    @objc public var y: CGFloat
    @objc public var width: CGFloat
    @objc public var height: CGFloat
    @objc public var pageStyle: String
    @objc public var title: String
    @objc public var backgroundColor: String

    @objc public init(
        id: String,
        x: CGFloat,
        y: CGFloat,
        width: CGFloat = 400,
        height: CGFloat = 520,
        pageStyle: String = "ruled",
        title: String = "Notes",
        backgroundColor: String = "#FFFFFF"
    ) {
        self.id = id
        self.x = x
        self.y = y
        self.width = width
        self.height = height
        self.pageStyle = pageStyle
        self.title = title
        self.backgroundColor = backgroundColor
        super.init()
    }
}
