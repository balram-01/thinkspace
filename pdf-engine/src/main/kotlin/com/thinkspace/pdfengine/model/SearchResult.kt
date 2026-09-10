package com.thinkspace.pdfengine.model

/**
 * Result of a full-text search match within the document.
 */
data class SearchResult(
    val pageIndex: Int,
    val matchedText: String,
    val bounds: BoundingBox,
    val context: String,
    val startOffset: Int,
    val endOffset: Int,
    val quads: List<Quad> = emptyList(),
    val isFromOcr: Boolean = false
)
