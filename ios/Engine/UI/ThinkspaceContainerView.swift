import Foundation
import UIKit
import PDFKit

/**
 * ThinkspaceContainerView: The master UIKit container view coordinating the PDF document reader,
 * the infinite 2D canvas, the interactive split divider, Apple Pencil inking, and Fabric bridge.
 */
@objc public class ThinkspaceContainerView: UIView,
    ThinkspaceCommandHandler,
    UndoRedoDelegate,
    PDFDocumentEngineDelegate,
    InfiniteCanvasViewDelegate,
    ApplePencilEngineDelegate {

    // ── Layout Components ───────────────────────────────────────────────────────
    @objc public let documentContainer = UIView()
    @objc public let canvasContainer = UIView()
    @objc public let splitDivider = UIView()
    private let dividerHandle = UIView()

    // ── Native Sub-Engines ─────────────────────────────────────────────────────
    @objc public let pdfEngine = PDFDocumentEngine()
    @objc public let infiniteCanvas = InfiniteCanvasView()
    @objc public let pencilEngine = ApplePencilEngine()

    // ── State & Settings ───────────────────────────────────────────────────────
    @objc public var splitRatio: CGFloat = 0.45 {
        didSet { setNeedsLayout() }
    }
    @objc public var isSqueezed: Bool = false
    @objc public var isImmersive: Bool = false
    @objc public var activeTool: String = "select" {
        didSet { updateActiveToolMode() }
    }
    @objc public var selectedColor: String = "#00ADB5"
    @objc public var pattern: String = "looseleaf" {
        didSet { infiniteCanvas.pattern = pattern }
    }

    // Pen State
    @objc public var penMode: String = "pen" {
        didSet { pencilEngine.activeMode = penMode }
    }
    @objc public var penColor: String = "#1A1A1A" {
        didSet { pencilEngine.strokeColor = penColor }
    }
    @objc public var penThickness: CGFloat = 2.5 {
        didSet { pencilEngine.strokeWidth = penThickness }
    }
    @objc public var penFavorites: [String] = ["#1A1A1A", "#FF3B30", "#34C759", "#007AFF"]

    // Data Collections
    @objc public var cards: [ExcerptModel] = []
    @objc public var strokes: [InkStroke] = []
    @objc public var inkLinks: [InkLink] = []
    @objc public var notebookPages: [NotebookPageModel] = []
    @objc public var workspaceDocuments: [WorkspaceDocumentEntry] = []
    @objc public var activeDocumentId: String?

    // Camera & Undo
    @objc public var camera: CameraTransform {
        infiniteCanvas.camera
    }
    @objc public let undoRedoManager = UndoRedoManager()
    @objc public lazy var commandRouter: ThinkspaceCommandRouter = {
        ThinkspaceCommandRouter(handler: self)
    }()

    // Haptics & Dividers
    private let impactFeedback = UIImpactFeedbackGenerator(style: .medium)
    private var isDraggingDivider = false
    private var dividerPanStartRatio: CGFloat = 0.45

    // ── Initializers ───────────────────────────────────────────────────────────
    @objc public override init(frame: CGRect) {
        super.init(frame: frame)
        setupViews()
    }

    public required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupViews()
    }

    private func setupViews() {
        backgroundColor = UIColor(white: 0.96, alpha: 1.0)
        clipsToBounds = true

        undoRedoManager.delegate = self

        // 1. Document Container & PDF Engine
        documentContainer.backgroundColor = .systemBackground
        documentContainer.clipsToBounds = true
        pdfEngine.delegate = self
        pdfEngine.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        documentContainer.addSubview(pdfEngine)
        addSubview(documentContainer)

        // 2. Canvas Container, Infinite Canvas & Pencil Engine
        canvasContainer.backgroundColor = UIColor(white: 0.95, alpha: 1.0)
        canvasContainer.clipsToBounds = true
        infiniteCanvas.delegate = self
        infiniteCanvas.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        canvasContainer.addSubview(infiniteCanvas)

        pencilEngine.delegate = self
        pencilEngine.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        canvasContainer.addSubview(pencilEngine)
        addSubview(canvasContainer)

        // 3. Split Divider
        splitDivider.backgroundColor = UIColor(white: 0.85, alpha: 1.0)
        splitDivider.isUserInteractionEnabled = true
        addSubview(splitDivider)

        dividerHandle.backgroundColor = UIColor(white: 0.5, alpha: 1.0)
        dividerHandle.layer.cornerRadius = 2.5
        splitDivider.addSubview(dividerHandle)

        let panGesture = UIPanGestureRecognizer(target: self, action: #selector(handleDividerPan(_:)))
        splitDivider.addGestureRecognizer(panGesture)

        updateActiveToolMode()
    }

    public override func layoutSubviews() {
        super.layoutSubviews()
        let totalHeight = bounds.height
        let totalWidth = bounds.width
        guard totalHeight > 0, totalWidth > 0 else { return }

        let dividerThickness: CGFloat = 10.0

        if isImmersive {
            documentContainer.frame = bounds
            canvasContainer.frame = .zero
            splitDivider.isHidden = true
            return
        }

        splitDivider.isHidden = false
        let docHeight = (totalHeight - dividerThickness) * splitRatio
        let canvasHeight = totalHeight - docHeight - dividerThickness

        documentContainer.frame = CGRect(x: 0, y: 0, width: totalWidth, height: docHeight)
        splitDivider.frame = CGRect(x: 0, y: docHeight, width: totalWidth, height: dividerThickness)
        canvasContainer.frame = CGRect(x: 0, y: docHeight + dividerThickness, width: totalWidth, height: canvasHeight)

        let handleWidth: CGFloat = 40.0
        let handleHeight: CGFloat = 5.0
        dividerHandle.frame = CGRect(
            x: (totalWidth - handleWidth) / 2.0,
            y: (dividerThickness - handleHeight) / 2.0,
            width: handleWidth,
            height: handleHeight
        )
    }

    private func updateActiveToolMode() {
        let isDrawing = (activeTool == "pen" || activeTool == "highlighter" || activeTool == "eraser")
        pencilEngine.isUserInteractionEnabled = isDrawing
    }

    // ── Interactive Split Divider Gesture ───────────────────────────────────────
    @objc private func handleDividerPan(_ gesture: UIPanGestureRecognizer) {
        let translation = gesture.translation(in: self)
        let totalHeight = bounds.height
        guard totalHeight > 0 else { return }

        switch gesture.state {
        case .began:
            isDraggingDivider = true
            dividerPanStartRatio = splitRatio
            impactFeedback.prepare()
        case .changed:
            let deltaRatio = translation.y / totalHeight
            var targetRatio = dividerPanStartRatio + deltaRatio
            targetRatio = min(0.85, max(0.15, targetRatio))

            let snapPoints: [CGFloat] = [0.25, 0.45, 0.70]
            for snap in snapPoints {
                if abs(targetRatio - snap) < 0.025 {
                    if abs(splitRatio - snap) >= 0.025 {
                        impactFeedback.impactOccurred()
                    }
                    targetRatio = snap
                    break
                }
            }

            splitRatio = targetRatio
            ThinkspaceBridgeEmitter.shared.sendSplitRatioChange(ratio: targetRatio)
        case .ended, .cancelled:
            isDraggingDivider = false
        default:
            break
        }
    }

    // ── Props Update Pipeline ──────────────────────────────────────────────────
    @objc public func updateSplitRatio(_ ratio: CGFloat) {
        if !isDraggingDivider && abs(splitRatio - ratio) > 0.001 {
            splitRatio = ratio
        }
    }

    @objc public func updateIsSqueezed(_ squeezed: Bool) {
        if isSqueezed != squeezed {
            isSqueezed = squeezed
            setNeedsLayout()
        }
    }

    @objc public func updateIsImmersive(_ immersive: Bool) {
        if isImmersive != immersive {
            isImmersive = immersive
            setNeedsLayout()
        }
    }

    @objc public func updateActiveTool(_ tool: String) {
        activeTool = tool
    }

    @objc public func updateSelectedColor(_ color: String) {
        selectedColor = color
    }

    @objc public func updatePattern(_ newPattern: String) {
        pattern = newPattern
    }

    @objc public func updateStrokesJson(_ json: String) {
        guard let data = json.data(using: .utf8),
              let list = try? JSONDecoder().decode([InkStroke].self, from: data) else { return }
        self.strokes = list
    }

    @objc public func updateExcerptsJson(_ json: String) {
        guard let data = json.data(using: .utf8),
              let list = try? JSONDecoder().decode([ExcerptModel].self, from: data) else { return }
        self.cards = list
        infiniteCanvas.syncCards(list)
    }

    @objc public func updateInkLinksJson(_ json: String) {
        guard let data = json.data(using: .utf8),
              let list = try? JSONDecoder().decode([InkLink].self, from: data) else { return }
        self.inkLinks = list
        infiniteCanvas.inkLinkOverlay.inkLinks = list
    }

    @objc public func updateNotebookPagesJson(_ json: String) {
        guard let data = json.data(using: .utf8),
              let list = try? JSONDecoder().decode([NotebookPageModel].self, from: data) else { return }
        self.notebookPages = list
    }

    @objc public func updateWorkspaceDocumentsJson(_ json: String) {
        guard let data = json.data(using: .utf8),
              let list = try? JSONDecoder().decode([WorkspaceDocumentEntry].self, from: data) else { return }
        self.workspaceDocuments = list
        if activeDocumentId == nil, let first = list.first {
            loadActiveDocument(first)
        }
    }

    @objc public func updateActiveDocumentId(_ docId: String?) {
        guard let docId = docId, docId != activeDocumentId else { return }
        self.activeDocumentId = docId
        if let targetDoc = workspaceDocuments.first(where: { $0.id == docId }) {
            loadActiveDocument(targetDoc)
        }
    }

    private func loadActiveDocument(_ doc: WorkspaceDocumentEntry) {
        activeDocumentId = doc.id
        pdfEngine.loadDocument(uri: doc.uri, documentId: doc.id)
    }

    // ── PDFDocumentEngineDelegate ──────────────────────────────────────────────
    public func pdfEngineDidSelectText(selection: PDFSelection, excerpt: ExcerptModel) {
        // Position newly extracted card onto visible canvas center
        excerpt.x = camera.screenToWorldX(canvasContainer.bounds.width / 2.0 - excerpt.width / 2.0)
        excerpt.y = camera.screenToWorldY(canvasContainer.bounds.height / 2.0 - 50.0)

        // Register card
        cards.append(excerpt)
        infiniteCanvas.addCard(excerpt)
        ThinkspaceBridgeEmitter.shared.sendExtractExcerpt(card: excerpt)

        // Create semantic margin InkLink
        let newLink = InkLink(
            id: UUID().uuidString,
            sourceExcerptId: excerpt.id,
            targetDocumentId: activeDocumentId ?? "",
            targetPageNumber: excerpt.pageNumber,
            targetRelativeY: 0.5,
            color: excerpt.color
        )
        inkLinks.append(newLink)
        infiniteCanvas.inkLinkOverlay.inkLinks = inkLinks
        ThinkspaceBridgeEmitter.shared.sendInkLinkCreate(newLink)

        // Record Undo action
        undoRedoManager.recordAction(.addCard(excerpt))
    }

    public func pdfEnginePageDidChange(pageNumber: Int, totalPages: Int) {
        // Dispatched if page changes
    }

    // ── InfiniteCanvasViewDelegate ─────────────────────────────────────────────
    public func canvasDidTransform(panX: CGFloat, panY: CGFloat, scale: CGFloat) {
        ThinkspaceBridgeEmitter.shared.sendTransformChange(panX: panX, panY: panY, scale: scale)
    }

    public func canvasExcerptDidMove(card: ExcerptModel) {
        ThinkspaceBridgeEmitter.shared.sendExcerptMoveEnd(card: card)
    }

    public func canvasExcerptDidTap(card: ExcerptModel) {
        ThinkspaceBridgeEmitter.shared.sendExcerptPress(id: card.id)
    }

    public func canvasInkLinkDidTap(link: InkLink) {
        // Smoothly scroll PDF engine to target document and page
        if let docId = link.targetDocumentId as String?, !docId.isEmpty, docId != activeDocumentId {
            updateActiveDocumentId(docId)
        }
        if link.targetPageNumber > 0 {
            pdfEngine.scrollToPage(link.targetPageNumber)
        }
        impactFeedback.prepare()
        impactFeedback.impactOccurred()
    }

    // ── ApplePencilEngineDelegate ──────────────────────────────────────────────
    public func pencilEngineDidAddStroke(stroke: InkStroke) {
        strokes.append(stroke)
        undoRedoManager.recordAction(.addStroke(stroke))
        ThinkspaceBridgeEmitter.shared.sendAddStroke(stroke)
    }

    public func pencilEngineToolDidChange(mode: String, color: String, thickness: CGFloat) {
        ThinkspaceBridgeEmitter.shared.sendPenStateChange(mode: mode, color: color, thickness: thickness, favorites: penFavorites)
    }

    // ── UndoRedoDelegate ───────────────────────────────────────────────────────
    public func onUndoStateChanged(canUndo: Bool, canRedo: Bool) {
        ThinkspaceBridgeEmitter.shared.sendUndoStateChange(canUndo: canUndo, canRedo: canRedo)
    }

    public func applyAction(_ action: Any, isUndo: Bool) {
        guard let wsAction = action as? WorkspaceAction else { return }
        switch wsAction {
        case .addCard(let card):
            if isUndo {
                cards.removeAll { $0.id == card.id }
                inkLinks.removeAll { $0.sourceExcerptId == card.id }
                infiniteCanvas.syncCards(cards)
                infiniteCanvas.inkLinkOverlay.inkLinks = inkLinks
                ThinkspaceBridgeEmitter.shared.sendCardDelete(id: card.id)
            } else {
                cards.append(card)
                infiniteCanvas.addCard(card)
                ThinkspaceBridgeEmitter.shared.sendExtractExcerpt(card: card)
            }
        case .removeCard(let card):
            if isUndo {
                cards.append(card)
                infiniteCanvas.addCard(card)
                ThinkspaceBridgeEmitter.shared.sendExtractExcerpt(card: card)
            } else {
                cards.removeAll { $0.id == card.id }
                inkLinks.removeAll { $0.sourceExcerptId == card.id }
                infiniteCanvas.syncCards(cards)
                infiniteCanvas.inkLinkOverlay.inkLinks = inkLinks
                ThinkspaceBridgeEmitter.shared.sendCardDelete(id: card.id)
            }
        case .moveCard(let id, let oldX, let oldY, let newX, let newY):
            if let card = cards.first(where: { $0.id == id }) {
                card.x = isUndo ? oldX : newX
                card.y = isUndo ? oldY : newY
                infiniteCanvas.syncCards(cards)
                ThinkspaceBridgeEmitter.shared.sendExcerptMoveEnd(card: card)
            }
        case .addStroke(let stroke):
            if isUndo {
                strokes.removeAll { $0.id == stroke.id }
                ThinkspaceBridgeEmitter.shared.sendEraseStroke(id: stroke.id)
            } else {
                strokes.append(stroke)
                ThinkspaceBridgeEmitter.shared.sendAddStroke(stroke)
            }
        case .removeStroke(let stroke):
            if isUndo {
                strokes.append(stroke)
                ThinkspaceBridgeEmitter.shared.sendAddStroke(stroke)
            } else {
                strokes.removeAll { $0.id == stroke.id }
                ThinkspaceBridgeEmitter.shared.sendEraseStroke(id: stroke.id)
            }
        case .addInkLink(let link):
            if isUndo {
                inkLinks.removeAll { $0.id == link.id }
                infiniteCanvas.inkLinkOverlay.inkLinks = inkLinks
                ThinkspaceBridgeEmitter.shared.sendInkLinkDelete(id: link.id)
            } else {
                inkLinks.append(link)
                infiniteCanvas.inkLinkOverlay.inkLinks = inkLinks
                ThinkspaceBridgeEmitter.shared.sendInkLinkCreate(link)
            }
        case .removeInkLink(let link):
            if isUndo {
                inkLinks.append(link)
                infiniteCanvas.inkLinkOverlay.inkLinks = inkLinks
                ThinkspaceBridgeEmitter.shared.sendInkLinkCreate(link)
            } else {
                inkLinks.removeAll { $0.id == link.id }
                infiniteCanvas.inkLinkOverlay.inkLinks = inkLinks
                ThinkspaceBridgeEmitter.shared.sendInkLinkDelete(id: link.id)
            }
        }
    }

    // ── ThinkspaceCommandHandler Implementation ────────────────────────────────
    public func handleOpenSearch() {}
    public func handleCloseSearch() {
        pdfEngine.clearSearch()
    }
    public func handleNextMatch() {
        pdfEngine.nextMatch()
    }
    public func handlePrevMatch() {
        pdfEngine.prevMatch()
    }
    public func handleSearch(query: String) {
        pdfEngine.search(query: query)
    }

    public func handleUndo() {
        _ = undoRedoManager.undo()
    }

    public func handleRedo() {
        _ = undoRedoManager.redo()
    }

    public func handleZoomToFit() {
        camera.reset()
        infiniteCanvas.setNeedsDisplay()
        ThinkspaceBridgeEmitter.shared.sendTransformChange(panX: camera.panX, panY: camera.panY, scale: camera.scale)
    }

    public func handleZoomOut() {
        camera.scale = max(camera.minScale, camera.scale * 0.8)
        infiniteCanvas.setNeedsDisplay()
        ThinkspaceBridgeEmitter.shared.sendTransformChange(panX: camera.panX, panY: camera.panY, scale: camera.scale)
    }

    public func handleSetViewport(x: CGFloat, y: CGFloat, scale: CGFloat) {
        camera.panX = x
        camera.panY = y
        camera.scale = scale
        infiniteCanvas.setNeedsDisplay()
        ThinkspaceBridgeEmitter.shared.sendTransformChange(panX: x, panY: y, scale: scale)
    }

    public func handleSetSplitRatio(ratio: CGFloat) {
        splitRatio = ratio
        ThinkspaceBridgeEmitter.shared.sendSplitRatioChange(ratio: ratio)
    }

    public func handleToggleSqueezeMode() {
        isSqueezed.toggle()
        ThinkspaceBridgeEmitter.shared.sendToggleSqueeze(isSqueezed: isSqueezed)
        setNeedsLayout()
    }

    public func handleSetActiveTool(tool: String) {
        activeTool = tool
    }

    public func handleClearSelection() {
        pdfEngine.clearSearch()
    }

    public func handleAddNotebookPage(style: String, title: String) {
        let newPage = NotebookPageModel(
            id: UUID().uuidString,
            x: camera.screenToWorldX(60),
            y: camera.screenToWorldY(60),
            pageStyle: style,
            title: title
        )
        notebookPages.append(newPage)
    }

    public func handleToggleImmersiveMode() {
        isImmersive.toggle()
        ThinkspaceBridgeEmitter.shared.sendToggleImmersive(isImmersive: isImmersive)
        setNeedsLayout()
    }

    public func handleSetImmersiveMode(enabled: Bool) {
        isImmersive = enabled
        ThinkspaceBridgeEmitter.shared.sendToggleImmersive(isImmersive: isImmersive)
        setNeedsLayout()
    }

    public func handleSetPenMode(mode: String) {
        penMode = mode
        pencilEngine.activeMode = mode
    }

    public func handleSetPenColor(color: String) {
        penColor = color
        pencilEngine.strokeColor = color
    }

    public func handleSetPenThickness(thickness: CGFloat) {
        penThickness = thickness
        pencilEngine.strokeWidth = thickness
    }

    public func handleSetPenFavorites(favorites: [String]) {
        penFavorites = favorites
    }

    public func handleTogglePenSettings() {}

    public func handleSwitchToDocument(documentId: String) {
        updateActiveDocumentId(documentId)
    }

    public func handleScrollToPage(pageNumber: Int) {
        pdfEngine.scrollToPage(pageNumber)
    }

    public func handleDeleteCard(cardId: String) {
        cards.removeAll { $0.id == cardId }
        inkLinks.removeAll { $0.sourceExcerptId == cardId }
        infiniteCanvas.syncCards(cards)
        infiniteCanvas.inkLinkOverlay.inkLinks = inkLinks
        ThinkspaceBridgeEmitter.shared.sendCardDelete(id: cardId)
    }

    public func handleDeleteInkLink(linkId: String) {
        inkLinks.removeAll { $0.id == linkId }
        infiniteCanvas.inkLinkOverlay.inkLinks = inkLinks
        ThinkspaceBridgeEmitter.shared.sendInkLinkDelete(id: linkId)
    }

    public func handleClearAllCards() {
        cards.removeAll()
        inkLinks.removeAll()
        infiniteCanvas.syncCards([])
        infiniteCanvas.inkLinkOverlay.inkLinks = []
    }

    public func handleClearAllStrokes() {
        strokes.removeAll()
        pencilEngine.clearDrawing()
    }
}
