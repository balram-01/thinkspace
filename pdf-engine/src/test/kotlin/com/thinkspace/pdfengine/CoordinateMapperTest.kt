package com.thinkspace.pdfengine

import com.thinkspace.pdfengine.coordinates.PagePoint
import com.thinkspace.pdfengine.coordinates.PageRect
import com.thinkspace.pdfengine.coordinates.PdfCoordinateMapper
import com.thinkspace.pdfengine.coordinates.PdfPoint
import com.thinkspace.pdfengine.coordinates.PdfRect
import com.thinkspace.pdfengine.coordinates.RenderPoint
import com.thinkspace.pdfengine.coordinates.ViewportPoint
import com.thinkspace.pdfengine.model.RotationAngle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CoordinateMapperTest {

    private val letterCropBox = PdfRect(0f, 0f, 612f, 792f)

    @Test
    fun testRotation0BidirectionalTransform() {
        val mapper = PdfCoordinateMapper(letterCropBox, RotationAngle.ROTATE_0)

        // PDF bottom-left (0,0) maps to Page bottom-left (0, 792)
        val pPdfBottomLeft = PdfPoint(0f, 0f)
        val pPage1 = mapper.pdfToPage(pPdfBottomLeft)
        assertEquals(0f, pPage1.x, 0.001f)
        assertEquals(792f, pPage1.y, 0.001f)

        // PDF top-left (0, 792) maps to Page top-left (0, 0)
        val pPdfTopLeft = PdfPoint(0f, 792f)
        val pPage2 = mapper.pdfToPage(pPdfTopLeft)
        assertEquals(0f, pPage2.x, 0.001f)
        assertEquals(0f, pPage2.y, 0.001f)

        // Round-trip check
        val originalPdf = PdfPoint(123.45f, 456.78f)
        val roundTripPdf = mapper.pageToPdf(mapper.pdfToPage(originalPdf))
        assertEquals(originalPdf.x, roundTripPdf.x, 0.001f)
        assertEquals(originalPdf.y, roundTripPdf.y, 0.001f)
    }

    @Test
    fun testRotation90BidirectionalTransform() {
        val mapper = PdfCoordinateMapper(letterCropBox, RotationAngle.ROTATE_90)
        assertEquals(792f, mapper.pageSize.width, 0.001f)
        assertEquals(612f, mapper.pageSize.height, 0.001f)

        val originalPdf = PdfPoint(100f, 200f)
        val pagePoint = mapper.pdfToPage(originalPdf)
        val backToPdf = mapper.pageToPdf(pagePoint)

        assertEquals(originalPdf.x, backToPdf.x, 0.001f)
        assertEquals(originalPdf.y, backToPdf.y, 0.001f)
    }

    @Test
    fun testRotation180BidirectionalTransform() {
        val mapper = PdfCoordinateMapper(letterCropBox, RotationAngle.ROTATE_180)
        assertEquals(612f, mapper.pageSize.width, 0.001f)
        assertEquals(792f, mapper.pageSize.height, 0.001f)

        val originalPdf = PdfPoint(150f, 350f)
        val pagePoint = mapper.pdfToPage(originalPdf)
        val backToPdf = mapper.pageToPdf(pagePoint)

        assertEquals(originalPdf.x, backToPdf.x, 0.001f)
        assertEquals(originalPdf.y, backToPdf.y, 0.001f)
    }

    @Test
    fun testRotation270BidirectionalTransform() {
        val mapper = PdfCoordinateMapper(letterCropBox, RotationAngle.ROTATE_270)
        assertEquals(792f, mapper.pageSize.width, 0.001f)
        assertEquals(612f, mapper.pageSize.height, 0.001f)

        val originalPdf = PdfPoint(75f, 500f)
        val pagePoint = mapper.pdfToPage(originalPdf)
        val backToPdf = mapper.pageToPdf(pagePoint)

        assertEquals(originalPdf.x, backToPdf.x, 0.001f)
        assertEquals(originalPdf.y, backToPdf.y, 0.001f)
    }

    @Test
    fun testNonZeroCropBoxOffsets() {
        val offsetBox = PdfRect(50f, 50f, 650f, 850f)
        val mapper = PdfCoordinateMapper(offsetBox, RotationAngle.ROTATE_0)

        // Point at offset origin (50, 850) is Page (0, 0)
        val pPage = mapper.pdfToPage(PdfPoint(50f, 850f))
        assertEquals(0f, pPage.x, 0.001f)
        assertEquals(0f, pPage.y, 0.001f)

        val orig = PdfPoint(200f, 400f)
        val roundTrip = mapper.pageToPdf(mapper.pdfToPage(orig))
        assertEquals(orig.x, roundTrip.x, 0.001f)
        assertEquals(orig.y, roundTrip.y, 0.001f)
    }

    @Test
    fun testRenderAndViewportScaling() {
        val mapper = PdfCoordinateMapper(letterCropBox, RotationAngle.ROTATE_0)
        val scale = 2.0f
        val pagePoint = PagePoint(100f, 150f)

        // Page to Render
        val renderPoint = mapper.pageToRender(pagePoint, scale)
        assertEquals(200f, renderPoint.x, 0.001f)
        assertEquals(300f, renderPoint.y, 0.001f)

        // Render to Page
        val backToPage = mapper.renderToPage(renderPoint, scale)
        assertEquals(pagePoint.x, backToPage.x, 0.001f)
        assertEquals(pagePoint.y, backToPage.y, 0.001f)

        // Viewport translation
        val viewportOrigin = RenderPoint(50f, 100f)
        val vpPoint = mapper.renderToViewport(renderPoint, viewportOrigin)
        assertEquals(150f, vpPoint.x, 0.001f)
        assertEquals(200f, vpPoint.y, 0.001f)

        // Composite Viewport to Page
        val vpToPage = mapper.viewportToPage(vpPoint, viewportOrigin, scale)
        assertEquals(pagePoint.x, vpToPage.x, 0.001f)
        assertEquals(pagePoint.y, vpToPage.y, 0.001f)
    }

    @Test
    fun testRectangleConversions() {
        val mapper = PdfCoordinateMapper(letterCropBox, RotationAngle.ROTATE_0)
        val pageRect = PageRect(left = 50f, top = 100f, right = 200f, bottom = 150f)

        val pdfRect = mapper.pageToPdf(pageRect)
        assertTrue(pdfRect.left < pdfRect.right)
        assertTrue(pdfRect.bottom < pdfRect.top)

        val roundTripPageRect = mapper.pdfToPage(pdfRect)
        assertEquals(pageRect.left, roundTripPageRect.left, 0.001f)
        assertEquals(pageRect.top, roundTripPageRect.top, 0.001f)
        assertEquals(pageRect.right, roundTripPageRect.right, 0.001f)
        assertEquals(pageRect.bottom, roundTripPageRect.bottom, 0.001f)
    }
}
