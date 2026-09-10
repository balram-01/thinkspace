package com.thinkspace.pdfengine.model

/**
 * Typographic font style flags.
 */
enum class FontStyle {
    REGULAR,
    BOLD,
    ITALIC,
    BOLD_ITALIC;

    val isBold: Boolean get() = this == BOLD || this == BOLD_ITALIC
    val isItalic: Boolean get() = this == ITALIC || this == BOLD_ITALIC
}

/**
 * Base sealed interface for all positioned text entities extracted from a document.
 */
sealed interface TextElement {
    val text: String
    val pageIndex: Int
    val bounds: BoundingBox
}

/**
 * Character-level geometry extracted from the PDF glyph stream.
 */
data class TextCharacter(
    val char: Char,
    override val pageIndex: Int,
    override val bounds: BoundingBox,
    val fontSize: Float,
    val fontName: String,
    val fontStyle: FontStyle = FontStyle.REGULAR,
    val baseline: Float,
    val rotation: Float = 0f,
    val orderIndex: Int = 0
) : TextElement {
    override val text: String get() = char.toString()
}

/**
 * Positioned word composed of individual characters or contiguous glyphs.
 */
data class TextWord(
    override val text: String,
    override val pageIndex: Int,
    override val bounds: BoundingBox,
    val fontSize: Float,
    val fontName: String,
    val fontStyle: FontStyle = FontStyle.REGULAR,
    val baseline: Float,
    val characters: List<TextCharacter> = emptyList(),
    val rotation: Float = 0f,
    val orderIndex: Int = 0
) : TextElement

/**
 * Horizontally or collinear grouped sequence of words forming a line.
 */
data class TextLine(
    override val text: String,
    override val pageIndex: Int,
    override val bounds: BoundingBox,
    val words: List<TextWord>,
    val averageFontSize: Float,
    val baseline: Float,
    val rotation: Float = 0f,
    val orderIndex: Int = 0
) : TextElement

/**
 * Cohesive visual block of text lines, belonging to a layout column.
 */
data class TextBlock(
    val id: String,
    override val text: String,
    override val pageIndex: Int,
    override val bounds: BoundingBox,
    val lines: List<TextLine>,
    val columnIndex: Int = 0,
    val isHeading: Boolean = false,
    val orderIndex: Int = 0
) : TextElement

/**
 * Semantically inferred paragraph containing one or more blocks or lines.
 */
data class Paragraph(
    val id: String,
    override val text: String,
    override val pageIndex: Int,
    override val bounds: BoundingBox,
    val blocks: List<TextBlock>,
    val orderIndex: Int = 0
) : TextElement
