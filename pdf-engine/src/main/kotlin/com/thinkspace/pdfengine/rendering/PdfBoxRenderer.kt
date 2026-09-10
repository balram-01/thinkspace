package com.thinkspace.pdfengine.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.thinkspace.pdfengine.api.PdfDocument
import com.thinkspace.pdfengine.api.RenderOptions
import com.thinkspace.pdfengine.cache.PdfCache
import com.thinkspace.pdfengine.coordinates.PageRect
import com.thinkspace.pdfengine.errors.PdfEngineError
import com.thinkspace.pdfengine.logging.PdfLogger
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.parser.PdfBoxDocumentWrapper
import com.tom_roush.pdfbox.rendering.PDFRenderer as TomRoushPdfRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * High-performance PDFBox page renderer supporting arbitrary zoom, viewport sub-rectangles,
 * bitmap pooling, and LRU memory caching.
 */
class PdfBoxRenderer(
    private val bitmapPool: BitmapPool,
    private val cache: PdfCache,
    private val logger: PdfLogger
) : PdfRenderer {

    override suspend fun renderPage(
        document: PdfDocument,
        pageIndex: Int,
        options: RenderOptions
    ): RenderedPage = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()

        val wrapper = document as? PdfBoxDocumentWrapper
            ?: throw PdfEngineError.RenderingFailure(pageIndex, "Document is not a PdfBoxDocumentWrapper")

        if (pageIndex < 0 || pageIndex >= document.pageCount) {
            throw PdfEngineError.RenderingFailure(pageIndex, "Page index $pageIndex out of range")
        }

        val pageHandle = document.getPage(pageIndex)
        val pageSize = pageHandle.size
        val scale = options.scale
        val viewport = options.viewport

        val targetWidth = if (viewport != null) (viewport.width * scale).roundToInt() else (pageSize.width * scale).roundToInt()
        val targetHeight = if (viewport != null) (viewport.height * scale).roundToInt() else (pageSize.height * scale).roundToInt()

        // Check memory safety (prevent OOM crash for excessive zoom on huge pages)
        val estimatedBytes = targetWidth.toLong() * targetHeight * 4L
        val maxSafeBytes = 128 * 1024 * 1024L // 128 MB max per single render
        if (estimatedBytes > maxSafeBytes) {
            throw PdfEngineError.OutOfMemoryRisk(estimatedBytes, maxSafeBytes)
        }

        val cacheKey = "scale_${scale}_vp_${viewport?.left}_${viewport?.top}_${viewport?.width}_${viewport?.height}"
        val cachedBitmap = cache.getRenderedBitmap(document.id, pageIndex, cacheKey)
        if (cachedBitmap != null && !cachedBitmap.isRecycled) {
            return@withContext RenderedPage(
                pageIndex = pageIndex,
                bitmap = cachedBitmap,
                scale = scale,
                renderedBounds = viewport ?: BoundingBox(0f, 0f, pageSize.width, pageSize.height),
                renderDurationMs = 0L
            )
        }

        val startTime = System.currentTimeMillis()
        try {
            val tomRoushRenderer = TomRoushPdfRenderer(wrapper.pdDocument)

            // Render page at requested scale
            currentCoroutineContext().ensureActive()
            val fullPageBitmap = tomRoushRenderer.renderImage(pageIndex, scale)
            currentCoroutineContext().ensureActive()

            val finalBitmap: Bitmap
            val effectiveBounds: BoundingBox

            if (viewport != null) {
                // Viewport crop into pooled bitmap
                val vpRect = pageHandle.coordinateMapper.pageToRender(
                    PageRect(viewport.left, viewport.top, viewport.right, viewport.bottom),
                    scale
                )

                val srcLeft = vpRect.left.roundToInt().coerceIn(0, fullPageBitmap.width)
                val srcTop = vpRect.top.roundToInt().coerceIn(0, fullPageBitmap.height)
                val srcRight = vpRect.right.roundToInt().coerceIn(srcLeft, fullPageBitmap.width)
                val srcBottom = vpRect.bottom.roundToInt().coerceIn(srcTop, fullPageBitmap.height)

                val cropWidth = maxOf(1, srcRight - srcLeft)
                val cropHeight = maxOf(1, srcBottom - srcTop)

                finalBitmap = bitmapPool.acquire(cropWidth, cropHeight, options.bitmapConfig)
                val canvas = Canvas(finalBitmap)
                canvas.drawColor(options.backgroundColor)

                val srcRect = Rect(srcLeft, srcTop, srcRight, srcBottom)
                val dstRect = Rect(0, 0, cropWidth, cropHeight)
                canvas.drawBitmap(fullPageBitmap, srcRect, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))

                // Recycle intermediate full page bitmap if not in cache
                if (!fullPageBitmap.isRecycled) {
                    fullPageBitmap.recycle()
                }
                effectiveBounds = viewport
            } else {
                finalBitmap = fullPageBitmap
                effectiveBounds = BoundingBox(0f, 0f, pageSize.width, pageSize.height)
            }

            val durationMs = System.currentTimeMillis() - startTime
            cache.putRenderedBitmap(document.id, pageIndex, cacheKey, finalBitmap)

            RenderedPage(
                pageIndex = pageIndex,
                bitmap = finalBitmap,
                scale = scale,
                renderedBounds = effectiveBounds,
                renderDurationMs = durationMs
            ) { releasedBitmap ->
                bitmapPool.release(releasedBitmap)
            }
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            logger.error("PdfBoxRenderer", { "Rendering failed for page $pageIndex" }, t)
            throw PdfEngineError.RenderingFailure(pageIndex, "Render failed: ${t.message}", t)
        }
    }
}
