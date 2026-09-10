package com.thinkspace.pdfengine.cache

import android.graphics.Bitmap
import com.thinkspace.pdfengine.model.PageStructure
import com.thinkspace.pdfengine.model.TextWord

interface PdfCache {
    fun putTextWords(documentId: String, pageIndex: Int, words: List<TextWord>)
    fun getTextWords(documentId: String, pageIndex: Int): List<TextWord>?

    fun putPageStructure(documentId: String, pageIndex: Int, structure: PageStructure)
    fun getPageStructure(documentId: String, pageIndex: Int): PageStructure?

    fun putRenderedBitmap(documentId: String, pageIndex: Int, key: String, bitmap: Bitmap)
    fun getRenderedBitmap(documentId: String, pageIndex: Int, key: String): Bitmap?

    fun clearDocument(documentId: String)
    fun clearAll()
    fun currentMemoryUsageBytes(): Long
    fun maxMemoryUsageBytes(): Long
}
