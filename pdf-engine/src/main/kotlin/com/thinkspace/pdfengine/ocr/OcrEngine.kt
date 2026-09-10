package com.thinkspace.pdfengine.ocr

import android.graphics.Bitmap
import com.thinkspace.pdfengine.api.PdfPage
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.OcrResult

/**
 * Replaceable abstraction for Optical Character Recognition.
 * Enables on-device (ML Kit, Tesseract) or remote OCR backends.
 */
interface OcrEngine {

    /**
     * Executes optical character recognition on a rasterized page bitmap.
     * Returned coordinates MUST be normalized to page coordinates.
     */
    suspend fun recognize(
        page: PdfPage,
        image: Bitmap
    ): OcrResult

    val isAvailable: Boolean
}

/**
 * Fallback OCR engine when OCR is disabled or unsupported.
 */
object NoOpOcrEngine : OcrEngine {
    override suspend fun recognize(page: PdfPage, image: Bitmap): OcrResult {
        return OcrResult(
            pageIndex = page.pageIndex,
            fullText = "",
            bounds = BoundingBox(0f, 0f, page.size.width, page.size.height),
            confidence = 0f
        )
    }

    override val isAvailable: Boolean = false
}
