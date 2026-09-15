package com.thinkspace.pdfengine.indexing

import com.thinkspace.pdfengine.logging.PdfLogger
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.Quad
import com.thinkspace.pdfengine.model.SearchResult
import com.thinkspace.pdfengine.model.TextWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        if (pageMap.containsKey(pageIndex) && !isFromOcr) return

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

                // Overlapping word spans
                val overlappingSpans = pageData.spans.filter { span ->
                    !(span.endChar <= matchStart || span.startChar >= matchEnd)
                }

                if (overlappingSpans.isNotEmpty()) {
                    var unionBox: BoundingBox? = null
                    val quads = mutableListOf<Quad>()

                    for (span in overlappingSpans) {
                        val w = span.word
                        if (w.characters.isNotEmpty()) {
                            val matchedChars = w.characters.filterIndexed { cIdx, _ ->
                                val pos = span.startChar + cIdx
                                pos in matchStart until matchEnd
                            }
                            if (matchedChars.isNotEmpty()) {
                                var charBox = matchedChars.first().bounds
                                for (c in matchedChars.drop(1)) {
                                    charBox = charBox.union(c.bounds)
                                }
                                quads.add(charBox.toQuad())
                                unionBox = if (unionBox == null) charBox else unionBox.union(charBox)
                                continue
                            }
                        }
                        // Fallback to full word bounds
                        quads.add(w.bounds.toQuad())
                        unionBox = if (unionBox == null) w.bounds else unionBox.union(w.bounds)
                    }

                    if (unionBox != null) {
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
                }

                searchIndex = matchStart + max(1, lowerQuery.length)
            }
        }

        // Maintain strict document / reading order across all pages
        results.sortWith(compareBy({ it.pageIndex }, { it.bounds.top }, { it.bounds.left }))
        results
    }

    /**
     * Incremental streaming search: searches page-by-page in document order,
     * calling [onMatchFound] immediately after each page yields results.
     * This allows the caller to show the first match without waiting for all pages.
     *
     * [onMatchFound] receives the list of results found on the current page and
     * the total count so far. It should return false to stop searching early.
     *
     * Only pages that have already been indexed are searched; pages not yet
     * indexed are skipped (they will be indexed externally and searched later).
     */
    suspend fun searchIncremental(
        documentId: String,
        query: String,
        sortedPageIndices: List<Int>,
        onMatchFound: suspend (pageResults: List<SearchResult>, totalSoFar: Int) -> Boolean
    ) = withContext(Dispatchers.Default) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return@withContext

        val lowerQuery = trimmedQuery.lowercase()
        val pageMap = documentPages[documentId] ?: return@withContext
        var totalSoFar = 0

        for (pageIdx in sortedPageIndices) {
            currentCoroutineContext().ensureActive()
            val pageData = pageMap[pageIdx] ?: continue // page not indexed yet — skip

            val pageResults = mutableListOf<SearchResult>()
            var searchIndex = 0

            while (searchIndex < pageData.lowerCaseText.length) {
                currentCoroutineContext().ensureActive()
                val matchStart = pageData.lowerCaseText.indexOf(lowerQuery, searchIndex)
                if (matchStart == -1) break

                val matchEnd = matchStart + lowerQuery.length
                val matchedText = pageData.fullText.substring(matchStart, matchEnd)

                val overlappingSpans = pageData.spans.filter { span ->
                    !(span.endChar <= matchStart || span.startChar >= matchEnd)
                }

                if (overlappingSpans.isNotEmpty()) {
                    var unionBox: BoundingBox? = null
                    val quads = mutableListOf<Quad>()

                    for (span in overlappingSpans) {
                        val w = span.word
                        if (w.characters.isNotEmpty()) {
                            val matchedChars = w.characters.filterIndexed { cIdx, _ ->
                                val pos = span.startChar + cIdx
                                pos in matchStart until matchEnd
                            }
                            if (matchedChars.isNotEmpty()) {
                                var charBox = matchedChars.first().bounds
                                for (c in matchedChars.drop(1)) charBox = charBox.union(c.bounds)
                                quads.add(charBox.toQuad())
                                unionBox = if (unionBox == null) charBox else unionBox.union(charBox)
                                continue
                            }
                        }
                        quads.add(w.bounds.toQuad())
                        unionBox = if (unionBox == null) w.bounds else unionBox.union(w.bounds)
                    }

                    if (unionBox != null) {
                        val snippetStart = max(0, matchStart - 35)
                        val snippetEnd = min(pageData.fullText.length, matchEnd + 35)
                        val prefix = if (snippetStart > 0) "..." else ""
                        val suffix = if (snippetEnd < pageData.fullText.length) "..." else ""
                        val contextSnippet = prefix + pageData.fullText.substring(snippetStart, snippetEnd).trim() + suffix

                        pageResults.add(
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
                }

                searchIndex = matchStart + max(1, lowerQuery.length)
            }

            if (pageResults.isNotEmpty()) {
                pageResults.sortWith(compareBy({ it.bounds.top }, { it.bounds.left }))
                totalSoFar += pageResults.size
                val continueSearch = onMatchFound(pageResults, totalSoFar)
                if (!continueSearch) return@withContext
            }
        }
    }

    /** Returns the set of page indices that have already been indexed for this document. */
    fun indexedPageIndices(documentId: String): Set<Int> =
        documentPages[documentId]?.keys?.toSet() ?: emptySet()

    override fun clearDocument(documentId: String) {
        documentPages.remove(documentId)
    }
}
