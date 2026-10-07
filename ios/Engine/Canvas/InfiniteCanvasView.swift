import Foundation
import UIKit
import CoreGraphics

@objc public protocol InfiniteCanvasViewDelegate: AnyObject {
    func canvasDidTransform(panX: CGFloat, panY: CGFloat, scale: CGFloat)
    func canvasExcerptDidMove(card: ExcerptModel)
    func canvasExcerptDidTap(card: ExcerptModel)
    func canvasExcerptDidDelete(card: ExcerptModel)
    func canvasExcerptDidChangeColor(card: ExcerptModel, color: String)
    func canvasExcerptDidUpdateText(card: ExcerptModel, text: String)
    func canvasExcerptDidTapSource(card: ExcerptModel)
    func canvasInkLinkDidTap(link: InkLink)
    func canvasNotebookPageDidMove(page: NotebookPageModel)
    func canvasNotebookPageDidResize(page: NotebookPageModel)
}

/**
 * High-performance 2D infinite workspace canvas with focal zoom, pan momentum, grid backgrounds,
 * card stacking, notebook pages, and GPU-accelerated InkLink tethers.
 */
@objc public class InfiniteCanvasView: UIView, ExcerptCardViewDelegate, NotebookPageViewDelegate, InkLinkRendererDelegate {

    @objc public let camera = CameraTransform()
    @objc public weak var delegate: InfiniteCanvasViewDelegate?

    @objc public var pattern: String = "looseleaf" {
        didSet { setNeedsDisplay() }
    }

    // Subviews & Layers
    private let notebookLayer = UIView()
    private let contentLayer = UIView()
    @objc public let inkLinkOverlay = InkLinkRenderer()
    private var cardViewMap: [String: ExcerptCardView] = [:]
    private var notebookViewMap: [String: NotebookPageView] = [:]

    // Gestures
    private var panGesture: UIPanGestureRecognizer!
    private var pinchGesture: UIPinchGestureRecognizer!

    @objc public override init(frame: CGRect) {
        super.init(frame: frame)
        setupCanvas()
    }

    public required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupCanvas()
    }

    private func setupCanvas() {
        backgroundColor = UIColor(white: 0.95, alpha: 1.0)
        clipsToBounds = true

        notebookLayer.frame = bounds
        notebookLayer.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(notebookLayer)

        contentLayer.frame = bounds
        contentLayer.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(contentLayer)

        inkLinkOverlay.frame = bounds
        inkLinkOverlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        inkLinkOverlay.camera = camera
        inkLinkOverlay.delegate = self
        addSubview(inkLinkOverlay)

        // 2-Finger Pan & Pinch for canvas navigation (1-finger moves cards/draws)
        panGesture = UIPanGestureRecognizer(target: self, action: #selector(handleCanvasPan(_:)))
        panGesture.minimumNumberOfTouches = 2
        panGesture.cancelsTouchesInView = false
        panGesture.delaysTouchesBegan = false
        panGesture.delegate = self
        addGestureRecognizer(panGesture)

        pinchGesture = UIPinchGestureRecognizer(target: self, action: #selector(handleCanvasPinch(_:)))
        pinchGesture.cancelsTouchesInView = false
        pinchGesture.delaysTouchesBegan = false
        pinchGesture.delegate = self
        addGestureRecognizer(pinchGesture)
    }

    // ── Grid & Pattern Rendering ───────────────────────────────────────────────
    public override func draw(_ rect: CGRect) {
        super.draw(rect)
        guard let context = UIGraphicsGetCurrentContext() else { return }

        let step: CGFloat = 24.0 * camera.scale
        guard step > 6.0 else { return } // Avoid dense moire pattern at high zoom-out

        let offsetX = camera.panX.truncatingRemainder(dividingBy: step)
        let offsetY = camera.panY.truncatingRemainder(dividingBy: step)

        if pattern == "dots" {
            context.setFillColor(UIColor(white: 0.75, alpha: 0.6).cgColor)
            let dotRadius: CGFloat = 1.2
            var x = offsetX
            while x < bounds.width {
                var y = offsetY
                while y < bounds.height {
                    context.fillEllipse(in: CGRect(x: x - dotRadius, y: y - dotRadius, width: dotRadius * 2, height: dotRadius * 2))
                    y += step
                }
                x += step
            }
        } else if pattern == "grid" {
            context.setStrokeColor(UIColor(white: 0.85, alpha: 0.7).cgColor)
            context.setLineWidth(0.6)
            var x = offsetX
            while x < bounds.width {
                context.move(to: CGPoint(x: x, y: 0))
                context.addLine(to: CGPoint(x: x, y: bounds.height))
                x += step
            }
            var y = offsetY
            while y < bounds.height {
                context.move(to: CGPoint(x: 0, y: y))
                context.addLine(to: CGPoint(x: bounds.width, y: y))
                y += step
            }
            context.strokePath()
        } else if pattern == "looseleaf" {
            context.setStrokeColor(UIColor(red: 0.82, green: 0.88, blue: 0.94, alpha: 0.75).cgColor)
            context.setLineWidth(0.8)
            var y = offsetY
            while y < bounds.height {
                context.move(to: CGPoint(x: 0, y: y))
                context.addLine(to: CGPoint(x: bounds.width, y: y))
                y += step
            }
            context.strokePath()
        }
    }

    // ── Gestures ───────────────────────────────────────────────────────────────
    @objc private func handleCanvasPan(_ gesture: UIPanGestureRecognizer) {
        let translation = gesture.translation(in: self)
        camera.applyPan(dx: translation.x, dy: translation.y)
        gesture.setTranslation(.zero, in: self)
        applyTransform()
        delegate?.canvasDidTransform(panX: camera.panX, panY: camera.panY, scale: camera.scale)
    }

    @objc private func handleCanvasPinch(_ gesture: UIPinchGestureRecognizer) {
        let focal = gesture.location(in: self)
        camera.applyPinch(focalPoint: focal, zoomFactor: gesture.scale)
        gesture.scale = 1.0
        applyTransform()
        delegate?.canvasDidTransform(panX: camera.panX, panY: camera.panY, scale: camera.scale)
    }

    private func applyTransform() {
        setNeedsDisplay()
        repositionAllCards()
        repositionAllNotebookPages()
        inkLinkOverlay.setNeedsDisplay()
    }

    // ── Card Management ────────────────────────────────────────────────────────
    @objc public func syncCards(_ cards: [ExcerptModel]) {
        let currentIds = Set(cards.map { $0.id })
        for (id, view) in cardViewMap where !currentIds.contains(id) {
            view.removeFromSuperview()
            cardViewMap.removeValue(forKey: id)
        }

        for model in cards {
            if let existing = cardViewMap[model.id] {
                existing.updateContent()
            } else {
                let cardView = ExcerptCardView(model: model)
                cardView.delegate = self
                cardView.camera = camera
                cardViewMap[model.id] = cardView
                contentLayer.addSubview(cardView)
            }
        }

        inkLinkOverlay.cardViews = cardViewMap
        repositionAllCards()
        inkLinkOverlay.setNeedsDisplay()
    }

    @objc public func addCard(_ card: ExcerptModel) {
        let cardView = ExcerptCardView(model: card)
        cardView.delegate = self
        cardView.camera = camera
        cardViewMap[card.id] = cardView
        contentLayer.addSubview(cardView)
        inkLinkOverlay.cardViews = cardViewMap
        repositionCard(cardView)
        inkLinkOverlay.setNeedsDisplay()
        triggerPlacementShockwave(at: cardView.center)
    }

    private func repositionCard(_ cardView: ExcerptCardView) {
        let screenOrigin = camera.worldToScreen(CGPoint(x: cardView.model.x, y: cardView.model.y))
        let width = cardView.model.width * camera.scale
        let height = cardView.model.estimatedHeight() * camera.scale
        cardView.frame = CGRect(x: screenOrigin.x, y: screenOrigin.y, width: width, height: height)
    }

    private func repositionAllCards() {
        for (_, view) in cardViewMap {
            repositionCard(view)
        }
    }

    // ── Notebook Pages Management ──────────────────────────────────────────────
    @objc public func syncNotebookPages(_ pages: [NotebookPageModel]) {
        let currentIds = Set(pages.map { $0.id })
        for (id, view) in notebookViewMap where !currentIds.contains(id) {
            view.removeFromSuperview()
            notebookViewMap.removeValue(forKey: id)
        }

        for page in pages {
            if let existing = notebookViewMap[page.id] {
                existing.setNeedsDisplay()
            } else {
                let pageView = NotebookPageView(model: page)
                pageView.delegate = self
                pageView.camera = camera
                notebookViewMap[page.id] = pageView
                notebookLayer.addSubview(pageView)
            }
        }
        repositionAllNotebookPages()
    }

    @objc public func addNotebookPage(_ page: NotebookPageModel) {
        let pageView = NotebookPageView(model: page)
        pageView.delegate = self
        pageView.camera = camera
        notebookViewMap[page.id] = pageView
        notebookLayer.addSubview(pageView)
        repositionNotebookPage(pageView)
    }

    private func repositionNotebookPage(_ pageView: NotebookPageView) {
        let screenOrigin = camera.worldToScreen(CGPoint(x: pageView.model.x, y: pageView.model.y))
        let width = pageView.model.width * camera.scale
        let height = pageView.model.height * camera.scale
        pageView.frame = CGRect(x: screenOrigin.x, y: screenOrigin.y, width: width, height: height)
    }

    private func repositionAllNotebookPages() {
        for (_, view) in notebookViewMap {
            repositionNotebookPage(view)
        }
    }

    // ── Visual Shockwave Animation ─────────────────────────────────────────────
    @objc public func triggerPlacementShockwave(at center: CGPoint, color: UIColor = UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 0.8)) {
        let pulseRing = UIView(frame: CGRect(x: center.x - 20, y: center.y - 20, width: 40, height: 40))
        pulseRing.layer.cornerRadius = 20.0
        pulseRing.layer.borderWidth = 2.5
        pulseRing.layer.borderColor = color.cgColor
        pulseRing.backgroundColor = color.withAlphaComponent(0.2)
        addSubview(pulseRing)

        UIView.animate(withDuration: 0.45, delay: 0, options: [.curveEaseOut]) {
            pulseRing.transform = CGAffineTransform(scaleX: 3.5, y: 3.5)
            pulseRing.alpha = 0.0
        } completion: { _ in
            pulseRing.removeFromSuperview()
        }
    }

    // ── ExcerptCardViewDelegate ────────────────────────────────────────────────
    public func excerptCardDidMove(card: ExcerptModel, worldX: CGFloat, worldY: CGFloat) {
        inkLinkOverlay.setNeedsDisplay()
    }

    public func excerptCardMoveDidEnd(card: ExcerptModel) {
        let otherCards = cardViewMap.values.map { $0.model }.filter { $0.id != card.id }
        if let target = MagneticStackingEngine.findSnapTarget(
            dragWorldX: card.x,
            dragWorldY: card.y,
            existingCards: otherCards
        ) {
            let grouped = GroupedExcerpt(
                id: card.id,
                text: card.text,
                pageNumber: card.pageNumber,
                color: card.color,
                isImage: card.isImage,
                imageUrl: card.imageUrl
            )
            MagneticStackingEngine.stackIntoCard(targetCard: target, newExcerpt: grouped)

            if let targetView = cardViewMap[target.id] {
                targetView.updateContent()
                triggerPlacementShockwave(at: targetView.center)
            }

            if let draggedView = cardViewMap[card.id] {
                draggedView.removeFromSuperview()
                cardViewMap.removeValue(forKey: card.id)
                inkLinkOverlay.cardViews = cardViewMap
            }
        }

        repositionAllCards()
        inkLinkOverlay.setNeedsDisplay()
        delegate?.canvasExcerptDidMove(card: card)
    }

    public func excerptCardDidTap(card: ExcerptModel) {
        delegate?.canvasExcerptDidTap(card: card)
    }

    public func excerptCardDidDelete(card: ExcerptModel) {
        if let view = cardViewMap[card.id] {
            view.removeFromSuperview()
            cardViewMap.removeValue(forKey: card.id)
            inkLinkOverlay.cardViews = cardViewMap
            inkLinkOverlay.setNeedsDisplay()
            delegate?.canvasExcerptDidDelete(card: card)
        }
    }

    public func excerptCardDidChangeColor(card: ExcerptModel, newColor: String) {
        card.color = newColor
        cardViewMap[card.id]?.updateContent()
        delegate?.canvasExcerptDidChangeColor(card: card, color: newColor)
    }

    public func excerptCardDidUpdateText(card: ExcerptModel, newText: String) {
        card.text = newText
        delegate?.canvasExcerptDidUpdateText(card: card, text: newText)
    }

    public func excerptCardDidTapSource(card: ExcerptModel) {
        delegate?.canvasExcerptDidTapSource(card: card)
    }

    public func inkLinkDidTap(link: InkLink) {
        delegate?.canvasInkLinkDidTap(link: link)
    }

    // ── NotebookPageViewDelegate ───────────────────────────────────────────────
    public func notebookPageDidMove(page: NotebookPageModel) {
        delegate?.canvasNotebookPageDidMove(page: page)
    }

    public func notebookPageDidResize(page: NotebookPageModel) {
        delegate?.canvasNotebookPageDidResize(page: page)
    }

    public func notebookPageDidTap(page: NotebookPageModel) {}
    public func notebookPageDidDelete(page: NotebookPageModel) {
        notebookViewMap[page.id]?.removeFromSuperview()
        notebookViewMap.removeValue(forKey: page.id)
    }
    public func notebookPageDidDuplicate(page: NotebookPageModel) {}
}

extension InfiniteCanvasView: UIGestureRecognizerDelegate {
    public func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        return true
    }
}
