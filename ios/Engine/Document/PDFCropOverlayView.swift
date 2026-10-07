import Foundation
import UIKit
import PDFKit

@objc public protocol PDFCropOverlayDelegate: AnyObject {
    func cropOverlayDidExtract(imagePath: String, pageNumber: Int, rect: CGRect, color: String)
    func cropOverlayDidBeginDragging(imagePath: String, previewImage: UIImage?, locationInContainer: CGPoint)
    func cropOverlayDidUpdateDragging(locationInContainer: CGPoint)
    func cropOverlayDidEndDragging(imagePath: String, pageNumber: Int, rect: CGRect, color: String, locationInContainer: CGPoint)
    func cropOverlayDidDismiss()
}

/**
 * LiquidText-style interactive 8-point figure/image crop selection overlay.
 * Features 8 drag handles, dynamic dimension badge, smooth resizing,
 * and a floating callout toolbar with Extract, Copy, Highlight, and Palette controls.
 */
@objc public class PDFCropOverlayView: UIView {

    public enum HandleType {
        case topLeft, topCenter, topRight
        case middleLeft, middleRight
        case bottomLeft, bottomCenter, bottomRight
        case none
    }

    @objc public weak var delegate: PDFCropOverlayDelegate?
    public weak var pdfView: PDFView?
    public var targetPage: PDFPage?
    public var pageCropBounds: CGRect = .zero // In PDF Page coordinate space

    public var currentColor: String = "#00ADB5" {
        didSet {
            updateColorStyles()
        }
    }

    // UI Elements
    private let boxView = UIView()
    private let badgeLabel = UILabel()
    private let calloutView = UIView()
    private let extractBtn = UIButton(type: .system)
    private let copyBtn = UIButton(type: .system)
    private let highlightBtn = UIButton(type: .system)
    private let dismissBtn = UIButton(type: .system)
    private let colorStack = UIStackView()

    // 8 Grab Handles
    private var handles: [HandleType: UIView] = [:]
    private var activeHandle: HandleType = .none
    private var initialPanTouch: CGPoint = .zero
    private var initialScreenRect: CGRect = .zero
    private var currentScreenRect: CGRect = .zero

    private let handleSize: CGFloat = 16.0

    public override init(frame: CGRect) {
        super.init(frame: frame)
        setupUI()
    }

    public required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupUI()
    }

    private func setupUI() {
        backgroundColor = .clear
        isUserInteractionEnabled = true

        // Crop Rectangle Box
        boxView.backgroundColor = UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 0.15)
        boxView.layer.borderColor = UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 0.85).cgColor
        boxView.layer.borderWidth = 2.0
        boxView.layer.cornerRadius = 6.0
        addSubview(boxView)

        // Dimension / Page Badge
        badgeLabel.font = UIFont.systemFont(ofSize: 10.0, weight: .bold)
        badgeLabel.textColor = .white
        badgeLabel.backgroundColor = UIColor(red: 0.09, green: 0.14, blue: 0.24, alpha: 0.90)
        badgeLabel.layer.cornerRadius = 4.0
        badgeLabel.clipsToBounds = true
        badgeLabel.textAlignment = .center
        badgeLabel.text = "Photo Excerpt"
        addSubview(badgeLabel)

        // 8 Interactive Handles
        let handleTypes: [HandleType] = [
            .topLeft, .topCenter, .topRight,
            .middleLeft, .middleRight,
            .bottomLeft, .bottomCenter, .bottomRight
        ]

        for hType in handleTypes {
            let handle = UIView()
            handle.backgroundColor = .white
            handle.layer.borderColor = UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 1.0).cgColor
            handle.layer.borderWidth = 2.5
            handle.layer.cornerRadius = handleSize / 2.0
            handle.layer.shadowColor = UIColor.black.cgColor
            handle.layer.shadowOpacity = 0.35
            handle.layer.shadowRadius = 3.0
            handle.layer.shadowOffset = CGSize(width: 0, height: 1.5)
            addSubview(handle)
            handles[hType] = handle
        }

        // Floating Callout Toolbar
        calloutView.backgroundColor = UIColor(red: 0.09, green: 0.14, blue: 0.24, alpha: 0.96)
        calloutView.layer.cornerRadius = 14.0
        calloutView.layer.borderWidth = 1.2
        calloutView.layer.borderColor = UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 0.8).cgColor
        calloutView.layer.shadowColor = UIColor.black.cgColor
        calloutView.layer.shadowRadius = 8.0
        calloutView.layer.shadowOpacity = 0.35
        calloutView.layer.shadowOffset = CGSize(width: 0, height: 4)
        addSubview(calloutView)

        // Extract Crop Button (Tappable + Draggable to Canvas)
        extractBtn.setTitle("Extract Crop", for: .normal)
        extractBtn.setTitleColor(.white, for: .normal)
        extractBtn.titleLabel?.font = UIFont.systemFont(ofSize: 12.0, weight: .bold)
        extractBtn.backgroundColor = UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 1.0)
        extractBtn.layer.cornerRadius = 8.0
        extractBtn.contentEdgeInsets = UIEdgeInsets(top: 4, left: 10, bottom: 4, right: 10)
        extractBtn.addTarget(self, action: #selector(handleExtractTap), for: .touchUpInside)

        let extractDragPan = UIPanGestureRecognizer(target: self, action: #selector(handleExtractDragPan(_:)))
        extractDragPan.cancelsTouchesInView = false
        extractDragPan.delaysTouchesBegan = false
        extractBtn.addGestureRecognizer(extractDragPan)
        calloutView.addSubview(extractBtn)

        // Copy Button
        copyBtn.setTitle("Copy", for: .normal)
        copyBtn.setTitleColor(UIColor(white: 0.85, alpha: 1.0), for: .normal)
        copyBtn.titleLabel?.font = UIFont.systemFont(ofSize: 12.0, weight: .semibold)
        copyBtn.addTarget(self, action: #selector(handleCopyTap), for: .touchUpInside)
        calloutView.addSubview(copyBtn)

        // Highlight Button
        highlightBtn.setTitle("Highlight", for: .normal)
        highlightBtn.setTitleColor(UIColor(white: 0.85, alpha: 1.0), for: .normal)
        highlightBtn.titleLabel?.font = UIFont.systemFont(ofSize: 12.0, weight: .semibold)
        highlightBtn.addTarget(self, action: #selector(handleHighlightTap), for: .touchUpInside)
        calloutView.addSubview(highlightBtn)

        // Color Swatches
        colorStack.axis = .horizontal
        colorStack.spacing = 6.0
        colorStack.distribution = .fillEqually
        let colors = ["#00ADB5", "#FFEB3B", "#4CAF50", "#9C27B0", "#FF4081", "#FF9800"]
        for hex in colors {
            let colorDot = UIButton(type: .custom)
            colorDot.backgroundColor = UIColor(hexString: hex)
            colorDot.layer.cornerRadius = 8.0
            colorDot.layer.borderWidth = 1.0
            colorDot.layer.borderColor = UIColor.white.withAlphaComponent(0.4).cgColor
            colorDot.accessibilityLabel = hex
            colorDot.addTarget(self, action: #selector(handleColorDotTap(_:)), for: .touchUpInside)
            colorStack.addArrangedSubview(colorDot)
        }
        calloutView.addSubview(colorStack)

        // Dismiss Button
        dismissBtn.setTitle("✕", for: .normal)
        dismissBtn.setTitleColor(UIColor(white: 0.7, alpha: 1.0), for: .normal)
        dismissBtn.titleLabel?.font = UIFont.systemFont(ofSize: 13.0, weight: .bold)
        dismissBtn.addTarget(self, action: #selector(handleDismissTap), for: .touchUpInside)
        calloutView.addSubview(dismissBtn)

        // Box & Handles Pan Gestures
        let boxPan = UIPanGestureRecognizer(target: self, action: #selector(handleBoxPan(_:)))
        boxPan.cancelsTouchesInView = false
        boxPan.delaysTouchesBegan = false
        boxView.addGestureRecognizer(boxPan)
    }

    private func updateColorStyles() {
        let col = UIColor(hexString: currentColor) ?? UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 1.0)
        boxView.backgroundColor = col.withAlphaComponent(0.15)
        boxView.layer.borderColor = col.withAlphaComponent(0.85).cgColor
        extractBtn.backgroundColor = col
        calloutView.layer.borderColor = col.withAlphaComponent(0.8).cgColor
        for (_, handle) in handles {
            handle.layer.borderColor = col.cgColor
        }
    }

    // ── Present Crop on Given Page ─────────────────────────────────────────────
    @objc public func presentCrop(on page: PDFPage, initialPageRect: CGRect, in pdfView: PDFView) {
        self.targetPage = page
        self.pdfView = pdfView
        self.pageCropBounds = initialPageRect

        let pageIndex = (page.document?.index(for: page) ?? 0) + 1
        badgeLabel.text = "  Figure • Page \(pageIndex)  "

        updateLayoutFromPageBounds()
        alpha = 0.0
        isHidden = false

        UIView.animate(withDuration: 0.25, delay: 0, options: [.curveEaseOut]) {
            self.alpha = 1.0
        }
    }

    @objc public func updateLayoutFromPageBounds() {
        guard let page = targetPage, let pdfView = pdfView else { return }
        let screenRect = pdfView.convert(pageCropBounds, from: page)
        self.currentScreenRect = screenRect

        // Expand bounds slightly to contain handles and callout
        let margin: CGFloat = 60.0
        let overlayFrame = screenRect.insetBy(dx: -margin, dy: -margin)
        self.frame = overlayFrame

        let localBoxRect = CGRect(
            x: margin,
            y: margin,
            width: max(40.0, screenRect.width),
            height: max(40.0, screenRect.height)
        )
        boxView.frame = localBoxRect

        // Layout Badge
        badgeLabel.sizeToFit()
        badgeLabel.frame = CGRect(
            x: localBoxRect.minX + 6.0,
            y: localBoxRect.minY + 6.0,
            width: badgeLabel.bounds.width + 12.0,
            height: 20.0
        )

        // Layout 8 Handles
        let minX = localBoxRect.minX
        let midX = localBoxRect.midX
        let maxX = localBoxRect.maxX
        let minY = localBoxRect.minY
        let midY = localBoxRect.midY
        let maxY = localBoxRect.maxY
        let hRad = handleSize / 2.0

        handles[.topLeft]?.frame = CGRect(x: minX - hRad, y: minY - hRad, width: handleSize, height: handleSize)
        handles[.topCenter]?.frame = CGRect(x: midX - hRad, y: minY - hRad, width: handleSize, height: handleSize)
        handles[.topRight]?.frame = CGRect(x: maxX - hRad, y: minY - hRad, width: handleSize, height: handleSize)
        handles[.middleLeft]?.frame = CGRect(x: minX - hRad, y: midY - hRad, width: handleSize, height: handleSize)
        handles[.middleRight]?.frame = CGRect(x: maxX - hRad, y: midY - hRad, width: handleSize, height: handleSize)
        handles[.bottomLeft]?.frame = CGRect(x: minX - hRad, y: maxY - hRad, width: handleSize, height: handleSize)
        handles[.bottomCenter]?.frame = CGRect(x: midX - hRad, y: maxY - hRad, width: handleSize, height: handleSize)
        handles[.bottomRight]?.frame = CGRect(x: maxX - hRad, y: maxY - hRad, width: handleSize, height: handleSize)

        // Layout Floating Callout Toolbar
        let calloutW: CGFloat = 340.0
        let calloutH: CGFloat = 44.0
        var calloutX = localBoxRect.midX - calloutW / 2.0
        calloutX = max(0, min(bounds.width - calloutW, calloutX))

        var calloutY = localBoxRect.minY - calloutH - 12.0
        if calloutY < 0 {
            calloutY = localBoxRect.maxY + 12.0
        }
        calloutView.frame = CGRect(x: calloutX, y: calloutY, width: calloutW, height: calloutH)

        extractBtn.frame = CGRect(x: 8.0, y: 7.0, width: 100.0, height: 30.0)
        copyBtn.frame = CGRect(x: extractBtn.frame.maxX + 4.0, y: 7.0, width: 44.0, height: 30.0)
        highlightBtn.frame = CGRect(x: copyBtn.frame.maxX + 4.0, y: 7.0, width: 64.0, height: 30.0)
        colorStack.frame = CGRect(x: highlightBtn.frame.maxX + 6.0, y: 14.0, width: 80.0, height: 16.0)
        dismissBtn.frame = CGRect(x: calloutW - 28.0, y: 8.0, width: 22.0, height: 28.0)
    }

    // ── Resizing & Drag Gestures ────────────────────────────────────────────────
    public override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else { return }
        let loc = touch.location(in: self)

        activeHandle = .none
        let hitRadius: CGFloat = handleSize + 12.0

        for (hType, handleView) in handles {
            if handleView.frame.insetBy(dx: -hitRadius, dy: -hitRadius).contains(loc) {
                activeHandle = hType
                initialPanTouch = loc
                initialScreenRect = currentScreenRect
                return
            }
        }
    }

    public override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard activeHandle != .none, let touch = touches.first, let page = targetPage, let pdfView = pdfView else { return }
        let loc = touch.location(in: self)
        let dx = loc.x - initialPanTouch.x
        let dy = loc.y - initialPanTouch.y

        var r = initialScreenRect
        switch activeHandle {
        case .topLeft:
            r.origin.x += dx
            r.origin.y += dy
            r.size.width -= dx
            r.size.height -= dy
        case .topCenter:
            r.origin.y += dy
            r.size.height -= dy
        case .topRight:
            r.origin.y += dy
            r.size.width += dx
            r.size.height -= dy
        case .middleLeft:
            r.origin.x += dx
            r.size.width -= dx
        case .middleRight:
            r.size.width += dx
        case .bottomLeft:
            r.origin.x += dx
            r.size.width -= dx
            r.size.height += dy
        case .bottomCenter:
            r.size.height += dy
        case .bottomRight:
            r.size.width += dx
            r.size.height += dy
        case .none:
            break
        }

        // Clamp minimum size
        if r.width < 40.0 { r.size.width = 40.0 }
        if r.height < 40.0 { r.size.height = 40.0 }

        // Convert screen rect back to PDF page coordinates
        let p1 = pdfView.convert(CGPoint(x: r.minX, y: r.minY), to: page)
        let p2 = pdfView.convert(CGPoint(x: r.maxX, y: r.maxY), to: page)
        let pageBounds = page.bounds(for: .cropBox)

        let minPageX = max(0, min(pageBounds.width, min(p1.x, p2.x)))
        let maxPageX = max(0, min(pageBounds.width, max(p1.x, p2.x)))
        let minPageY = max(0, min(pageBounds.height, min(p1.y, p2.y)))
        let maxPageY = max(0, min(pageBounds.height, max(p1.y, p2.y)))

        pageCropBounds = CGRect(x: minPageX, y: minPageY, width: max(10, maxPageX - minPageX), height: max(10, maxPageY - minPageY))
        updateLayoutFromPageBounds()
    }

    public override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        activeHandle = .none
    }

    public override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        activeHandle = .none
    }

    @objc private func handleBoxPan(_ gesture: UIPanGestureRecognizer) {
        guard let page = targetPage, let pdfView = pdfView else { return }
        let translation = gesture.translation(in: self)

        switch gesture.state {
        case .began:
            initialScreenRect = currentScreenRect
        case .changed:
            var r = initialScreenRect
            r.origin.x += translation.x
            r.origin.y += translation.y

            let p1 = pdfView.convert(CGPoint(x: r.minX, y: r.minY), to: page)
            let p2 = pdfView.convert(CGPoint(x: r.maxX, y: r.maxY), to: page)
            let pageBounds = page.bounds(for: .cropBox)

            let minPageX = max(0, min(pageBounds.width, min(p1.x, p2.x)))
            let maxPageX = max(0, min(pageBounds.width, max(p1.x, p2.x)))
            let minPageY = max(0, min(pageBounds.height, min(p1.y, p2.y)))
            let maxPageY = max(0, min(pageBounds.height, max(p1.y, p2.y)))

            pageCropBounds = CGRect(x: minPageX, y: minPageY, width: max(10, maxPageX - minPageX), height: max(10, maxPageY - minPageY))
            updateLayoutFromPageBounds()
        default:
            break
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────────
    @objc private func handleExtractTap() {
        guard let page = targetPage, let result = renderCropImage() else { return }
        let pageNumber = (page.document?.index(for: page) ?? 0) + 1
        delegate?.cropOverlayDidExtract(imagePath: result.url.path, pageNumber: pageNumber, rect: pageCropBounds, color: currentColor)
        dismiss()
    }

    @objc private func handleExtractDragPan(_ gesture: UIPanGestureRecognizer) {
        guard let page = targetPage, let container = self.window ?? self.superview else { return }
        let location = gesture.location(in: container)
        let pageNumber = (page.document?.index(for: page) ?? 0) + 1

        switch gesture.state {
        case .began:
            if let result = renderCropImage() {
                delegate?.cropOverlayDidBeginDragging(imagePath: result.url.path, previewImage: result.image, locationInContainer: location)
            }
        case .changed:
            delegate?.cropOverlayDidUpdateDragging(locationInContainer: location)
        case .ended, .cancelled:
            if let result = renderCropImage() {
                delegate?.cropOverlayDidEndDragging(imagePath: result.url.path, pageNumber: pageNumber, rect: pageCropBounds, color: currentColor, locationInContainer: location)
            }
            dismiss()
        default:
            break
        }
    }

    @objc private func handleCopyTap() {
        if let result = renderCropImage() {
            UIPasteboard.general.image = result.image
        }
        dismiss()
    }

    @objc private func handleHighlightTap() {
        if let page = targetPage {
            let annot = PDFAnnotation(bounds: pageCropBounds, forType: .square, withProperties: nil)
            annot.color = UIColor(hexString: currentColor) ?? UIColor.yellow
            page.addAnnotation(annot)
        }
        dismiss()
    }

    @objc private func handleColorDotTap(_ sender: UIButton) {
        if let hex = sender.accessibilityLabel {
            currentColor = hex
        }
    }

    @objc private func handleDismissTap() {
        dismiss()
    }

    public func dismiss() {
        UIView.animate(withDuration: 0.15, animations: {
            self.alpha = 0.0
        }) { _ in
            self.isHidden = true
            self.delegate?.cropOverlayDidDismiss()
        }
    }

    // ── High-Quality PDF Region Rendering ──────────────────────────────────────
    public func renderCropImage(scale: CGFloat = 2.0) -> (image: UIImage, url: URL)? {
        guard let page = targetPage else { return nil }
        let cropW = max(10.0, pageCropBounds.width)
        let cropH = max(10.0, pageCropBounds.height)
        let pixelWidth = Int(cropW * scale)
        let pixelHeight = Int(cropH * scale)
        guard pixelWidth > 0, pixelHeight > 0 else { return nil }

        let renderer = UIGraphicsImageRenderer(size: CGSize(width: pixelWidth, height: pixelHeight))
        let image = renderer.image { ctx in
            UIColor.white.set()
            ctx.fill(CGRect(x: 0, y: 0, width: pixelWidth, height: pixelHeight))

            ctx.cgContext.saveGState()
            ctx.cgContext.scaleBy(x: scale, y: scale)
            let pageRect = page.bounds(for: .cropBox)
            ctx.cgContext.translateBy(x: -pageCropBounds.minX, y: -(pageRect.height - pageCropBounds.maxY))
            page.draw(with: .cropBox, to: ctx.cgContext)
            ctx.cgContext.restoreGState()
        }

        guard let pngData = image.pngData() else { return nil }
        let filename = "crop_\(UUID().uuidString).png"
        let tempURL = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(filename)

        do {
            try pngData.write(to: tempURL)
            return (image, tempURL)
        } catch {
            return nil
        }
    }
}
