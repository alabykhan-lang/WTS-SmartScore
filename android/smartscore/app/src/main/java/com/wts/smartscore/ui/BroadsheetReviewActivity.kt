package com.wts.smartscore.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.wts.smartscore.R
import com.wts.smartscore.data.CorrectionEntity
import com.wts.smartscore.data.ScoreReadingEntity
import com.wts.smartscore.data.SmartScoreDatabase
import com.wts.smartscore.export.CsvScoreExporter
import com.wts.smartscore.export.JsonScoreExporter
import com.wts.smartscore.export.XlsxScoreExporter
import com.wts.smartscore.scanner.DigitSampleStore
import com.wts.smartscore.scanner.LocalProcessingQueue
import com.wts.smartscore.scanner.ProcessingTaskTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Score-level review. Individual digit evidence appears only when a score is opened. */
class BroadsheetReviewActivity : AppCompatActivity() {
    private val dao by lazy { SmartScoreDatabase.get(this).dao() }
    private lateinit var root: LinearLayout
    private var sheetId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sheetId = intent.getStringExtra("sheetId") ?: return finish()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
            setBackgroundColor(ContextCompat.getColor(this@BroadsheetReviewActivity, R.color.smartscore_background))
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() {
        lifecycleScope.launch {
            val sheet = withContext(Dispatchers.IO) { dao.broadsheet(sheetId) }
            if (sheet == null) {
                root.removeAllViews()
                root.addView(TextView(this@BroadsheetReviewActivity).apply { text = "Broadsheet not found"; textSize = 20f })
                return@launch
            }
            val pages = withContext(Dispatchers.IO) { dao.pages(sheetId) }
            val readings = withContext(Dispatchers.IO) { dao.readings(sheetId) }
            root.removeAllViews()
            root.addView(TextView(this@BroadsheetReviewActivity).apply { text = "BROADSHEET REVIEW"; textSize = 12f; letterSpacing = 0.12f; setTextColor(ContextCompat.getColor(this@BroadsheetReviewActivity, R.color.smartscore_text_muted)) })
            root.addView(TextView(this@BroadsheetReviewActivity).apply {
                text = listOf(sheet.classLabel, sheet.subject).filter { it.isNotBlank() && it !in listOf("Broadsheet", "Identity pending") }.joinToString(" • ").ifBlank { "Unidentified broadsheet" }
                textSize = 27f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@BroadsheetReviewActivity, R.color.smartscore_text))
                setPadding(0, dp(5), 0, dp(3))
            })
            root.addView(TextView(this@BroadsheetReviewActivity).apply {
                text = "${pages.size} page${if (pages.size == 1) "" else "s"}  •  ${sheet.recognizedCount} score${if (sheet.recognizedCount == 1) "" else "s"} recognised  •  ${sheet.reviewCount} need review"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@BroadsheetReviewActivity, R.color.smartscore_text_muted))
                setPadding(0, 0, 0, dp(14))
            })
            root.addView(statusCard(sheet.reviewStatus))
            if (readings.isEmpty()) {
                root.addView(TextView(this@BroadsheetReviewActivity).apply {
                    text = "No scores are available yet. The page itself is already saved locally, so you can process it again when ready."
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(this@BroadsheetReviewActivity, R.color.smartscore_text_muted))
                    setPadding(0, dp(18), 0, dp(18))
                })
            } else {
                addGrid(readings)
            }
            val actions = LinearLayout(this@BroadsheetReviewActivity).apply { orientation = LinearLayout.VERTICAL }
            actions.addView(MaterialButton(this@BroadsheetReviewActivity).apply { text = "PROCESS AGAIN"; setOnClickListener { processAgain() } })
            val exports = LinearLayout(this@BroadsheetReviewActivity).apply { orientation = LinearLayout.HORIZONTAL }
            exports.addView(exportButton("JSON") { export(readings, "json") }, LinearLayout.LayoutParams(0, -2, 1f))
            exports.addView(exportButton("CSV") { export(readings, "csv") }, LinearLayout.LayoutParams(0, -2, 1f))
            exports.addView(exportButton("EXCEL") { export(readings, "xlsx") }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(exports)
            root.addView(actions)
            root.addView(TextView(this@BroadsheetReviewActivity).apply {
                text = "Tap a score to inspect the full crop and each digit's confidence."
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@BroadsheetReviewActivity, R.color.smartscore_text_muted))
                setPadding(0, dp(12), 0, 0)
            })
        }
    }

    private fun statusCard(state: String): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        strokeWidth = 1
        strokeColor = ContextCompat.getColor(this@BroadsheetReviewActivity, R.color.smartscore_border)
        setCardBackgroundColor(ContextCompat.getColor(this@BroadsheetReviewActivity, R.color.smartscore_surface))
        setContentPadding(dp(14), dp(12), dp(14), dp(12))
        addView(TextView(this@BroadsheetReviewActivity).apply {
            text = when (state) {
                "SCANNED" -> "Saved locally — recognition has not started"
                "PROCESSING" -> "Processing locally — you can leave this screen"
                "READY" -> "Ready"
                "REVIEW_REQUIRED" -> "Ready with a few scores to review"
                "UNIDENTIFIED" -> "Identity needs attention — page retained"
                "FAILED" -> "Processing failed — page retained for another attempt"
                else -> "Saved locally"
            }
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@BroadsheetReviewActivity, R.color.smartscore_text))
        })
    }

    private fun addGrid(rows: List<ScoreReadingEntity>) {
        val assessments = rows.map { it.assessmentId }.distinct()
        val table = TableLayout(this).apply { isStretchAllColumns = false; setPadding(0, dp(12), 0, dp(12)) }
        val header = TableRow(this).apply { setBackgroundColor(ContextCompat.getColor(this@BroadsheetReviewActivity, R.color.smartscore_primary)) }
        header.addView(cell("Student", 190, true, Color.WHITE))
        assessments.forEach { header.addView(cell(label(it), 92, true, Color.WHITE)) }
        table.addView(header)
        rows.groupBy { it.studentId }.forEach { (_, studentRows) ->
            val byAssessment = studentRows.associateBy { it.assessmentId }
            val row = TableRow(this)
            row.addView(cell(studentRows.first().studentName, 190, false, ContextCompat.getColor(this, R.color.smartscore_text)))
            assessments.forEach { assessment -> row.addView(scoreCell(byAssessment[assessment])) }
            table.addView(row)
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(table) })
    }

    private fun cell(text: String, widthDp: Int, header: Boolean, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = if (header) 12f else 13f
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(11), dp(10), dp(11))
        minWidth = dp(widthDp)
        if (!header) setBackgroundColor(ContextCompat.getColor(this@BroadsheetReviewActivity, R.color.smartscore_surface))
    }

    private fun scoreCell(reading: ScoreReadingEntity?): TextView = cell(displayValue(reading), 92, false, ContextCompat.getColor(this, R.color.smartscore_text)).apply {
        gravity = Gravity.CENTER
        setOnClickListener { reading?.let(::showScoreEvidence) }
        when (reading?.state) {
            "REVIEW_REQUIRED", "DOUBTFUL", "MISALIGNED" -> setTextColor(Color.rgb(181, 116, 0))
            "INVALID", "UNREADABLE" -> setTextColor(Color.rgb(190, 50, 50))
            "MANUALLY_CORRECTED" -> setTextColor(Color.rgb(31, 114, 76))
        }
    }

    private fun displayValue(reading: ScoreReadingEntity?): String {
        if (reading == null) return "—"
        if (reading.state == "BLANK") return "—"
        if (reading.state == "MANUALLY_CORRECTED") return reading.reviewedValue?.toInt()?.toString() ?: "?"
        return reading.recognizedText?.takeIf { it.isNotBlank() } ?: reading.reviewedValue?.toInt()?.toString() ?: reading.rawValue?.toInt()?.toString() ?: "??"
    }

    private fun showScoreEvidence(reading: ScoreReadingEntity) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), 0) }
        reading.cropPath?.let { path ->
            box.addView(ImageView(this).apply { setImageBitmap(BitmapFactory.decodeFile(path)); adjustViewBounds = true; maxHeight = dp(220) }, LinearLayout.LayoutParams(-1, dp(220)))
        }
        box.addView(TextView(this).apply {
            text = "${reading.studentName}\n${label(reading.assessmentId)} / ${reading.maximum.toInt()}\nScore shown: ${displayValue(reading)}\nOverall confidence: ${"%.0f".format(reading.confidence * 100)}%"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(10))
        })
        val details = runCatching { JSONArray(reading.digitDetailsJson ?: "[]") }.getOrDefault(JSONArray())
        if (details.length() > 0) {
            box.addView(TextView(this).apply { text = "DIGITS"; textSize = 12f; letterSpacing = 0.1f; setTextColor(ContextCompat.getColor(this@BroadsheetReviewActivity, R.color.smartscore_text_muted)) })
            val digitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until details.length()) {
                val detail = details.optJSONObject(i) ?: continue
                val digit = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(5), dp(5), dp(5), dp(5)) }
                val cropPath = detail.optString("preprocessed_path").takeIf { it.isNotBlank() } ?: detail.optString("source_path")
                if (cropPath.isNotBlank()) digit.addView(ImageView(this).apply { setImageBitmap(BitmapFactory.decodeFile(cropPath)); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(68), dp(68)))
                val value = if (detail.isNull("value")) "?" else detail.optInt("value").toString()
                digit.addView(TextView(this).apply { text = "$value\n${"%.0f".format(detail.optDouble("confidence", 0.0) * 100)}%"; gravity = Gravity.CENTER; textSize = 12f })
                digitRow.addView(digit)
            }
            box.addView(HorizontalScrollView(this).apply { addView(digitRow) })
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Correct score (leave blank for unreadable)"
            setText(reading.reviewedValue?.toInt()?.toString() ?: reading.rawValue?.toInt()?.toString().orEmpty())
        }
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Review score")
            .setView(box)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SAVE") { _, _ ->
                val value = input.text.toString().toDoubleOrNull()
                if (value != null && (value < 0 || value > reading.maximum)) {
                    Toast.makeText(this, "Score must be between 0 and ${reading.maximum.toInt()}", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val now = System.currentTimeMillis()
                    dao.correctReading(reading.id, value, now)
                    dao.saveCorrection(CorrectionEntity(UUID.randomUUID().toString(), reading.id, reading.reviewedValue ?: reading.rawValue, value, "Manual score correction", now))
                    withContext(Dispatchers.IO) { DigitSampleStore.record(this@BroadsheetReviewActivity, reading, value) }
                    refreshSheetSummary()
                    load()
                }
            }.show()
    }

    private fun processAgain() {
        lifecycleScope.launch {
            val pages = withContext(Dispatchers.IO) { dao.pages(sheetId) }
            pages.forEach { page ->
                dao.saveSide(page.copy(pageState = "SCANNED"))
                val payload = org.json.JSONObject().put("page_id", page.sideId)
                LocalProcessingQueue.enqueue(this@BroadsheetReviewActivity, ProcessingTaskTypes.IDENTIFY_DOCUMENT, sheetId, payload)
                LocalProcessingQueue.enqueue(this@BroadsheetReviewActivity, ProcessingTaskTypes.REGISTER_TEMPLATE, sheetId, payload)
                LocalProcessingQueue.enqueue(this@BroadsheetReviewActivity, ProcessingTaskTypes.READ_SCORES, sheetId, payload)
            }
            LocalProcessingQueue.schedule(this@BroadsheetReviewActivity)
            Toast.makeText(this@BroadsheetReviewActivity, "Processing queued", Toast.LENGTH_SHORT).show()
            load()
        }
    }

    private suspend fun refreshSheetSummary() = withContext(Dispatchers.IO) {
        val sheet = dao.broadsheet(sheetId) ?: return@withContext
        val readings = dao.readings(sheetId)
        val review = readings.count { it.state in setOf("DOUBTFUL", "REVIEW_REQUIRED", "MISALIGNED", "INVALID", "UNREADABLE") }
        val state = when {
            readings.isEmpty() -> sheet.reviewStatus
            review > 0 -> "REVIEW_REQUIRED"
            else -> "READY"
        }
        dao.saveBroadsheet(sheet.copy(reviewStatus = state, recognizedCount = readings.count { it.rawValue != null || it.reviewedValue != null }, reviewCount = review, lastUpdatedAt = System.currentTimeMillis()))
    }

    private fun label(id: String): String = id.replace('_', ' ').uppercase()

    private fun export(rows: List<ScoreReadingEntity>, type: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dir = File(filesDir, "exports").apply { mkdirs() }
            val file = when (type) {
                "json" -> File(dir, "$sheetId.json").also { JsonScoreExporter.export(it, sheetId, rows) }
                "csv" -> File(dir, "$sheetId.csv").also { CsvScoreExporter.export(it, rows) }
                else -> File(dir, "$sheetId.xlsx").also { XlsxScoreExporter.export(it, sheetId, rows) }
            }
            runOnUiThread { Toast.makeText(this@BroadsheetReviewActivity, "Saved ${file.name}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun exportButton(label: String, click: () -> Unit) = androidx.appcompat.widget.AppCompatButton(this).apply { text = label; setOnClickListener { click() } }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
