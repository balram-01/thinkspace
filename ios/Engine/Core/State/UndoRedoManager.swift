import Foundation

public enum WorkspaceAction {
    case addCard(ExcerptModel)
    case removeCard(ExcerptModel)
    case moveCard(id: String, oldX: CGFloat, oldY: CGFloat, newX: CGFloat, newY: CGFloat)
    case addStroke(InkStroke)
    case removeStroke(InkStroke)
    case addInkLink(InkLink)
    case removeInkLink(InkLink)
}

@objc public protocol UndoRedoDelegate: AnyObject {
    func onUndoStateChanged(canUndo: Bool, canRedo: Bool)
    func applyAction(_ action: Any, isUndo: Bool)
}

@objc public class UndoRedoManager: NSObject {
    private var undoStack: [WorkspaceAction] = []
    private var redoStack: [WorkspaceAction] = []
    private let maxHistory: Int = 100

    @objc public weak var delegate: UndoRedoDelegate?

    @objc public var canUndo: Bool {
        !undoStack.isEmpty
    }

    @objc public var canRedo: Bool {
        !redoStack.isEmpty
    }

    public func recordAction(_ action: WorkspaceAction) {
        undoStack.append(action)
        if undoStack.count > maxHistory {
            undoStack.removeFirst()
        }
        redoStack.removeAll()
        notifyState()
    }

    @objc public func undo() -> Bool {
        guard let action = undoStack.popLast() else { return false }
        redoStack.append(action)
        delegate?.applyAction(action, isUndo: true)
        notifyState()
        return true
    }

    @objc public func redo() -> Bool {
        guard let action = redoStack.popLast() else { return false }
        undoStack.append(action)
        delegate?.applyAction(action, isUndo: false)
        notifyState()
        return true
    }

    @objc public func clear() {
        undoStack.removeAll()
        redoStack.removeAll()
        notifyState()
    }

    private func notifyState() {
        delegate?.onUndoStateChanged(canUndo: canUndo, canRedo: canRedo)
    }
}
