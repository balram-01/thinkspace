package com.thinkspace.pdfengine

import com.thinkspace.pdfengine.api.DefaultPdfPage
import com.thinkspace.pdfengine.coordinates.PdfCoordinateMapper
import com.thinkspace.pdfengine.coordinates.PdfRect
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.FontStyle
import com.thinkspace.pdfengine.model.ImageElement
import com.thinkspace.pdfengine.model.PageMetadata
import com.thinkspace.pdfengine.model.PageSize
import com.thinkspace.pdfengine.model.PageType
import com.thinkspace.pdfengine.model.RotationAngle
import com.thinkspace.pdfengine.model.TextWord
import com.thinkspace.pdfengine.ocr.ScannedPageDetector
import org.junit.Assert.assertEquals
import org.junit.Test

class ScannedPageDetectorTest {

    private val detector = ScannedPageDetector()
    private val mapper = PdfCoordinateMapper(PdfRect(0f, 0f, 600f, 800f), RotationAngle.ROTATE_0)
    private val page = DefaultPdfPage(
        pageIndex = 0,
        documentId = "doc",
        metadata = PageMetadata(
            pageIndex = 0,
            mediaBox = BoundingBox(0f, 0f, 600f, 800f),
            cropBox = BoundingBox(0f, 0f, 600f, 800f),
            rotation = RotationAngle.ROTATE_0,
            effectiveSize = PageSize(600f, 800f)
        ),
        size = PageSize(600f, 800f),
        coordinateMapper = mapper
    )

    @Test
    fun testDigitalPageDetection() {
        val words = (1..15).map {
            TextWord("Word$it", 0, BoundingBox(50f, it * 20f, 100f, it * 20f + 15f), 12f, "Helvetica", FontStyle.REGULAR, 20f)
        }
        val type = detector.classify(page, words, emptyList())
        assertEquals(PageType.DIGITAL, type)
    }

    @Test
    fun testScannedPageDetection() {
        // High coverage image, zero text
        val fullPageImage = ImageElement(
            id = "img1",
            pageIndex = 0,
            bounds = BoundingBox(0f, 0f, 600f, 800f),
            pixelWidth = 1200,
            pixelHeight = 1600
        )
        val type = detector.classify(page, emptyList(), listOf(fullPageImage))
        assertEquals(PageType.SCANNED, type)
    }

    @Test
    fun testMixedPageDetection() {
        // Embedded text plus large figure
        val words = (1..15).map {
            TextWord("Word$it", 0, BoundingBox(50f, it * 20f, 100f, it * 20f + 15f), 12f, "Helvetica", FontStyle.REGULAR, 20f)
        }
        val halfPageImage = ImageElement(
            id = "img2",
            pageIndex = 0,
            bounds = BoundingBox(50f, 350f, 550f, 750f),
            pixelWidth = 800,
            pixelHeight = 600
        )
        val type = detector.classify(page, words, listOf(halfPageImage))
        assertEquals(PageType.MIXED, type)
    }
}
