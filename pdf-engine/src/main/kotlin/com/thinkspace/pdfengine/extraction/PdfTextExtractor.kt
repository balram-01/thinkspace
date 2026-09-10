package com.thinkspace.pdfengine.extraction

import com.thinkspace.pdfengine.api.PdfDocument
import com.thinkspace.pdfengine.model.TextWord

interface PdfTextExtractor {
    /**
     * Extracts all positioned words and character geometries from the given page.
     */
    suspend fun extractWords(document: PdfDocument, pageIndex: Int): List<TextWord>
}
