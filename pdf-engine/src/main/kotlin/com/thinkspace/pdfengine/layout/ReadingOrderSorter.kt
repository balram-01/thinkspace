package com.thinkspace.pdfengine.layout

import com.thinkspace.pdfengine.model.PageSize
import com.thinkspace.pdfengine.model.TextWord
import kotlin.math.abs

/**
 * Geometric reading order sorter handling multi-column documents.
 */
class ReadingOrderSorter {

    fun sortWords(words: List<TextWord>, pageSize: PageSize): List<TextWord> {
        if (words.size <= 1) return words

        // Check if multi-column layout is present
        val columns = partitionIntoColumns(words, pageSize)
        val sortedList = mutableListOf<TextWord>()

        for (columnWords in columns) {
            val sortedInColumn = sortColumnWords(columnWords)
            sortedList.addAll(sortedInColumn)
        }

        // Reassign clean order indices
        return sortedList.mapIndexed { index, word ->
            word.copy(orderIndex = index)
        }
    }

    private fun partitionIntoColumns(words: List<TextWord>, pageSize: PageSize): List<List<TextWord>> {
        val midX = pageSize.width / 2f
        val leftColumn = mutableListOf<TextWord>()
        val rightColumn = mutableListOf<TextWord>()
        val spanning = mutableListOf<TextWord>()

        for (word in words) {
            val cx = word.bounds.centerX
            if (word.bounds.width > pageSize.width * 0.7f) {
                spanning.add(word)
            } else if (cx < midX - 10f) {
                leftColumn.add(word)
            } else if (cx > midX + 10f) {
                rightColumn.add(word)
            } else {
                spanning.add(word)
            }
        }

        // Only treat as 2-column if both columns have significant content (>15% each)
        val total = words.size.toFloat()
        val isTwoColumn = (leftColumn.size / total > 0.15f) && (rightColumn.size / total > 0.15f)

        return if (isTwoColumn) {
            listOf(leftColumn, rightColumn, spanning)
        } else {
            listOf(words)
        }
    }

    private fun sortColumnWords(words: List<TextWord>): List<TextWord> {
        return words.sortedWith { w1, w2 ->
            val fontAvg = (w1.fontSize + w2.fontSize) / 2f
            val lineTolerance = fontAvg * 0.45f
            val yDiff = w1.bounds.top - w2.bounds.top

            if (abs(yDiff) > lineTolerance) {
                yDiff.compareTo(0f)
            } else {
                w1.bounds.left.compareTo(w2.bounds.left)
            }
        }
    }
}
