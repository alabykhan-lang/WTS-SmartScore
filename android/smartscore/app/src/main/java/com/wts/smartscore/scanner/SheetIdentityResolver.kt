package com.wts.smartscore.scanner

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject

data class SheetPageIdentity(
    val sheetId: String,
    val pageId: String?,
    val pageNumber: Int?,
    val layoutId: String?,
    val classLabel: String?,
    val subjectGroup: String?,
    val session: String?,
    val term: String?,
    val method: String,
    val confidence: Double,
    val rawValue: String,
    val qrBounds: RectF? = null
)

/** QR is identity evidence only. A missing QR never invalidates the image. */
object SheetIdentityResolver {
    fun resolvePageIdentity(vararg bitmaps: Bitmap): SheetPageIdentity? {
        val options = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        val scanner = BarcodeScanning.getClient(options)
        return try {
            bitmaps.asSequence().flatMap { bitmap ->
                runCatching { Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0))) }.getOrDefault(emptyList()).asSequence()
            }.mapNotNull { code -> parse(code.rawValue ?: "", code.boundingBox?.let { RectF(it) }) }.firstOrNull()
        } finally {
            scanner.close()
        }
    }

    /** Legacy API retained for old callers; the returned value is now a page id. */
    fun resolveSideId(vararg bitmaps: Bitmap): String? = resolvePageIdentity(*bitmaps)?.pageId

    private fun parse(raw: String, qrBounds: RectF? = null): SheetPageIdentity? {
        if (raw.isBlank()) return null
        val json = runCatching { JSONObject(raw) }.getOrNull()
        if (json != null) {
            val sheet = json.optString("sheet_id", json.optString("s")).takeIf { it.isNotBlank() } ?: return null
            val legacyPage = json.optString("side_id").takeIf { it.isNotBlank() }
            val page = json.optString("page_id", json.optString("p")).takeIf { it.isNotBlank() }
                ?: legacyPage?.replace(Regex("-S([0-9]+)$"), "-P$1")
            return SheetPageIdentity(
                sheetId = sheet,
                pageId = page,
                pageNumber = json.optInt("page_number", json.optInt("n", 0)).takeIf { it > 0 },
                layoutId = json.optString("layout_id", json.optString("l")).takeIf { it.isNotBlank() },
                classLabel = json.optString("class", json.optString("c")).takeIf { it.isNotBlank() },
                subjectGroup = json.optString("subject_group", json.optString("u")).takeIf { it.isNotBlank() },
                session = json.optString("session").takeIf { it.isNotBlank() },
                term = json.optString("term", json.optString("t")).takeIf { it.isNotBlank() },
                method = "QR",
                confidence = 0.99,
                rawValue = raw,
                qrBounds = qrBounds
            )
        }
        val pageMatch = Regex("(?i)((?:WTS|SMB)-[A-Z0-9-]+)-(?:P|S)([0-9]+)").find(raw)
        if (pageMatch != null) {
            val sheet = pageMatch.groupValues[1].uppercase()
            val page = "${sheet}-P${pageMatch.groupValues[2]}"
            return SheetPageIdentity(
                sheetId = sheet,
                pageId = page,
                pageNumber = pageMatch.groupValues[2].toIntOrNull(),
                layoutId = null,
                classLabel = null,
                subjectGroup = null,
                session = null,
                term = null,
                method = "QR_LEGACY",
                confidence = 0.82,
                rawValue = raw,
                qrBounds = qrBounds
            )
        }

        // Some locally printed one-page fixtures carry only the deterministic
        // sheet id. That is still useful identity evidence; the repository can
        // resolve it to its sole page without inventing a duplex side.
        val sheetMatch = Regex("(?i)((?:WTS|SMB)-[A-Z0-9-]+)").find(raw) ?: return null
        val sheet = sheetMatch.groupValues[1].uppercase()
        return SheetPageIdentity(
            sheetId = sheet,
            pageId = null,
            pageNumber = null,
            layoutId = null,
            classLabel = null,
            subjectGroup = null,
            session = null,
            term = null,
            method = "QR_SHEET_ID",
            confidence = 0.74,
            rawValue = raw,
            qrBounds = qrBounds
        )
    }
}
