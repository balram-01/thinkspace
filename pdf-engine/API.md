# Public API Reference

## Primary Interface: `PdfDocumentEngine`

```kotlin
package com.thinkspace.pdfengine.api

interface PdfDocumentEngine {
    suspend fun open(source: PdfSource): PdfDocument
    suspend fun process(document: PdfDocument, options: ProcessingOptions = ProcessingOptions()): ProcessingResult
    suspend fun classifyPage(document: PdfDocument, pageIndex: Int): PageType
    suspend fun extractText(document: PdfDocument, pageIndex: Int): List<TextElement>
    suspend fun analyzePage(document: PdfDocument, pageIndex: Int): PageStructure
    suspend fun extractImages(document: PdfDocument, pageIndex: Int): List<ImageElement>
    suspend fun extractAnnotations(document: PdfDocument, pageIndex: Int): List<Annotation>
    suspend fun renderPage(document: PdfDocument, pageIndex: Int, options: RenderOptions = RenderOptions()): RenderedPage
    suspend fun search(document: PdfDocument, query: String): List<SearchResult>
    suspend fun getSelectionGeometry(document: PdfDocument, selection: TextSelectionRequest): TextSelection
    fun close(document: PdfDocument)
    fun shutdown()
}
```

---

## Input Sources: `PdfSource`

```kotlin
sealed class PdfSource {
    abstract val password: String?

    data class FromFile(val file: File, override val password: String? = null) : PdfSource()
    data class FromPath(val path: String, override val password: String? = null) : PdfSource()
    data class FromUri(val uri: Uri, val contentResolver: ContentResolver, override val password: String? = null) : PdfSource()
    data class FromByteArray(val bytes: ByteArray, val identifier: String = "memory_doc", override val password: String? = null) : PdfSource()
    data class FromStream(val streamProvider: () -> InputStream, val identifier: String = "stream_doc", override val password: String? = null) : PdfSource()
}
```

---

## Options: `ProcessingOptions` & `RenderOptions`

```kotlin
data class ProcessingOptions(
    val extractImages: Boolean = false,
    val performLayoutAnalysis: Boolean = true,
    val enableOcr: Boolean = true,
    val ocrMinimumCharThreshold: Int = 15,
    val indexForSearch: Boolean = true,
    val startPageIndex: Int = 0,
    val pageLimit: Int? = null,
    val onProgress: ((ProcessingProgress) -> Unit)? = null
)

data class RenderOptions(
    val scale: Float = 1.5f,
    val viewport: BoundingBox? = null,
    val bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888,
    val backgroundColor: Int = 0xFFFFFFFF.toInt(),
    val renderAnnotations: Boolean = true
)
```

---

## Document Model Hierarchy

### Geometry
- `Point(x: Float, y: Float)`
- `BoundingBox(left: Float, top: Float, right: Float, bottom: Float)`
- `Quad(topLeft: Point, topRight: Point, bottomRight: Point, bottomLeft: Point)`

### Text Elements
- `TextCharacter(char: Char, pageIndex: Int, bounds: BoundingBox, fontSize: Float, fontName: String, fontStyle: FontStyle, baseline: Float, rotation: Float, orderIndex: Int)`
- `TextWord(text: String, pageIndex: Int, bounds: BoundingBox, fontSize: Float, fontName: String, fontStyle: FontStyle, baseline: Float, characters: List<TextCharacter>, rotation: Float, orderIndex: Int)`
- `TextLine(text: String, pageIndex: Int, bounds: BoundingBox, words: List<TextWord>, averageFontSize: Float, baseline: Float, rotation: Float, orderIndex: Int)`
- `TextBlock(id: String, text: String, pageIndex: Int, bounds: BoundingBox, lines: List<TextLine>, columnIndex: Int, isHeading: Boolean, orderIndex: Int)`
- `Paragraph(id: String, text: String, pageIndex: Int, bounds: BoundingBox, blocks: List<TextBlock>, orderIndex: Int)`

### Embedded Images
- `ImageElement(id: String, pageIndex: Int, bounds: BoundingBox, pixelWidth: Int, pixelHeight: Int, format: String, colorSpace: String?, bitsPerComponent: Int, bitmap: Bitmap?)`

### Search & Selection
- `SearchResult(pageIndex: Int, matchedText: String, bounds: BoundingBox, context: String, startOffset: Int, endOffset: Int, quads: List<Quad>, isFromOcr: Boolean)`
- `TextSelection(text: String, startPage: Int, endPage: Int, bounds: BoundingBox, quads: List<Quad>, pageSelections: List<PageTextSelection>)`

---

## Error Handling: `PdfEngineError`

```kotlin
sealed class PdfEngineError : Exception {
    class FileNotFound(val path: String, cause: Throwable? = null)
    class PermissionDenied(val path: String, cause: Throwable? = null)
    class InvalidPdf(val reason: String, cause: Throwable? = null)
    class EncryptedPdf(val isPasswordProtected: Boolean, cause: Throwable? = null)
    class PasswordRequired(cause: Throwable? = null)
    class UnsupportedPdf(val feature: String, cause: Throwable? = null)
    class MalformedPdf(val details: String, cause: Throwable? = null)
    class ExtractionFailure(val pageIndex: Int, val details: String, cause: Throwable? = null)
    class RenderingFailure(val pageIndex: Int, val details: String, cause: Throwable? = null)
    class LayoutAnalysisFailure(val pageIndex: Int, val details: String, cause: Throwable? = null)
    class OcrFailure(val pageIndex: Int, val details: String, cause: Throwable? = null)
    class SearchFailure(val query: String, val details: String, cause: Throwable? = null)
    class Cancelled(val operation: String)
    class OutOfMemoryRisk(val estimatedBytes: Long, val availableBytes: Long)
    class DocumentClosed(val documentId: String)
}
```
