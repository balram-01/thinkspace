import Foundation
import UIKit
import PDFKit

/**
 * Handles precision character quad extraction, word snapping, and dual-handle selection overlays on PDF pages.
 */
@objc public class PDFSelectionEngine: NSObject {

    @objc public static func extractBoundingBoxes(from selection: PDFSelection, page: PDFPage) -> [BoundingBox] {
        var boxes: [BoundingBox] = []
        let pageBounds = page.bounds(for: .cropBox)
        let lineSelections = selection.selectionsByLine()

        if lineSelections.isEmpty {
            let rect = selection.bounds(for: page)
            let convertedY = pageBounds.height - rect.maxY
            boxes.append(BoundingBox(x: rect.origin.x, y: convertedY, width: rect.width, height: rect.height))
        } else {
            for line in lineSelections {
                let rect = line.bounds(for: page)
                let convertedY = pageBounds.height - rect.maxY
                boxes.append(BoundingBox(x: rect.origin.x, y: convertedY, width: rect.width, height: rect.height))
            }
        }
        return boxes
    }

    /**
     * Creates an ExcerptModel from an active PDFSelection.
     */
    @objc public static func createExcerpt(
        from selection: PDFSelection,
        documentId: String?,
        color: String = "#FFEB3B"
    ) -> ExcerptModel? {
        guard let text = selection.string, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let firstPage = selection.pages.first,
              let document = firstPage.document else {
            return nil
        }

        let pageIndex = document.index(for: firstPage) + 1
        let boxes = extractBoundingBoxes(from: selection, page: firstPage)

        return ExcerptModel(
            id: UUID().uuidString,
            documentId: documentId,
            pageNumber: pageIndex,
            text: text,
            color: color,
            x: 0,
            y: 0,
            width: 220,
            comment: nil,
            tags: [],
            clusterId: nil,
            stackCount: 1,
            imageUrl: nil,
            isTable: false,
            isImage: false,
            groupedItems: nil,
            sourceRects: boxes
        )
    }
}
