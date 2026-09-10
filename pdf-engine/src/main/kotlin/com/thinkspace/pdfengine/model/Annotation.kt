package com.thinkspace.pdfengine.model

enum class AnnotationType {
    HIGHLIGHT,
    UNDERLINE,
    STRIKETHROUGH,
    FREEHAND,
    TEXT_NOTE
}

/**
 * Common representation for document annotations, independent of physical PDF storage.
 */
sealed interface Annotation {
    val id: String
    val documentId: String
    val pageIndex: Int
    val type: AnnotationType
    val bounds: BoundingBox
    val color: Int // ARGB packed integer
    val author: String?
    val createdAt: Long
    val textRange: TextRange?
}

/**
 * Character span within a document or page for anchored text annotations.
 */
data class TextRange(
    val startOffset: Int,
    val endOffset: Int,
    val selectedText: String
)

data class HighlightAnnotation(
    override val id: String,
    override val documentId: String,
    override val pageIndex: Int,
    override val bounds: BoundingBox,
    val quads: List<Quad>,
    override val color: Int = 0x55FFFF00, // Semi-transparent yellow
    override val author: String? = null,
    override val createdAt: Long = System.currentTimeMillis(),
    override val textRange: TextRange? = null
) : Annotation {
    override val type: AnnotationType get() = AnnotationType.HIGHLIGHT
}

data class UnderlineAnnotation(
    override val id: String,
    override val documentId: String,
    override val pageIndex: Int,
    override val bounds: BoundingBox,
    val quads: List<Quad>,
    override val color: Int = 0xFF0000FF.toInt(),
    override val author: String? = null,
    override val createdAt: Long = System.currentTimeMillis(),
    override val textRange: TextRange? = null
) : Annotation {
    override val type: AnnotationType get() = AnnotationType.UNDERLINE
}

data class StrikethroughAnnotation(
    override val id: String,
    override val documentId: String,
    override val pageIndex: Int,
    override val bounds: BoundingBox,
    val quads: List<Quad>,
    override val color: Int = 0xFFFF0000.toInt(),
    override val author: String? = null,
    override val createdAt: Long = System.currentTimeMillis(),
    override val textRange: TextRange? = null
) : Annotation {
    override val type: AnnotationType get() = AnnotationType.STRIKETHROUGH
}

data class FreehandAnnotation(
    override val id: String,
    override val documentId: String,
    override val pageIndex: Int,
    override val bounds: BoundingBox,
    val paths: List<List<Point>>,
    val strokeWidth: Float = 2.0f,
    override val color: Int = 0xFF000000.toInt(),
    override val author: String? = null,
    override val createdAt: Long = System.currentTimeMillis(),
    override val textRange: TextRange? = null
) : Annotation {
    override val type: AnnotationType get() = AnnotationType.FREEHAND
}

data class TextAnnotation(
    override val id: String,
    override val documentId: String,
    override val pageIndex: Int,
    override val bounds: BoundingBox,
    val contents: String,
    override val color: Int = 0xFFFFA500.toInt(),
    override val author: String? = null,
    override val createdAt: Long = System.currentTimeMillis(),
    override val textRange: TextRange? = null
) : Annotation {
    override val type: AnnotationType get() = AnnotationType.TEXT_NOTE
}
