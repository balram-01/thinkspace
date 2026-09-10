package com.thinkspace.pdfengine

import android.graphics.Bitmap
import com.thinkspace.pdfengine.cache.LruMemoryCache
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.FontStyle
import com.thinkspace.pdfengine.model.PageStructure
import com.thinkspace.pdfengine.model.TextWord
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LruMemoryCacheTest {

    @Test
    fun testTextWordsCachingAndClear() {
        val cache = LruMemoryCache(1024 * 1024L)
        val docId = "doc_test"
        val words = listOf(
            TextWord("Test", 0, BoundingBox(0f, 0f, 10f, 10f), 12f, "Font", FontStyle.REGULAR, 10f)
        )

        assertNull(cache.getTextWords(docId, 0))
        cache.putTextWords(docId, 0, words)
        val cached = cache.getTextWords(docId, 0)
        assertNotNull(cached)
        assertEquals("Test", cached!![0].text)

        cache.clearDocument(docId)
        assertNull(cache.getTextWords(docId, 0))
    }

    @Test
    fun testPageStructureCaching() {
        val cache = LruMemoryCache(1024 * 1024L)
        val docId = "doc_struct"
        val structure = PageStructure(
            pageIndex = 0,
            bounds = BoundingBox(0f, 0f, 100f, 100f),
            words = emptyList(),
            lines = emptyList(),
            blocks = emptyList(),
            paragraphs = emptyList()
        )

        cache.putPageStructure(docId, 0, structure)
        val retrieved = cache.getPageStructure(docId, 0)
        assertNotNull(retrieved)
        assertEquals(0, retrieved!!.pageIndex)
    }

    @Test
    fun testBitmapCacheEviction() {
        // 1 MB limit
        val cache = LruMemoryCache(1024 * 1024L)

        // Mock bitmaps with 600 KB each (2 bitmaps = 1.2 MB > 1.0 MB limit)
        val bitmap1 = mockk<Bitmap>(relaxed = true) {
            every { allocationByteCount } returns 600 * 1024
            every { byteCount } returns 600 * 1024
            every { isRecycled } returns false
        }
        val bitmap2 = mockk<Bitmap>(relaxed = true) {
            every { allocationByteCount } returns 600 * 1024
            every { byteCount } returns 600 * 1024
            every { isRecycled } returns false
        }

        cache.putRenderedBitmap("doc", 0, "key1", bitmap1)
        assertNotNull(cache.getRenderedBitmap("doc", 0, "key1"))

        cache.putRenderedBitmap("doc", 1, "key2", bitmap2)
        assertNotNull(cache.getRenderedBitmap("doc", 1, "key2"))

        // bitmap1 should have been evicted because 600KB + 600KB > 1024KB
        assertNull("Oldest bitmap should be evicted", cache.getRenderedBitmap("doc", 0, "key1"))
    }
}
