package com.thinkspace.pdfengine.layout

import com.thinkspace.pdfengine.api.PdfPage
import com.thinkspace.pdfengine.logging.PdfLogger
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.ImageElement
import com.thinkspace.pdfengine.model.PageStructure
import com.thinkspace.pdfengine.model.Paragraph
import com.thinkspace.pdfengine.model.TextBlock
import com.thinkspace.pdfengine.model.TextLine
import com.thinkspace.pdfengine.model.TextWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Geometric heuristic layout analyzer for PDF documents.
 * Reconstructs visual reading order, lines, blocks, paragraphs, and columns
 * without assuming artificial semantic markup.
 */
class SpatialLayoutAnalyzer(
    private val sorter: ReadingOrderSorter = ReadingOrderSorter(),
    private val logger: PdfLogger
) : PdfLayoutAnalyzer {

    override suspend fun analyze(
        page: PdfPage,
        words: List<TextWord>,
        images: List<ImageElement>,
        isScanned: Boolean
    ): PageStructure = withContext(Dispatchers.Default) {
        val pageBounds = BoundingBox(0f, 0f, page.size.width, page.size.height)

        if (words.isEmpty()) {
            return@withContext PageStructure(
                pageIndex = page.pageIndex,
                bounds = pageBounds,
                words = emptyList(),
                lines = emptyList(),
                blocks = emptyList(),
                paragraphs = emptyList(),
                images = images,
                columnCount = 1,
                layoutConfidence = 1.0f,
                isScanned = isScanned
            )
        }

        // 1. Sort words in reading order
        val sortedWords = sorter.sortWords(words, page.size)

        // 2. Cluster words into lines
        val lines = buildLines(sortedWords, page.pageIndex)

        // 3. Detect dominant font size for heading heuristics
        val medianFontSize = if (words.isNotEmpty()) {
            val sizes = words.map { it.fontSize }.sorted()
            sizes[sizes.size / 2]
        } else 12f

        // 4. Cluster lines into blocks
        val blocks = buildBlocks(lines, page.pageIndex, medianFontSize)

        // 5. Cluster blocks into paragraphs
        val paragraphs = buildParagraphs(blocks, page.pageIndex)

        // 6. Detect columns
        val detectedColumns = detectColumns(blocks, page.size.width)

        // 7. Calculate confidence
        val confidence = computeConfidence(lines, blocks)

        PageStructure(
            pageIndex = page.pageIndex,
            bounds = pageBounds,
            words = sortedWords,
            lines = lines,
            blocks = blocks,
            paragraphs = paragraphs,
            images = images,
            columnCount = detectedColumns,
            layoutConfidence = confidence,
            isScanned = isScanned
        )
    }

    private fun buildLines(words: List<TextWord>, pageIndex: Int): List<TextLine> {
        val lines = mutableListOf<TextLine>()
        var currentLineWords = mutableListOf<TextWord>()
        var lineIndex = 0

        fun flushLine() {
            if (currentLineWords.isNotEmpty()) {
                val sortedX = currentLineWords.sortedBy { it.bounds.left }
                val lineText = sortedX.joinToString(" ") { it.text }
                var lineBounds = sortedX.first().bounds
                for (i in 1 until sortedX.size) {
                    lineBounds = lineBounds.union(sortedX[i].bounds)
                }

                val avgFontSize = sortedX.map { it.fontSize }.average().toFloat()
                val avgBaseline = sortedX.map { it.baseline }.average().toFloat()

                lines.add(
                    TextLine(
                        text = lineText,
                        pageIndex = pageIndex,
                        bounds = lineBounds,
                        words = sortedX,
                        averageFontSize = avgFontSize,
                        baseline = avgBaseline,
                        orderIndex = lineIndex++
                    )
                )
                currentLineWords = mutableListOf()
            }
        }

        for (word in words) {
            if (currentLineWords.isEmpty()) {
                currentLineWords.add(word)
            } else {
                val prev = currentLineWords.last()
                val lineThreshold = max(prev.fontSize, word.fontSize) * 0.45f
                val baselineDiff = abs(word.baseline - prev.baseline)
                val topDiff = abs(word.bounds.top - prev.bounds.top)

                if (baselineDiff <= lineThreshold || topDiff <= lineThreshold) {
                    currentLineWords.add(word)
                } else {
                    flushLine()
                    currentLineWords.add(word)
                }
            }
        }
        flushLine()

        return lines
    }

    private fun buildBlocks(
        lines: List<TextLine>,
        pageIndex: Int,
        medianFontSize: Float
    ): List<TextBlock> {
        val blocks = mutableListOf<TextBlock>()
        var currentBlockLines = mutableListOf<TextLine>()
        var blockIndex = 0

        fun flushBlock() {
            if (currentBlockLines.isNotEmpty()) {
                val blockText = currentBlockLines.joinToString("\n") { it.text }
                var blockBounds = currentBlockLines.first().bounds
                for (i in 1 until currentBlockLines.size) {
                    blockBounds = blockBounds.union(currentBlockLines[i].bounds)
                }

                val isHeading = currentBlockLines.size <= 2 &&
                    currentBlockLines.any { it.averageFontSize >= medianFontSize * 1.25f || it.words.all { w -> w.fontStyle.isBold } }

                blocks.add(
                    TextBlock(
                        id = "p${pageIndex}_b${blockIndex}",
                        text = blockText,
                        pageIndex = pageIndex,
                        bounds = blockBounds,
                        lines = currentBlockLines.toList(),
                        columnIndex = 0,
                        isHeading = isHeading,
                        orderIndex = blockIndex++
                    )
                )
                currentBlockLines = mutableListOf()
            }
        }

        for (line in lines) {
            if (currentBlockLines.isEmpty()) {
                currentBlockLines.add(line)
            } else {
                val prev = currentBlockLines.last()
                val lineSpacing = line.bounds.top - prev.bounds.bottom
                val expectedLineHeight = prev.bounds.height

                // If line spacing exceeds 1.8x line height or horizontal gap is excessive, start new block
                if (lineSpacing > expectedLineHeight * 1.8f || lineSpacing < -expectedLineHeight * 0.5f) {
                    flushBlock()
                }
                currentBlockLines.add(line)
            }
        }
        flushBlock()

        return blocks
    }

    private fun buildParagraphs(blocks: List<TextBlock>, pageIndex: Int): List<Paragraph> {
        var pIndex = 0
        return blocks.map { block ->
            Paragraph(
                id = "p${pageIndex}_para${pIndex++}",
                text = block.text,
                pageIndex = pageIndex,
                bounds = block.bounds,
                blocks = listOf(block),
                orderIndex = block.orderIndex
            )
        }
    }

    private fun detectColumns(blocks: List<TextBlock>, pageWidth: Float): Int {
        if (blocks.size < 4) return 1
        val midX = pageWidth / 2f
        val left = blocks.count { it.bounds.right < midX }
        val right = blocks.count { it.bounds.left > midX }
        return if (left >= 2 && right >= 2) 2 else 1
    }

    private fun computeConfidence(lines: List<TextLine>, blocks: List<TextBlock>): Float {
        if (lines.isEmpty()) return 1.0f
        val averageWordsPerLine = lines.map { it.words.size }.average()
        return if (averageWordsPerLine >= 3) 0.95f else 0.80f
    }
}
