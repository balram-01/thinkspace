package com.thinkspace.pdfengine.model

/**
 * Optical character recognition result element.
 */
data class OcrTextElement(
    val text: String,
    val bounds: BoundingBox,
    val confidence: Float,
    val language: String? = null
)

data class OcrLine(
    val text: String,
    val bounds: BoundingBox,
    val elements: List<OcrTextElement>,
    val confidence: Float
)

data class OcrBlock(
    val text: String,
    val bounds: BoundingBox,
    val lines: List<OcrLine>,
    val confidence: Float
)

/**
 * Full-page OCR recognition payload.
 */
data class OcrResult(
    val pageIndex: Int,
    val fullText: String,
    val bounds: BoundingBox,
    val confidence: Float,
    val blocks: List<OcrBlock> = emptyList(),
    val lines: List<OcrLine> = emptyList(),
    val elements: List<OcrTextElement> = emptyList()
) {
    /**
     * Converts OCR elements into engine-native TextWord objects for seamless layout and search integration.
     */
    fun toTextWords(): List<TextWord> {
        return elements.mapIndexed { idx, elem ->
            TextWord(
                text = elem.text,
                pageIndex = pageIndex,
                bounds = elem.bounds,
                fontSize = elem.bounds.height * 0.8f,
                fontName = "OCR_Recognized",
                fontStyle = FontStyle.REGULAR,
                baseline = elem.bounds.bottom,
                orderIndex = idx
            )
        }
    }
}
