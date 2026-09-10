package com.thinkspace.pdfengine.model

enum class ProcessingStage {
    OPENING,
    INSPECTING,
    CLASSIFYING,
    TEXT_EXTRACTION,
    IMAGE_EXTRACTION,
    LAYOUT_ANALYSIS,
    OCR,
    INDEXING,
    COMPLETED,
    FAILED
}

data class ProcessingProgress(
    val currentPage: Int,
    val totalPages: Int,
    val currentStage: ProcessingStage,
    val percentage: Float
)

data class PageProcessingStatus(
    val pageIndex: Int,
    val pageType: PageType,
    val wordCount: Int,
    val imageCount: Int,
    val ocrApplied: Boolean,
    val processingTimeMs: Long,
    val error: String? = null
)

data class ProcessingResult(
    val documentId: String,
    val totalPages: Int,
    val successfulPages: Int,
    val pageStatuses: List<PageProcessingStatus>,
    val totalDurationMs: Long,
    val isFullyIndexed: Boolean
)
