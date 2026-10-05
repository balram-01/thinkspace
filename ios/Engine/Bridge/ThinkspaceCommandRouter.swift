import Foundation
import CoreGraphics

@objc public protocol ThinkspaceCommandHandler: AnyObject {
    func handleOpenSearch()
    func handleCloseSearch()
    func handleNextMatch()
    func handlePrevMatch()
    func handleSearch(query: String)
    func handleUndo()
    func handleRedo()
    func handleZoomToFit()
    func handleZoomOut()
    func handleSetViewport(x: CGFloat, y: CGFloat, scale: CGFloat)
    func handleSetSplitRatio(ratio: CGFloat)
    func handleToggleSqueezeMode()
    func handleSetActiveTool(tool: String)
    func handleClearSelection()
    func handleAddNotebookPage(style: String, title: String)
    func handleToggleImmersiveMode()
    func handleSetImmersiveMode(enabled: Bool)
    func handleSetPenMode(mode: String)
    func handleSetPenColor(color: String)
    func handleSetPenThickness(thickness: CGFloat)
    func handleSetPenFavorites(favorites: [String])
    func handleTogglePenSettings()
    func handleSwitchToDocument(documentId: String)
    func handleScrollToPage(pageNumber: Int)
    func handleDeleteCard(cardId: String)
    func handleDeleteInkLink(linkId: String)
    func handleClearAllCards()
    func handleClearAllStrokes()
}

@objc public class ThinkspaceCommandRouter: NSObject {
    @objc public weak var handler: ThinkspaceCommandHandler?

    @objc public init(handler: ThinkspaceCommandHandler? = nil) {
        self.handler = handler
        super.init()
    }

    @objc public func dispatchCommand(name: String, args: [Any]) {
        guard let handler = handler else { return }

        switch name {
        case "openSearch":
            handler.handleOpenSearch()
        case "closeSearch":
            handler.handleCloseSearch()
        case "nextMatch":
            handler.handleNextMatch()
        case "prevMatch":
            handler.handlePrevMatch()
        case "search":
            if let query = args.first as? String {
                handler.handleSearch(query: query)
            }
        case "undo":
            handler.handleUndo()
        case "redo":
            handler.handleRedo()
        case "zoomToFit":
            handler.handleZoomToFit()
        case "zoomOut":
            handler.handleZoomOut()
        case "setViewport":
            if args.count >= 3,
               let x = args[0] as? NSNumber,
               let y = args[1] as? NSNumber,
               let scale = args[2] as? NSNumber {
                handler.handleSetViewport(x: CGFloat(x.doubleValue), y: CGFloat(y.doubleValue), scale: CGFloat(scale.doubleValue))
            }
        case "setSplitRatio":
            if let ratio = args.first as? NSNumber {
                handler.handleSetSplitRatio(ratio: CGFloat(ratio.doubleValue))
            }
        case "toggleSqueezeMode":
            handler.handleToggleSqueezeMode()
        case "setActiveTool":
            if let tool = args.first as? String {
                handler.handleSetActiveTool(tool: tool)
            }
        case "clearSelection":
            handler.handleClearSelection()
        case "addNotebookPage":
            let style = (args.count > 0 ? args[0] as? String : nil) ?? "ruled"
            let title = (args.count > 1 ? args[1] as? String : nil) ?? "Notes"
            handler.handleAddNotebookPage(style: style, title: title)
        case "toggleImmersiveMode":
            handler.handleToggleImmersiveMode()
        case "setImmersiveMode":
            if let enabled = args.first as? Bool {
                handler.handleSetImmersiveMode(enabled: enabled)
            }
        case "setPenMode":
            if let mode = args.first as? String {
                handler.handleSetPenMode(mode: mode)
            }
        case "setPenColor":
            if let color = args.first as? String {
                handler.handleSetPenColor(color: color)
            }
        case "setPenThickness":
            if let thickness = args.first as? NSNumber {
                handler.handleSetPenThickness(thickness: CGFloat(thickness.doubleValue))
            }
        case "setPenFavorites":
            if let favs = args.first as? [String] {
                handler.handleSetPenFavorites(favorites: favs)
            }
        case "togglePenSettings":
            handler.handleTogglePenSettings()
        case "switchToDocument":
            if let docId = args.first as? String {
                handler.handleSwitchToDocument(documentId: docId)
            }
        case "scrollToPage":
            if let page = args.first as? NSNumber {
                handler.handleScrollToPage(pageNumber: page.intValue)
            }
        case "deleteCard":
            if let cardId = args.first as? String {
                handler.handleDeleteCard(cardId: cardId)
            }
        case "deleteInkLink":
            if let linkId = args.first as? String {
                handler.handleDeleteInkLink(linkId: linkId)
            }
        case "clearAllCards":
            handler.handleClearAllCards()
        case "clearAllStrokes":
            handler.handleClearAllStrokes()
        default:
            break
        }
    }
}
