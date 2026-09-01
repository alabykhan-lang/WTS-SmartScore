package com.wts.smartscore.scanner

import android.content.Context
import android.graphics.BitmapFactory
import com.wts.smartscore.data.ScoreReadingEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Persists corrections as labelled samples; it never retrains on the phone. */
object DigitSampleStore {
    fun record(context: Context, reading: ScoreReadingEntity, correctedScore: Double?) {
        val details = runCatching { JSONArray(reading.digitDetailsJson ?: "[]") }.getOrNull() ?: return
        val digits = correctedScore?.toInt()?.toString()?.padStart(details.length(), '0') ?: return
        val root = File(context.filesDir, "digit-dataset").apply { mkdirs() }
        val labels = File(root, "labels.csv")
        if (!labels.exists()) labels.writeText("sample_id,image,ground_truth,model_prediction,confidence,template,scan_id,reading_id\n")
        for (index in 0 until details.length()) {
            val detail = details.optJSONObject(index) ?: continue
            val source = detail.optString("source_path").takeIf { it.isNotBlank() } ?: continue
            val bitmap = BitmapFactory.decodeFile(source) ?: continue
            val id = UUID.randomUUID().toString()
            val out = File(root, "$id.jpg")
            out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 96, it) }
            bitmap.recycle()
            val prediction = detail.opt("value").takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
            val confidence = detail.optDouble("confidence", 0.0)
            val truth = digits.getOrNull(index)?.toString().orEmpty()
            labels.appendText(listOf(id, out.name, truth, prediction, confidence, reading.sheetId, reading.scanId, reading.id).joinToString(",") { csv(it) } + "\n")
        }
    }

    private fun csv(value: Any?): String = "\"${value?.toString()?.replace("\"", "\"\"") ?: ""}\""
}
