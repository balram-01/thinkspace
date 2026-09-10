package com.thinkspace.pdfengine.model

/**
 * Geometric and semantic layout structure of a page.
 */
data class PageStructure(
    val pageIndex: Int,
    val bounds: BoundingBox,
    val words: List<TextWord>,
    val lines: List<TextLine>,
    val blocks: List<TextBlock>,
    val paragraphs: List<Paragraph>,
    val images: List<ImageElement> = emptyList(),
    val columnCount: Int = 1,
    val layoutConfidence: Float = 1.0f,
    val isScanned: Boolean = false
) {
    val fullText: String by lazy {
        lines.joinToString("\n") { it.text }
    }
}
