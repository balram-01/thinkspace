package com.thinkspace.pdfengine.storage

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * Handles staging of streaming PDF sources to seekable temporary files.
 */
class TempFileManager(
    private val cacheDirProvider: () -> File
) {
    fun createTempPdfFromStream(
        inputStream: InputStream,
        resourceManager: ResourceManager
    ): File {
        val baseDir = cacheDirProvider()
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        val tempFile = File(baseDir, "pdf_engine_${UUID.randomUUID()}.pdf")
        tempFile.deleteOnExit()

        FileOutputStream(tempFile).use { outputStream ->
            inputStream.copyTo(outputStream, bufferSize = 64 * 1024)
        }

        resourceManager.registerTempFile(tempFile)
        return tempFile
    }

    fun createTempPdfFromBytes(
        bytes: ByteArray,
        resourceManager: ResourceManager
    ): File {
        val baseDir = cacheDirProvider()
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        val tempFile = File(baseDir, "pdf_engine_${UUID.randomUUID()}.pdf")
        tempFile.deleteOnExit()

        tempFile.writeBytes(bytes)
        resourceManager.registerTempFile(tempFile)
        return tempFile
    }
}
