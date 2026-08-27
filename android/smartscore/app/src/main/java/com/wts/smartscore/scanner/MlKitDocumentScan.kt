package com.wts.smartscore.scanner

import android.app.Activity
import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.util.UUID

/** Stable SmartScore-facing result. Consumers do not depend on scanner implementation details. */
data class SmartScanPage(val pageNumber: Int, val imagePath: String)
data class SmartScanResult(
    val scanId: String,
    val pages: List<SmartScanPage>,
    val pdfPath: String?,
    val pageCount: Int
)

object MlKitDocumentScan {
    fun client(pageLimit: Int = 50): GmsDocumentScanner {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(pageLimit)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        return GmsDocumentScanning.getClient(options)
    }

    fun persistResult(context: Context, result: GmsDocumentScanningResult, parentDir: File): SmartScanResult {
        parentDir.mkdirs()
        val scanId = UUID.randomUUID().toString()
        val scanDir = File(parentDir, scanId).apply { mkdirs() }
        val pages = result.pages.orEmpty().mapIndexed { index, page ->
            val out = File(scanDir, "page-${index + 1}.jpg")
            copyUri(context, page.imageUri, out)
            SmartScanPage(index + 1, out.absolutePath)
        }
        val pdfFile = result.pdf?.let { pdf ->
            File(scanDir, "document.pdf").also { copyUri(context, pdf.uri, it) }
        }
        return SmartScanResult(scanId, pages, pdfFile?.absolutePath, pages.size)
    }

    private fun copyUri(context: Context, uri: Uri, out: File) {
        out.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open scanner result URI" }
            out.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
