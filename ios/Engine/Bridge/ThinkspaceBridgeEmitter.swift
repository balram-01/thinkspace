import Foundation
import CoreGraphics

@objc public protocol ThinkspaceEventEmitterProtocol: AnyObject {
    func emitAddStroke(strokeJson: String)
    func emitEraseStroke(id: String)
    func emitExcerptMoveEnd(id: String, x: CGFloat, y: CGFloat, clusterId: String?, stackCount: Int)
    func emitExcerptPress(id: String)
    func emitCardDelete(id: String)
    func emitChangeCardColor(id: String, color: String)
    func emitUpdateCardComment(id: String, comment: String)
    func emitHoldCard(id: String)
    func emitTransformChange(panX: CGFloat, panY: CGFloat, scale: CGFloat)
    func emitSplitRatioChange(ratio: CGFloat)
    func emitExtractExcerpt(
        text: String,
        pageNumber: Int,
        color: String,
        isTable: Bool,
        isImage: Bool,
        imageUrl: String?,
        id: String?,
        x: CGFloat,
        y: CGFloat
    )
    func emitToggleSqueeze(isSqueezed: Bool)
    func emitUndoStateChange(canUndo: Bool, canRedo: Bool)
    func emitRequestDocumentSwitch(documentId: String, sourcePageNumber: Int, cardId: String)
    func emitToggleImmersive(isImmersive: Bool)
    func emitInkLinkCreate(linkJson: String)
    func emitInkLinkDelete(id: String)
    func emitPenStateChange(mode: String, color: String, thickness: CGFloat, favoritesJson: String)
    func emitNotebookPageAdded(id: String, x: CGFloat, y: CGFloat, width: CGFloat, height: CGFloat, pageStyle: String, title: String)
    func emitNotebookPageMoved(id: String, x: CGFloat, y: CGFloat)
    func emitNotebookPageDeleted(id: String)
    func emitDocumentChange(documentId: String, title: String, uri: String, pageCount: Int)
    func emitDocumentsUpdated(documentsJson: String, foldersJson: String)
    func emitRequestAddDocument()
}

@objc public class ThinkspaceBridgeEmitter: NSObject {
    @objc public weak var delegate: ThinkspaceEventEmitterProtocol?

    @objc public static let shared = ThinkspaceBridgeEmitter()

    @objc public func sendAddStroke(_ stroke: InkStroke) {
        if let data = try? JSONEncoder().encode(stroke),
           let json = String(data: data, encoding: .utf8) {
            delegate?.emitAddStroke(strokeJson: json)
        }
    }

    @objc public func sendEraseStroke(id: String) {
        delegate?.emitEraseStroke(id: id)
    }

    @objc public func sendExcerptMoveEnd(card: ExcerptModel) {
        delegate?.emitExcerptMoveEnd(
            id: card.id,
            x: card.x,
            y: card.y,
            clusterId: card.clusterId,
            stackCount: card.stackCount
        )
    }

    @objc public func sendExcerptPress(id: String) {
        delegate?.emitExcerptPress(id: id)
    }

    @objc public func sendCardDelete(id: String) {
        delegate?.emitCardDelete(id: id)
    }

    @objc public func sendChangeCardColor(id: String, color: String) {
        delegate?.emitChangeCardColor(id: id, color: color)
    }

    @objc public func sendUpdateCardComment(id: String, comment: String) {
        delegate?.emitUpdateCardComment(id: id, comment: comment)
    }

    @objc public func sendHoldCard(id: String) {
        delegate?.emitHoldCard(id: id)
    }

    @objc public func sendTransformChange(panX: CGFloat, panY: CGFloat, scale: CGFloat) {
        delegate?.emitTransformChange(panX: panX, panY: panY, scale: scale)
    }

    @objc public func sendSplitRatioChange(ratio: CGFloat) {
        delegate?.emitSplitRatioChange(ratio: ratio)
    }

    @objc public func sendExtractExcerpt(card: ExcerptModel) {
        delegate?.emitExtractExcerpt(
            text: card.text,
            pageNumber: card.pageNumber,
            color: card.color,
            isTable: card.isTable,
            isImage: card.isImage,
            imageUrl: card.imageUrl,
            id: card.id,
            x: card.x,
            y: card.y
        )
    }

    @objc public func sendToggleSqueeze(isSqueezed: Bool) {
        delegate?.emitToggleSqueeze(isSqueezed: isSqueezed)
    }

    @objc public func sendUndoStateChange(canUndo: Bool, canRedo: Bool) {
        delegate?.emitUndoStateChange(canUndo: canUndo, canRedo: canRedo)
    }

    @objc public func sendRequestDocumentSwitch(documentId: String, pageNumber: Int, cardId: String) {
        delegate?.emitRequestDocumentSwitch(
            documentId: documentId,
            sourcePageNumber: pageNumber,
            cardId: cardId
        )
    }

    @objc public func sendToggleImmersive(isImmersive: Bool) {
        delegate?.emitToggleImmersive(isImmersive: isImmersive)
    }

    @objc public func sendInkLinkCreate(_ link: InkLink) {
        if let data = try? JSONEncoder().encode(link),
           let json = String(data: data, encoding: .utf8) {
            delegate?.emitInkLinkCreate(linkJson: json)
        }
    }

    @objc public func sendInkLinkDelete(id: String) {
        delegate?.emitInkLinkDelete(id: id)
    }

    @objc public func sendPenStateChange(mode: String, color: String, thickness: CGFloat, favorites: [String]) {
        let favJson = (try? JSONSerialization.data(withJSONObject: favorites)).flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
        delegate?.emitPenStateChange(mode: mode, color: color, thickness: thickness, favoritesJson: favJson)
    }

    @objc public func sendNotebookPageAdded(page: NotebookPageModel) {
        delegate?.emitNotebookPageAdded(
            id: page.id,
            x: page.x,
            y: page.y,
            width: page.width,
            height: page.height,
            pageStyle: page.pageStyle,
            title: page.title
        )
    }

    @objc public func sendNotebookPageMoved(id: String, x: CGFloat, y: CGFloat) {
        delegate?.emitNotebookPageMoved(id: id, x: x, y: y)
    }

    @objc public func sendNotebookPageDeleted(id: String) {
        delegate?.emitNotebookPageDeleted(id: id)
    }

    @objc public func sendDocumentChange(documentId: String, title: String, uri: String, pageCount: Int) {
        delegate?.emitDocumentChange(documentId: documentId, title: title, uri: uri, pageCount: pageCount)
    }

    @objc public func sendDocumentsUpdated(documentsJson: String, foldersJson: String) {
        delegate?.emitDocumentsUpdated(documentsJson: documentsJson, foldersJson: foldersJson)
    }

    @objc public func sendRequestAddDocument() {
        delegate?.emitRequestAddDocument()
    }
}
