package com.thinkspace.pdfengine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thinkspace.pdfengine.api.PdfSource
import com.thinkspace.pdfengine.api.ProcessingOptions
import com.thinkspace.pdfengine.core.DefaultPdfDocumentEngine
import com.thinkspace.pdfengine.errors.PdfEngineError
import com.thinkspace.pdfengine.logging.NoOpPdfLogger
import com.thinkspace.pdfengine.model.Point
import com.thinkspace.pdfengine.model.TextSelectionRequest
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentEngineIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var engine: DefaultPdfDocumentEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
        engine = DefaultPdfDocumentEngine(
            context = context,
            logger = NoOpPdfLogger,
            cacheDirProvider = { tempFolder.root }
        )
    }

    @Test
    fun testEndToEndDigitalPdfPipeline() = runBlocking {
        val pdfFile = tempFolder.newFile("test_digital.pdf")
        PdfTestFactory.createDigitalPdf(pdfFile, pageCount = 3)

        // 1. Open
        val document = engine.open(PdfSource.FromFile(pdfFile))
        assertTrue(document.isOpen)
        assertEquals(3, document.pageCount)

        // 2. Extract Text
        val words = engine.extractText(document, 0)
        assertTrue("Words should be extracted", words.isNotEmpty())
        val titleWord = words.firstOrNull { it.text.contains("Document") }
        assertNotNull("Should contain 'Document'", titleWord)
        assertTrue(titleWord!!.bounds.width > 0f)
        assertTrue(titleWord.bounds.height > 0f)

        // 3. Layout Analysis
        val structure = engine.analyzePage(document, 0)
        assertTrue("Lines should be formed", structure.lines.isNotEmpty())
        assertTrue("Blocks should be formed", structure.blocks.isNotEmpty())
        assertTrue("Paragraphs should be formed", structure.paragraphs.isNotEmpty())

        // 4. Batch Processing
        val processResult = engine.process(
            document,
            ProcessingOptions(
                performLayoutAnalysis = true,
                indexForSearch = true
            )
        )
        assertEquals(3, processResult.successfulPages)

        // 5. Search
        val searchResults = engine.search(document, "bounding boxes")
        assertEquals(3, searchResults.size) // Appears on each page
        assertEquals("bounding boxes", searchResults[0].matchedText)
        assertTrue(searchResults[0].bounds.width > 0f)

        // 6. Text Selection
        val selection = engine.getSelectionGeometry(
            document,
            TextSelectionRequest.PointRange(0, Point(50f, 650f), Point(200f, 650f))
        )
        assertTrue("Should have selected text", selection.text.isNotEmpty())
        assertTrue("Should have quads", selection.quads.isNotEmpty())

        // 7. Close and Resource Disposal
        engine.close(document)
        assertFalse(document.isOpen)
        assertTrue(document.isClosed)

        try {
            document.getPage(0)
            fail("Accessing closed document should throw DocumentClosed")
        } catch (e: PdfEngineError.DocumentClosed) {
            // Success
        }
    }

    @Test
    fun testEncryptedPdf() = runBlocking {
        val secretPdf = tempFolder.newFile("encrypted.pdf")
        val password = "secretPassword123"
        PdfTestFactory.createEncryptedPdf(secretPdf, password)

        // Attempt open with no password should fail
        try {
            engine.open(PdfSource.FromFile(secretPdf))
            fail("Opening encrypted PDF without password should fail")
        } catch (e: PdfEngineError.PasswordRequired) {
            // Expected
        } catch (e: PdfEngineError.EncryptedPdf) {
            // Expected
        }

        // Open with correct password
        val doc = engine.open(PdfSource.FromFile(secretPdf, password = password))
        assertEquals(1, doc.pageCount)
        val words = engine.extractText(doc, 0)
        assertTrue(words.any { it.text.contains("Secret") })
        engine.close(doc)
    }

    @Test
    fun testMalformedPdfHandling() = runBlocking {
        val malformedFile = tempFolder.newFile("corrupted.pdf")
        malformedFile.writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x00, 0x12, 0x34)) // Incomplete header

        try {
            engine.open(PdfSource.FromFile(malformedFile))
            fail("Opening malformed PDF should throw PdfEngineError.MalformedPdf or InvalidPdf")
        } catch (e: PdfEngineError.MalformedPdf) {
            // Expected
        } catch (e: PdfEngineError.InvalidPdf) {
            // Expected
        }
    }

    @Test
    fun testMultiColumnLayoutAnalysis() = runBlocking {
        val multiColFile = tempFolder.newFile("multicolumn.pdf")
        PdfTestFactory.createMultiColumnPdf(multiColFile)

        val doc = engine.open(PdfSource.FromFile(multiColFile))
        val structure = engine.analyzePage(doc, 0)

        assertTrue(structure.lines.size >= 4)
        engine.close(doc)
    }

    @Test
    fun testRotatedPagesExtraction() = runBlocking {
        val rotatedFile = tempFolder.newFile("rotated.pdf")
        PdfTestFactory.createRotatedPdf(rotatedFile)

        val doc = engine.open(PdfSource.FromFile(rotatedFile))
        assertEquals(4, doc.pageCount)

        for (p in 0 until 4) {
            val words = engine.extractText(doc, p)
            assertTrue("Should extract text on rotated page $p", words.isNotEmpty())
        }
        engine.close(doc)
    }
}
