package com.thinkspace.pdfengine.model

/**
 * Request to compute geometric selection across one or multiple pages.
 */
sealed class TextSelectionRequest {

    /**
     * Point-to-point touch selection on a single page.
     */
    data class PointRange(
        val pageIndex: Int,
        val startPoint: Point,
        val endPoint: Point
    ) : TextSelectionRequest()

    /**
     * Character-index selection on a single page based on reading order.
     */
    data class CharacterRange(
        val pageIndex: Int,
        val startCharIndex: Int,
        val endCharIndex: Int
    ) : TextSelectionRequest()

    /**
     * Multi-page selection between two endpoints across arbitrary pages.
     */
    data class MultiPageRange(
        val startPage: Int,
        val startPoint: Point,
        val endPage: Int,
        val endPoint: Point
    ) : TextSelectionRequest()
}

/**
 * Geometric and textual result of a text selection operation.
 */
data class TextSelection(
    val text: String,
    val startPage: Int,
    val endPage: Int,
    val bounds: BoundingBox,
    val quads: List<Quad>,
    val pageSelections: List<PageTextSelection> = emptyList()
)

/**
 * Page-specific selection snippet contributing to a multi-page selection.
 */
data class PageTextSelection(
    val pageIndex: Int,
    val text: String,
    val bounds: BoundingBox,
    val quads: List<Quad>,
    val selectedWords: List<TextWord> = emptyList()
)
