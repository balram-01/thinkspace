package com.thinkspace.pdfengine.model

/**
 * Dimensions of a PDF page in points (1/72 inch).
 */
data class PageSize(
    val width: Float,
    val height: Float
) {
    val aspectRatio: Float get() = if (height > 0f) width / height else 1f
    val isLandscape: Boolean get() = width > height
    val isPortrait: Boolean get() = height >= width

    companion object {
        val LETTER = PageSize(612f, 792f)
        val A4 = PageSize(595.28f, 841.89f)
        val LEGAL = PageSize(612f, 1008f)
        val A3 = PageSize(841.89f, 1190.55f)
        val ZERO = PageSize(0f, 0f)
    }
}
