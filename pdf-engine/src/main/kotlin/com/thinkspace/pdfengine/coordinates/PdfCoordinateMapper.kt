package com.thinkspace.pdfengine.coordinates

import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.PageSize
import com.thinkspace.pdfengine.model.RotationAngle
import kotlin.math.max
import kotlin.math.min

/**
 * Mathematically rigorous coordinate transformation engine.
 * Never mixes coordinate systems implicitly.
 *
 * Fully supports:
 * - CropBox & MediaBox non-zero origins
 * - Arbitrary page sizes
 * - Rotations: 0°, 90°, 180°, 270°
 * - Arbitrary render scaling factors
 * - Sub-rectangle viewport offsets
 */
class PdfCoordinateMapper(
    val cropBox: PdfRect,
    val rotation: RotationAngle
) {
    /**
     * Visible page size in points after rotation is applied.
     */
    val pageSize: PageSize = when (rotation) {
        RotationAngle.ROTATE_0, RotationAngle.ROTATE_180 -> PageSize(cropBox.width, cropBox.height)
        RotationAngle.ROTATE_90, RotationAngle.ROTATE_270 -> PageSize(cropBox.height, cropBox.width)
    }

    // ==========================================
    // PDF Space <---> Page Space
    // ==========================================

    fun pdfToPage(point: PdfPoint): PagePoint {
        return when (rotation) {
            RotationAngle.ROTATE_0 -> {
                PagePoint(
                    x = point.x - cropBox.left,
                    y = cropBox.top - point.y
                )
            }
            RotationAngle.ROTATE_90 -> {
                PagePoint(
                    x = point.y - cropBox.bottom,
                    y = point.x - cropBox.left
                )
            }
            RotationAngle.ROTATE_180 -> {
                PagePoint(
                    x = cropBox.right - point.x,
                    y = point.y - cropBox.bottom
                )
            }
            RotationAngle.ROTATE_270 -> {
                PagePoint(
                    x = cropBox.top - point.y,
                    y = cropBox.right - point.x
                )
            }
        }
    }

    fun pageToPdf(point: PagePoint): PdfPoint {
        return when (rotation) {
            RotationAngle.ROTATE_0 -> {
                PdfPoint(
                    x = point.x + cropBox.left,
                    y = cropBox.top - point.y
                )
            }
            RotationAngle.ROTATE_90 -> {
                PdfPoint(
                    x = point.y + cropBox.left,
                    y = point.x + cropBox.bottom
                )
            }
            RotationAngle.ROTATE_180 -> {
                PdfPoint(
                    x = cropBox.right - point.x,
                    y = point.y + cropBox.bottom
                )
            }
            RotationAngle.ROTATE_270 -> {
                PdfPoint(
                    x = cropBox.right - point.y,
                    y = cropBox.top - point.x
                )
            }
        }
    }

    fun pdfToPage(rect: PdfRect): PageRect {
        val p1 = pdfToPage(PdfPoint(rect.left, rect.bottom))
        val p2 = pdfToPage(PdfPoint(rect.right, rect.top))
        val minX = min(p1.x, p2.x)
        val maxX = max(p1.x, p2.x)
        val minY = min(p1.y, p2.y)
        val maxY = max(p1.y, p2.y)
        return PageRect(left = minX, top = minY, right = maxX, bottom = maxY)
    }

    fun pageToPdf(rect: PageRect): PdfRect {
        val p1 = pageToPdf(PagePoint(rect.left, rect.top))
        val p2 = pageToPdf(PagePoint(rect.right, rect.bottom))
        val minX = min(p1.x, p2.x)
        val maxX = max(p1.x, p2.x)
        val minY = min(p1.y, p2.y)
        val maxY = max(p1.y, p2.y)
        return PdfRect(left = minX, bottom = minY, right = maxX, top = maxY)
    }

    // ==========================================
    // Page Space <---> Render Space
    // ==========================================

    fun pageToRender(point: PagePoint, scale: Float): RenderPoint {
        require(scale > 0f) { "Scale must be positive, got: $scale" }
        return RenderPoint(
            x = point.x * scale,
            y = point.y * scale
        )
    }

    fun renderToPage(point: RenderPoint, scale: Float): PagePoint {
        require(scale > 0f) { "Scale must be positive, got: $scale" }
        return PagePoint(
            x = point.x / scale,
            y = point.y / scale
        )
    }

    fun pageToRender(rect: PageRect, scale: Float): RenderRect {
        return RenderRect(
            left = rect.left * scale,
            top = rect.top * scale,
            right = rect.right * scale,
            bottom = rect.bottom * scale
        )
    }

    fun renderToPage(rect: RenderRect, scale: Float): PageRect {
        return PageRect(
            left = rect.left / scale,
            top = rect.top / scale,
            right = rect.right / scale,
            bottom = rect.bottom / scale
        )
    }

    // ==========================================
    // Render Space <---> Viewport Space
    // ==========================================

    fun renderToViewport(point: RenderPoint, viewportOrigin: RenderPoint): ViewportPoint {
        return ViewportPoint(
            x = point.x - viewportOrigin.x,
            y = point.y - viewportOrigin.y
        )
    }

    fun viewportToRender(point: ViewportPoint, viewportOrigin: RenderPoint): RenderPoint {
        return RenderPoint(
            x = point.x + viewportOrigin.x,
            y = point.y + viewportOrigin.y
        )
    }

    fun renderToViewport(rect: RenderRect, viewportOrigin: RenderPoint): ViewportRect {
        return ViewportRect(
            left = rect.left - viewportOrigin.x,
            top = rect.top - viewportOrigin.y,
            right = rect.right - viewportOrigin.x,
            bottom = rect.bottom - viewportOrigin.y
        )
    }

    fun viewportToRender(rect: ViewportRect, viewportOrigin: RenderPoint): RenderRect {
        return RenderRect(
            left = rect.left + viewportOrigin.x,
            top = rect.top + viewportOrigin.y,
            right = rect.right + viewportOrigin.x,
            bottom = rect.bottom + viewportOrigin.y
        )
    }

    // ==========================================
    // Composite: Viewport <---> Page Space
    // ==========================================

    fun viewportToPage(point: ViewportPoint, viewportOrigin: RenderPoint, scale: Float): PagePoint {
        val renderPoint = viewportToRender(point, viewportOrigin)
        return renderToPage(renderPoint, scale)
    }

    fun pageToViewport(point: PagePoint, viewportOrigin: RenderPoint, scale: Float): ViewportPoint {
        val renderPoint = pageToRender(point, scale)
        return renderToViewport(renderPoint, viewportOrigin)
    }

    companion object {
        fun fromBoundingBox(bounds: BoundingBox, rotation: RotationAngle = RotationAngle.ROTATE_0): PdfCoordinateMapper {
            val pdfRect = PdfRect(
                left = bounds.left,
                bottom = bounds.top,
                right = bounds.right,
                top = bounds.bottom
            )
            return PdfCoordinateMapper(pdfRect, rotation)
        }
    }
}
