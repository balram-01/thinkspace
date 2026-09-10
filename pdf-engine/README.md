# Kotlin PDF Document Engine (`pdf-engine`)

A standalone, production-grade Android/Kotlin document processing engine designed for high-performance workspace applications. It transforms raw PDF documents into structured, searchable, positional models with exact word and character coordinates, multi-column layout analysis, optical character recognition (OCR), high-fidelity raster rendering, and arbitrary text-selection geometry.

## Key Features

- **Pure Kotlin/Android Library**: Zero React Native, JavaScript, or C++ NDK bindings required; fully compatible with modern Android apps (minSdk 24, compileSdk 36, Java 17).
- **Spatial Text Extraction**: Extracts full text, words, and characters with exact bounding boxes (`BoundingBox`), font size, font family, style (bold/italic), baselines, and reading order.
- **Rigorous Coordinate Mapping**: Full bidirectional coordinate transformations (`PdfCoordinateMapper`) handling PDF User Space (bottom-left origin), Page Space (top-left origin), Render Space (pixels), and Viewport Space across $0^\circ, 90^\circ, 180^\circ,$ and $270^\circ$ rotations and arbitrary CropBoxes.
- **Geometric Layout Analysis**: Clusters words into lines, lines into blocks, and blocks into paragraphs with automatic multi-column paper detection and heading heuristics.
- **Intelligent Page Classification**: Classifies pages as `DIGITAL`, `SCANNED`, or `MIXED` to only invoke OCR when needed.
- **Pluggable OCR**: Integrated on-device Google ML Kit OCR (`MlKitOcrEngine`) with normalized page coordinates.
- **High-Performance Rendering**: Viewport sub-rectangle rasterization, arbitrary zoom scaling, zero-churn bitmap pooling (`BitmapPool`), and LRU memory caching (`LruMemoryCache`).
- **Positional Search**: Fast in-memory inverted search index returning exact bounding boxes, match offsets, and contextual snippets.
- **Source-Accurate Selection**: Computes exact bounding box unions and quadrilaterals across words, lines, blocks, and multiple pages.
- **Deterministic Resource Management**: Managed lifecycle for file descriptors, streams, temporary files, native PDFBox handles, and bitmaps.
- **100% Permissive Licensing**: Apache License 2.0 dependencies only. Commercial redistribution safe.

---

## Quick Start

### 1. Adding Dependency
Include `pdf-engine` as a library module in your Android project:

```groovy
// settings.gradle
include ':pdf-engine'
project(':pdf-engine').projectDir = new File(rootDir, 'pdf-engine')

// app/build.gradle
dependencies {
    implementation project(':pdf-engine')
}
```

### 2. Initialization and Usage

```kotlin
import com.thinkspace.pdfengine.core.DefaultPdfDocumentEngine
import com.thinkspace.pdfengine.api.PdfSource
import com.thinkspace.pdfengine.api.ProcessingOptions
import com.thinkspace.pdfengine.api.RenderOptions
import com.thinkspace.pdfengine.model.Point
import com.thinkspace.pdfengine.model.TextSelectionRequest

// 1. Create engine instance (typically a singleton in your DI container)
val engine = DefaultPdfDocumentEngine(context = applicationContext)

// 2. Open a PDF document (File, Path, Uri, Stream, or ByteArray)
val document = engine.open(PdfSource.FromFile(pdfFile))

// 3. Extract text with character and word-level coordinates
val words = engine.extractText(document, pageIndex = 0)
words.forEach { word ->
    println("${word.text} at (${word.bounds.left}, ${word.bounds.top})")
}

// 4. Analyze layout (words, lines, blocks, columns, headings)
val structure = engine.analyzePage(document, pageIndex = 0)
println("Detected ${structure.lines.size} lines across ${structure.columnCount} columns")

// 5. Search for text across pages
val searchResults = engine.search(document, "architecture")
searchResults.forEach { match ->
    println("Found '${match.matchedText}' on page ${match.pageIndex} at ${match.bounds}")
}

// 6. Render high-resolution page bitmap with pooling
val renderedPage = engine.renderPage(document, pageIndex = 0, RenderOptions(scale = 2.0f))
imageView.setImageBitmap(renderedPage.bitmap)
// When finished, release bitmap back to pool
renderedPage.close()

// 7. Get source-accurate text selection geometry
val selection = engine.getSelectionGeometry(
    document,
    TextSelectionRequest.PointRange(
        pageIndex = 0,
        startPoint = Point(50f, 100f),
        endPoint = Point(300f, 120f)
    )
)
println("Selected text: '${selection.text}', bounds: ${selection.bounds}")

// 8. Deterministic cleanup
engine.close(document)
```

---

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md): Detailed pipeline, threading model, and memory design.
- [API.md](API.md): Comprehensive public API specification.
- [PERFORMANCE.md](PERFORMANCE.md): Memory management, bitmap pooling, and benchmarks.
- [LICENSING.md](LICENSING.md): Licensing compliance and commercial redistribution analysis.
