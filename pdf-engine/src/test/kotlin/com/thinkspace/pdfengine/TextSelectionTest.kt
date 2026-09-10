package com.thinkspace.pdfengine

import com.thinkspace.pdfengine.api.DefaultPdfPage
import com.thinkspace.pdfengine.api.PdfDocument
import com.thinkspace.pdfengine.api.PdfSource
import com.thinkspace.pdfengine.coordinates.PdfCoordinateMapper
import com.thinkspace.pdfengine.coordinates.PdfRect
import com.thinkspace.pdfengine.core.DefaultPdfDocumentEngine
import com.thinkspace.pdfengine.extraction.PdfTextExtractor
import com.thinkspace.pdfengine.logging.NoOpPdfLogger
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.DocumentMetadata
import com.thinkspace.pdfengine.model.FontStyle
import com.thinkspace.pdfengine.model.PageMetadata
import com.thinkspace.pdfengine.model.PageSize
import com.thinkspace.pdfengine.model.Point
import com.thinkspace.pdfengine.model.RotationAngle
import com.thinkspace.pdfengine.model.TextSelectionRequest
import com.thinkspace.pdfengine.model.TextWord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TextSelectionTest {

    private class FakeDocument(override val id: String = "fake_doc") : PdfDocument {
        override val pageCount: Int = 2
        override val metadata = DocumentMetadata(pageCount = 2)
        override val isOpen: Boolean = true
        private val mapper = PdfCoordinateMapper(PdfRect(0f, 0f, 600f, 800f), RotationAngle.ROTATE_0)

        override fun getPage(pageIndex: Int) = DefaultPdfPage(
            pageIndex = pageIndex,
            documentId = id,
            metadata = PageMetadata(pageIndex, BoundingBox(0f, 0f, 600f, 800f), BoundingBox(0f, 0f, 600f, 800f), RotationAngle.ROTATE_0, PageSize(600f, 800f)),
            size = PageSize(600f, 800f),
            coordinateMapper = mapper
        )
        override fun close() {}
    }

    private class FakeTextExtractor(private val wordsByPage: Map<Int, List<TextWord>>) : PdfTextExtractor {
        override suspend fun extractWords(document: PdfDocument, pageIndex: Int): List<TextWord> {
            return wordsByPage[pageIndex] ?: emptyList()
        }
    }

    @Test
    fun testPointRangeSelection() = runBlocking {
        val words = listOf(
            TextWord("Kotlin", 0, BoundingBox(50f, 100f, 100f, 120f), 12f, "Helvetica", FontStyle.REGULAR, 120f, orderIndex = 0),
            TextWord("PDF", 0, BoundingBox(110f, 100f, 140f, 120f), 12f, "Helvetica", FontStyle.REGULAR, 120f, orderIndex = 1),
            TextWord("Engine", 0, BoundingBox(150f, 100f, 200f, 120f), 12f, "Helvetica", FontStyle.REGULAR, 120f, orderIndex = 2)
        )

        val engine = DefaultPdfDocumentEngine(
            logger = NoOpPdfLogger,
            textExtractor = FakeTextExtractor(mapOf(0 to words))
        )

        val doc = FakeDocument()

        // Point range spanning from "Kotlin" to "PDF"
        val request = TextSelectionRequest.PointRange(
            pageIndex = 0,
            startPoint = Point(55f, 110f),
            endPoint = Point(120f, 110f)
        )

        val selection = engine.getSelectionGeometry(doc, request)

        assertEquals("Kotlin PDF", selection.text)
        assertEquals(0, selection.startPage)
        assertEquals(0, selection.endPage)
        assertEquals(50f, selection.bounds.left, 0.001f)
        assertEquals(140f, selection.bounds.right, 0.001f)
        assertEquals(2, selection.quads.size)
    }

    @Test
    fun testCharacterRangeSelection() = runBlocking {
        val words = listOf(
            TextWord("First", 0, BoundingBox(10f, 10f, 50f, 25f), 12f, "Helvetica", FontStyle.REGULAR, 25f, orderIndex = 0),
            TextWord("Second", 0, BoundingBox(60f, 10f, 110f, 25f), 12f, "Helvetica", FontStyle.REGULAR, 25f, orderIndex = 1),
            TextWord("Third", 0, BoundingBox(120f, 10f, 160f, 25f), 12f, "Helvetica", FontStyle.REGULAR, 25f, orderIndex = 2)
        )

        val engine = DefaultPdfDocumentEngine(
            logger = NoOpPdfLogger,
            textExtractor = FakeTextExtractor(mapOf(0 to words))
        )

        val doc = FakeDocument()
        val selection = engine.getSelectionGeometry(
            doc,
            TextSelectionRequest.CharacterRange(0, 1, 2)
        )

        assertEquals("Second Third", selection.text)
        assertEquals(60f, selection.bounds.left, 0.001f)
        assertEquals(160f, selection.bounds.right, 0.001f)
    }
}
