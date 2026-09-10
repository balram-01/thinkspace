package com.thinkspace.pdfengine.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.thinkspace.pdfengine.api.PdfPage
import com.thinkspace.pdfengine.coordinates.PageRect
import com.thinkspace.pdfengine.coordinates.RenderRect
import com.thinkspace.pdfengine.errors.PdfEngineError
import com.thinkspace.pdfengine.logging.PdfLogger
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.OcrBlock
import com.thinkspace.pdfengine.model.OcrLine
import com.thinkspace.pdfengine.model.OcrResult
import com.thinkspace.pdfengine.model.OcrTextElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device, privacy-preserving OCR engine powered by Google ML Kit.
 * Transforms recognized pixel coordinates to normalized page points.
 */
class MlKitOcrEngine(
    private val logger: PdfLogger
) : OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override val isAvailable: Boolean = true

    override suspend fun recognize(
        page: PdfPage,
        image: Bitmap
    ): OcrResult = withContext(Dispatchers.Default) {
        val pageBounds = BoundingBox(0f, 0f, page.size.width, page.size.height)
        if (image.isRecycled) {
            throw PdfEngineError.OcrFailure(page.pageIndex, "Cannot perform OCR on recycled bitmap")
        }

        try {
            val inputImage = InputImage.fromBitmap(image, 0)
            val visionText = Tasks.await(recognizer.process(inputImage))

            val scaleX = if (page.size.width > 0f) image.width.toFloat() / page.size.width else 1f
            val scaleY = if (page.size.height > 0f) image.height.toFloat() / page.size.height else 1f

            val allElements = mutableListOf<OcrTextElement>()
            val allLines = mutableListOf<OcrLine>()
            val allBlocks = mutableListOf<OcrBlock>()

            for (block in visionText.textBlocks) {
                val blockBox = block.boundingBox
                val normBlockBounds = if (blockBox != null) {
                    BoundingBox(
                        left = blockBox.left / scaleX,
                        top = blockBox.top / scaleY,
                        right = blockBox.right / scaleX,
                        bottom = blockBox.bottom / scaleY
                    )
                } else pageBounds

                val blockLines = mutableListOf<OcrLine>()

                for (line in block.lines) {
                    val lineBox = line.boundingBox
                    val normLineBounds = if (lineBox != null) {
                        BoundingBox(
                            left = lineBox.left / scaleX,
                            top = lineBox.top / scaleY,
                            right = lineBox.right / scaleX,
                            bottom = lineBox.bottom / scaleY
                        )
                    } else normBlockBounds

                    val lineElements = mutableListOf<OcrTextElement>()

                    for (element in line.elements) {
                        val elemBox = element.boundingBox
                        val normElemBounds = if (elemBox != null) {
                            BoundingBox(
                                left = elemBox.left / scaleX,
                                top = elemBox.top / scaleY,
                                right = elemBox.right / scaleX,
                                bottom = elemBox.bottom / scaleY
                            )
                        } else normLineBounds

                        val ocrElement = OcrTextElement(
                            text = element.text,
                            bounds = normElemBounds,
                            confidence = element.confidence ?: 0.85f,
                            language = element.recognizedLanguage
                        )
                        lineElements.add(ocrElement)
                        allElements.add(ocrElement)
                    }

                    val ocrLine = OcrLine(
                        text = line.text,
                        bounds = normLineBounds,
                        elements = lineElements,
                        confidence = line.confidence ?: 0.85f
                    )
                    blockLines.add(ocrLine)
                    allLines.add(ocrLine)
                }

                allBlocks.add(
                    OcrBlock(
                        text = block.text,
                        bounds = normBlockBounds,
                        lines = blockLines,
                        confidence = 0.85f
                    )
                )
            }

            OcrResult(
                pageIndex = page.pageIndex,
                fullText = visionText.text,
                bounds = pageBounds,
                confidence = if (allElements.isNotEmpty()) allElements.map { it.confidence }.average().toFloat() else 0f,
                blocks = allBlocks,
                lines = allLines,
                elements = allElements
            )
        } catch (t: Throwable) {
            logger.error("MlKitOcrEngine", { "OCR failed for page ${page.pageIndex}" }, t)
            throw PdfEngineError.OcrFailure(page.pageIndex, "ML Kit OCR failed: ${t.message}", t)
        }
    }
}
