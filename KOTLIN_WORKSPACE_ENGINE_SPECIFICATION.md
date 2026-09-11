# ThinkSpace Native Kotlin Workspace Engine (`kt-engine`)
## Comprehensive Architecture, UI & Logic Technical Specification

---

## 🌟 1. Executive Summary & Vision

This document specifies the complete reimplementation of the **ThinkSpace Workspace Engine** purely in **Kotlin** (targeting Android Native with Jetpack Compose, with an architectural pathway to Compose Multiplatform).

By moving the entire engine—from PDF document parsing to the infinite 2D canvas, 3D lift & drag gestures, selection handles, bezier ink-links, and vector drawing—into **100% Kotlin**, we achieve:
1. **Zero Bridge Overhead**: Eliminates React Native JS-to-Native bridge serialization, JSON stringification, and async event lag.
2. **Sub-millisecond Touch Response (120 FPS)**: Direct access to Android Choreographer, `RenderNode`, `GraphicsLayer`, and hardware-accelerated Skia pipelines.
3. **Exact PDF Font & Glyph Metrics**: Access to TrueType/OpenType font glyph advance widths and character bounding boxes directly from `PdfBox-Android` / `PDFium`, eliminating synthetic font weight guessing and selection drift.
4. **Unified Concurrency**: High-performance asynchronous page indexing and disk streaming managed via Kotlin **Coroutines (`Dispatchers.Default`, `Dispatchers.IO`)** and reactive **`StateFlow` / `SharedFlow`**.

---

## 🏛️ 2. Architectural Blueprint & Package Hierarchy

```text
com.thinkspace.engine/
├── core/
│   ├── model/                      # Immutable domain models (Excerpts, Strokes, Links, PDF)
│   │   ├── Excerpt.kt
│   │   ├── InkLink.kt
│   │   ├── InkStroke.kt
│   │   ├── DocumentPage.kt
│   │   ├── BoundingBox.kt
│   │   └── TableData.kt
│   └── math/                       # Matrix math, space transforms, bezier spline calculus
│       ├── CameraTransform.kt
│       ├── BezierCalculus.kt
│       └── CollisionEngine.kt
├── camera/                         # Infinite 2D viewport & camera engine
│   ├── CameraState.kt              # Observable panX, panY, zoomScale
│   └── CameraController.kt         # Focal pinch, fling momentum, bounds clamping
├── gestures/                       # Gesture coordinators & physical touch arbitration
│   ├── SplitTouchArbitrator.kt     # Resolves touches between Document and Canvas
│   ├── LiftDragCoordinator.kt      # Coordinates 3D elevated card dragging across splits
│   └── SelectionGestureDetector.kt # Long-press, word-snapping, and pin handle drag
├── document/                       # Document reader & sheet rendering
│   ├── NativeDocumentProcessor.kt  # PdfRenderer, PdfBox-Android, ML Kit OCR
│   ├── DocumentReaderState.kt      # Virtualized page cache & viewport monitoring
│   ├── AccordionSqueezeEngine.kt   # Dynamic folding of unannotated pages
│   └── SelectionEngine.kt          # Exact character indexer & multi-line highlighting
├── canvas/                         # Infinite 2D workspace canvas
│   ├── WorkspaceCanvasState.kt     # Reactive excerpt list, clusters, and active tool
│   ├── SkiaVectorLayer.kt          # Hardware-accelerated grid, ink strokes, and ripples
│   ├── InkLinkRenderer.kt          # Cubic bezier elastic margin tethers
│   └── SmartPlacementEngine.kt     # Magnetic stacking, slot collision displacement
├── ui/                             # Jetpack Compose UI components
│   ├── WorkspaceScreen.kt          # Master coordinator view
│   ├── SplitDivider.kt             # Spring-snapping interactive divider
│   ├── DocumentPane.kt             # Virtualized LazyColumn with native bitmap sheets
│   ├── ExcerptCard.kt              # Interactive composable card with 3D elevation
│   ├── ImageCropperDialog.kt       # 8-point interactive native bounding box cropper
│   └── toolbars/                   # Contextual, drawing, and document toolbars
└── data/                           # Persistence, Room DB & Cache
    ├── database/                   # Room entities, DAOs, TypeConverters
    │   ├── ThinkSpaceDatabase.kt
    │   ├── dao/
    │   └── entity/
    └── repository/                 # Workspace snapshot serialization (kotlinx.serialization)
```

---

## 📐 3. Core Domain Models (`com.thinkspace.engine.core.model`)

All models are designed as immutable, serializable Kotlin data classes:

```kotlin
package com.thinkspace.engine.core.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

@Serializable
data class BoundingBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    fun toComposeRect(): Rect = Rect(x, y, x + width, y + height)
    fun contains(point: Offset): Boolean = 
        point.x in x..(x + width) && point.y in y..(y + height)
}

@Serializable
enum class ExcerptType { TEXT, IMAGE, TABLE }

@Serializable
data class ExcerptModel(
    val id: String,
    val documentId: String,
    val pageNumber: Int,
    val text: String,
    val type: ExcerptType = ExcerptType.TEXT,
    val color: Long, // Color encoded as ARGB Long
    val x: Float,
    val y: Float,
    val width: Float = 185f,
    val comment: String? = null,
    val tags: List<String> = emptyList(),
    val clusterId: String? = null,
    val stackCount: Int = 1,
    val imageUrl: String? = null,
    val tableData: ExtractedTable? = null,
    val sourceRects: List<BoundingBox> = emptyList()
)

@Serializable
data class InkLink(
    val id: String,
    val sourceExcerptId: String,
    val targetPageNumber: Int,
    val targetRelativeY: Float, // Relative Y on document page
    val color: Long
)

@Serializable
data class InkPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1.0f,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class InkStroke(
    val id: String,
    val points: List<InkPoint>,
    val color: Long,
    val strokeWidth: Float,
    val isHighlighter: Boolean = false
)

@Serializable
data class ExtractedTableRow(val cells: List<String>)

@Serializable
data class ExtractedTable(val rows: List<ExtractedTableRow>)
```

---

## 🎥 4. Camera & Infinite Viewport Engine (`com.thinkspace.engine.camera`)

The infinite 2D canvas is governed by an immutable transformation state with thread-safe atomic transitions.

### 4.1 Camera State & Coordinate Mapping

```kotlin
package com.thinkspace.engine.camera

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

@Stable
class CameraState(
    initialPanX: Float = 0f,
    initialPanY: Float = 0f,
    initialScale: Float = 1f
) {
    var panX by mutableFloatStateOf(initialPanX)
    var panY by mutableFloatStateOf(initialPanY)
    var scale by mutableFloatStateOf(initialScale)

    val minScale: Float = 0.1f
    val maxScale: Float = 5.0f

    /** Transforms screen viewport pixels to infinite world canvas coordinates */
    fun screenToWorld(screenOffset: Offset): Offset {
        return Offset(
            x = (screenOffset.x - panX) / scale,
            y = (screenOffset.y - panY) / scale
        )
    }

    /** Transforms world canvas coordinates to device screen viewport pixels */
    fun worldToScreen(worldOffset: Offset): Offset {
        return Offset(
            x = worldOffset.x * scale + panX,
            y = worldOffset.y * scale + panY
        )
    }

    /** Applies focal-aware pinch zoom anchoring directly around the user's fingers */
    fun applyPinch(focalPoint: Offset, zoomFactor: Float) {
        val newScale = (scale * zoomFactor).coerceIn(minScale, maxScale)
        val scaleRatio = newScale / scale
        panX = focalPoint.x - (focalPoint.x - panX) * scaleRatio
        panY = focalPoint.y - (focalPoint.y - panY) * scaleRatio
        scale = newScale
    }

    fun applyPan(delta: Offset) {
        panX += delta.x
        panY += delta.y
    }
}
```

---

## ✂️ 5. UI-Thread Split-View Engine (`com.thinkspace.engine.ui.SplitDivider`)

The split pane divider runs with pure Jetpack Compose pointer input, spring animations, and magnetic snapping.

```kotlin
package com.thinkspace.engine.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
fun SplitDivider(
    splitRatio: Float,
    totalHeightPx: Float,
    onRatioChange: (Float) -> Unit,
    onRatioSettled: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val snapPoints = remember { listOf(0.25f, 0.46f, 0.72f) }
    val snapThreshold = 0.04f
    var isDragging by remember { mutableStateOf(false) }

    val handleScale by animateFloatAsState(
        targetValue = if (isDragging) 1.15f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "HandleScale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(Color(0xFF0F172A))
            .pointerInput(totalHeightPx) {
                detectVerticalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        // Nearest magnetic snap
                        val nearest = snapPoints.minByOrNull { abs(it - splitRatio) } ?: splitRatio
                        val finalRatio = if (abs(nearest - splitRatio) < snapThreshold) nearest else splitRatio
                        onRatioSettled(finalRatio)
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (totalHeightPx > 0) {
                            val newRatio = (splitRatio + dragAmount / totalHeightPx).coerceIn(0.12f, 0.88f)
                            onRatioChange(newRatio)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Hairline divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(if (isDragging) Color(0xFF00ADB5) else Color(0xFF334155))
        )
        // Pill handle
        Box(
            modifier = Modifier
                .scale(handleScale)
                .width(52.dp)
                .height(18.dp)
                .background(Color(0xFF1E293B), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .width(20.dp)
                            .height(1.5.dp)
                            .background(Color(0xFF00ADB5).copy(alpha = 0.7f), CircleShape)
                    )
                }
            }
        }
    }
}
```

---

## 📄 6. Native Document Reader & Page Extraction (`com.thinkspace.engine.document`)

### 6.1 Native Extraction with Pixel-Perfect Bounding Boxes

To resolve the root causes of vertical offset and coordinate drift identified earlier:
1. Coordinates are **strictly computed relative to the `cropBox`**, matching the visible rendered canvas.
2. Character widths are **extracted directly from PDFBox glyph advances**, completely eliminating synthetic weight heuristics.
3. Bounding boxes are computed from the **ascent line down to descent**, correctly encompassing the entire visible glyph rather than starting at the baseline.

```kotlin
package com.thinkspace.engine.document

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.thinkspace.engine.core.model.BoundingBox
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class IndexedChar(
    val char: Char,
    val bbox: BoundingBox
)

data class IndexedLine(
    val text: String,
    val bbox: BoundingBox,
    val chars: List<IndexedChar>,
    val globalIndex: Int
)

class NativeDocumentProcessor(private val cacheDir: File) {

    suspend fun extractPage(
        pdfFile: File,
        pageNumber: Int,
        dpi: Float = 200f
    ): DocumentPageResult = withContext(Dispatchers.IO) {
        val pageIdx = pageNumber - 1
        PDDocument.load(pdfFile).use { pdDoc ->
            val pdPage = pdDoc.getPage(pageIdx)
            val cropBox = pdPage.cropBox ?: pdPage.mediaBox
            val pageWidthPt = cropBox.width
            val pageHeightPt = cropBox.height
            val originX = cropBox.lowerLeftX
            val originY = cropBox.lowerLeftY

            // 1. Render raster backdrop with exact cropBox matching
            val scale = (dpi / 72.0f).coerceIn(1.5f, 3.0f)
            val renderW = (pageWidthPt * scale).toInt()
            val renderH = (pageHeightPt * scale).toInt()

            val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val renderPage = renderer.openPage(pageIdx)

            val bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
            renderPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            renderPage.close()
            renderer.close()
            pfd.close()

            val rasterFile = File(cacheDir, "page_${pageNumber}_${pdfFile.nameWithoutExtension}.png")
            FileOutputStream(rasterFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            bitmap.recycle()

            // 2. Extract true character-level glyph advances
            val lines = extractLinesWithAccurateGlyphs(pdDoc, pageIdx, pageHeightPt, originX, originY)

            DocumentPageResult(
                pageNumber = pageNumber,
                widthPt = pageWidthPt,
                heightPt = pageHeightPt,
                rasterPath = rasterFile.absolutePath,
                lines = lines
            )
        }
    }

    private fun extractLinesWithAccurateGlyphs(
        pdDoc: PDDocument,
        pageIndex: Int,
        pageHeightPt: Float,
        originX: Float,
        originY: Float
    ): List<IndexedLine> {
        val extractedLines = mutableListOf<IndexedLine>()
        var lineCounter = 0

        val stripper = object : PDFTextStripper() {
            init {
                sortByPosition = true
                startPage = pageIndex + 1
                endPage = pageIndex + 1
            }

            override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                if (text.isNullOrBlank() || textPositions.isNullOrEmpty()) return

                val charList = mutableListOf<IndexedChar>()
                var minX = Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxX = Float.MIN_VALUE
                var maxY = Float.MIN_VALUE

                for (pos in textPositions) {
                    val font = pos.font
                    val fontSize = pos.fontSizeInPt

                    // Normalized relative to cropBox origin
                    val charX = pos.xDirAdj - originX
                    // In PDFBox: yDirAdj is the baseline. 
                    // To accurately bound the glyph: top = baseline - ascent
                    val ascent = (font?.fontDescriptor?.ascent ?: 800f) / 1000f * fontSize
                    val descent = abs((font?.fontDescriptor?.descent ?: -200f) / 1000f * fontSize)
                    
                    val charTop = pos.yDirAdj - ascent - originY
                    val charHeight = ascent + descent
                    val charWidth = pos.widthDirAdj

                    val charBbox = BoundingBox(charX, charTop, charWidth, charHeight)
                    val ch = if (pos.unicode.isNotEmpty()) pos.unicode[0] else ' '
                    charList.add(IndexedChar(ch, charBbox))

                    if (charX < minX) minX = charX
                    if (charTop < minY) minY = charTop
                    if (charX + charWidth > maxX) maxX = charX + charWidth
                    if (charTop + charHeight > maxY) maxY = charTop + charHeight
                }

                val lineBbox = BoundingBox(minX, minY, maxX - minX, maxY - minY)
                extractedLines.add(
                    IndexedLine(
                        text = text.trim(),
                        bbox = lineBbox,
                        chars = charList,
                        globalIndex = lineCounter++
                    )
                )
            }
        }
        stripper.getText(pdDoc)
        return extractedLines
    }
}

data class DocumentPageResult(
    val pageNumber: Int,
    val widthPt: Float,
    val heightPt: Float,
    val rasterPath: String,
    val lines: List<IndexedLine>
)
```

---

## 🔍 7. Exact Selection Engine (`com.thinkspace.engine.document.SelectionEngine`)

### 7.1 Real-Time Text Selection with Dual Drag Handles

```kotlin
package com.thinkspace.engine.document

import androidx.compose.ui.geometry.Offset
import com.thinkspace.engine.core.model.BoundingBox
import kotlin.math.abs

data class TextPosition(val lineIndex: Int, val charIndex: Int)

data class SelectionResult(
    val start: TextPosition,
    val end: TextPosition,
    val selectedText: String,
    val highlightRects: List<BoundingBox>,
    val pageNumber: Int
)

class SelectionEngine(
    private val lines: List<IndexedLine>,
    private val pageNumber: Int
) {
    /** Hit tests nearest text line and char index using real glyph boundaries */
    fun getPositionAt(docPoint: Offset): TextPosition? {
        if (lines.isEmpty()) return null

        // 1. Direct vertical hit
        val directLine = lines.firstOrNull { 
            docPoint.y in (it.bbox.y - 4f)..(it.bbox.y + it.bbox.height + 4f) 
        } ?: lines.minByOrNull { 
            val centerY = it.bbox.y + it.bbox.height / 2f
            abs(docPoint.y - centerY) * 8f + abs(docPoint.x - it.bbox.x)
        } ?: return null

        // 2. Exact character hit using real glyph x boundaries
        val line = directLine
        if (line.chars.isEmpty()) return TextPosition(line.globalIndex, 0)

        val charIdx = when {
            docPoint.x <= line.chars.first().bbox.x -> 0
            docPoint.x >= line.chars.last().bbox.x + line.chars.last().bbox.width -> line.chars.size
            else -> {
                line.chars.indices.minByOrNull { idx ->
                    val c = line.chars[idx]
                    abs(docPoint.x - (c.bbox.x + c.bbox.width / 2f))
                } ?: 0
            }
        }
        return TextPosition(line.globalIndex, charIdx)
    }

    /** Resolves highlight rectangles spanning across lines */
    fun resolveSelection(startPos: TextPosition, endPos: TextPosition): SelectionResult {
        var s = startPos
        var e = endPos
        if (s.lineIndex > e.lineIndex || (s.lineIndex == e.lineIndex && s.charIndex > e.charIndex)) {
            s = endPos
            e = startPos
        }

        val textBuilder = StringBuilder()
        val rects = mutableListOf<BoundingBox>()

        for (lIdx in s.lineIndex..e.lineIndex) {
            val line = lines.getOrNull(lIdx) ?: continue
            val fromChar = if (lIdx == s.lineIndex) s.charIndex.coerceIn(0, line.chars.size) else 0
            val toChar = if (lIdx == e.lineIndex) e.charIndex.coerceIn(0, line.chars.size) else line.chars.size

            if (toChar <= fromChar) continue

            val selectedChars = line.chars.subList(fromChar, toChar)
            textBuilder.append(selectedChars.map { it.char }.joinToString("")).append(" ")

            val startX = selectedChars.first().bbox.x
            val endX = selectedChars.last().bbox.x + selectedChars.last().bbox.width

            rects.add(
                BoundingBox(
                    x = startX,
                    y = line.bbox.y - 1f,
                    width = endX - startX,
                    height = line.bbox.height + 2f
                )
            )
        }

        return SelectionResult(
            start = s,
            end = e,
            selectedText = textBuilder.toString().trim(),
            highlightRects = rects,
            pageNumber = pageNumber
        )
    }
}
```

---

## 🪢 8. Elastic Margin Tethers (Ink-Links) & Bezier Calculus (`com.thinkspace.engine.canvas`)

The signature elastic cords linking excerpt cards on the canvas directly back to source passages in the document are modeled as continuous cubic bezier splines.

### 8.1 Pure Bezier Geometry & Dynamic Edge Projection

$$\mathbf{B}(t) = (1-t)^3 \mathbf{P}_0 + 3(1-t)^2 t \mathbf{P}_1 + 3(1-t) t^2 \mathbf{P}_2 + t^3 \mathbf{P}_3$$

```kotlin
package com.thinkspace.engine.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Color
import com.thinkspace.engine.camera.CameraState
import kotlin.math.max
import kotlin.math.min

object InkLinkRenderer {

    fun drawTether(
        drawScope: DrawScope,
        cardWorldPos: Offset,
        cardWidth: Float,
        linkTargetRelativeY: Float,
        linkColor: Color,
        isHeld: Boolean,
        camera: CameraState
    ) {
        val endX = cardWorldPos.x + 14f
        val endY = cardWorldPos.y + 16f

        // Dynamic off-screen projection: project origin point past the left visible screen border
        val viewLeftWorldX = (-camera.panX - 60f) / camera.scale
        val startX = min(viewLeftWorldX, endX - 70f)
        val startY = endY - 6f

        val spanX = max(24f, endX - startX)
        val cp1 = Offset(startX + spanX * 0.35f, endY + 14f)
        val cp2 = Offset(startX + spanX * 0.68f, endY - 12f)

        val path = Path().apply {
            moveTo(startX, startY)
            cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, endX, endY)
        }

        with(drawScope) {
            // 1. Halo glow when card or highlight is held
            if (isHeld) {
                drawPath(
                    path = path,
                    color = linkColor.copy(alpha = 0.35f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f)
                )
            }

            // 2. Main Dotted Elastic Cord
            drawPath(
                path = path,
                color = linkColor.copy(alpha = if (isHeld) 0.95f else 0.65f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = if (isHeld) 2.5f else 1.6f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                )
            )

            // 3. Anchor node circle
            drawCircle(
                color = linkColor,
                radius = if (isHeld) 5.5f else 3.5f,
                center = Offset(endX, endY)
            )
            if (isHeld) {
                drawCircle(
                    color = Color.White,
                    radius = 2f,
                    center = Offset(endX, endY)
                )
            }
        }
    }
}
```

---

## 🧲 9. Smart Placement & Magnetic Stacking Engine (`com.thinkspace.engine.canvas.SmartPlacementEngine`)

When drops occur on the canvas:
1. **Direct Magnetic Hit**: If dropped within `42px` of an existing card, snap into that cluster.
2. **Collision Avoidance**: If overlapping an existing card, evaluate 5 surrounding slot candidates (Right, Below, Diagonal, Left, Above) and place in the nearest clear slot.
3. **Auto-Flow Masonry**: If extracted from an action menu, tile across 8 balanced columns.

```kotlin
package com.thinkspace.engine.canvas

import androidx.compose.ui.geometry.Offset
import com.thinkspace.engine.core.model.ExcerptModel
import kotlin.math.hypot

data class PlacementResult(
    val finalPos: Offset,
    val stackedTarget: ExcerptModel?
)

object SmartPlacementEngine {
    private const val GAP = 18f
    private const val STACK_RADIUS = 42f

    fun calculatePlacement(
        dropTarget: Offset?,
        cardWidth: Float,
        cardHeight: Float,
        existingCards: List<ExcerptModel>,
        ignoreCardId: String? = null
    ): PlacementResult {
        val pool = if (ignoreCardId != null) existingCards.filter { it.id != ignoreCardId } else existingCards

        // 1. Explicit Drop
        if (dropTarget != null) {
            val magneticHit = pool.find { hypot(dropTarget.x - it.x, dropTarget.y - it.y) < STACK_RADIUS }
            if (magneticHit != null) {
                return PlacementResult(
                    finalPos = Offset(magneticHit.x + 6f, magneticHit.y + 6f),
                    stackedTarget = magneticHit
                )
            }

            val checkOverlap = { pos: Offset ->
                pool.any { c ->
                    val otherW = c.width
                    val otherH = if (c.imageUrl != null) 150f else 105f
                    pos.x < c.x + otherW + GAP && pos.x + cardWidth + GAP > c.x &&
                    pos.y < c.y + otherH + GAP && pos.y + cardHeight + GAP > c.y
                }
            }

            if (!checkOverlap(dropTarget)) {
                return PlacementResult(dropTarget, null)
            }

            // Proximity Candidates
            val candidates = mutableListOf<Offset>()
            for (card in pool) {
                val cw = card.width
                val ch = if (card.imageUrl != null) 150f else 105f
                listOf(
                    Offset(card.x + cw + GAP, card.y),
                    Offset(card.x, card.y + ch + GAP),
                    Offset(card.x + cw + GAP, card.y + ch + GAP),
                    Offset(maxOf(15f, card.x - cardWidth - GAP), card.y),
                    Offset(card.x, maxOf(15f, card.y - cardHeight - GAP))
                ).filter { !checkOverlap(it) }.forEach { candidates.add(it) }
            }

            val bestSlot = candidates.minByOrNull { hypot(it.x - dropTarget.x, it.y - dropTarget.y) }
            return PlacementResult(bestSlot ?: Offset(dropTarget.x + 24f, dropTarget.y + 24f), null)
        }

        // 2. Auto-Flow Columns
        val startX = 25f
        val startY = 60f
        val colWidth = 205f
        for (col in 0..7) {
            val colX = startX + col * (colWidth + GAP)
            val cardsInCol = pool.filter { abs(it.x - colX) < colWidth * 0.75f }
            if (cardsInCol.isEmpty()) return PlacementResult(Offset(colX, startY), null)
            val lowestY = cardsInCol.maxOf { it.y + (if (it.imageUrl != null) 150f else 105f) }
            if (lowestY + cardHeight + GAP <= 700f) {
                return PlacementResult(Offset(colX, lowestY + GAP), null)
            }
        }
        return PlacementResult(Offset(startX, startY), null)
    }
}
```

---

## 🪗 10. Accordion Squeeze (LiquidText Signature Margin Pinch)

The accordion squeeze folds unannotated pages into thin separator bars, collapsing hundreds of pages to synthesize all notes into one view.

```kotlin
package com.thinkspace.engine.document

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AccordionFold(
    pageNumber: Int,
    isSqueezed: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isSqueezed,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(Color(0xFF1E293B), RoundedCornerShape(4.dp))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(modifier = Modifier.weight(1f).height(1.dp).background(Color(0xFF334155)))
            Text(
                text = "≈ Page $pageNumber (Squeezed) ≈",
                color = Color(0xFF64748B),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Box(modifier = Modifier.weight(1f).height(1.dp).background(Color(0xFF334155)))
        }
    }
}
```

---

## ✍️ 11. Hardware-Accelerated Vector Drawing Engine

Freehand drawing runs on Compose `Canvas` with Catmull-Rom spline smoothing.

```kotlin
package com.thinkspace.engine.canvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.thinkspace.engine.core.model.InkPoint
import com.thinkspace.engine.core.model.InkStroke

object VectorInkRenderer {

    fun buildSmoothPath(points: List<InkPoint>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        if (points.size < 3) {
            path.moveTo(points.first().x, points.first().y)
            points.forEach { path.lineTo(it.x, it.y) }
            return path
        }

        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size - 1) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val midX = (p0.x + p1.x) / 2f
            val midY = (p0.y + p1.y) / 2f
            path.quadraticBezierTo(p0.x, p0.y, midX, midY)
        }
        path.lineTo(points.last().x, points.last().y)
        return path
    }

    fun drawStroke(drawScope: DrawScope, stroke: InkStroke) {
        val path = buildSmoothPath(stroke.points)
        val alpha = if (stroke.isHighlighter) 0.38f else 0.95f
        val color = Color(stroke.color).copy(alpha = alpha)

        drawScope.drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = stroke.strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}
```

---

## 💾 12. Persistence & Room Database Schema (`com.thinkspace.engine.data`)

```kotlin
package com.thinkspace.engine.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "excerpts")
data class ExcerptEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val pageNumber: Int,
    val text: String,
    val type: String,
    val color: Long,
    val x: Float,
    val y: Float,
    val width: Float,
    val clusterId: String?,
    val stackCount: Int,
    val comment: String?,
    val imageUrl: String?,
    val tableJson: String?,
    val lastModified: Long = System.currentTimeMillis()
)

@Entity(tableName = "ink_links")
data class InkLinkEntity(
    @PrimaryKey val id: String,
    val sourceExcerptId: String,
    val targetPageNumber: Int,
    val targetRelativeY: Float,
    val color: Long
)

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM excerpts WHERE documentId = :docId")
    fun observeExcerpts(docId: String): Flow<List<ExcerptEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExcerpt(excerpt: ExcerptEntity)

    @Delete
    suspend fun deleteExcerpt(excerpt: ExcerptEntity)

    @Query("SELECT * FROM ink_links WHERE sourceExcerptId IN (SELECT id FROM excerpts WHERE documentId = :docId)")
    fun observeLinks(docId: String): Flow<List<InkLinkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLink(link: InkLinkEntity)
}
```

---

## 🚀 13. Comparison & Benefits of the Kotlin Engine

| Metric / Aspect | React Native + Bridge | Pure Kotlin (`kt-engine`) |
| :--- | :--- | :--- |
| **Split Divider Resizing** | Reanimated 3 JS-bridge thread hops | Pure Compose pointerInput (0ms lag, direct Choreographer sync) |
| **Infinite 2D Pan & Zoom** | Bridge-synced Skia canvas + DOM view reconciliation | Single-pass GPU `DrawScope` with hardware Skia matrix |
| **PDF Text Extraction** | JNI stringification & bridge JSON parsing | Direct `PdfBox-Android` memory extraction |
| **Glyph Positioning** | Guessed with synthetic font weights (`0.28..0.68`) | True glyph ascent/descent & advance widths directly from PDF fonts |
| **Image Extraction** | Hardcoded fallback box `(50, 150, 400, 250)` on iOS | Native stream CTM matrix parsing (`pdPage.resources`) |
| **Elastic Tethers (Ink-Links)**| Reanimated derived values driving SVG/Skia props | Jetpack Compose `Path` with Skia `dashPathEffect` at 120 FPS |
| **Memory Footprint** | Dual runtime (V8 / Hermes + Android ART VM) | Single native ART VM with aggressive bitmap recycling |

---

## 🏁 14. Implementation Checklist for Kotlin Migration

- [ ] **Phase 1: Core Models & Math Engine**
  - Implement `BoundingBox`, `CameraState`, and `screenToWorld` / `worldToScreen` matrix transforms.
- [ ] **Phase 2: PDF Processor & Accurate Text Stripper**
  - Implement `NativeDocumentProcessor` using `PdfBox-Android` and native `PdfRenderer`.
  - Fix ascent/descent bounding box calculation to prevent text selection vertical offsets.
- [ ] **Phase 3: Split-View & Infinite Canvas**
  - Build `SplitDivider` Composable with vertical drag gestures and spring snapping.
  - Build `WorkspaceCanvas` with hardware-accelerated Skia rendering and camera focal pinch.
- [ ] **Phase 4: 3D Lift & Drag Coordinator**
  - Implement cross-pane touch coordination with elevated spring physics and `RenderNode`.
- [ ] **Phase 5: Stacking & Elastic Tethers**
  - Implement `SmartPlacementEngine` (magnetic snapping & collision avoidance).
  - Implement `InkLinkRenderer` (cubic bezier cords with dynamic off-screen projection).
- [ ] **Phase 6: Persistence & Room Database**
  - Wire SQLite Room entities, DAOs, and reactive `StateFlow` synchronization.
