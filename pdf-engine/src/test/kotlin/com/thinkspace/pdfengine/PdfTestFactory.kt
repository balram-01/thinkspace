package com.thinkspace.pdfengine

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object PdfTestFactory {

    /**
     * Creates a standard digital PDF with known words and coordinates.
     */
    fun createDigitalPdf(file: File, pageCount: Int = 1): File {
        val document = PDDocument()
        try {
            for (p in 0 until pageCount) {
                val page = PDPage(PDRectangle.LETTER)
                document.addPage(page)

                PDPageContentStream(document, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 18f)
                    cs.newLineAtOffset(50f, 700f)
                    cs.showText("Document Title for Page ${p + 1}")
                    cs.endText()

                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 12f)
                    cs.newLineAtOffset(50f, 650f)
                    cs.showText("This is a production grade native Kotlin PDF engine.")
                    cs.endText()

                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 12f)
                    cs.newLineAtOffset(50f, 620f)
                    cs.showText("It extracts text with exact bounding boxes and word coordinates.")
                    cs.endText()
                }
            }
            document.save(file)
        } finally {
            document.close()
        }
        return file
    }

    /**
     * Creates a multi-column document (simulating an academic paper).
     */
    fun createMultiColumnPdf(file: File): File {
        val document = PDDocument()
        try {
            val page = PDPage(PDRectangle.LETTER)
            document.addPage(page)

            PDPageContentStream(document, page).use { cs ->
                // Header
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 20f)
                cs.newLineAtOffset(50f, 720f)
                cs.showText("Academic Paper on Document Geometry")
                cs.endText()

                // Left Column (X: 50 to 280)
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 10f)
                cs.newLineAtOffset(50f, 660f)
                cs.showText("Left column paragraph line 1.")
                cs.endText()

                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 10f)
                cs.newLineAtOffset(50f, 640f)
                cs.showText("Left column paragraph line 2.")
                cs.endText()

                // Right Column (X: 330 to 560)
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 10f)
                cs.newLineAtOffset(330f, 660f)
                cs.showText("Right column paragraph line 1.")
                cs.endText()

                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 10f)
                cs.newLineAtOffset(330f, 640f)
                cs.showText("Right column paragraph line 2.")
                cs.endText()
            }
            document.save(file)
        } finally {
            document.close()
        }
        return file
    }

    /**
     * Creates a PDF with rotated pages (0, 90, 180, 270 degrees).
     */
    fun createRotatedPdf(file: File): File {
        val document = PDDocument()
        try {
            val rotations = listOf(0, 90, 180, 270)
            for (rot in rotations) {
                val page = PDPage(PDRectangle.LETTER)
                page.rotation = rot
                document.addPage(page)

                PDPageContentStream(document, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 14f)
                    cs.newLineAtOffset(100f, 500f)
                    cs.showText("Rotated $rot degrees text")
                    cs.endText()
                }
            }
            document.save(file)
        } finally {
            document.close()
        }
        return file
    }

    /**
     * Creates an empty page PDF.
     */
    fun createEmptyPdf(file: File): File {
        val document = PDDocument()
        try {
            document.addPage(PDPage(PDRectangle.LETTER))
            document.save(file)
        } finally {
            document.close()
        }
        return file
    }

    /**
     * Creates an encrypted PDF protected by a user password.
     */
    fun createEncryptedPdf(file: File, userPassword: String): File {
        val document = PDDocument()
        try {
            val page = PDPage(PDRectangle.LETTER)
            document.addPage(page)

            PDPageContentStream(document, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("Secret encrypted document text.")
                cs.endText()
            }

            val ap = AccessPermission()
            val spp = StandardProtectionPolicy("owner_secret", userPassword, ap)
            spp.encryptionKeyLength = 128
            spp.permissions = ap
            document.protect(spp)
            document.save(file)
        } finally {
            document.close()
        }
        return file
    }

    /**
     * Creates a large PDF with 50 pages for benchmark and memory tests.
     */
    fun createLargePdf(file: File, pageCount: Int = 50): File {
        val document = PDDocument()
        try {
            for (i in 0 until pageCount) {
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 11f)
                    cs.newLineAtOffset(50f, 800f)
                    cs.showText("Page ${i + 1} content with multiple sentences for testing large scale indexer.")
                    cs.endText()
                }
            }
            document.save(file)
        } finally {
            document.close()
        }
        return file
    }
}
