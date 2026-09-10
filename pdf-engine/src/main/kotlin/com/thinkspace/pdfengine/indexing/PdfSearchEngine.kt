package com.thinkspace.pdfengine.indexing

import com.thinkspace.pdfengine.model.SearchResult
import com.thinkspace.pdfengine.model.TextWord

interface PdfSearchEngine {

    /**
     * Indexes text words for a given page.
     */
    fun indexPage(
        documentId: String,
        pageIndex: Int,
        words: List<TextWord>,
        isFromOcr: Boolean = false
    )

    /**
     * Executes full-text positional search for the specified query string.
     */
    suspend fun search(
        documentId: String,
        query: String
    ): List<SearchResult>

    /**
     * Clears indexed data for a document.
     */
    fun clearDocument(documentId: String)
}
