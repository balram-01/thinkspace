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
        case "openSearch", "1":
            handler.handleOpenSearch()
        case "closeSearch", "2":
            handler.handleCloseSearch()
        case "nextMatch", "3":
            handler.handleNextMatch()
        case "prevMatch", "4":
            handler.handlePrevMatch()
        case "search", "performSearch", "18":
            if let query = args.first as? String {
                handler.handleSearch(query: query)
            }
        case "undo", "5":
            handler.handleUndo()
        case "redo", "6":
            handler.handleRedo()
        case "zoomToFit", "zoomToFitCards", "zoomOut", "7":
            handler.handleZoomToFit()
        case "setViewport", "24":
            if args.count >= 3,
               let x = args[0] as? NSNumber,
               let y = args[1] as? NSNumber,
               let scale = args[2] as? NSNumber {
                handler.handleSetViewport(x: CGFloat(x.doubleValue), y: CGFloat(y.doubleValue), scale: CGFloat(scale.doubleValue))
            }
        case "setSplitRatio", "25":
            if let ratio = args.first as? NSNumber {
                handler.handleSetSplitRatio(ratio: CGFloat(ratio.doubleValue))
            }
        case "toggleSqueezeMode", "26":
            handler.handleToggleSqueezeMode()
        case "setActiveTool", "27":
            if let tool = args.first as? String {
                handler.handleSetActiveTool(tool: tool)
            }
        case "clearSelection", "19":
            handler.handleClearSelection()
        case "addNotebookPage", "8":
            let style = (args.count > 0 ? args[0] as? String : nil) ?? "ruled"
            let title = (args.count > 1 ? args[1] as? String : nil) ?? "Notes"
            handler.handleAddNotebookPage(style: style, title: title)
        case "toggleImmersiveMode", "9":
            handler.handleToggleImmersiveMode()
        case "setImmersiveMode", "10":
            if let enabled = args.first as? Bool {
                handler.handleSetImmersiveMode(enabled: enabled)
            }
        case "setPenMode", "11":
            if let mode = args.first as? String {
                handler.handleSetPenMode(mode: mode)
            }
        case "setPenColor", "12":
            if let color = args.first as? String {
                handler.handleSetPenColor(color: color)
            }
        case "setPenThickness", "13":
            if let thickness = args.first as? NSNumber {
                handler.handleSetPenThickness(thickness: CGFloat(thickness.doubleValue))
            }
        case "setPenFavorites", "14":
            if let favs = args.first as? [String] {
                handler.handleSetPenFavorites(favorites: favs)
            } else if let favJson = args.first as? String,
                      let data = favJson.data(using: .utf8),
                      let favs = try? JSONDecoder().decode([String].self, from: data) {
                handler.handleSetPenFavorites(favorites: favs)
            }
        case "togglePenSettings", "15":
            handler.handleTogglePenSettings()
        case "switchToDocument", "16":
            if let docId = args.first as? String {
                handler.handleSwitchToDocument(documentId: docId)
            }
        case "scrollToPage", "17":
            if let page = args.first as? NSNumber {
                handler.handleScrollToPage(pageNumber: page.intValue)
            }
        case "deleteCard", "20":
            if let cardId = args.first as? String {
                handler.handleDeleteCard(cardId: cardId)
            }
        case "deleteInkLink", "21":
            if let linkId = args.first as? String {
                handler.handleDeleteInkLink(linkId: linkId)
            }
        case "clearAllCards", "22":
            handler.handleClearAllCards()
        case "clearAllStrokes", "23":
            handler.handleClearAllStrokes()
        default:
            break
        }
    }
}
