import Foundation
import UIKit
import CoreGraphics

/**
 * Renders the bottom floating HUD toast notifications matching Android & the reference design:
 *  - "Extracted figure placed neatly with live Ink-Link!"
 *  - "Excerpt placed without overlap with live Ink-Link!"
 *  - "Magnetically stacked with nearby card!"
 *  - "Page squeezed — highlights aligned"
 */
@objc public class HudToastView: UIView {

    private let messageLabel = UILabel()
    private let statusDot = UIView()
    private var hideTimer: Timer?

    @objc public override init(frame: CGRect) {
        super.init(frame: frame)
        setupUI()
    }

    public required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupUI()
    }

    private func setupUI() {
        backgroundColor = UIColor(red: 0.06, green: 0.09, blue: 0.16, alpha: 0.92)
        layer.cornerRadius = 18.0
        layer.borderWidth = 1.5
        layer.borderColor = UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 0.9).cgColor

        layer.shadowColor = UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 0.5).cgColor
        layer.shadowRadius = 8.0
        layer.shadowOpacity = 0.35
        layer.shadowOffset = CGSize(width: 0, height: 2)

        // Glowing Cyan Status Dot
        statusDot.backgroundColor = UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 1.0)
        statusDot.layer.cornerRadius = 4.0
        statusDot.clipsToBounds = true
        addSubview(statusDot)

        // Text
        messageLabel.font = UIFont.systemFont(ofSize: 13.0, weight: .semibold)
        messageLabel.textColor = .white
        messageLabel.numberOfLines = 1
        addSubview(messageLabel)

        alpha = 0.0
        isUserInteractionEnabled = false
    }

    @objc public func showToast(message: String, in parentView: UIView, bottomMargin: CGFloat = 24.0) {
        hideTimer?.invalidate()
        messageLabel.text = message
        messageLabel.sizeToFit()

        let dotSize: CGFloat = 8.0
        let hPad: CGFloat = 16.0
        let vPad: CGFloat = 10.0
        let spacing: CGFloat = 10.0

        let totalWidth = hPad + dotSize + spacing + messageLabel.bounds.width + hPad
        let totalHeight: CGFloat = 36.0

        let originX = (parentView.bounds.width - totalWidth) / 2.0
        let originY = parentView.bounds.height - totalHeight - bottomMargin

        frame = CGRect(x: originX, y: originY, width: totalWidth, height: totalHeight)
        layer.cornerRadius = totalHeight / 2.0

        statusDot.frame = CGRect(x: hPad, y: (totalHeight - dotSize) / 2.0, width: dotSize, height: dotSize)
        messageLabel.frame = CGRect(
            x: statusDot.frame.maxX + spacing,
            y: (totalHeight - messageLabel.bounds.height) / 2.0,
            width: messageLabel.bounds.width,
            height: messageLabel.bounds.height
        )

        if superview != parentView {
            removeFromSuperview()
            parentView.addSubview(self)
        }
        parentView.bringSubviewToFront(self)

        UIView.animate(withDuration: 0.25, delay: 0, options: [.curveEaseOut]) {
            self.alpha = 1.0
            self.transform = .identity
        }

        hideTimer = Timer.scheduledTimer(withTimeInterval: 2.8, repeats: false) { [weak self] _ in
            self?.hideToast()
        }
    }

    @objc public func hideToast() {
        hideTimer?.invalidate()
        hideTimer = nil
        UIView.animate(withDuration: 0.3, delay: 0, options: [.curveEaseIn]) {
            self.alpha = 0.0
        }
    }
}
