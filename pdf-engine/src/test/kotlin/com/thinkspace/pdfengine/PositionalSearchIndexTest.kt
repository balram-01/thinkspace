package com.thinkspace.pdfengine

import com.thinkspace.pdfengine.indexing.PositionalSearchIndex
import com.thinkspace.pdfengine.logging.NoOpPdfLogger
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.FontStyle
import com.thinkspace.pdfengine.model.TextWord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionalSearchIndexTest {

    private val searchEngine = PositionalSearchIndex(NoOpPdfLogger)

    @Test
    fun testExactAndCaseInsensitiveSearch() = runBlocking {
        val docId = "doc_search_1"
        val words = listOf(
            TextWord("The", 0, BoundingBox(50f, 100f, 75f, 115f), 12f, "Helvetica", FontStyle.REGULAR, 115f, orderIndex = 0),
            TextWord("Quick", 0, BoundingBox(80f, 100f, 120f, 115f), 12f, "Helvetica", FontStyle.REGULAR, 115f, orderIndex = 1),
            TextWord("Brown", 0, BoundingBox(125f, 100f, 170f, 115f), 12f, "Helvetica", FontStyle.REGULAR, 115f, orderIndex = 2),
            TextWord("Fox", 0, BoundingBox(175f, 100f, 205f, 115f), 12f, "Helvetica", FontStyle.REGULAR, 115f, orderIndex = 3)
        )

        searchEngine.indexPage(docId, 0, words)

        // Lowercase query
        val resultsLower = searchEngine.search(docId, "quick")
        assertEquals(1, resultsLower.size)
        assertEquals("Quick", resultsLower[0].matchedText)
        assertEquals(0, resultsLower[0].pageIndex)
        assertEquals(80f, resultsLower[0].bounds.left, 0.001f)

        // Multi-word phrase query
        val phraseResults = searchEngine.search(docId, "Brown Fox")
        assertEquals(1, phraseResults.size)
        assertEquals("Brown Fox", phraseResults[0].matchedText)
        assertEquals(125f, phraseResults[0].bounds.left, 0.001f)
        assertEquals(205f, phraseResults[0].bounds.right, 0.001f)

        // Non-existent query
        val emptyResults = searchEngine.search(docId, "Zebra")
        assertTrue(emptyResults.isEmpty())
    }

    @Test
    fun testClearDocumentIndex() = runBlocking {
        val docId = "doc_to_clear"
        val words = listOf(
            TextWord("Secret", 0, BoundingBox(10f, 10f, 60f, 25f), 12f, "Helvetica", FontStyle.REGULAR, 25f)
        )
        searchEngine.indexPage(docId, 0, words)

        assertEquals(1, searchEngine.search(docId, "Secret").size)
        searchEngine.clearDocument(docId)
        assertEquals(0, searchEngine.search(docId, "Secret").size)
    }
}
