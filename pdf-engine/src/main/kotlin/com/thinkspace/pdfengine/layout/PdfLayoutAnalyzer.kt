package com.thinkspace.pdfengine.layout

import com.thinkspace.pdfengine.api.PdfPage
import com.thinkspace.pdfengine.model.ImageElement
import com.thinkspace.pdfengine.model.PageStructure
import com.thinkspace.pdfengine.model.TextWord

interface PdfLayoutAnalyzer {
    suspend fun analyze(
        page: PdfPage,
        words: List<TextWord>,
        images: List<ImageElement> = emptyList(),
        isScanned: Boolean = false
    ): PageStructure
}
