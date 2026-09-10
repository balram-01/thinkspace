package com.thinkspace.pdfengine.coordinates

import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.Point
import kotlin.math.max
import kotlin.math.min

/**
 * Point in PDF User Space.
 * Origin: Bottom-left of Crop/Media box.
 * X: Points Right (points, 1/72").
 * Y: Points UP (points, 1/72").
 */
data class PdfPoint(val x: Float, val y: Float)

/**
 * Axis-aligned rectangle in PDF User Space.
 * bottom <= top, left <= right.
 */
data class PdfRect(
    val left: Float,
    val bottom: Float,
    val right: Float,
    val top: Float
) {
    init {
        require(left <= right) { "left ($left) must be <= right ($right)" }
        require(bottom <= top) { "bottom ($bottom) must be <= top ($top)" }
    }
    val width: Float get() = right - left
    val height: Float get() = top - bottom
}

/**
 * Point in Normalized Page Coordinate Space.
 * Origin: Top-left of the rotated visible crop area.
 * X: Points Right (points, 1/72").
 * Y: Points DOWN (points, 1/72").
 */
data class PagePoint(val x: Float, val y: Float) {
    fun toModelPoint(): Point = Point(x, y)
}

/**
 * Rectangle in Page Coordinate Space.
 * left <= right, top <= bottom.
 */
data class PageRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    init {
        require(left <= right) { "left ($left) must be <= right ($right)" }
        require(top <= bottom) { "top ($top) must be <= bottom ($bottom)" }
    }
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun toBoundingBox(): BoundingBox = BoundingBox(left, top, right, bottom)
}

/**
 * Point in Render Pixel Space.
 * Origin: Top-left of the rendered raster bitmap.
 * Units: Device physical pixels.
 */
data class RenderPoint(val x: Float, val y: Float)

/**
 * Rectangle in Render Pixel Space.
 */
data class RenderRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Point in Viewport / Visible Window Space.
 * Origin: Top-left of the active display viewport.
 * Units: Screen pixels.
 */
data class ViewportPoint(val x: Float, val y: Float)

/**
 * Rectangle in Viewport / Visible Window Space.
 */
data class ViewportRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}
