package com.thinkspace.pdfengine.extraction

import com.thinkspace.pdfengine.api.PdfDocument
import com.thinkspace.pdfengine.model.ImageElement

interface PdfImageExtractor {
    /**
     * Extracts embedded raster/vector images from the given page stream.
     */
    suspend fun extractImages(document: PdfDocument, pageIndex: Int): List<ImageElement>
}
