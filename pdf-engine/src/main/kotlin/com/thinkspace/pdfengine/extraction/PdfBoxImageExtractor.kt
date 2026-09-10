package com.thinkspace.pdfengine.extraction

import android.graphics.Bitmap
import com.thinkspace.pdfengine.api.PdfDocument
import com.thinkspace.pdfengine.errors.PdfEngineError
import com.thinkspace.pdfengine.logging.PdfLogger
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.ImageElement
import com.thinkspace.pdfengine.parser.PdfBoxDocumentWrapper
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts embedded PDF image XObjects from page resources.
 */
class PdfBoxImageExtractor(
    private val logger: PdfLogger
) : PdfImageExtractor {

    override suspend fun extractImages(
        document: PdfDocument,
        pageIndex: Int
    ): List<ImageElement> = withContext(Dispatchers.IO) {
        val wrapper = document as? PdfBoxDocumentWrapper
            ?: throw PdfEngineError.ExtractionFailure(pageIndex, "Document is not a PdfBoxDocumentWrapper")

        if (pageIndex < 0 || pageIndex >= document.pageCount) {
            throw PdfEngineError.ExtractionFailure(pageIndex, "Page index out of bounds: $pageIndex")
        }

        val images = mutableListOf<ImageElement>()
        try {
            val pdPage = wrapper.pdDocument.getPage(pageIndex)
            val resources = pdPage.resources ?: return@withContext emptyList()
            val pageHandle = document.getPage(pageIndex)
            val pageSize = pageHandle.size

            var imageIndex = 0
            for (xObjectName in resources.xObjectNames) {
                if (resources.isImageXObject(xObjectName)) {
                    val pdImage = resources.getXObject(xObjectName) as? PDImageXObject ?: continue

                    val width = pdImage.width
                    val height = pdImage.height
                    val suffix = pdImage.suffix?.uppercase() ?: "PNG"
                    val colorSpace = try { pdImage.colorSpace?.name } catch (_: Throwable) { null }
                    val bitsPerComp = try { pdImage.bitsPerComponent } catch (_: Throwable) { 8 }

                    // Extract actual bitmap safely
                    val bitmap: Bitmap? = try {
                        pdImage.image
                    } catch (t: Throwable) {
                        logger.warn("PdfBoxImageExtractor", { "Could not decode embedded image $xObjectName on page $pageIndex" }, t)
                        null
                    }

                    // Page bounds default to full page if content stream transform is unavailable
                    val bounds = BoundingBox(
                        left = 0f,
                        top = 0f,
                        right = pageSize.width,
                        bottom = pageSize.height
                    )

                    images.add(
                        ImageElement(
                            id = "${document.id}_p${pageIndex}_img${imageIndex++}",
                            pageIndex = pageIndex,
                            bounds = bounds,
                            pixelWidth = width,
                            pixelHeight = height,
                            format = suffix,
                            colorSpace = colorSpace,
                            bitsPerComponent = bitsPerComp,
                            bitmap = bitmap
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            logger.warn("PdfBoxImageExtractor", { "Failed scanning image resources on page $pageIndex" }, t)
        }

        images
    }
}
