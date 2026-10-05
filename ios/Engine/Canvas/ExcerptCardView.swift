import Foundation
import UIKit

@objc public protocol ExcerptCardViewDelegate: AnyObject {
    func excerptCardDidMove(card: ExcerptModel, worldX: CGFloat, worldY: CGFloat)
    func excerptCardMoveDidEnd(card: ExcerptModel)
    func excerptCardDidTap(card: ExcerptModel)
    func excerptCardDidDelete(card: ExcerptModel)
}

/**
 * High-performance UIKit excerpt card view with 3D elevation, dynamic shadow, and spring physics.
 */
@objc public class ExcerptCardView: UIView {

    @objc public let model: ExcerptModel
    @objc public weak var delegate: ExcerptCardViewDelegate?

    private let colorBar = UIView()
    private let textLabel = UILabel()
    private let pageBadge = UILabel()
    private let stackBadge = UILabel()
    private let haptic = UIImpactFeedbackGenerator(style: .light)

    private var initialDragLocation: CGPoint = .zero
    private var isElevated = false

    @objc public init(model: ExcerptModel) {
        self.model = model
        super.init(frame: CGRect(x: model.x, y: model.y, width: model.width, height: model.estimatedHeight()))
        setupUI()
        updateContent()
    }

    public required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func setupUI() {
        backgroundColor = .white
        layer.cornerRadius = 10.0
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.12
        layer.shadowRadius = 6.0
        layer.shadowOffset = CGSize(width: 0, height: 3)

        // Accent Color Bar
        colorBar.layer.cornerRadius = 3.0
        addSubview(colorBar)

        // Text Content
        textLabel.numberOfLines = 0
        textLabel.font = UIFont.systemFont(ofSize: 13.0, weight: .regular)
        textLabel.textColor = UIColor(white: 0.15, alpha: 1.0)
        addSubview(textLabel)

        // Page Badge
        pageBadge.font = UIFont.systemFont(ofSize: 10.0, weight: .semibold)
        pageBadge.textColor = UIColor(white: 0.45, alpha: 1.0)
        addSubview(pageBadge)

        // Stack Badge
        stackBadge.font = UIFont.systemFont(ofSize: 10.0, weight: .bold)
        stackBadge.textColor = .white
        stackBadge.backgroundColor = UIColor(red: 0.0, green: 0.48, blue: 1.0, alpha: 1.0)
        stackBadge.layer.cornerRadius = 8.0
        stackBadge.clipsToBounds = true
        stackBadge.textAlignment = .center
        stackBadge.isHidden = true
        addSubview(stackBadge)

        // Gestures
        let tapGesture = UITapGestureRecognizer(target: self, action: #selector(handleTap))
        addGestureRecognizer(tapGesture)

        let panGesture = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        addGestureRecognizer(panGesture)
    }

    @objc public func updateContent() {
        textLabel.text = model.text
        pageBadge.text = MagneticStackingEngine.formatStackedPages(card: model)

        let hexColor = UIColor(hexString: model.color) ?? UIColor.systemYellow
        colorBar.backgroundColor = hexColor

        if model.stackCount > 1 {
            stackBadge.isHidden = false
            stackBadge.text = "+\(model.stackCount - 1)"
        } else {
            stackBadge.isHidden = true
        }

        setNeedsLayout()
    }

    public override func layoutSubviews() {
        super.layoutSubviews()
        let pad: CGFloat = 10.0
        let barWidth: CGFloat = 4.0

        colorBar.frame = CGRect(x: pad, y: pad, width: barWidth, height: bounds.height - pad * 2)

        let contentX = colorBar.frame.maxX + 8.0
        let availableWidth = bounds.width - contentX - pad

        let pageBadgeHeight: CGFloat = 16.0
        pageBadge.frame = CGRect(
            x: contentX,
            y: bounds.height - pad - pageBadgeHeight,
            width: 100,
            height: pageBadgeHeight
        )

        if !stackBadge.isHidden {
            stackBadge.frame = CGRect(
                x: bounds.width - pad - 30,
                y: bounds.height - pad - pageBadgeHeight,
                width: 30,
                height: 16
            )
        }

        textLabel.frame = CGRect(
            x: contentX,
            y: pad,
            width: availableWidth,
            height: bounds.height - pad * 2 - pageBadgeHeight
        )
    }

    // ── Touch & Gesture Handling ───────────────────────────────────────────────
    @objc private func handleTap() {
        delegate?.excerptCardDidTap(card: model)
    }

    @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
        guard let superview = superview else { return }

        switch gesture.state {
        case .began:
            isElevated = true
            haptic.prepare()
            haptic.impactOccurred()
            elevateCard(true)
            superview.bringSubviewToFront(self)
        case .changed:
            let translation = gesture.translation(in: superview)
            center = CGPoint(x: center.x + translation.x, y: center.y + translation.y)
            gesture.setTranslation(.zero, in: superview)
            model.x = frame.origin.x
            model.y = frame.origin.y
            delegate?.excerptCardDidMove(card: model, worldX: model.x, worldY: model.y)
        case .ended, .cancelled:
            isElevated = false
            elevateCard(false)
            model.x = frame.origin.x
            model.y = frame.origin.y
            delegate?.excerptCardMoveDidEnd(card: model)
        default:
            break
        }
    }

    private func elevateCard(_ elevate: Bool) {
        UIView.animate(withDuration: 0.2, delay: 0, options: [.curveEaseOut]) {
            if elevate {
                self.transform = CGAffineTransform(scaleX: 1.05, y: 1.05)
                self.layer.shadowRadius = 14.0
                self.layer.shadowOpacity = 0.30
                self.layer.shadowOffset = CGSize(width: 0, height: 8)
            } else {
                self.transform = .identity
                self.layer.shadowRadius = 6.0
                self.layer.shadowOpacity = 0.12
                self.layer.shadowOffset = CGSize(width: 0, height: 3)
            }
        }
    }
}

// ── Color Utilities ────────────────────────────────────────────────────────────
extension UIColor {
    convenience init?(hexString: String) {
        var hexSanitized = hexString.trimmingCharacters(in: .whitespacesAndNewlines)
        hexSanitized = hexSanitized.replacingOccurrences(of: "#", with: "")

        var rgb: UInt64 = 0
        guard Scanner(string: hexSanitized).scanHexInt64(&rgb) else { return nil }

        let r = CGFloat((rgb & 0xFF0000) >> 16) / 255.0
        let g = CGFloat((rgb & 0x00FF00) >> 8) / 255.0
        let b = CGFloat(rgb & 0x0000FF) / 255.0
        self.init(red: r, green: g, blue: b, alpha: 1.0)
    }
}
