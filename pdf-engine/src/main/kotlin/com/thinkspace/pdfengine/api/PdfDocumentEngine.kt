package com.thinkspace.pdfengine.api

import com.thinkspace.pdfengine.model.Annotation
import com.thinkspace.pdfengine.model.ImageElement
import com.thinkspace.pdfengine.model.PageStructure
import com.thinkspace.pdfengine.model.PageType
import com.thinkspace.pdfengine.model.ProcessingResult
import com.thinkspace.pdfengine.model.SearchResult
import com.thinkspace.pdfengine.model.TextElement
import com.thinkspace.pdfengine.model.TextSelection
import com.thinkspace.pdfengine.model.TextSelectionRequest
import com.thinkspace.pdfengine.rendering.RenderedPage

/**
 * Primary public contract for the PDF Document Engine.
 * Provides thread-safe, coroutine-based document processing, spatial layout analysis,
 * high-fidelity rendering, search, and geometric text selection.
 */
interface PdfDocumentEngine {

    /**
     * Fast-opens a PDF source, parses metadata, and initializes page descriptors.
     * Does not block or parse entire page content up-front.
     */
    suspend fun open(source: PdfSource): PdfDocument

    /**
     * Incrementally processes pages in the background according to provided options.
     */
    suspend fun process(
        document: PdfDocument,
        options: ProcessingOptions = ProcessingOptions()
    ): ProcessingResult

    /**
     * Classifies page content type (DIGITAL, SCANNED, or MIXED).
     */
    suspend fun classifyPage(
        document: PdfDocument,
        pageIndex: Int
    ): PageType

    /**
     * Extracts native text elements (words, lines, characters) with exact spatial geometry.
     */
    suspend fun extractText(
        document: PdfDocument,
        pageIndex: Int
    ): List<TextElement>

    /**
     * Performs spatial layout analysis, grouping words into lines, blocks, columns, and headings.
     */
    suspend fun analyzePage(
        document: PdfDocument,
        pageIndex: Int
    ): PageStructure

    /**
     * Extracts embedded raster/vector images from the PDF stream.
     */
    suspend fun extractImages(
        document: PdfDocument,
        pageIndex: Int
    ): List<ImageElement>

    /**
     * Extracts existing PDF annotations.
     */
    suspend fun extractAnnotations(
        document: PdfDocument,
        pageIndex: Int
    ): List<Annotation>

    /**
     * Rasterizes a single page into a hardware-backed bitmap with optional viewport clipping and pooling.
     */
    suspend fun renderPage(
        document: PdfDocument,
        pageIndex: Int,
        options: RenderOptions = RenderOptions()
    ): RenderedPage

    /**
     * Searches for text occurrences across the document index with exact bounding boxes and context.
     */
    suspend fun search(
        document: PdfDocument,
        query: String
    ): List<SearchResult>

    /**
     * Computes high-precision selection geometry (bounds and quads) across single or multiple pages.
     */
    suspend fun getSelectionGeometry(
        document: PdfDocument,
        selection: TextSelectionRequest
    ): TextSelection

    /**
     * Closes the document and disposes associated native and cached memory handles.
     */
    fun close(document: PdfDocument)

    /**
     * Closes the engine entirely and flushes all global caches and pools.
     */
    fun shutdown()
}
