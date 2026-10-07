import Foundation
import UIKit

@objc public protocol ExcerptCardViewDelegate: AnyObject {
    func excerptCardDidMove(card: ExcerptModel, worldX: CGFloat, worldY: CGFloat)
    func excerptCardMoveDidEnd(card: ExcerptModel)
    func excerptCardDidTap(card: ExcerptModel)
    func excerptCardDidDelete(card: ExcerptModel)
    func excerptCardDidChangeColor(card: ExcerptModel, newColor: String)
    func excerptCardDidUpdateText(card: ExcerptModel, newText: String)
    func excerptCardDidTapSource(card: ExcerptModel)
}

/**
 * High-performance UIKit excerpt card view with 3D elevation, dynamic shadow,
 * in-place typography editing, color switcher, source-jump badge, and stack clustering.
 */
@objc public class ExcerptCardView: UIView, UITextFieldDelegate, UITextViewDelegate {

    @objc public let model: ExcerptModel
    @objc public weak var delegate: ExcerptCardViewDelegate?
    public weak var camera: CameraTransform?

    // UI Components
    private let colorBar = UIView()
    private let textView = UITextView()
    private let pageBadge = UIButton(type: .system)
    private let stackBadge = UILabel()
    private let docBadge = UILabel()
    private let imageView = UIImageView()
    private let deleteBtn = UIButton(type: .system)
    private let editBtn = UIButton(type: .system)
    private let actionBar = UIView()
    private let haptic = UIImpactFeedbackGenerator(style: .medium)

    // Editing State
    private var isEditingText = false
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
        layer.shadowOpacity = 0.14
        layer.shadowRadius = 6.0
        layer.shadowOffset = CGSize(width: 0, height: 3)

        // Accent Color Bar
        colorBar.layer.cornerRadius = 3.0
        addSubview(colorBar)

        // Text Content
        textView.isScrollEnabled = false
        textView.backgroundColor = .clear
        textView.delegate = self
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.isEditable = false
        addSubview(textView)

        // Image View (for Figure Cropping)
        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        imageView.layer.cornerRadius = 6.0
        imageView.isHidden = true
        addSubview(imageView)

        // Page / Source Badge (clickable to jump to source PDF passage)
        pageBadge.titleLabel?.font = UIFont.systemFont(ofSize: 10.0, weight: .bold)
        pageBadge.setTitleColor(UIColor(red: 0.0, green: 0.55, blue: 0.65, alpha: 1.0), for: .normal)
        pageBadge.backgroundColor = UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 0.12)
        pageBadge.layer.cornerRadius = 4.0
        pageBadge.contentEdgeInsets = UIEdgeInsets(top: 2, left: 6, bottom: 2, right: 6)
        pageBadge.addTarget(self, action: #selector(handleSourceTap), for: .touchUpInside)
        addSubview(pageBadge)

        // Document ID Badge (for multi-document workspaces)
        docBadge.font = UIFont.systemFont(ofSize: 9.0, weight: .bold)
        docBadge.textColor = UIColor(white: 0.5, alpha: 1.0)
        docBadge.isHidden = true
        addSubview(docBadge)

        // Stack Count Badge
        stackBadge.font = UIFont.systemFont(ofSize: 10.0, weight: .bold)
        stackBadge.textColor = .white
        stackBadge.backgroundColor = UIColor(red: 0.0, green: 0.48, blue: 1.0, alpha: 1.0)
        stackBadge.layer.cornerRadius = 8.0
        stackBadge.clipsToBounds = true
        stackBadge.textAlignment = .center
        stackBadge.isHidden = true
        addSubview(stackBadge)

        // Delete Button
        deleteBtn.setTitle("✕", for: .normal)
        deleteBtn.setTitleColor(UIColor(white: 0.6, alpha: 1.0), for: .normal)
        deleteBtn.titleLabel?.font = UIFont.systemFont(ofSize: 11.0, weight: .bold)
        deleteBtn.addTarget(self, action: #selector(handleDeleteTap), for: .touchUpInside)
        addSubview(deleteBtn)

        // Gestures
        let tapGesture = UITapGestureRecognizer(target: self, action: #selector(handleCardTap))
        tapGesture.cancelsTouchesInView = false
        tapGesture.delaysTouchesBegan = false
        tapGesture.delegate = self
        addGestureRecognizer(tapGesture)

        let panGesture = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        panGesture.cancelsTouchesInView = false
        panGesture.delaysTouchesBegan = false
        panGesture.delegate = self
        addGestureRecognizer(panGesture)

        let doubleTapGesture = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap))
        doubleTapGesture.numberOfTapsRequired = 2
        doubleTapGesture.cancelsTouchesInView = false
        doubleTapGesture.delaysTouchesBegan = false
        doubleTapGesture.delegate = self
        addGestureRecognizer(doubleTapGesture)
        tapGesture.require(toFail: doubleTapGesture)
    }

    @objc public func updateContent() {
        // Typography styling matching Android NativeCard
        var fontDescriptor = UIFont.systemFont(ofSize: max(12.0, model.fontSize)).fontDescriptor
        var traits: UIFontDescriptor.SymbolicTraits = []
        if model.isBold { traits.insert(.traitBold) }
        if model.isItalic { traits.insert(.traitItalic) }
        if let desc = fontDescriptor.withSymbolicTraits(traits) {
            fontDescriptor = desc
        }
        let font = UIFont(descriptor: fontDescriptor, size: max(12.0, model.fontSize))

        var attributes: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: UIColor(hexString: model.textColor) ?? UIColor(red: 0.12, green: 0.16, blue: 0.23, alpha: 1.0)
        ]
        if model.isUnderline {
            attributes[.underlineStyle] = NSUnderlineStyle.single.rawValue
        }
        if model.isStrikethrough {
            attributes[.strikethroughStyle] = NSUnderlineStyle.single.rawValue
        }

        textView.attributedText = NSAttributedString(string: model.text, attributes: attributes)

        // Image crop
        if model.isImage, let imgUrl = model.imageUrl {
            imageView.isHidden = false
            if let localImg = UIImage(contentsOfFile: imgUrl.replacingOccurrences(of: "file://", with: "")) {
                imageView.image = localImg
            } else if let url = URL(string: imgUrl), let data = try? Data(contentsOf: url) {
                imageView.image = UIImage(data: data)
            }
        } else {
            imageView.isHidden = true
        }

        // Badges
        let pageStr = MagneticStackingEngine.formatStackedPages(card: model)
        pageBadge.setTitle(pageStr, for: .normal)

        if let docId = model.documentId, !docId.isEmpty {
            docBadge.isHidden = false
            docBadge.text = docId
        } else {
            docBadge.isHidden = true
        }

        let hexColor = UIColor(hexString: model.color) ?? UIColor(red: 1.0, green: 0.85, blue: 0.0, alpha: 1.0)
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
        let contentW = bounds.width - contentX - pad

        deleteBtn.frame = CGRect(x: bounds.width - 24.0, y: 6.0, width: 18.0, height: 18.0)

        var topY: CGFloat = pad
        if !imageView.isHidden {
            let imgHeight: CGFloat = 100.0
            imageView.frame = CGRect(x: contentX, y: topY, width: contentW - 16.0, height: imgHeight)
            topY += imgHeight + 8.0
        }

        let bottomBadgeH: CGFloat = 20.0
        let textH = max(24.0, bounds.height - topY - bottomBadgeH - pad - 6.0)
        textView.frame = CGRect(x: contentX, y: topY, width: contentW - 16.0, height: textH)

        let badgeY = bounds.height - bottomBadgeH - pad
        pageBadge.sizeToFit()
        pageBadge.frame = CGRect(x: contentX, y: badgeY, width: pageBadge.bounds.width + 12.0, height: bottomBadgeH)

        if !docBadge.isHidden {
            docBadge.sizeToFit()
            docBadge.frame = CGRect(x: pageBadge.frame.maxX + 8.0, y: badgeY + 2.0, width: docBadge.bounds.width, height: bottomBadgeH - 4.0)
        }

        if !stackBadge.isHidden {
            stackBadge.frame = CGRect(x: bounds.width - pad - 32.0, y: badgeY, width: 30.0, height: bottomBadgeH)
        }
    }

    @objc private func handleCardTap() {
        if isEditingText {
            endEditing(true)
        }
        delegate?.excerptCardDidTap(card: model)
    }

    @objc private func handleDoubleTap() {
        // Toggle inline editing on double tap
        isEditingText = true
        textView.isEditable = true
        textView.becomeFirstResponder()
        haptic.prepare()
        haptic.impactOccurred()
    }

    @objc private func handleSourceTap() {
        haptic.prepare()
        haptic.impactOccurred()
        delegate?.excerptCardDidTapSource(card: model)
    }

    @objc private func handleDeleteTap() {
        haptic.prepare()
        haptic.impactOccurred()
        delegate?.excerptCardDidDelete(card: model)
    }

    // ── UITextViewDelegate ─────────────────────────────────────────────────────
    public func textViewDidEndEditing(_ textView: UITextView) {
        isEditingText = false
        textView.isEditable = false
        model.text = textView.text ?? ""
        delegate?.excerptCardDidUpdateText(card: model, newText: model.text)
    }

    // ── Pan Gesture ────────────────────────────────────────────────────────────
    @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
        guard let superview = superview else { return }

        switch gesture.state {
        case .began:
            isElevated = true
            haptic.prepare()
            haptic.impactOccurred()
            elevateCard(true)
            layer.zPosition = 100
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
            delegate?.excerptCardDidMove(card: model, worldX: model.x, worldY: model.y)
        case .ended, .cancelled:
            isElevated = false
            elevateCard(false)
            layer.zPosition = 0
            if let cam = camera {
                let screenOrigin = cam.worldToScreen(CGPoint(x: model.x, y: model.y))
                frame.origin = screenOrigin
            }
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
                self.layer.shadowOpacity = 0.14
                self.layer.shadowOffset = CGSize(width: 0, height: 3)
            }
        }
    }
}

extension ExcerptCardView: UIGestureRecognizerDelegate {
    public func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        return true
    }
}
