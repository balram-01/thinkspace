package com.thinkspace.pdfengine.api

import com.thinkspace.pdfengine.coordinates.PdfCoordinateMapper
import com.thinkspace.pdfengine.model.PageMetadata
import com.thinkspace.pdfengine.model.PageSize

/**
 * Handle to an individual page within a PdfDocument.
 */
interface PdfPage {
    val pageIndex: Int
    val documentId: String
    val metadata: PageMetadata
    val size: PageSize
    val coordinateMapper: PdfCoordinateMapper
}

/**
 * Immutable concrete implementation of PdfPage.
 */
data class DefaultPdfPage(
    override val pageIndex: Int,
    override val documentId: String,
    override val metadata: PageMetadata,
    override val size: PageSize,
    override val coordinateMapper: PdfCoordinateMapper
) : PdfPage
