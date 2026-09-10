package com.thinkspace.pdfengine.rendering

import android.graphics.Bitmap
import com.thinkspace.pdfengine.model.BoundingBox
import java.io.Closeable

/**
 * Encapsulates a rendered page raster bitmap and its spatial bounds.
 */
data class RenderedPage(
    val pageIndex: Int,
    val bitmap: Bitmap,
    val scale: Float,
    val renderedBounds: BoundingBox,
    val renderDurationMs: Long,
    private val onRecycle: ((Bitmap) -> Unit)? = null
) : Closeable {

    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height
    val isRecycled: Boolean get() = bitmap.isRecycled

    override fun close() {
        if (!bitmap.isRecycled) {
            onRecycle?.invoke(bitmap)
        }
    }
}
