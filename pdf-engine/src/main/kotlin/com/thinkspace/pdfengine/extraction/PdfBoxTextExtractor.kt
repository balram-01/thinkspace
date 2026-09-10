package com.thinkspace.pdfengine.extraction

import com.thinkspace.pdfengine.api.PdfDocument
import com.thinkspace.pdfengine.errors.PdfEngineError
import com.thinkspace.pdfengine.logging.PdfLogger
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.FontStyle
import com.thinkspace.pdfengine.model.TextCharacter
import com.thinkspace.pdfengine.model.TextWord
import com.thinkspace.pdfengine.parser.PdfBoxDocumentWrapper
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.StringWriter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * High-fidelity text extractor utilizing PDFBox's glyph-level TextPosition stream.
 * Extracts words, characters, font metrics, baselines, and exact bounding boxes.
 */
class PdfBoxTextExtractor(
    private val logger: PdfLogger
) : PdfTextExtractor {

    override suspend fun extractWords(
        document: PdfDocument,
        pageIndex: Int
    ): List<TextWord> = withContext(Dispatchers.Default) {
        val wrapper = document as? PdfBoxDocumentWrapper
            ?: throw PdfEngineError.ExtractionFailure(pageIndex, "Document is not a PdfBoxDocumentWrapper")

        if (pageIndex < 0 || pageIndex >= document.pageCount) {
            throw PdfEngineError.ExtractionFailure(pageIndex, "Page index out of bounds: $pageIndex")
        }

        try {
            val collector = CharacterPositionCollector(pageIndex, logger)
            collector.startPage = pageIndex + 1
            collector.endPage = pageIndex + 1
            collector.sortByPosition = true

            // Writing to dummy writer triggers processTextPosition
            val dummyWriter = StringWriter()
            collector.writeText(wrapper.pdDocument, dummyWriter)

            val rawCharacters = collector.characters
            if (rawCharacters.isEmpty()) {
                return@withContext emptyList()
            }

            // Cluster characters into words
            buildWordsFromCharacters(rawCharacters, pageIndex)
        } catch (t: Throwable) {
            logger.error("PdfBoxTextExtractor", { "Failed to extract text from page $pageIndex" }, t)
            throw PdfEngineError.ExtractionFailure(pageIndex, "Text extraction failed: ${t.message}", t)
        }
    }

    private fun buildWordsFromCharacters(
        characters: List<TextCharacter>,
        pageIndex: Int
    ): List<TextWord> {
        val words = mutableListOf<TextWord>()
        var currentWordChars = mutableListOf<TextCharacter>()
        var wordIndex = 0

        fun flushWord() {
            if (currentWordChars.isNotEmpty()) {
                val text = currentWordChars.joinToString("") { it.text }
                if (text.isNotBlank()) {
                    var unionBounds = currentWordChars.first().bounds
                    for (i in 1 until currentWordChars.size) {
                        unionBounds = unionBounds.union(currentWordChars[i].bounds)
                    }

                    val avgFontSize = currentWordChars.map { it.fontSize }.average().toFloat()
                    val fontName = currentWordChars.first().fontName
                    val fontStyle = currentWordChars.first().fontStyle
                    val avgBaseline = currentWordChars.map { it.baseline }.average().toFloat()

                    words.add(
                        TextWord(
                            text = text,
                            pageIndex = pageIndex,
                            bounds = unionBounds,
                            fontSize = avgFontSize,
                            fontName = fontName,
                            fontStyle = fontStyle,
                            baseline = avgBaseline,
                            characters = currentWordChars.toList(),
                            rotation = currentWordChars.first().rotation,
                            orderIndex = wordIndex++
                        )
                    )
                }
                currentWordChars = mutableListOf()
            }
        }

        for (i in characters.indices) {
            val curr = characters[i]

            if (curr.char.isWhitespace()) {
                flushWord()
                continue
            }

            if (currentWordChars.isEmpty()) {
                currentWordChars.add(curr)
            } else {
                val prev = currentWordChars.last()
                val gapX = curr.bounds.left - prev.bounds.right
                val gapY = abs(curr.baseline - prev.baseline)
                val spaceThreshold = max(prev.fontSize * 0.28f, 2.0f)
                val lineThreshold = max(prev.fontSize * 0.5f, 4.0f)

                if (gapX > spaceThreshold || gapY > lineThreshold || gapX < -prev.fontSize * 0.5f) {
                    flushWord()
                }
                currentWordChars.add(curr)
            }
        }
        flushWord()

        return words
    }

    private class CharacterPositionCollector(
        private val pageIndex: Int,
        private val logger: PdfLogger
    ) : PDFTextStripper() {

        val characters = mutableListOf<TextCharacter>()
        private var charOrder = 0

        override fun processTextPosition(text: TextPosition) {
            val unicode = text.unicode ?: return
            if (unicode.isEmpty()) return

            val x = text.xDirAdj
            val y = text.yDirAdj
            val width = text.widthDirAdj
            val height = text.heightDir

            val left = min(x, x + width)
            val right = max(x, x + width)
            val top = min(y - height, y)
            val bottom = max(y - height, y)

            val bounds = BoundingBox(
                left = left,
                top = top,
                right = if (right <= left) left + 1f else right,
                bottom = if (bottom <= top) top + 1f else bottom
            )

            val font = text.font
            val fontName = font?.name ?: "Unknown"
            val descriptor = font?.fontDescriptor

            val isBold = descriptor?.isForceBold == true ||
                fontName.contains("Bold", ignoreCase = true) ||
                (descriptor?.flags ?: 0) and 262144 != 0

            val isItalic = descriptor?.isItalic == true ||
                fontName.contains("Italic", ignoreCase = true) ||
                fontName.contains("Oblique", ignoreCase = true) ||
                (descriptor?.flags ?: 0) and 64 != 0

            val style = when {
                isBold && isItalic -> FontStyle.BOLD_ITALIC
                isBold -> FontStyle.BOLD
                isItalic -> FontStyle.ITALIC
                else -> FontStyle.REGULAR
            }

            for (c in unicode) {
                characters.add(
                    TextCharacter(
                        char = c,
                        pageIndex = pageIndex,
                        bounds = bounds,
                        fontSize = text.fontSizeInPt,
                        fontName = fontName,
                        fontStyle = style,
                        baseline = y,
                        rotation = text.rotation.toFloat(),
                        orderIndex = charOrder++
                    )
                )
            }
        }
    }
}
