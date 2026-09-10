package com.thinkspace.pdfengine.indexing

import com.thinkspace.pdfengine.logging.PdfLogger
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.Quad
import com.thinkspace.pdfengine.model.SearchResult
import com.thinkspace.pdfengine.model.TextWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Fast in-memory positional inverted index with character-level spatial resolution.
 * Supports exact phrase matching across native and OCR text without database overhead.
 */
class PositionalSearchIndex(
    private val logger: PdfLogger
) : PdfSearchEngine {

    private val documentPages = ConcurrentHashMap<String, ConcurrentHashMap<Int, PageSearchData>>()

    private data class WordSpan(
        val startChar: Int,
        val endChar: Int,
        val word: TextWord
    )

    private data class PageSearchData(
        val pageIndex: Int,
        val fullText: String,
        val lowerCaseText: String,
        val spans: List<WordSpan>,
        val isFromOcr: Boolean
    )

    override fun indexPage(
        documentId: String,
        pageIndex: Int,
        words: List<TextWord>,
        isFromOcr: Boolean
    ) {
        if (words.isEmpty()) return

        val pageMap = documentPages.computeIfAbsent(documentId) { ConcurrentHashMap() }
        val sb = StringBuilder()
        val spans = mutableListOf<WordSpan>()

        for (word in words) {
            val start = sb.length
            sb.append(word.text)
            val end = sb.length
            spans.add(WordSpan(start, end, word))
            sb.append(" ") // word delimiter
        }

        val fullText = sb.toString()
        pageMap[pageIndex] = PageSearchData(
            pageIndex = pageIndex,
            fullText = fullText,
            lowerCaseText = fullText.lowercase(),
            spans = spans,
            isFromOcr = isFromOcr
        )
    }

    override suspend fun search(
        documentId: String,
        query: String
    ): List<SearchResult> = withContext(Dispatchers.Default) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return@withContext emptyList()

        val lowerQuery = trimmedQuery.lowercase()
        val pageMap = documentPages[documentId] ?: return@withContext emptyList()
        val results = mutableListOf<SearchResult>()

        // Sort pages sequentially
        val sortedPages = pageMap.values.sortedBy { it.pageIndex }

        for (pageData in sortedPages) {
            var searchIndex = 0
            while (searchIndex < pageData.lowerCaseText.length) {
                val matchStart = pageData.lowerCaseText.indexOf(lowerQuery, searchIndex)
                if (matchStart == -1) break

                val matchEnd = matchStart + lowerQuery.length
                val matchedText = pageData.fullText.substring(matchStart, matchEnd)

                // Overlapping words
                val matchedWords = pageData.spans.filter { span ->
                    !(span.endChar <= matchStart || span.startChar >= matchEnd)
                }.map { it.word }

                if (matchedWords.isNotEmpty()) {
                    var unionBox = matchedWords.first().bounds
                    val quads = mutableListOf<Quad>()
                    for (w in matchedWords) {
                        unionBox = unionBox.union(w.bounds)
                        quads.add(w.bounds.toQuad())
                    }

                    // Context snippet
                    val snippetStart = max(0, matchStart - 35)
                    val snippetEnd = min(pageData.fullText.length, matchEnd + 35)
                    val prefix = if (snippetStart > 0) "..." else ""
                    val suffix = if (snippetEnd < pageData.fullText.length) "..." else ""
                    val contextSnippet = prefix + pageData.fullText.substring(snippetStart, snippetEnd).trim() + suffix

                    results.add(
                        SearchResult(
                            pageIndex = pageData.pageIndex,
                            matchedText = matchedText,
                            bounds = unionBox,
                            context = contextSnippet,
                            startOffset = matchStart,
                            endOffset = matchEnd,
                            quads = quads,
                            isFromOcr = pageData.isFromOcr
                        )
                    )
                }

                searchIndex = matchStart + max(1, lowerQuery.length)
            }
        }

        results
    }

    override fun clearDocument(documentId: String) {
        documentPages.remove(documentId)
    }
}
