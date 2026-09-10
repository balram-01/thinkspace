package com.thinkspace.pdfengine

import com.thinkspace.pdfengine.api.DefaultPdfPage
import com.thinkspace.pdfengine.coordinates.PdfCoordinateMapper
import com.thinkspace.pdfengine.coordinates.PdfRect
import com.thinkspace.pdfengine.layout.SpatialLayoutAnalyzer
import com.thinkspace.pdfengine.logging.NoOpPdfLogger
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.FontStyle
import com.thinkspace.pdfengine.model.PageMetadata
import com.thinkspace.pdfengine.model.PageSize
import com.thinkspace.pdfengine.model.RotationAngle
import com.thinkspace.pdfengine.model.TextWord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialLayoutAnalyzerTest {

    private val analyzer = SpatialLayoutAnalyzer(logger = NoOpPdfLogger)
    private val mapper = PdfCoordinateMapper(PdfRect(0f, 0f, 612f, 792f), RotationAngle.ROTATE_0)
    private val dummyPage = DefaultPdfPage(
        pageIndex = 0,
        documentId = "doc1",
        metadata = PageMetadata(
            pageIndex = 0,
            mediaBox = BoundingBox(0f, 0f, 612f, 792f),
            cropBox = BoundingBox(0f, 0f, 612f, 792f),
            rotation = RotationAngle.ROTATE_0,
            effectiveSize = PageSize.LETTER
        ),
        size = PageSize.LETTER,
        coordinateMapper = mapper
    )

    @Test
    fun testWordClusteringIntoLines() = runBlocking {
        // Create words along the same horizontal baseline Y=100
        val w1 = TextWord("Hello", 0, BoundingBox(50f, 85f, 90f, 100f), 12f, "Helvetica", FontStyle.REGULAR, 100f)
        val w2 = TextWord("World", 0, BoundingBox(95f, 85f, 140f, 100f), 12f, "Helvetica", FontStyle.REGULAR, 100f)

        // Word on a lower line baseline Y=130
        val w3 = TextWord("Second", 0, BoundingBox(50f, 115f, 105f, 130f), 12f, "Helvetica", FontStyle.REGULAR, 130f)
        val w4 = TextWord("Line", 0, BoundingBox(110f, 115f, 145f, 130f), 12f, "Helvetica", FontStyle.REGULAR, 130f)

        val structure = analyzer.analyze(dummyPage, listOf(w1, w2, w3, w4))

        assertEquals(2, structure.lines.size)
        assertEquals("Hello World", structure.lines[0].text)
        assertEquals("Second Line", structure.lines[1].text)
    }

    @Test
    fun testHeadingDetection() = runBlocking {
        // Normal text words
        val normalWords = listOf(
            TextWord("Body", 0, BoundingBox(50f, 140f, 90f, 152f), 11f, "Helvetica", FontStyle.REGULAR, 152f),
            TextWord("text", 0, BoundingBox(95f, 140f, 130f, 152f), 11f, "Helvetica", FontStyle.REGULAR, 152f)
        )

        // Large bold heading word
        val headingWord = TextWord(
            text = "Chapter 1",
            pageIndex = 0,
            bounds = BoundingBox(50f, 50f, 180f, 75f),
            fontSize = 22f, // Much larger than 11f
            fontName = "Helvetica-Bold",
            fontStyle = FontStyle.BOLD,
            baseline = 75f
        )

        val structure = analyzer.analyze(dummyPage, listOf(headingWord) + normalWords)

        assertTrue("Should detect heading block", structure.blocks.any { it.isHeading })
        val headingBlock = structure.blocks.first { it.isHeading }
        assertEquals("Chapter 1", headingBlock.text)
    }
}
