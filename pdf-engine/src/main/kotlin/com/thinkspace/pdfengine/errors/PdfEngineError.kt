package com.thinkspace.pdfengine.errors

/**
 * Sealed hierarchy of explicit domain errors for the PDF Document Engine.
 * Never silently swallows issues; provides diagnostic context.
 */
sealed class PdfEngineError(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    class FileNotFound(val path: String, cause: Throwable? = null) :
        PdfEngineError("PDF file not found at path: $path", cause)

    class PermissionDenied(val path: String, cause: Throwable? = null) :
        PdfEngineError("Permission denied when accessing PDF source: $path", cause)

    class InvalidPdf(val reason: String, cause: Throwable? = null) :
        PdfEngineError("Invalid PDF document: $reason", cause)

    class EncryptedPdf(val isPasswordProtected: Boolean, cause: Throwable? = null) :
        PdfEngineError("Document is encrypted and requires decryption credentials", cause)

    class PasswordRequired(cause: Throwable? = null) :
        PdfEngineError("A valid password is required to open this document", cause)

    class UnsupportedPdf(val feature: String, cause: Throwable? = null) :
        PdfEngineError("PDF contains unsupported feature: $feature", cause)

    class MalformedPdf(val details: String, cause: Throwable? = null) :
        PdfEngineError("Malformed PDF document stream: $details", cause)

    class ExtractionFailure(val pageIndex: Int, val details: String, cause: Throwable? = null) :
        PdfEngineError("Text or image extraction failed on page $pageIndex: $details", cause)

    class RenderingFailure(val pageIndex: Int, val details: String, cause: Throwable? = null) :
        PdfEngineError("Rendering failed for page $pageIndex: $details", cause)

    class LayoutAnalysisFailure(val pageIndex: Int, val details: String, cause: Throwable? = null) :
        PdfEngineError("Layout analysis failed for page $pageIndex: $details", cause)

    class OcrFailure(val pageIndex: Int, val details: String, cause: Throwable? = null) :
        PdfEngineError("OCR recognition failed for page $pageIndex: $details", cause)

    class SearchFailure(val query: String, val details: String, cause: Throwable? = null) :
        PdfEngineError("Search indexing/query failed for '$query': $details", cause)

    class Cancelled(val operation: String) :
        PdfEngineError("Operation '$operation' was cancelled")

    class OutOfMemoryRisk(val estimatedBytes: Long, val availableBytes: Long) :
        PdfEngineError("Operation aborted to prevent OOM. Requested: $estimatedBytes bytes, Available: $availableBytes bytes")

    class DocumentClosed(val documentId: String) :
        PdfEngineError("PDF document '$documentId' is closed and cannot be accessed")
}
