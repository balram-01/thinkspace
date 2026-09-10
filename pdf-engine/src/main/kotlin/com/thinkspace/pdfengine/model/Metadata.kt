package com.thinkspace.pdfengine.model

/**
 * Standard PDF page rotation angles.
 */
enum class RotationAngle(val degrees: Int) {
    ROTATE_0(0),
    ROTATE_90(90),
    ROTATE_180(180),
    ROTATE_270(270);

    companion object {
        fun fromDegrees(deg: Int): RotationAngle {
            val normalized = ((deg % 360) + 360) % 360
            return when (normalized) {
                90 -> ROTATE_90
                180 -> ROTATE_180
                270 -> ROTATE_270
                else -> ROTATE_0
            }
        }
    }
}

/**
 * Classification of a page based on its content nature.
 */
enum class PageType {
    /** Page contains native digital text streams. */
    DIGITAL,
    /** Page is primarily a scanned bitmap image with no or negligible embedded text. */
    SCANNED,
    /** Page contains both native digital text and substantial scanned/raster imagery. */
    MIXED
}

/**
 * Metadata associated with a specific PDF page.
 */
data class PageMetadata(
    val pageIndex: Int,
    val mediaBox: BoundingBox,
    val cropBox: BoundingBox,
    val rotation: RotationAngle,
    val effectiveSize: PageSize,
    val pageType: PageType = PageType.DIGITAL,
    val hasImages: Boolean = false,
    val imageCount: Int = 0,
    val textCharacterCount: Int = 0
)

/**
 * Document-level metadata extracted from PDF Catalog & Information Dictionary.
 */
data class DocumentMetadata(
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: List<String> = emptyList(),
    val creator: String? = null,
    val producer: String? = null,
    val creationDate: String? = null,
    val modificationDate: String? = null,
    val pageCount: Int = 0,
    val isEncrypted: Boolean = false,
    val pdfVersion: String? = null,
    val fileSizeInBytes: Long = 0L
)
