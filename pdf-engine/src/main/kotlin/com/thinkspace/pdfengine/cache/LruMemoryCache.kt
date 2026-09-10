package com.thinkspace.pdfengine.cache

import android.graphics.Bitmap
import com.thinkspace.pdfengine.model.PageStructure
import com.thinkspace.pdfengine.model.TextWord

/**
 * Thread-safe, bounded memory LRU cache for PDF documents.
 * Automatically evicts least-recently-used items when the memory threshold is exceeded.
 */
class LruMemoryCache(
    private val maxBytes: Long = 64 * 1024 * 1024L // 64 MB default
) : PdfCache {

    private val lock = Any()

    // Key format: "$documentId:$pageIndex:$subKey"
    private val bitmapMap = object : LinkedHashMap<String, BitmapEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BitmapEntry>?): Boolean {
            return false // We handle manual eviction based on total bytes
        }
    }

    private val textMap = object : LinkedHashMap<String, List<TextWord>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<TextWord>>?): Boolean {
            return size > 200 // Max 200 cached pages of words
        }
    }

    private val structureMap = object : LinkedHashMap<String, PageStructure>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageStructure>?): Boolean {
            return size > 100 // Max 100 cached page structures
        }
    }

    private var currentBitmapBytes: Long = 0L

    private data class BitmapEntry(
        val bitmap: Bitmap,
        val bytes: Long
    )

    override fun putTextWords(documentId: String, pageIndex: Int, words: List<TextWord>) {
        synchronized(lock) {
            textMap["$documentId:$pageIndex"] = words
        }
    }

    override fun getTextWords(documentId: String, pageIndex: Int): List<TextWord>? {
        synchronized(lock) {
            return textMap["$documentId:$pageIndex"]
        }
    }

    override fun putPageStructure(documentId: String, pageIndex: Int, structure: PageStructure) {
        synchronized(lock) {
            structureMap["$documentId:$pageIndex"] = structure
        }
    }

    override fun getPageStructure(documentId: String, pageIndex: Int): PageStructure? {
        synchronized(lock) {
            return structureMap["$documentId:$pageIndex"]
        }
    }

    override fun putRenderedBitmap(documentId: String, pageIndex: Int, key: String, bitmap: Bitmap) {
        val compositeKey = "$documentId:$pageIndex:$key"
        val bytes = try {
            bitmap.allocationByteCount.toLong()
        } catch (_: Throwable) {
            bitmap.byteCount.toLong()
        }

        synchronized(lock) {
            val existing = bitmapMap.remove(compositeKey)
            if (existing != null) {
                currentBitmapBytes -= existing.bytes
            }

            bitmapMap[compositeKey] = BitmapEntry(bitmap, bytes)
            currentBitmapBytes += bytes

            trimBitmapsToSize(maxBytes)
        }
    }

    override fun getRenderedBitmap(documentId: String, pageIndex: Int, key: String): Bitmap? {
        val compositeKey = "$documentId:$pageIndex:$key"
        synchronized(lock) {
            val entry = bitmapMap[compositeKey] ?: return null
            if (entry.bitmap.isRecycled) {
                bitmapMap.remove(compositeKey)
                currentBitmapBytes -= entry.bytes
                return null
            }
            return entry.bitmap
        }
    }

    private fun trimBitmapsToSize(limit: Long) {
        val iterator = bitmapMap.entries.iterator()
        while (iterator.hasNext() && currentBitmapBytes > limit) {
            val entry = iterator.next()
            iterator.remove()
            currentBitmapBytes -= entry.value.bytes
        }
    }

    override fun clearDocument(documentId: String) {
        synchronized(lock) {
            val prefix = "$documentId:"

            // Clear bitmaps
            val bitmapIter = bitmapMap.entries.iterator()
            while (bitmapIter.hasNext()) {
                val entry = bitmapIter.next()
                if (entry.key.startsWith(prefix)) {
                    currentBitmapBytes -= entry.value.bytes
                    bitmapIter.remove()
                }
            }

            // Clear text
            val textIter = textMap.entries.iterator()
            while (textIter.hasNext()) {
                if (textIter.next().key.startsWith(prefix)) {
                    textIter.remove()
                }
            }

            // Clear structures
            val structIter = structureMap.entries.iterator()
            while (structIter.hasNext()) {
                if (structIter.next().key.startsWith(prefix)) {
                    structIter.remove()
                }
            }
        }
    }

    override fun clearAll() {
        synchronized(lock) {
            bitmapMap.clear()
            textMap.clear()
            structureMap.clear()
            currentBitmapBytes = 0L
        }
    }

    override fun currentMemoryUsageBytes(): Long {
        synchronized(lock) {
            return currentBitmapBytes
        }
    }

    override fun maxMemoryUsageBytes(): Long = maxBytes
}
