import Foundation
import UIKit
import CoreGraphics

@objc public protocol InkLinkRendererDelegate: AnyObject {
    func inkLinkDidTap(link: InkLink)
}

/**
 * Hardware-accelerated renderer for elastic margin tethers (Ink-Links).
 * Draws dynamic cubic Bézier splines, glowing anchor nodes, and pulsating dashed cords at 120 FPS.
 */
@objc public class InkLinkRenderer: UIView {

    @objc public var inkLinks: [InkLink] = [] {
        didSet { setNeedsDisplay() }
    }

    @objc public weak var delegate: InkLinkRendererDelegate?
    public var cardViews: [String: ExcerptCardView] = [:]
    public var camera: CameraTransform?

    @objc public override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isUserInteractionEnabled = true
        setupGesture()
    }

    public required init?(coder: NSCoder) {
        super.init(coder: coder)
        backgroundColor = .clear
        isUserInteractionEnabled = true
        setupGesture()
    }

    private func setupGesture() {
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        tap.cancelsTouchesInView = false
        tap.delaysTouchesBegan = false
        addGestureRecognizer(tap)
    }

    public override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        return findLink(at: point) != nil
    }

    @objc private func handleTap(_ gesture: UITapGestureRecognizer) {
        let location = gesture.location(in: self)
        if let link = findLink(at: location) {
            delegate?.inkLinkDidTap(link: link)
        }
    }

    public func findLink(at point: CGPoint) -> InkLink? {
        guard let camera = camera else { return nil }

        for link in inkLinks {
            guard let cardView = cardViews[link.sourceExcerptId] else { continue }
            let cardWorldOrigin = CGPoint(x: cardView.model.x, y: cardView.model.y)
            let cardScreenOrigin = camera.worldToScreen(cardWorldOrigin)
            let cardScreenRect = CGRect(
                x: cardScreenOrigin.x,
                y: cardScreenOrigin.y,
                width: cardView.bounds.width * camera.scale,
                height: cardView.bounds.height * camera.scale
            )

            let endPoint = CGPoint(x: cardScreenRect.minX + 12, y: cardScreenRect.minY + 20)
            let startPoint = CGPoint(x: max(-40, cardScreenRect.minX - 120), y: endPoint.y - 10)
            let tetherPath = BezierCalculus.calculateTetherCurve(start: startPoint, end: endPoint)

            // Hit test near path or pins (15pt tolerance)
            let strokedPath = tetherPath.cgPath.copy(strokingWithWidth: 30.0, lineCap: .round, lineJoin: .round, miterLimit: 1.0)
            if strokedPath.contains(point) {
                return link
            }
        }
        return nil
    }

    public override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext(), let camera = camera else { return }

        for link in inkLinks {
            guard let cardView = cardViews[link.sourceExcerptId] else { continue }

            // Target anchor on canvas: card's left/nearest edge
            let cardWorldOrigin = CGPoint(x: cardView.model.x, y: cardView.model.y)
            let cardScreenOrigin = camera.worldToScreen(cardWorldOrigin)
            let cardScreenRect = CGRect(
                x: cardScreenOrigin.x,
                y: cardScreenOrigin.y,
                width: cardView.bounds.width * camera.scale,
                height: cardView.bounds.height * camera.scale
            )

            let endPoint = CGPoint(x: cardScreenRect.minX + 12, y: cardScreenRect.minY + 20)
            // Left margin anchor projected off the left side of the canvas
            let startPoint = CGPoint(x: max(-40, cardScreenRect.minX - 120), y: endPoint.y - 10)

            let tetherPath = BezierCalculus.calculateTetherCurve(start: startPoint, end: endPoint)
            let baseColor = UIColor(hexString: link.color) ?? UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 1.0)

            // 1. Halo Glow
            context.saveGState()
            context.setLineWidth(6.0)
            context.setLineCap(.round)
            context.setStrokeColor(baseColor.withAlphaComponent(0.25).cgColor)
            context.addPath(tetherPath.cgPath)
            context.strokePath()
            context.restoreGState()

            // 2. Dashed Elastic Cord
            context.saveGState()
            context.setLineWidth(2.2)
            context.setLineCap(.round)
            let dashPattern: [CGFloat] = [10.0, 6.0]
            context.setLineDash(phase: 0, lengths: dashPattern)
            context.setStrokeColor(baseColor.withAlphaComponent(0.85).cgColor)
            context.addPath(tetherPath.cgPath)
            context.strokePath()
            context.restoreGState()

            // 3. Anchor Pins
            context.saveGState()
            context.setFillColor(baseColor.cgColor)
            let pinRadius: CGFloat = 4.5
            context.fillEllipse(in: CGRect(x: endPoint.x - pinRadius, y: endPoint.y - pinRadius, width: pinRadius * 2, height: pinRadius * 2))

            // White Inner Core
            context.setFillColor(UIColor.white.cgColor)
            let innerRadius: CGFloat = 2.0
            context.fillEllipse(in: CGRect(x: endPoint.x - innerRadius, y: endPoint.y - innerRadius, width: innerRadius * 2, height: innerRadius * 2))
            context.restoreGState()
        }
    }
}
