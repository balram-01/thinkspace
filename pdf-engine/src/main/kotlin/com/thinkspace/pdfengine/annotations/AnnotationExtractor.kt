package com.thinkspace.pdfengine.annotations

import com.thinkspace.pdfengine.api.PdfDocument
import com.thinkspace.pdfengine.coordinates.PdfPoint
import com.thinkspace.pdfengine.coordinates.PdfRect
import com.thinkspace.pdfengine.logging.PdfLogger
import com.thinkspace.pdfengine.model.Annotation
import com.thinkspace.pdfengine.model.FreehandAnnotation
import com.thinkspace.pdfengine.model.HighlightAnnotation
import com.thinkspace.pdfengine.model.Point
import com.thinkspace.pdfengine.model.Quad
import com.thinkspace.pdfengine.model.StrikethroughAnnotation
import com.thinkspace.pdfengine.model.TextAnnotation
import com.thinkspace.pdfengine.model.UnderlineAnnotation
import com.thinkspace.pdfengine.parser.PdfBoxDocumentWrapper
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts and maps PDF-native annotations into strongly-typed document models.
 */
class AnnotationExtractor(
    private val logger: PdfLogger
) {

    suspend fun extractAnnotations(
        document: PdfDocument,
        pageIndex: Int
    ): List<Annotation> = withContext(Dispatchers.IO) {
        val wrapper = document as? PdfBoxDocumentWrapper ?: return@withContext emptyList()
        val pdPage = try { wrapper.pdDocument.getPage(pageIndex) } catch (_: Throwable) { return@withContext emptyList() }
        val rawAnnotations = pdPage.annotations ?: return@withContext emptyList()

        val pageHandle = document.getPage(pageIndex)
        val mapper = pageHandle.coordinateMapper
        val annotations = mutableListOf<Annotation>()

        for ((idx, raw) in rawAnnotations.withIndex()) {
            val rect = raw.rectangle ?: continue
            val pdfRect = PdfRect(
                left = rect.lowerLeftX,
                bottom = rect.lowerLeftY,
                right = rect.upperRightX,
                top = rect.upperRightY
            )
            val pageBounds = mapper.pdfToPage(pdfRect).toBoundingBox()
            val color = extractColor(raw)
            val author = (raw as? PDAnnotationMarkup)?.titlePopup
            val id = "${document.id}_p${pageIndex}_ann${idx}"

            when (raw.subtype) {
                PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT -> {
                    val quads = extractQuads(raw as? PDAnnotationTextMarkup, mapper)
                    annotations.add(
                        HighlightAnnotation(
                            id = id,
                            documentId = document.id,
                            pageIndex = pageIndex,
                            bounds = pageBounds,
                            quads = quads.ifEmpty { listOf(pageBounds.toQuad()) },
                            color = color ?: 0x55FFFF00,
                            author = author
                        )
                    )
                }
                PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE -> {
                    val quads = extractQuads(raw as? PDAnnotationTextMarkup, mapper)
                    annotations.add(
                        UnderlineAnnotation(
                            id = id,
                            documentId = document.id,
                            pageIndex = pageIndex,
                            bounds = pageBounds,
                            quads = quads.ifEmpty { listOf(pageBounds.toQuad()) },
                            color = color ?: 0xFF0000FF.toInt(),
                            author = author
                        )
                    )
                }
                PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT -> {
                    val quads = extractQuads(raw as? PDAnnotationTextMarkup, mapper)
                    annotations.add(
                        StrikethroughAnnotation(
                            id = id,
                            documentId = document.id,
                            pageIndex = pageIndex,
                            bounds = pageBounds,
                            quads = quads.ifEmpty { listOf(pageBounds.toQuad()) },
                            color = color ?: 0xFFFF0000.toInt(),
                            author = author
                        )
                    )
                }
                PDAnnotationText.SUB_TYPE, "Text" -> {
                    annotations.add(
                        TextAnnotation(
                            id = id,
                            documentId = document.id,
                            pageIndex = pageIndex,
                            bounds = pageBounds,
                            contents = raw.contents ?: "",
                            color = color ?: 0xFFFFA500.toInt(),
                            author = author
                        )
                    )
                }
                "Ink" -> {
                    annotations.add(
                        FreehandAnnotation(
                            id = id,
                            documentId = document.id,
                            pageIndex = pageIndex,
                            bounds = pageBounds,
                            paths = emptyList(),
                            color = color ?: 0xFF000000.toInt(),
                            author = author
                        )
                    )
                }
            }
        }

        annotations
    }

    private fun extractQuads(
        markup: PDAnnotationTextMarkup?,
        mapper: com.thinkspace.pdfengine.coordinates.PdfCoordinateMapper
    ): List<Quad> {
        val quadPoints = markup?.quadPoints ?: return emptyList()
        val quads = mutableListOf<Quad>()
        var i = 0
        while (i + 7 < quadPoints.size) {
            val p1 = mapper.pdfToPage(PdfPoint(quadPoints[i], quadPoints[i + 1])).toModelPoint()
            val p2 = mapper.pdfToPage(PdfPoint(quadPoints[i + 2], quadPoints[i + 3])).toModelPoint()
            val p3 = mapper.pdfToPage(PdfPoint(quadPoints[i + 4], quadPoints[i + 5])).toModelPoint()
            val p4 = mapper.pdfToPage(PdfPoint(quadPoints[i + 6], quadPoints[i + 7])).toModelPoint()
            quads.add(Quad(topLeft = p1, topRight = p2, bottomRight = p4, bottomLeft = p3))
            i += 8
        }
        return quads
    }

    private fun extractColor(annotation: PDAnnotation): Int? {
        val color = annotation.color ?: return null
        val components = color.components
        return when (components.size) {
            1 -> {
                val v = (components[0] * 255).toInt()
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            3 -> {
                val r = (components[0] * 255).toInt()
                val g = (components[1] * 255).toInt()
                val b = (components[2] * 255).toInt()
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            4 -> { // CMYK
                val c = components[0]
                val m = components[1]
                val y = components[2]
                val k = components[3]
                val r = ((1f - c) * (1f - k) * 255).toInt()
                val g = ((1f - m) * (1f - k) * 255).toInt()
                val b = ((1f - y) * (1f - k) * 255).toInt()
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            else -> null
        }
    }
}
