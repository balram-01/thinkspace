package com.thinkspace.pdfengine.storage

import android.graphics.Bitmap
import com.thinkspace.pdfengine.logging.PdfLogger
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks native and managed resources per document to guarantee deterministic cleanup.
 */
class ResourceManager(
    private val logger: PdfLogger
) : Closeable {

    private val isDisposed = AtomicBoolean(false)
    private val closeables = ConcurrentHashMap.newKeySet<Closeable>()
    private val tempFiles = ConcurrentHashMap.newKeySet<File>()
    private val activeBitmaps = ConcurrentHashMap.newKeySet<Bitmap>()

    fun registerCloseable(closeable: Closeable) {
        if (isDisposed.get()) {
            try {
                closeable.close()
            } catch (_: Throwable) {}
            return
        }
        closeables.add(closeable)
    }

    fun registerTempFile(file: File) {
        if (isDisposed.get()) {
            file.delete()
            return
        }
        tempFiles.add(file)
    }

    fun registerBitmap(bitmap: Bitmap) {
        if (isDisposed.get()) {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
            return
        }
        activeBitmaps.add(bitmap)
    }

    fun unregisterBitmap(bitmap: Bitmap) {
        activeBitmaps.remove(bitmap)
    }

    override fun close() {
        if (isDisposed.compareAndSet(false, true)) {
            logger.debug("ResourceManager") { "Releasing resources: ${closeables.size} closeables, ${tempFiles.size} temp files, ${activeBitmaps.size} bitmaps" }

            for (closeable in closeables) {
                try {
                    closeable.close()
                } catch (e: Throwable) {
                    logger.warn("ResourceManager", { "Error closing resource" }, e)
                }
            }
            closeables.clear()

            for (file in tempFiles) {
                try {
                    if (file.exists()) {
                        file.delete()
                    }
                } catch (e: Throwable) {
                    logger.warn("ResourceManager", { "Error deleting temp file: ${file.absolutePath}" }, e)
                }
            }
            tempFiles.clear()

            for (bitmap in activeBitmaps) {
                try {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                } catch (e: Throwable) {
                    logger.warn("ResourceManager", { "Error recycling bitmap" }, e)
                }
            }
            activeBitmaps.clear()
        }
    }
}
