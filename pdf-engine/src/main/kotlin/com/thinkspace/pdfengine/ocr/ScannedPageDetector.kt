package com.thinkspace.pdfengine.ocr

import com.thinkspace.pdfengine.api.PdfPage
import com.thinkspace.pdfengine.model.ImageElement
import com.thinkspace.pdfengine.model.PageType
import com.thinkspace.pdfengine.model.TextWord

/**
 * Objective classifier for distinguishing digital, scanned, and mixed PDF pages.
 * Prevents redundant OCR execution on digital documents.
 */
class ScannedPageDetector(
    private val minDigitalCharThreshold: Int = 30,
    private val scannedImageCoverageThreshold: Float = 0.50f
) {

    fun classify(
        page: PdfPage,
        words: List<TextWord>,
        images: List<ImageElement>
    ): PageType {
        val totalChars = words.sumOf { it.text.length }
        val pageArea = page.size.width * page.size.height

        var totalImageArea = 0f
        for (img in images) {
            totalImageArea += (img.bounds.width * img.bounds.height)
        }
        val coverageRatio = if (pageArea > 0f) totalImageArea / pageArea else 0f

        return when {
            totalChars < minDigitalCharThreshold && (coverageRatio >= scannedImageCoverageThreshold || images.isNotEmpty()) -> {
                PageType.SCANNED
            }
            totalChars >= minDigitalCharThreshold && coverageRatio >= 0.35f -> {
                PageType.MIXED
            }
            else -> {
                PageType.DIGITAL
            }
        }
    }
}
