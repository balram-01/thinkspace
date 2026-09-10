package com.thinkspace.pdfengine.parser

import android.content.Context
import com.thinkspace.pdfengine.api.DefaultPdfPage
import com.thinkspace.pdfengine.api.PdfDocument
import com.thinkspace.pdfengine.api.PdfPage
import com.thinkspace.pdfengine.api.PdfSource
import com.thinkspace.pdfengine.coordinates.PdfCoordinateMapper
import com.thinkspace.pdfengine.coordinates.PdfRect
import com.thinkspace.pdfengine.errors.PdfEngineError
import com.thinkspace.pdfengine.logging.PdfLogger
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.DocumentMetadata
import com.thinkspace.pdfengine.model.PageMetadata
import com.thinkspace.pdfengine.model.PageSize
import com.thinkspace.pdfengine.model.RotationAngle
import com.thinkspace.pdfengine.storage.ResourceManager
import com.thinkspace.pdfengine.storage.TempFileManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concrete parser backed by Apache PDFBox for Android (Apache License 2.0).
 */
class DefaultPdfParser(
    private val context: Context?,
    private val tempFileManager: TempFileManager,
    private val logger: PdfLogger
) : PdfParser {

    init {
        context?.let {
            if (!PDFBoxResourceLoader.isReady()) {
                try {
                    PDFBoxResourceLoader.init(it.applicationContext)
                } catch (e: Throwable) {
                    logger.warn("DefaultPdfParser", { "PDFBoxResourceLoader initialization warning" }, e)
                }
            }
        }
    }

    override suspend fun open(
        source: PdfSource,
        resourceManager: ResourceManager
    ): PdfDocument = withContext(Dispatchers.IO) {
        val documentId = UUID.randomUUID().toString()
        val fileToOpen = resolveFile(source, resourceManager)

        val pdDocument: PDDocument = try {
            if (source.password != null) {
                PDDocument.load(fileToOpen, source.password)
            } else {
                PDDocument.load(fileToOpen)
            }
        } catch (fnf: FileNotFoundException) {
            throw PdfEngineError.FileNotFound(fileToOpen.absolutePath, fnf)
        } catch (se: SecurityException) {
            throw PdfEngineError.PermissionDenied(fileToOpen.absolutePath, se)
        } catch (ioe: IOException) {
            val msg = ioe.message ?: ""
            if (msg.contains("encrypted", ignoreCase = true) || msg.contains("password", ignoreCase = true)) {
                throw PdfEngineError.PasswordRequired(ioe)
            }
            throw PdfEngineError.MalformedPdf(msg, ioe)
        } catch (e: Throwable) {
            throw PdfEngineError.InvalidPdf("Failed to load PDF: ${e.message}", e)
        }

        if (pdDocument.isEncrypted && !pdDocument.currentAccessPermission.canExtractContent()) {
            pdDocument.close()
            throw PdfEngineError.EncryptedPdf(isPasswordProtected = true)
        }

        resourceManager.registerCloseable {
            try {
                pdDocument.close()
            } catch (t: Throwable) {
                logger.warn("DefaultPdfParser", { "Error closing PDDocument" }, t)
            }
        }

        val info = pdDocument.documentInformation
        val docMetadata = DocumentMetadata(
            title = info?.title,
            author = info?.author,
            subject = info?.subject,
            keywords = info?.keywords?.split(",")?.map { it.trim() } ?: emptyList(),
            creator = info?.creator,
            producer = info?.producer,
            creationDate = info?.creationDate?.time?.toString(),
            modificationDate = info?.modificationDate?.time?.toString(),
            pageCount = pdDocument.numberOfPages,
            isEncrypted = pdDocument.isEncrypted,
            pdfVersion = pdDocument.version.toString(),
            fileSizeInBytes = fileToOpen.length()
        )

        PdfBoxDocumentWrapper(
            id = documentId,
            pdDocument = pdDocument,
            metadata = docMetadata,
            resourceManager = resourceManager,
            logger = logger
        )
    }

    private fun resolveFile(source: PdfSource, resourceManager: ResourceManager): File {
        return when (source) {
            is PdfSource.FromFile -> {
                if (!source.file.exists()) {
                    throw PdfEngineError.FileNotFound(source.file.absolutePath)
                }
                source.file
            }
            is PdfSource.FromPath -> {
                val file = File(source.path)
                if (!file.exists()) {
                    throw PdfEngineError.FileNotFound(source.path)
                }
                file
            }
            is PdfSource.FromUri -> {
                val inputStream = source.contentResolver.openInputStream(source.uri)
                    ?: throw PdfEngineError.FileNotFound("Cannot open stream for URI: ${source.uri}")
                inputStream.use { stream ->
                    tempFileManager.createTempPdfFromStream(stream, resourceManager)
                }
            }
            is PdfSource.FromByteArray -> {
                tempFileManager.createTempPdfFromBytes(source.bytes, resourceManager)
            }
            is PdfSource.FromStream -> {
                source.streamProvider().use { stream ->
                    tempFileManager.createTempPdfFromStream(stream, resourceManager)
                }
            }
        }
    }
}

/**
 * Concrete PdfDocument wrapping a native PDFBox PDDocument handle.
 */
class PdfBoxDocumentWrapper(
    override val id: String,
    val pdDocument: PDDocument,
    override val metadata: DocumentMetadata,
    private val resourceManager: ResourceManager,
    private val logger: PdfLogger
) : PdfDocument {

    private val isClosedFlag = AtomicBoolean(false)
    override val isOpen: Boolean get() = !isClosedFlag.get()
    override val pageCount: Int get() = pdDocument.numberOfPages

    private val pageCache = mutableMapOf<Int, PdfPage>()

    override fun getPage(pageIndex: Int): PdfPage {
        ensureOpen()
        if (pageIndex < 0 || pageIndex >= pageCount) {
            throw IllegalArgumentException("Page index $pageIndex is out of range (0 until $pageCount)")
        }

        return synchronized(pageCache) {
            pageCache.getOrPut(pageIndex) {
                val pdPage = pdDocument.getPage(pageIndex)
                val cropBox = pdPage.cropBox ?: pdPage.mediaBox ?: PDRectangle(0f, 0f, 612f, 792f)
                val mediaBox = pdPage.mediaBox ?: cropBox
                val rotation = RotationAngle.fromDegrees(pdPage.rotation)

                val cropPdfRect = PdfRect(
                    left = cropBox.lowerLeftX,
                    bottom = cropBox.lowerLeftY,
                    right = cropBox.upperRightX,
                    top = cropBox.upperRightY
                )
                val mediaPdfRect = PdfRect(
                    left = mediaBox.lowerLeftX,
                    bottom = mediaBox.lowerLeftY,
                    right = mediaBox.upperRightX,
                    top = mediaBox.upperRightY
                )

                val coordinateMapper = PdfCoordinateMapper(cropPdfRect, rotation)

                val pageMeta = PageMetadata(
                    pageIndex = pageIndex,
                    mediaBox = BoundingBox(mediaPdfRect.left, mediaPdfRect.bottom, mediaPdfRect.right, mediaPdfRect.top),
                    cropBox = BoundingBox(cropPdfRect.left, cropPdfRect.bottom, cropPdfRect.right, cropPdfRect.top),
                    rotation = rotation,
                    effectiveSize = coordinateMapper.pageSize
                )

                DefaultPdfPage(
                    pageIndex = pageIndex,
                    documentId = id,
                    metadata = pageMeta,
                    size = coordinateMapper.pageSize,
                    coordinateMapper = coordinateMapper
                )
            }
        }
    }

    private fun ensureOpen() {
        if (isClosedFlag.get()) {
            throw PdfEngineError.DocumentClosed(id)
        }
    }

    override fun close() {
        if (isClosedFlag.compareAndSet(false, true)) {
            logger.debug("PdfBoxDocumentWrapper") { "Closing PDF document $id" }
            pageCache.clear()
            resourceManager.close()
        }
    }
}
