import Foundation
import UIKit
import CoreGraphics

@objc public protocol NotebookPageViewDelegate: AnyObject {
    func notebookPageDidMove(page: NotebookPageModel)
    func notebookPageDidResize(page: NotebookPageModel)
    func notebookPageDidTap(page: NotebookPageModel)
    func notebookPageDidDelete(page: NotebookPageModel)
    func notebookPageDidDuplicate(page: NotebookPageModel)
}

/**
 * Movable, resizable physical paper notebook page on the infinite 2D canvas.
 * Supports "ruled", "grid", "dotted", "blank", and "sketch" textured patterns.
 */
@objc public class NotebookPageView: UIView {

    @objc public let model: NotebookPageModel
    @objc public weak var delegate: NotebookPageViewDelegate?
    public weak var camera: CameraTransform?

    private let titleLabel = UILabel()
    private let headerBar = UIView()
    private let resizeHandle = UIView()
    private let patternView = UIView()
    private let haptic = UIImpactFeedbackGenerator(style: .light)

    @objc public init(model: NotebookPageModel) {
        self.model = model
        super.init(frame: CGRect(x: model.x, y: model.y, width: model.width, height: model.height))
        setupUI()
    }

    public required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func setupUI() {
        backgroundColor = UIColor(hexString: model.backgroundColor) ?? UIColor(red: 1.0, green: 0.99, blue: 0.94, alpha: 1.0)
        layer.cornerRadius = 8.0
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.18
        layer.shadowRadius = 8.0
        layer.shadowOffset = CGSize(width: 0, height: 4)

        // Header bar with title
        headerBar.backgroundColor = UIColor(white: 0.0, alpha: 0.05)
        addSubview(headerBar)

        titleLabel.text = model.title
        titleLabel.font = UIFont.systemFont(ofSize: 13.0, weight: .bold)
        titleLabel.textColor = UIColor(red: 0.18, green: 0.24, blue: 0.32, alpha: 1.0)
        headerBar.addSubview(titleLabel)

        // Resize handle at bottom-right corner
        resizeHandle.backgroundColor = UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 0.8)
        resizeHandle.layer.cornerRadius = 6.0
        addSubview(resizeHandle)

        let panGesture = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        addGestureRecognizer(panGesture)

        let resizeGesture = UIPanGestureRecognizer(target: self, action: #selector(handleResize(_:)))
        resizeHandle.addGestureRecognizer(resizeGesture)

        let tapGesture = UITapGestureRecognizer(target: self, action: #selector(handleTap))
        addGestureRecognizer(tapGesture)
    }

    public override func layoutSubviews() {
        super.layoutSubviews()
        headerBar.frame = CGRect(x: 0, y: 0, width: bounds.width, height: 32.0)
        titleLabel.frame = CGRect(x: 14.0, y: 6.0, width: bounds.width - 28.0, height: 20.0)
        resizeHandle.frame = CGRect(x: bounds.width - 16.0, y: bounds.height - 16.0, width: 14.0, height: 14.0)
    }

    public override func draw(_ rect: CGRect) {
        super.draw(rect)
        guard let context = UIGraphicsGetCurrentContext() else { return }

        let style = model.pageStyle
        let headerH: CGFloat = 32.0
        let contentBounds = CGRect(x: 0, y: headerH, width: bounds.width, height: bounds.height - headerH)

        if style == "ruled" {
            // Horizontal lines
            let lineSpacing: CGFloat = 22.0
            context.setStrokeColor(UIColor(red: 0.75, green: 0.82, blue: 0.90, alpha: 0.7).cgColor)
            context.setLineWidth(0.8)

            var y = contentBounds.minY + lineSpacing
            while y < contentBounds.maxY {
                context.move(to: CGPoint(x: 14.0, y: y))
                context.addLine(to: CGPoint(x: bounds.width - 14.0, y: y))
                y += lineSpacing
            }
            context.strokePath()

            // Left vertical margin line in pale coral
            context.setStrokeColor(UIColor(red: 0.95, green: 0.55, blue: 0.55, alpha: 0.6).cgColor)
            context.setLineWidth(1.0)
            context.move(to: CGPoint(x: 44.0, y: contentBounds.minY))
            context.addLine(to: CGPoint(x: 44.0, y: contentBounds.maxY))
            context.strokePath()

        } else if style == "grid" {
            // Squared grid
            let gridStep: CGFloat = 18.0
            context.setStrokeColor(UIColor(red: 0.80, green: 0.85, blue: 0.90, alpha: 0.6).cgColor)
            context.setLineWidth(0.6)

            var x: CGFloat = 14.0
            while x < bounds.width - 14.0 {
                context.move(to: CGPoint(x: x, y: contentBounds.minY))
                context.addLine(to: CGPoint(x: x, y: contentBounds.maxY))
                x += gridStep
            }
            var y = contentBounds.minY + gridStep
            while y < contentBounds.maxY {
                context.move(to: CGPoint(x: 14.0, y: y))
                context.addLine(to: CGPoint(x: bounds.width - 14.0, y: y))
                y += gridStep
            }
            context.strokePath()

        } else if style == "dotted" {
            // Dotted grid
            let dotStep: CGFloat = 18.0
            let dotRadius: CGFloat = 1.0
            context.setFillColor(UIColor(red: 0.65, green: 0.70, blue: 0.78, alpha: 0.6).cgColor)

            var x: CGFloat = 14.0
            while x < bounds.width - 14.0 {
                var y = contentBounds.minY + dotStep
                while y < contentBounds.maxY {
                    context.fillEllipse(in: CGRect(x: x - dotRadius, y: y - dotRadius, width: dotRadius * 2, height: dotRadius * 2))
                    y += dotStep
                }
                x += dotStep
            }
        }
    }

    @objc private func handleTap() {
        delegate?.notebookPageDidTap(page: model)
    }

    @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
        guard let superview = superview else { return }

        switch gesture.state {
        case .began:
            haptic.prepare()
            haptic.impactOccurred()
            superview.bringSubviewToFront(self)
        case .changed:
            let translation = gesture.translation(in: superview)
            let scale = camera?.scale ?? 1.0
            let worldDx = translation.x / scale
            let worldDy = translation.y / scale
            model.x += worldDx
            model.y += worldDy

            if let cam = camera {
                let screenOrigin = cam.worldToScreen(CGPoint(x: model.x, y: model.y))
                frame.origin = screenOrigin
            } else {
                frame.origin = CGPoint(x: frame.origin.x + translation.x, y: frame.origin.y + translation.y)
            }

            gesture.setTranslation(.zero, in: superview)
            delegate?.notebookPageDidMove(page: model)
        case .ended, .cancelled:
            if let cam = camera {
                let screenOrigin = cam.worldToScreen(CGPoint(x: model.x, y: model.y))
                frame.origin = screenOrigin
            }
            delegate?.notebookPageDidMove(page: model)
        default:
            break
        }
    }

    @objc private func handleResize(_ gesture: UIPanGestureRecognizer) {
        guard let superview = superview else { return }

        switch gesture.state {
        case .changed:
            let translation = gesture.translation(in: superview)
            let scale = camera?.scale ?? 1.0
            let worldDw = translation.x / scale
            let worldDh = translation.y / scale

            model.width = max(200.0, model.width + worldDw)
            model.height = max(200.0, model.height + worldDh)

            frame.size = CGSize(width: model.width * scale, height: model.height * scale)
            gesture.setTranslation(.zero, in: superview)
            setNeedsDisplay()
            delegate?.notebookPageDidResize(page: model)
        case .ended, .cancelled:
            delegate?.notebookPageDidResize(page: model)
        default:
            break
        }
    }
}
