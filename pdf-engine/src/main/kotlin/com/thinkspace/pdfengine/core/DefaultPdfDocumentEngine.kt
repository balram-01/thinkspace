package com.thinkspace.pdfengine.core

import android.content.Context
import com.thinkspace.pdfengine.annotations.AnnotationExtractor
import com.thinkspace.pdfengine.api.DefaultPdfPage
import com.thinkspace.pdfengine.api.PdfDocument
import com.thinkspace.pdfengine.api.PdfDocumentEngine
import com.thinkspace.pdfengine.api.PdfSource
import com.thinkspace.pdfengine.api.ProcessingOptions
import com.thinkspace.pdfengine.api.RenderOptions
import com.thinkspace.pdfengine.cache.LruMemoryCache
import com.thinkspace.pdfengine.cache.PdfCache
import com.thinkspace.pdfengine.errors.PdfEngineError
import com.thinkspace.pdfengine.extraction.PdfBoxImageExtractor
import com.thinkspace.pdfengine.extraction.PdfBoxTextExtractor
import com.thinkspace.pdfengine.extraction.PdfImageExtractor
import com.thinkspace.pdfengine.extraction.PdfTextExtractor
import com.thinkspace.pdfengine.indexing.PdfSearchEngine
import com.thinkspace.pdfengine.indexing.PositionalSearchIndex
import com.thinkspace.pdfengine.layout.PdfLayoutAnalyzer
import com.thinkspace.pdfengine.layout.SpatialLayoutAnalyzer
import com.thinkspace.pdfengine.logging.DefaultPdfLogger
import com.thinkspace.pdfengine.logging.PdfLogger
import com.thinkspace.pdfengine.model.Annotation
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.ImageElement
import com.thinkspace.pdfengine.model.PageProcessingStatus
import com.thinkspace.pdfengine.model.PageStructure
import com.thinkspace.pdfengine.model.PageTextSelection
import com.thinkspace.pdfengine.model.PageType
import com.thinkspace.pdfengine.model.Point
import com.thinkspace.pdfengine.model.ProcessingProgress
import com.thinkspace.pdfengine.model.ProcessingResult
import com.thinkspace.pdfengine.model.ProcessingStage
import com.thinkspace.pdfengine.model.Quad
import com.thinkspace.pdfengine.model.SearchResult
import com.thinkspace.pdfengine.model.TextElement
import com.thinkspace.pdfengine.model.TextSelection
import com.thinkspace.pdfengine.model.TextSelectionRequest
import com.thinkspace.pdfengine.model.TextWord
import com.thinkspace.pdfengine.ocr.MlKitOcrEngine
import com.thinkspace.pdfengine.ocr.NoOpOcrEngine
import com.thinkspace.pdfengine.ocr.OcrEngine
import com.thinkspace.pdfengine.ocr.ScannedPageDetector
import com.thinkspace.pdfengine.parser.DefaultPdfParser
import com.thinkspace.pdfengine.parser.PdfParser
import com.thinkspace.pdfengine.rendering.BitmapPool
import com.thinkspace.pdfengine.rendering.PdfBoxRenderer
import com.thinkspace.pdfengine.rendering.PdfRenderer
import com.thinkspace.pdfengine.rendering.RenderedPage
import com.thinkspace.pdfengine.storage.ResourceManager
import com.thinkspace.pdfengine.storage.TempFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Production-grade standalone implementation of PdfDocumentEngine.
 * Handles high-concurrency background processing, spatial layout extraction,
 * optical character recognition, bitmap pooling, and source-accurate text selection.
 */
class DefaultPdfDocumentEngine(
    private val context: Context? = null,
    private val cacheDirProvider: () -> File = { context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp") },
    private val logger: PdfLogger = DefaultPdfLogger(),
    private val ocrEngine: OcrEngine = if (context != null) MlKitOcrEngine(logger) else NoOpOcrEngine,
    private val cache: PdfCache = LruMemoryCache(64 * 1024 * 1024L),
    private val bitmapPool: BitmapPool = BitmapPool(32 * 1024 * 1024L, logger),
    private val textExtractor: PdfTextExtractor = PdfBoxTextExtractor(logger),
    private val imageExtractor: PdfImageExtractor = PdfBoxImageExtractor(logger),
    private val layoutAnalyzer: PdfLayoutAnalyzer = SpatialLayoutAnalyzer(logger = logger),
    private val searchEngine: PdfSearchEngine = PositionalSearchIndex(logger),
    private val annotationExtractor: AnnotationExtractor = AnnotationExtractor(logger),
    private val scannedDetector: ScannedPageDetector = ScannedPageDetector()
) : PdfDocumentEngine {

    private val tempFileManager = TempFileManager(cacheDirProvider)
    private val parser: PdfParser = DefaultPdfParser(context, tempFileManager, logger)
    private val renderer: PdfRenderer = PdfBoxRenderer(bitmapPool, cache, logger)

    private val openDocuments = ConcurrentHashMap<String, PdfDocument>()
    private val documentResources = ConcurrentHashMap<String, ResourceManager>()

    override suspend fun open(source: PdfSource): PdfDocument {
        logger.info("DefaultPdfDocumentEngine") { "Opening PDF document from source" }
        val resourceManager = ResourceManager(logger)
        val doc = parser.open(source, resourceManager)
        openDocuments[doc.id] = doc
        documentResources[doc.id] = resourceManager
        return doc
    }

    override suspend fun process(
        document: PdfDocument,
        options: ProcessingOptions
    ): ProcessingResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val totalPages = document.pageCount
        val startPage = options.startPageIndex.coerceIn(0, totalPages - 1)
        val endPage = if (options.pageLimit != null) {
            min(totalPages - 1, startPage + options.pageLimit - 1)
        } else {
            totalPages - 1
        }

        val statuses = mutableListOf<PageProcessingStatus>()
        var successfulCount = 0

        for (pageIdx in startPage..endPage) {
            currentCoroutineContext().ensureActive()

            val pageStart = System.currentTimeMillis()
            var pageType = PageType.DIGITAL
            var wordCount = 0
            var imageCount = 0
            var ocrApplied = false
            var pageError: String? = null

            try {
                options.onProgress?.invoke(
                    ProcessingProgress(
                        currentPage = pageIdx + 1,
                        totalPages = totalPages,
                        currentStage = ProcessingStage.CLASSIFYING,
                        percentage = (pageIdx - startPage).toFloat() / max(1, (endPage - startPage + 1))
                    )
                )

                // 1. Text Extraction
                var words = extractTextInternal(document, pageIdx)
                wordCount = words.size

                // 2. Image Extraction
                val images = if (options.extractImages) {
                    extractImages(document, pageIdx).also { imageCount = it.size }
                } else emptyList()

                // 3. Classify Page
                pageType = scannedDetector.classify(document.getPage(pageIdx), words, images)

                // 4. OCR if needed
                if (options.enableOcr && pageType == PageType.SCANNED && ocrEngine.isAvailable) {
                    options.onProgress?.invoke(
                        ProcessingProgress(
                            currentPage = pageIdx + 1,
                            totalPages = totalPages,
                            currentStage = ProcessingStage.OCR,
                            percentage = (pageIdx - startPage).toFloat() / max(1, (endPage - startPage + 1))
                        )
                    )
                    val rendered = renderPage(document, pageIdx, RenderOptions(scale = 2.0f))
                    try {
                        val ocrResult = ocrEngine.recognize(document.getPage(pageIdx), rendered.bitmap)
                        val ocrWords = ocrResult.toTextWords()
                        if (ocrWords.isNotEmpty()) {
                            words = ocrWords
                            wordCount = words.size
                            ocrApplied = true
                            cache.putTextWords(document.id, pageIdx, words)
                        }
                    } finally {
                        rendered.close()
                    }
                }

                // 5. Layout Analysis
                if (options.performLayoutAnalysis) {
                    options.onProgress?.invoke(
                        ProcessingProgress(
                            currentPage = pageIdx + 1,
                            totalPages = totalPages,
                            currentStage = ProcessingStage.LAYOUT_ANALYSIS,
                            percentage = (pageIdx - startPage).toFloat() / max(1, (endPage - startPage + 1))
                        )
                    )
                    val structure = layoutAnalyzer.analyze(document.getPage(pageIdx), words, images, pageType == PageType.SCANNED)
                    cache.putPageStructure(document.id, pageIdx, structure)
                }

                // 6. Indexing
                if (options.indexForSearch && words.isNotEmpty()) {
                    searchEngine.indexPage(document.id, pageIdx, words, isFromOcr = ocrApplied)
                }

                successfulCount++
            } catch (t: Throwable) {
                currentCoroutineContext().ensureActive()
                logger.error("DefaultPdfDocumentEngine", { "Error processing page $pageIdx" }, t)
                pageError = t.message ?: "Unknown error"
            }

            statuses.add(
                PageProcessingStatus(
                    pageIndex = pageIdx,
                    pageType = pageType,
                    wordCount = wordCount,
                    imageCount = imageCount,
                    ocrApplied = ocrApplied,
                    processingTimeMs = System.currentTimeMillis() - pageStart,
                    error = pageError
                )
            )
        }

        options.onProgress?.invoke(
            ProcessingProgress(
                currentPage = endPage + 1,
                totalPages = totalPages,
                currentStage = ProcessingStage.COMPLETED,
                percentage = 1.0f
            )
        )

        ProcessingResult(
            documentId = document.id,
            totalPages = totalPages,
            successfulPages = successfulCount,
            pageStatuses = statuses,
            totalDurationMs = System.currentTimeMillis() - startTime,
            isFullyIndexed = options.indexForSearch && successfulCount == (endPage - startPage + 1)
        )
    }

    override suspend fun classifyPage(
        document: PdfDocument,
        pageIndex: Int
    ): PageType {
        val words = extractTextInternal(document, pageIndex)
        val images = imageExtractor.extractImages(document, pageIndex)
        return scannedDetector.classify(document.getPage(pageIndex), words, images)
    }

    override suspend fun extractText(
        document: PdfDocument,
        pageIndex: Int
    ): List<TextElement> {
        return extractTextInternal(document, pageIndex)
    }

    private suspend fun extractTextInternal(
        document: PdfDocument,
        pageIndex: Int
    ): List<TextWord> {
        val cached = cache.getTextWords(document.id, pageIndex)
        if (cached != null) return cached

        val words = textExtractor.extractWords(document, pageIndex)
        cache.putTextWords(document.id, pageIndex, words)
        return words
    }

    override suspend fun analyzePage(
        document: PdfDocument,
        pageIndex: Int
    ): PageStructure {
        val cached = cache.getPageStructure(document.id, pageIndex)
        if (cached != null) return cached

        val words = extractTextInternal(document, pageIndex)
        val images = extractImages(document, pageIndex)
        val pageType = scannedDetector.classify(document.getPage(pageIndex), words, images)
        val structure = layoutAnalyzer.analyze(document.getPage(pageIndex), words, images, pageType == PageType.SCANNED)
        cache.putPageStructure(document.id, pageIndex, structure)
        return structure
    }

    override suspend fun extractImages(
        document: PdfDocument,
        pageIndex: Int
    ): List<ImageElement> {
        return imageExtractor.extractImages(document, pageIndex)
    }

    override suspend fun extractAnnotations(
        document: PdfDocument,
        pageIndex: Int
    ): List<Annotation> {
        return annotationExtractor.extractAnnotations(document, pageIndex)
    }

    override suspend fun renderPage(
        document: PdfDocument,
        pageIndex: Int,
        options: RenderOptions
    ): RenderedPage {
        return renderer.renderPage(document, pageIndex, options)
    }

    override suspend fun search(
        document: PdfDocument,
        query: String
    ): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Ensure all pages of the document are indexed before searching
        for (pageIdx in 0 until document.pageCount) {
            val cachedWords = cache.getTextWords(document.id, pageIdx)
            val words = cachedWords ?: runCatching {
                textExtractor.extractWords(document, pageIdx)
            }.getOrDefault(emptyList())

            if (cachedWords == null && words.isNotEmpty()) {
                cache.putTextWords(document.id, pageIdx, words)
            }
            if (words.isNotEmpty()) {
                searchEngine.indexPage(document.id, pageIdx, words, isFromOcr = false)
            }
        }
        return searchEngine.search(document.id, trimmed)
    }

    /**
     * Incremental search: extracts & indexes each page on-demand then immediately
     * calls [onMatchFound] with that page's hits — callers see the FIRST match
     * without waiting for the entire document to be processed.
     *
     * Already-cached pages are used instantly. Pages not yet extracted are
     * processed lazily one-by-one in document order.
     *
     * [onMatchFound] is called on Dispatchers.Default. Return `false` to cancel.
     * The coroutine itself respects cancellation between each page.
     *
     * @param onPageIndexed callback invoked after each page is extracted+indexed
     *        (even if no matches); useful for progress reporting.
     */
    suspend fun searchIncremental(
        document: PdfDocument,
        query: String,
        onMatchFound: suspend (pageResults: List<SearchResult>, totalSoFar: Int) -> Boolean,
        onPageIndexed: (suspend (pageIndex: Int, totalPages: Int) -> Unit)? = null
    ) = withContext(Dispatchers.Default) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext

        val totalPages = document.pageCount
        val posIndex = searchEngine as? com.thinkspace.pdfengine.indexing.PositionalSearchIndex
            ?: return@withContext // fall back gracefully if engine type changed

        for (pageIdx in 0 until totalPages) {
            currentCoroutineContext().ensureActive()

            // Check if already indexed; if not, extract & index now
            if (!posIndex.indexedPageIndices(document.id).contains(pageIdx)) {
                val words = runCatching {
                    val cached = cache.getTextWords(document.id, pageIdx)
                    if (cached != null) {
                        cached
                    } else {
                        val extracted = textExtractor.extractWords(document, pageIdx)
                        if (extracted.isNotEmpty()) {
                            cache.putTextWords(document.id, pageIdx, extracted)
                        }
                        extracted
                    }
                }.getOrDefault(emptyList())

                if (words.isNotEmpty()) {
                    searchEngine.indexPage(document.id, pageIdx, words, isFromOcr = false)
                }
            }

            onPageIndexed?.invoke(pageIdx, totalPages)

            // Now search this one page incrementally
            var continueSearch = true
            posIndex.searchIncremental(
                documentId = document.id,
                query = trimmed,
                sortedPageIndices = listOf(pageIdx)
            ) { pageResults, totalSoFar ->
                continueSearch = onMatchFound(pageResults, totalSoFar)
                continueSearch
            }
            if (!continueSearch) return@withContext
        }
    }

    override suspend fun getSelectionGeometry(
        document: PdfDocument,
        selection: TextSelectionRequest
    ): TextSelection = withContext(Dispatchers.Default) {
        when (selection) {
            is TextSelectionRequest.PointRange -> {
                val words = extractTextInternal(document, selection.pageIndex)
                if (words.isEmpty()) {
                    return@withContext emptySelection(selection.pageIndex, selection.pageIndex)
                }

                val pageSelection = selectWordsBetweenPoints(
                    words = words,
                    pageIndex = selection.pageIndex,
                    p1 = selection.startPoint,
                    p2 = selection.endPoint
                )

                TextSelection(
                    text = pageSelection.text,
                    startPage = selection.pageIndex,
                    endPage = selection.pageIndex,
                    bounds = pageSelection.bounds,
                    quads = pageSelection.quads,
                    pageSelections = listOf(pageSelection)
                )
            }
            is TextSelectionRequest.CharacterRange -> {
                val words = extractTextInternal(document, selection.pageIndex)
                if (words.isEmpty()) {
                    return@withContext emptySelection(selection.pageIndex, selection.pageIndex)
                }

                val selectedWords = words.filter {
                    it.orderIndex in selection.startCharIndex..selection.endCharIndex
                }
                buildSelectionFromWords(selection.pageIndex, selectedWords)
            }
            is TextSelectionRequest.MultiPageRange -> {
                val pageSelections = mutableListOf<PageTextSelection>()
                val startP = min(selection.startPage, selection.endPage)
                val endP = max(selection.startPage, selection.endPage)

                for (p in startP..endP) {
                    val words = extractTextInternal(document, p)
                    if (words.isEmpty()) continue

                    val pageSel = when (p) {
                        selection.startPage -> selectWordsBetweenPoints(words, p, selection.startPoint, Point(Float.MAX_VALUE, Float.MAX_VALUE))
                        selection.endPage -> selectWordsBetweenPoints(words, p, Point(0f, 0f), selection.endPoint)
                        else -> selectWordsBetweenPoints(words, p, Point(0f, 0f), Point(Float.MAX_VALUE, Float.MAX_VALUE))
                    }
                    if (pageSel.selectedWords.isNotEmpty()) {
                        pageSelections.add(pageSel)
                    }
                }

                if (pageSelections.isEmpty()) {
                    return@withContext emptySelection(selection.startPage, selection.endPage)
                }

                var unionBounds = pageSelections.first().bounds
                for (i in 1 until pageSelections.size) {
                    unionBounds = unionBounds.union(pageSelections[i].bounds)
                }

                TextSelection(
                    text = pageSelections.joinToString("\n\n") { it.text },
                    startPage = selection.startPage,
                    endPage = selection.endPage,
                    bounds = unionBounds,
                    quads = pageSelections.flatMap { it.quads },
                    pageSelections = pageSelections
                )
            }
        }
    }

    private fun selectWordsBetweenPoints(
        words: List<TextWord>,
        pageIndex: Int,
        p1: Point,
        p2: Point
    ): PageTextSelection {
        if (words.isEmpty()) {
            return PageTextSelection(pageIndex, "", BoundingBox.ZERO, emptyList(), emptyList())
        }

        // Find closest word to start and end point
        val startWord = words.minByOrNull { hypot(it.bounds.centerX - p1.x, it.bounds.centerY - p1.y) } ?: words.first()
        val endWord = words.minByOrNull { hypot(it.bounds.centerX - p2.x, it.bounds.centerY - p2.y) } ?: words.last()

        val minOrder = min(startWord.orderIndex, endWord.orderIndex)
        val maxOrder = max(startWord.orderIndex, endWord.orderIndex)

        val selectedWords = words.filter { it.orderIndex in minOrder..maxOrder }
        if (selectedWords.isEmpty()) {
            return PageTextSelection(pageIndex, "", BoundingBox.ZERO, emptyList(), emptyList())
        }

        var unionBounds = selectedWords.first().bounds
        val quads = mutableListOf<Quad>()
        for (w in selectedWords) {
            unionBounds = unionBounds.union(w.bounds)
            quads.add(w.bounds.toQuad())
        }

        return PageTextSelection(
            pageIndex = pageIndex,
            text = selectedWords.joinToString(" ") { it.text },
            bounds = unionBounds,
            quads = quads,
            selectedWords = selectedWords
        )
    }

    private fun buildSelectionFromWords(pageIndex: Int, selectedWords: List<TextWord>): TextSelection {
        if (selectedWords.isEmpty()) {
            return emptySelection(pageIndex, pageIndex)
        }

        var unionBounds = selectedWords.first().bounds
        val quads = mutableListOf<Quad>()
        for (w in selectedWords) {
            unionBounds = unionBounds.union(w.bounds)
            quads.add(w.bounds.toQuad())
        }

        val pageSel = PageTextSelection(
            pageIndex = pageIndex,
            text = selectedWords.joinToString(" ") { it.text },
            bounds = unionBounds,
            quads = quads,
            selectedWords = selectedWords
        )

        return TextSelection(
            text = pageSel.text,
            startPage = pageIndex,
            endPage = pageIndex,
            bounds = unionBounds,
            quads = quads,
            pageSelections = listOf(pageSel)
        )
    }

    private fun emptySelection(startPage: Int, endPage: Int) = TextSelection(
        text = "",
        startPage = startPage,
        endPage = endPage,
        bounds = BoundingBox.ZERO,
        quads = emptyList(),
        pageSelections = emptyList()
    )

    override fun close(document: PdfDocument) {
        logger.info("DefaultPdfDocumentEngine") { "Closing document ${document.id}" }
        openDocuments.remove(document.id)
        cache.clearDocument(document.id)
        searchEngine.clearDocument(document.id)
        val rm = documentResources.remove(document.id)
        rm?.close()
        try {
            document.close()
        } catch (t: Throwable) {
            logger.warn("DefaultPdfDocumentEngine", { "Exception while closing document ${document.id}" }, t)
        }
    }

    override fun shutdown() {
        logger.info("DefaultPdfDocumentEngine") { "Shutting down engine" }
        for (doc in openDocuments.values) {
            try {
                doc.close()
            } catch (_: Throwable) {}
        }
        openDocuments.clear()
        for (rm in documentResources.values) {
            rm.close()
        }
        documentResources.clear()
        cache.clearAll()
        bitmapPool.clear()
    }
}
