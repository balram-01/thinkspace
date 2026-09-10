package com.thinkspace.pdfengine.model

import android.graphics.Bitmap

/**
 * Distinguishes embedded vector/raster images within the PDF stream from rendered pages.
 */
data class ImageElement(
    val id: String,
    val pageIndex: Int,
    val bounds: BoundingBox,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val format: String = "PNG",
    val colorSpace: String? = null,
    val bitsPerComponent: Int = 8,
    /**
     * Lazily loadable or cached bitmap stream of the original embedded image.
     * Distinct from a rendered page canvas.
     */
    val bitmap: Bitmap? = null
) {
    val isEmbedded: Boolean = true
}
