package com.thinkspace.pdfengine.parser

import com.thinkspace.pdfengine.api.PdfDocument
import com.thinkspace.pdfengine.api.PdfSource
import com.thinkspace.pdfengine.model.DocumentMetadata
import com.thinkspace.pdfengine.storage.ResourceManager

interface PdfParser {
    suspend fun open(
        source: PdfSource,
        resourceManager: ResourceManager
    ): PdfDocument
}
