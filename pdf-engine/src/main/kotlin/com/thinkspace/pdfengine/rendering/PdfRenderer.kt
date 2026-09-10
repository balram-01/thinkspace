package com.thinkspace.pdfengine.rendering

import com.thinkspace.pdfengine.api.PdfDocument
import com.thinkspace.pdfengine.api.RenderOptions

interface PdfRenderer {
    /**
     * Rasterizes a single page into a hardware-backed Bitmap.
     */
    suspend fun renderPage(
        document: PdfDocument,
        pageIndex: Int,
        options: RenderOptions = RenderOptions()
    ): RenderedPage
}
