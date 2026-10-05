import Foundation
import UIKit
import PDFKit

@objc public protocol PDFDocumentEngineDelegate: AnyObject {
    func pdfEngineDidSelectText(selection: PDFSelection, excerpt: ExcerptModel)
    func pdfEnginePageDidChange(pageNumber: Int, totalPages: Int)
}

/**
 * High-performance PDF reader powered by Apple PDFKit.
 * Features vector glyph crispness, sub-millisecond page virtualization, and on-device text search.
 */
@objc public class PDFDocumentEngine: UIView, PDFDocumentDelegate {

    @objc public let pdfView = PDFView()
    @objc public weak var delegate: PDFDocumentEngineDelegate?

    private var currentDocument: PDFDocument?
    private var searchResults: [PDFSelection] = []
    private var currentSearchIndex: Int = -1
    public var documentId: String?

    @objc public override init(frame: CGRect) {
        super.init(frame: frame)
        setupPdfView()
    }

    public required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupPdfView()
    }

    private func setupPdfView() {
        pdfView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        pdfView.frame = bounds
        pdfView.autoScales = true
        pdfView.displayMode = .singlePageContinuous
        pdfView.displayDirection = .vertical
        pdfView.displaysPageBreaks = true
        pdfView.backgroundColor = UIColor(white: 0.94, alpha: 1.0)
        addSubview(pdfView)

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handlePageChanged(_:)),
            name: .PDFViewPageChanged,
            object: pdfView
        )

        // Long press gesture for excerpt extraction
        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
        longPress.minimumPressDuration = 0.4
        pdfView.addGestureRecognizer(longPress)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
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
            } else {
                let fileURL = URL(fileURLWithPath: uri)
                doc = PDFDocument(url: fileURL)
            }

            DispatchQueue.main.async {
                self.currentDocument = doc
                self.pdfView.document = doc
                if let firstPage = doc?.page(at: 0) {
                    self.pdfView.go(to: firstPage)
                }
                if let count = doc?.pageCount {
                    self.delegate?.pdfEnginePageDidChange(pageNumber: 1, totalPages: count)
                }
            }
        }
    }

    // ── Navigation ─────────────────────────────────────────────────────────────
    @objc public func scrollToPage(_ pageNumber: Int) {
        guard let doc = currentDocument, pageNumber >= 1, pageNumber <= doc.pageCount else { return }
        if let targetPage = doc.page(at: pageNumber - 1) {
            pdfView.go(to: targetPage)
        }
    }

    @objc private func handlePageChanged(_ notification: Notification) {
        guard let doc = currentDocument, let currentPage = pdfView.currentPage else { return }
        let pageIndex = doc.index(for: currentPage) + 1
        delegate?.pdfEnginePageDidChange(pageNumber: pageIndex, totalPages: doc.pageCount)
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

    // ── Selection & Excerpt Extraction ─────────────────────────────────────────
    @objc private func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began else { return }
        if let currentSelection = pdfView.currentSelection,
           let excerpt = PDFSelectionEngine.createExcerpt(from: currentSelection, documentId: documentId) {
            delegate?.pdfEngineDidSelectText(selection: currentSelection, excerpt: excerpt)
        }
    }

    public override func layoutSubviews() {
        super.layoutSubviews()
        pdfView.frame = bounds
    }
}
