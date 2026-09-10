package com.thinkspace.pdfengine.rendering

import android.graphics.Bitmap
import com.thinkspace.pdfengine.logging.PdfLogger
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thread-safe Bitmap pool to eliminate GC allocations during rendering.
 */
class BitmapPool(
    private val maxPoolBytes: Long = 32 * 1024 * 1024L, // 32 MB pool
    private val logger: PdfLogger
) {
    private val pool = ConcurrentLinkedQueue<Bitmap>()
    private var currentPoolBytes = 0L
    private val lock = Any()

    fun acquire(width: Int, height: Int, config: Bitmap.Config): Bitmap {
        synchronized(lock) {
            val iterator = pool.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (!candidate.isRecycled &&
                    candidate.width == width &&
                    candidate.height == height &&
                    candidate.config == config
                ) {
                    iterator.remove()
                    currentPoolBytes -= candidate.allocationByteCount
                    candidate.eraseColor(0) // Clear previous content
                    return candidate
                }
            }
        }

        // Fallback: allocate new bitmap
        return Bitmap.createBitmap(width, height, config)
    }

    fun release(bitmap: Bitmap) {
        if (bitmap.isRecycled) return

        val bytes = try { bitmap.allocationByteCount } catch (_: Throwable) { bitmap.byteCount }

        synchronized(lock) {
            if (currentPoolBytes + bytes <= maxPoolBytes) {
                pool.add(bitmap)
                currentPoolBytes += bytes
            } else {
                bitmap.recycle()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            while (pool.isNotEmpty()) {
                val b = pool.poll()
                if (b != null && !b.isRecycled) {
                    b.recycle()
                }
            }
            currentPoolBytes = 0L
        }
    }
}
