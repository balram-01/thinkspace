package com.thinkspace.pdfengine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thinkspace.pdfengine.api.PdfSource
import com.thinkspace.pdfengine.api.ProcessingOptions
import com.thinkspace.pdfengine.core.DefaultPdfDocumentEngine
import com.thinkspace.pdfengine.logging.NoOpPdfLogger
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BenchmarkTest {

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
    fun benchmarkDocumentOpenAndPageInspection() = runBlocking {
        val largePdf = tempFolder.newFile("bench_large.pdf")
        PdfTestFactory.createLargePdf(largePdf, pageCount = 20)

        // 1. Measure Open Latency
        val openTime = measureTimeMillis {
            val doc = engine.open(PdfSource.FromFile(largePdf))
            assertTrue(doc.pageCount == 20)
            engine.close(doc)
        }
        println("[BENCHMARK] Open 20-page document: ${openTime}ms")
        assertTrue("Opening 20-page document should take under 1000ms", openTime < 1000)
    }

    @Test
    fun benchmarkTextExtractionAndLayout() = runBlocking {
        val testPdf = tempFolder.newFile("bench_text.pdf")
        PdfTestFactory.createDigitalPdf(testPdf, pageCount = 5)

        val doc = engine.open(PdfSource.FromFile(testPdf))

        // 2. Measure Text Extraction across 5 pages
        val extractTime = measureTimeMillis {
            for (p in 0 until 5) {
                val words = engine.extractText(doc, p)
                assertTrue(words.isNotEmpty())
            }
        }
        println("[BENCHMARK] Text extraction 5 pages: ${extractTime}ms (avg: ${extractTime / 5f}ms/page)")

        // 3. Measure Layout Analysis across 5 pages
        val layoutTime = measureTimeMillis {
            for (p in 0 until 5) {
                val structure = engine.analyzePage(doc, p)
                assertTrue(structure.lines.isNotEmpty())
            }
        }
        println("[BENCHMARK] Layout analysis 5 pages: ${layoutTime}ms (avg: ${layoutTime / 5f}ms/page)")

        // 4. Batch process and search index
        engine.process(doc, ProcessingOptions(performLayoutAnalysis = true, indexForSearch = true))
        val searchTime = measureTimeMillis {
            val results = engine.search(doc, "bounding boxes")
            assertTrue(results.isNotEmpty())
        }
        println("[BENCHMARK] Search 5 pages for phrase: ${searchTime}ms")
        assertTrue("Search should complete in under 100ms", searchTime < 100)

        engine.close(doc)
    }
}
