package com.thinkspace.pdfengine.model

import kotlin.math.max
import kotlin.math.min

/**
 * Fundamental 2D point representation in page coordinates (points, 1/72 inch).
 */
data class Point(
    val x: Float,
    val y: Float
)

/**
 * Axis-aligned bounding box defined in top-left origin coordinates.
 * Left <= Right and Top <= Bottom.
 */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    init {
        require(left <= right) { "BoundingBox left ($left) cannot be greater than right ($right)" }
        require(top <= bottom) { "BoundingBox top ($top) cannot be greater than bottom ($bottom)" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(point: Point): Boolean {
        return point.x in left..right && point.y in top..bottom
    }

    fun contains(x: Float, y: Float): Boolean {
        return x in left..right && y in top..bottom
    }

    fun intersects(other: BoundingBox): Boolean {
        return !(left > other.right || right < other.left || top > other.bottom || bottom < other.top)
    }

    fun union(other: BoundingBox): BoundingBox {
        return BoundingBox(
            left = min(left, other.left),
            top = min(top, other.top),
            right = max(right, other.right),
            bottom = max(bottom, other.bottom)
        )
    }

    fun intersection(other: BoundingBox): BoundingBox? {
        if (!intersects(other)) return null
        return BoundingBox(
            left = max(left, other.left),
            top = max(top, other.top),
            right = min(right, other.right),
            bottom = min(bottom, other.bottom)
        )
    }

    fun toQuad(): Quad {
        return Quad(
            topLeft = Point(left, top),
            topRight = Point(right, top),
            bottomRight = Point(right, bottom),
            bottomLeft = Point(left, bottom)
        )
    }

    companion object {
        val ZERO = BoundingBox(0f, 0f, 0f, 0f)

        fun fromPoints(p1: Point, p2: Point): BoundingBox {
            return BoundingBox(
                left = min(p1.x, p2.x),
                top = min(p1.y, p2.y),
                right = max(p1.x, p2.x),
                bottom = max(p1.y, p2.y)
            )
        }
    }
}

/**
 * Arbitrary quadrilateral (for rotated or skewed text selections).
 * Vertices are defined clockwise: topLeft, topRight, bottomRight, bottomLeft.
 */
data class Quad(
    val topLeft: Point,
    val topRight: Point,
    val bottomRight: Point,
    val bottomLeft: Point
) {
    fun toBoundingBox(): BoundingBox {
        val minX = min(min(topLeft.x, topRight.x), min(bottomRight.x, bottomLeft.x))
        val maxX = max(max(topLeft.x, topRight.x), max(bottomRight.x, bottomLeft.x))
        val minY = min(min(topLeft.y, topRight.y), min(bottomRight.y, bottomLeft.y))
        val maxY = max(max(topLeft.y, topRight.y), max(bottomRight.y, bottomLeft.y))
        return BoundingBox(left = minX, top = minY, right = maxX, bottom = maxY)
    }
}
