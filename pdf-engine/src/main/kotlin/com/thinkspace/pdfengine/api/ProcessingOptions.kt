package com.thinkspace.pdfengine.api

import android.graphics.Bitmap
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.ProcessingProgress

/**
 * Configuration options controlling the incremental document processing pipeline.
 */
data class ProcessingOptions(
    val extractImages: Boolean = false,
    val performLayoutAnalysis: Boolean = true,
    val enableOcr: Boolean = true,
    val ocrMinimumCharThreshold: Int = 15,
    val indexForSearch: Boolean = true,
    val startPageIndex: Int = 0,
    val pageLimit: Int? = null,
    val onProgress: ((ProcessingProgress) -> Unit)? = null
)

/**
 * Configuration options controlling page rasterization.
 */
data class RenderOptions(
    /**
     * Scale factor relative to nominal 72 DPI (e.g. 1.0 = 72 DPI, 2.0 = 144 DPI, 4.0 = 288 DPI).
     */
    val scale: Float = 1.5f,

    /**
     * Optional sub-region of the page in page points (top-left origin).
     * If specified, only this viewport region is rendered for maximum performance and minimum memory footprint.
     */
    val viewport: BoundingBox? = null,

    /**
     * Bitmap pixel configuration. Defaults to ARGB_8888 for high quality, or RGB_565 for 50% memory saving.
     */
    val bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888,

    /**
     * Background color (default white 0xFFFFFFFF).
     */
    val backgroundColor: Int = 0xFFFFFFFF.toInt(),

    /**
     * Whether to render annotations embedded in the PDF.
     */
    val renderAnnotations: Boolean = true
)
