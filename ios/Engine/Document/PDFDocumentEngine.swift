import Foundation
import UIKit
import PDFKit

@objc public protocol PDFDocumentEngineDelegate: AnyObject {
    func pdfEngineDidSelectText(selection: PDFSelection, excerpt: ExcerptModel)
    func pdfEngineDidExtractCrop(imagePath: String, pageNumber: Int, rect: CGRect)
    func pdfEnginePageDidChange(pageNumber: Int, totalPages: Int)
    @objc optional func pdfEngineDidBeginDraggingSelection(selection: PDFSelection, locationInContainer: CGPoint)
    @objc optional func pdfEngineDidUpdateDraggingSelection(locationInContainer: CGPoint)
    @objc optional func pdfEngineDidEndDraggingSelection(selection: PDFSelection, locationInContainer: CGPoint)
    @objc optional func pdfEngineDidBeginDraggingCrop(imagePath: String, previewImage: UIImage?, locationInContainer: CGPoint)
    @objc optional func pdfEngineDidUpdateDraggingCrop(locationInContainer: CGPoint)
    @objc optional func pdfEngineDidEndDraggingCrop(imagePath: String, pageNumber: Int, rect: CGRect, color: String, locationInContainer: CGPoint)
}

/**
 * High-performance PDF reader powered by Apple PDFKit.
 * Features vector glyph crispness, sub-millisecond page virtualization, instant text search,
 * in-document selection floating callouts, and LiquidText-style 8-point interactive figure cropper.
 */
@objc public class PDFDocumentEngine: UIView, PDFDocumentDelegate {

    @objc public let pdfView = PDFView()
    @objc public weak var delegate: PDFDocumentEngineDelegate?
    @objc public let compressionEngine = DocumentCompressionEngine()
    @objc public let cropOverlayView = PDFCropOverlayView()

    private var currentDocument: PDFDocument?
    private var searchResults: [PDFSelection] = []
    private var currentSearchIndex: Int = -1
    public var documentId: String?

    // Floating Callout Overlay for Text Selection
    private let calloutView = UIView()
    private let extractBtn = UIButton(type: .system)
    private let highlightBtn = UIButton(type: .system)
    private let copyBtn = UIButton(type: .system)
    private let colorStack = UIStackView()
    private var activeSelection: PDFSelection?
    private var activeHighlightColor: String = "#FFEB3B"

    // Shockwave pulse overlay for source-jump animation
    private let shockwaveLayer = CAShapeLayer()

    @objc public override init(frame: CGRect) {
        super.init(frame: frame)
        setupPdfView()
        setupCalloutUI()
        setupCropOverlay()
    }

    public required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupPdfView()
        setupCalloutUI()
        setupCropOverlay()
    }

    private func setupPdfView() {
        pdfView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        pdfView.frame = bounds
        pdfView.autoScales = true
        pdfView.displayMode = .singlePageContinuous
        pdfView.displayDirection = .vertical
        pdfView.displaysPageBreaks = true
        pdfView.backgroundColor = UIColor(red: 0.06, green: 0.10, blue: 0.18, alpha: 1.0)
        addSubview(pdfView)

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handlePageChanged(_:)),
            name: .PDFViewPageChanged,
            object: pdfView
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSelectionChanged(_:)),
            name: .PDFViewSelectionChanged,
            object: pdfView
        )

        // Long press on PDF to trigger LiquidText 8-point Figure Crop selection
        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(handlePdfLongPress(_:)))
        longPress.minimumPressDuration = 0.45
        longPress.cancelsTouchesInView = false
        longPress.delaysTouchesBegan = false
        pdfView.addGestureRecognizer(longPress)
    }

    private func setupCalloutUI() {
        calloutView.backgroundColor = UIColor(red: 0.09, green: 0.14, blue: 0.24, alpha: 0.96)
        calloutView.layer.cornerRadius = 14.0
        calloutView.layer.borderWidth = 1.2
        calloutView.layer.borderColor = UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 0.8).cgColor
        calloutView.layer.shadowColor = UIColor.black.cgColor
        calloutView.layer.shadowRadius = 8.0
        calloutView.layer.shadowOpacity = 0.35
        calloutView.layer.shadowOffset = CGSize(width: 0, height: 4)
        calloutView.alpha = 0.0
        calloutView.isHidden = true
        addSubview(calloutView)

        extractBtn.setTitle("Extract Excerpt", for: .normal)
        extractBtn.setTitleColor(.white, for: .normal)
        extractBtn.titleLabel?.font = UIFont.systemFont(ofSize: 12.0, weight: .bold)
        extractBtn.backgroundColor = UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 1.0)
        extractBtn.layer.cornerRadius = 8.0
        extractBtn.contentEdgeInsets = UIEdgeInsets(top: 4, left: 10, bottom: 4, right: 10)
        extractBtn.addTarget(self, action: #selector(handleExtractTap), for: .touchUpInside)

        let extractBtnPan = UIPanGestureRecognizer(target: self, action: #selector(handlePdfSelectionDragPan(_:)))
        extractBtnPan.cancelsTouchesInView = false
        extractBtnPan.delaysTouchesBegan = false
        extractBtn.addGestureRecognizer(extractBtnPan)
        calloutView.addSubview(extractBtn)

        copyBtn.setTitle("Copy", for: .normal)
        copyBtn.setTitleColor(UIColor(white: 0.85, alpha: 1.0), for: .normal)
        copyBtn.titleLabel?.font = UIFont.systemFont(ofSize: 12.0, weight: .semibold)
        copyBtn.addTarget(self, action: #selector(handleCopyTap), for: .touchUpInside)
        calloutView.addSubview(copyBtn)

        highlightBtn.setTitle("Highlight", for: .normal)
        highlightBtn.setTitleColor(UIColor(white: 0.85, alpha: 1.0), for: .normal)
        highlightBtn.titleLabel?.font = UIFont.systemFont(ofSize: 12.0, weight: .semibold)
        highlightBtn.addTarget(self, action: #selector(handleHighlightTap), for: .touchUpInside)
        calloutView.addSubview(highlightBtn)

        colorStack.axis = .horizontal
        colorStack.spacing = 6.0
        colorStack.distribution = .fillEqually
        let colors = ["#FFEB3B", "#4CAF50", "#00ADB5", "#9C27B0", "#FF4081", "#FF9800"]
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
    }

    private func setupCropOverlay() {
        cropOverlayView.delegate = self
        cropOverlayView.isHidden = true
        addSubview(cropOverlayView)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // ── Sample Document Generator ──────────────────────────────────────────────
    public static func createSamplePDFData() -> Data {
        let pageRect = CGRect(x: 0, y: 0, width: 612, height: 792)
        let renderer = UIGraphicsPDFRenderer(bounds: pageRect)
        return renderer.pdfData { context in
            let chapters = [
                ("THE DISCOVERY OF INDIA", "Preface — Jawaharlal Nehru\n\nDuring the years of imprisonment in the Ahmadnagar Fort from 1942 to 1945, this exploration of Indian history, culture, and civilization was written.\n\nHistory is not a sterile chronicle of the past; it is an organic, living continuum linking yesterday with tomorrow. Through thousands of years of dynamic transformations, India has maintained an unbroken cultural continuity, assimilating diverse streams of thought while preserving its inner core of spiritual and intellectual vitality."),
                ("CHAPTER I: THE ROOTS OF CIVILIZATION", "The Indus Valley Civilization and the Vedic Age\n\nThe excavations at Mohenjo-daro and Harappa revealed a mature urban civilization flourishing four millennia ago. Brick buildings, well-planned streets, drainage systems, public baths, and trading quarters attest to an advanced civic culture.\n\nFollowing this came the hymns of the Rigveda and the philosophical inquiries of the Upanishads, setting forth early humanity's quest for truth, harmony, and cosmic order."),
                ("CHAPTER II: SYNTHESIS AND DIVERSITY", "Epics, Philosophy, and the Golden Ages\n\nThe Ramayana and the Mahabharata embedded moral, political, and philosophical archetypes into the consciousness of the subcontinent. The Bhagavad Gita offered a profound discourse on duty, action without attachment, and spiritual equanimity.\n\nSubsequent ages witnessed the rise of Buddhism and Jainism, the Maurya Empire under Ashoka the Great, and the classical renaissance of the Gupta period in art, science, astronomy, and mathematics."),
                ("CHAPTER III: CONTINUITY IN CHANGE", "The Modern Renaissance and Freedom\n\nThe encounter with the modern world challenged traditions and ignited a renaissance led by reformers, thinkers, and freedom fighters. India sought not merely political freedom, but the liberation of the human spirit through democratic self-determination, secularism, and universal brotherhood.")
            ]

            for (title, body) in chapters {
                context.beginPage()

                let headerColor = UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 1.0)
                headerColor.setFill()
                context.cgContext.fill(CGRect(x: 54, y: 44, width: 504, height: 3))

                let titleFont = UIFont.systemFont(ofSize: 18, weight: .bold)
                let bodyFont = UIFont.systemFont(ofSize: 13, weight: .regular)

                let titleAttributes: [NSAttributedString.Key: Any] = [
                    .font: titleFont,
                    .foregroundColor: UIColor(red: 0.06, green: 0.10, blue: 0.18, alpha: 1.0)
                ]
                let bodyAttributes: [NSAttributedString.Key: Any] = [
                    .font: bodyFont,
                    .foregroundColor: UIColor(red: 0.18, green: 0.24, blue: 0.32, alpha: 1.0)
                ]

                let titleString = NSAttributedString(string: title, attributes: titleAttributes)
                titleString.draw(at: CGPoint(x: 54, y: 58))

                let bodyString = NSAttributedString(string: body, attributes: bodyAttributes)
                let bodyRect = CGRect(x: 54, y: 100, width: 504, height: 630)
                bodyString.draw(in: bodyRect)
            }
        }
    }

    // ── Document Loading ───────────────────────────────────────────────────────
    @objc public func loadDocument(uri: String, documentId: String?) {
        self.documentId = documentId

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }

            var doc: PDFDocument?
            if uri.hasPrefix("http://") || uri.hasPrefix("https://") {
                if let url = URL(string: uri), let data = try? Data(contentsOf: url) {
                    doc = PDFDocument(data: data)
                }
            } else if uri.hasPrefix("file://") {
                if let url = URL(string: uri) {
                    doc = PDFDocument(url: url)
                }
            } else if uri == "sample.pdf" || uri.hasPrefix("file:///android_asset/") || uri.contains("sample.pdf") {
                if let bundleURL = Bundle.main.url(forResource: "sample", withExtension: "pdf") {
                    doc = PDFDocument(url: bundleURL)
                } else {
                    doc = PDFDocument(data: Self.createSamplePDFData())
                }
            } else {
                let fileURL = URL(fileURLWithPath: uri)
                if FileManager.default.fileExists(atPath: fileURL.path) {
                    doc = PDFDocument(url: fileURL)
                } else if let bundleURL = Bundle.main.url(forResource: (uri as NSString).lastPathComponent, withExtension: nil) {
                    doc = PDFDocument(url: bundleURL)
                }
            }

            if doc == nil {
                doc = PDFDocument(data: Self.createSamplePDFData())
            }

            DispatchQueue.main.async {
                self.currentDocument = doc
                self.pdfView.document = doc
                self.pdfView.autoScales = true
                self.pdfView.layoutDocumentView()
                if let firstPage = doc?.page(at: 0) {
                    self.pdfView.go(to: firstPage)
                }
                if let count = doc?.pageCount {
                    self.compressionEngine.ensurePageCount(count)
                    self.delegate?.pdfEnginePageDidChange(pageNumber: 1, totalPages: count)
                }
                self.setNeedsLayout()
            }
        }
    }

    // ── Navigation & Source Jump Shockwave ─────────────────────────────────────
    @objc public func scrollToPage(_ pageNumber: Int, sourceRects: [BoundingBox] = []) {
        guard let doc = currentDocument, pageNumber >= 1, pageNumber <= doc.pageCount else { return }
        if let targetPage = doc.page(at: pageNumber - 1) {
            pdfView.go(to: targetPage)
            if !sourceRects.isEmpty {
                triggerShockwave(on: targetPage, rects: sourceRects)
            }
        }
    }

    @objc public func triggerShockwave(on page: PDFPage, rects: [BoundingBox]) {
        guard let firstBox = rects.first else { return }
        let pageRect = page.bounds(for: .cropBox)
        let convertedRect = CGRect(x: firstBox.x, y: pageRect.height - firstBox.y - firstBox.height, width: firstBox.width, height: firstBox.height)
        let viewRect = pdfView.convert(convertedRect, from: page)

        let pulse = UIView(frame: viewRect.insetBy(dx: -8, dy: -8))
        pulse.layer.cornerRadius = 6.0
        pulse.layer.borderWidth = 3.0
        pulse.layer.borderColor = UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 1.0).cgColor
        pulse.backgroundColor = UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 0.25)
        addSubview(pulse)

        UIView.animate(withDuration: 0.6, delay: 0, options: [.curveEaseOut]) {
            pulse.transform = CGAffineTransform(scaleX: 1.4, y: 1.4)
            pulse.alpha = 0.0
        } completion: { _ in
            pulse.removeFromSuperview()
        }
    }

    @objc private func handlePageChanged(_ notification: Notification) {
        guard let doc = currentDocument, let currentPage = pdfView.currentPage else { return }
        let pageIndex = doc.index(for: currentPage) + 1
        delegate?.pdfEnginePageDidChange(pageNumber: pageIndex, totalPages: doc.pageCount)

        if !cropOverlayView.isHidden {
            cropOverlayView.updateLayoutFromPageBounds()
        }
    }

    // ── LiquidText Long-Press Figure / Area Cropper ────────────────────────────
    @objc private func handlePdfLongPress(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began else { return }
        let touchLocation = gesture.location(in: pdfView)

        // Find the page under touch
        guard let page = pdfView.page(for: touchLocation, nearest: true) else { return }
        let pagePoint = pdfView.convert(touchLocation, to: page)
        let pageBounds = page.bounds(for: .cropBox)

        // Default crop size centered on touch
        let defaultW = min(260.0, pageBounds.width * 0.75)
        let defaultH = min(180.0, pageBounds.height * 0.35)
        let originX = max(0, min(pageBounds.width - defaultW, pagePoint.x - defaultW / 2.0))
        let originY = max(0, min(pageBounds.height - defaultH, pagePoint.y - defaultH / 2.0))
        let cropRect = CGRect(x: originX, y: originY, width: defaultW, height: defaultH)

        // Clear any text selection and hide text callout
        pdfView.clearSelection()
        hideCallout()

        // Present LiquidText 8-point interactive cropper
        cropOverlayView.currentColor = activeHighlightColor
        cropOverlayView.presentCrop(on: page, initialPageRect: cropRect, in: pdfView)
        bringSubviewToFront(cropOverlayView)

        let impact = UIImpactFeedbackGenerator(style: .medium)
        impact.impactOccurred()
    }

    // Programmatic crop box creation (e.g. from toolbar)
    @objc public func triggerCropOnCurrentPage() {
        guard let currentPage = pdfView.currentPage else { return }
        let pageBounds = currentPage.bounds(for: .cropBox)
        let defaultW = min(280.0, pageBounds.width * 0.8)
        let defaultH = min(200.0, pageBounds.height * 0.4)
        let originX = (pageBounds.width - defaultW) / 2.0
        let originY = (pageBounds.height - defaultH) / 2.0
        let cropRect = CGRect(x: originX, y: originY, width: defaultW, height: defaultH)

        pdfView.clearSelection()
        hideCallout()

        cropOverlayView.currentColor = activeHighlightColor
        cropOverlayView.presentCrop(on: currentPage, initialPageRect: cropRect, in: pdfView)
        bringSubviewToFront(cropOverlayView)
    }

    // ── Selection & Floating Callout ───────────────────────────────────────────
    @objc private func handleSelectionChanged(_ notification: Notification) {
        guard let sel = pdfView.currentSelection,
              let text = sel.string, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let firstPage = sel.pages.first else {
            hideCallout()
            return
        }

        // Dismiss crop overlay when text is actively selected
        if !cropOverlayView.isHidden {
            cropOverlayView.dismiss()
        }

        activeSelection = sel
        let selBounds = sel.bounds(for: firstPage)
        let viewRect = pdfView.convert(selBounds, from: firstPage)
        showCallout(above: viewRect)
    }

    private func showCallout(above rect: CGRect) {
        let calloutW: CGFloat = 340.0
        let calloutH: CGFloat = 44.0
        var originX = rect.midX - calloutW / 2.0
        originX = max(12.0, min(bounds.width - calloutW - 12.0, originX))

        var originY = rect.minY - calloutH - 12.0
        if originY < 20.0 {
            originY = rect.maxY + 12.0
        }

        calloutView.frame = CGRect(x: originX, y: originY, width: calloutW, height: calloutH)

        extractBtn.frame = CGRect(x: 8.0, y: 7.0, width: 110.0, height: 30.0)
        copyBtn.frame = CGRect(x: extractBtn.frame.maxX + 6.0, y: 7.0, width: 44.0, height: 30.0)
        highlightBtn.frame = CGRect(x: copyBtn.frame.maxX + 4.0, y: 7.0, width: 64.0, height: 30.0)
        colorStack.frame = CGRect(x: highlightBtn.frame.maxX + 8.0, y: 14.0, width: 90.0, height: 16.0)

        calloutView.isHidden = false
        bringSubviewToFront(calloutView)

        UIView.animate(withDuration: 0.2) {
            self.calloutView.alpha = 1.0
        }
    }

    private func hideCallout() {
        guard !calloutView.isHidden else { return }
        UIView.animate(withDuration: 0.15) {
            self.calloutView.alpha = 0.0
        } completion: { _ in
            self.calloutView.isHidden = true
        }
    }

    @objc private func handleExtractTap() {
        guard let sel = activeSelection,
              let excerpt = PDFSelectionEngine.createExcerpt(from: sel, documentId: documentId, color: activeHighlightColor) else { return }
        delegate?.pdfEngineDidSelectText(selection: sel, excerpt: excerpt)
        hideCallout()
        pdfView.clearSelection()
    }

    @objc private func handleCopyTap() {
        if let text = activeSelection?.string {
            UIPasteboard.general.string = text
        }
        hideCallout()
    }

    @objc private func handleHighlightTap() {
        if let sel = activeSelection, let page = sel.pages.first {
            let annot = PDFAnnotation(bounds: sel.bounds(for: page), forType: .highlight, withProperties: nil)
            annot.color = UIColor(hexString: activeHighlightColor) ?? UIColor.yellow
            page.addAnnotation(annot)
        }
        hideCallout()
        pdfView.clearSelection()
    }

    @objc private func handleColorDotTap(_ sender: UIButton) {
        if let hex = sender.accessibilityLabel {
            activeHighlightColor = hex
            extractBtn.backgroundColor = UIColor(hexString: hex) ?? UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 1.0)
            calloutView.layer.borderColor = (UIColor(hexString: hex) ?? UIColor(red: 0.0, green: 0.68, blue: 0.71, alpha: 1.0)).withAlphaComponent(0.8).cgColor
        }
    }

    // ── Drag & Drop Excerpt Gesture Handling (from Callout Button) ──────────────
    @objc private func handlePdfSelectionDragPan(_ gesture: UIPanGestureRecognizer) {
        guard let sel = activeSelection, let container = self.window ?? self.superview else { return }
        let location = gesture.location(in: container)

        switch gesture.state {
        case .began:
            delegate?.pdfEngineDidBeginDraggingSelection?(selection: sel, locationInContainer: location)
        case .changed:
            delegate?.pdfEngineDidUpdateDraggingSelection?(locationInContainer: location)
        case .ended, .cancelled:
            delegate?.pdfEngineDidEndDraggingSelection?(selection: sel, locationInContainer: location)
            hideCallout()
            pdfView.clearSelection()
        default:
            break
        }
    }

    // ── Instant Text Search ────────────────────────────────────────────────────
    @objc public func search(query: String) {
        searchResults.removeAll()
        currentSearchIndex = -1
        guard let doc = currentDocument, !query.isEmpty else {
            pdfView.highlightedSelections = nil
            return
        }

        let selections = doc.findString(query, withOptions: .caseInsensitive)
        searchResults = selections
        pdfView.highlightedSelections = selections

        if !selections.isEmpty {
            currentSearchIndex = 0
            pdfView.go(to: selections[0])
            pdfView.setCurrentSelection(selections[0], animate: true)
        }
    }

    @objc public func nextMatch() {
        guard !searchResults.isEmpty else { return }
        currentSearchIndex = (currentSearchIndex + 1) % searchResults.count
        let selection = searchResults[currentSearchIndex]
        pdfView.go(to: selection)
        pdfView.setCurrentSelection(selection, animate: true)
    }

    @objc public func prevMatch() {
        guard !searchResults.isEmpty else { return }
        currentSearchIndex = (currentSearchIndex - 1 + searchResults.count) % searchResults.count
        let selection = searchResults[currentSearchIndex]
        pdfView.go(to: selection)
        pdfView.setCurrentSelection(selection, animate: true)
    }

    @objc public func clearSearch() {
        searchResults.removeAll()
        currentSearchIndex = -1
        pdfView.highlightedSelections = nil
        pdfView.clearSelection()
    }

    public override func layoutSubviews() {
        super.layoutSubviews()
        pdfView.frame = bounds
        if !cropOverlayView.isHidden {
            cropOverlayView.updateLayoutFromPageBounds()
        }
    }
}

// ── PDFCropOverlayDelegate Implementation ──────────────────────────────────────
extension PDFDocumentEngine: PDFCropOverlayDelegate {
    public func cropOverlayDidExtract(imagePath: String, pageNumber: Int, rect: CGRect, color: String) {
        delegate?.pdfEngineDidExtractCrop(imagePath: imagePath, pageNumber: pageNumber, rect: rect)
    }

    public func cropOverlayDidBeginDragging(imagePath: String, previewImage: UIImage?, locationInContainer: CGPoint) {
        delegate?.pdfEngineDidBeginDraggingCrop?(imagePath: imagePath, previewImage: previewImage, locationInContainer: locationInContainer)
    }

    public func cropOverlayDidUpdateDragging(locationInContainer: CGPoint) {
        delegate?.pdfEngineDidUpdateDraggingCrop?(locationInContainer: locationInContainer)
    }

    public func cropOverlayDidEndDragging(imagePath: String, pageNumber: Int, rect: CGRect, color: String, locationInContainer: CGPoint) {
        delegate?.pdfEngineDidEndDraggingCrop?(imagePath: imagePath, pageNumber: pageNumber, rect: rect, color: color, locationInContainer: locationInContainer)
    }

    public func cropOverlayDidDismiss() {}
}
