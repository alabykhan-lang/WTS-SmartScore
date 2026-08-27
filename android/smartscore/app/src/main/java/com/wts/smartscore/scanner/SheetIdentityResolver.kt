package com.wts.smartscore.scanner

import android.graphics.Bitmap
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
    val rawValue: String
)

/** QR is identity evidence only. A missing QR never invalidates the image. */
object SheetIdentityResolver {
    fun resolvePageIdentity(vararg bitmaps: Bitmap): SheetPageIdentity? {
        val options = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        val scanner = BarcodeScanning.getClient(options)
        return try {
            bitmaps.asSequence().flatMap { bitmap ->
                runCatching { Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0))) }.getOrDefault(emptyList()).asSequence()
            }.mapNotNull { code -> parse(code.rawValue ?: "") }.firstOrNull()
        } finally {
            scanner.close()
        }
    }

    /** Legacy API retained for old callers; the returned value is now a page id. */
    fun resolveSideId(vararg bitmaps: Bitmap): String? = resolvePageIdentity(*bitmaps)?.pageId

    private fun parse(raw: String): SheetPageIdentity? {
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
                rawValue = raw
            )
        }
        val match = Regex("(?i)(WTS-[A-Z0-9-]+)-(?:P|S)([0-9]+)").find(raw) ?: return null
        val sheet = match.groupValues[1]
        val page = match.groupValues[0].uppercase().replace(Regex("-S([0-9]+)$"), "-P$1")
        return SheetPageIdentity(sheet, page, match.groupValues[2].toIntOrNull(), null, null, null, null, null, "QR_LEGACY", 0.82, raw)
    }
}
