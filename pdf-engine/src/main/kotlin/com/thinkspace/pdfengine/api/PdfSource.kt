package com.thinkspace.pdfengine.api

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.InputStream

/**
 * Encapsulates the origin of a PDF document without leaking implementation-specific file handles.
 */
sealed class PdfSource {
    abstract val password: String?

    data class FromFile(
        val file: File,
        override val password: String? = null
    ) : PdfSource()

    data class FromPath(
        val path: String,
        override val password: String? = null
    ) : PdfSource()

    data class FromUri(
        val uri: Uri,
        val contentResolver: ContentResolver,
        override val password: String? = null
    ) : PdfSource()

    data class FromByteArray(
        val bytes: ByteArray,
        val identifier: String = "memory_doc",
        override val password: String? = null
    ) : PdfSource() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FromByteArray) return false
            return bytes.contentEquals(other.bytes) && identifier == other.identifier && password == other.password
        }
        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + identifier.hashCode()
            result = 31 * result + (password?.hashCode() ?: 0)
            return result
        }
    }

    data class FromStream(
        val streamProvider: () -> InputStream,
        val identifier: String = "stream_doc",
        override val password: String? = null
    ) : PdfSource()
}
