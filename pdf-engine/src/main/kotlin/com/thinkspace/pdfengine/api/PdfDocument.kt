package com.thinkspace.pdfengine.api

import com.thinkspace.pdfengine.model.DocumentMetadata
import java.io.Closeable

/**
 * Handle to an opened PDF document session.
 * Manages document-level lifecycle and page indexing.
 */
interface PdfDocument : Closeable {
    val id: String
    val pageCount: Int
    val metadata: DocumentMetadata
    val isOpen: Boolean
    val isClosed: Boolean get() = !isOpen

    /**
     * Retrieves lightweight page handle.
     */
    fun getPage(pageIndex: Int): PdfPage

    /**
     * Deterministically closes underlying PDF resources.
     */
    override fun close()
}
