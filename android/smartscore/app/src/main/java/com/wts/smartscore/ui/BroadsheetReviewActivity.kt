package com.wts.smartscore.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wts.smartscore.data.CorrectionEntity
import com.wts.smartscore.data.ScoreReadingEntity
import com.wts.smartscore.data.SmartScoreDatabase
import com.wts.smartscore.export.CsvScoreExporter
import com.wts.smartscore.export.JsonScoreExporter
import com.wts.smartscore.export.XlsxScoreExporter
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Compact broadsheet-style grid; only cells that need attention receive emphasis. */
class BroadsheetReviewActivity : AppCompatActivity() {
    private val dao by lazy { SmartScoreDatabase.get(this).dao() }
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private var sheetId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sheetId = intent.getStringExtra("sheetId") ?: return finish()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 18, 16, 22) }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() {
        lifecycleScope.launch {
            root.removeAllViews()
            val sheet = dao.broadsheet(sheetId) ?: run {
                root.addView(TextView(this@BroadsheetReviewActivity).apply { text = "Broadsheet was not found" })
                return@launch
            }
            val pages = dao.pages(sheetId)
            val rows = dao.readings(sheetId)
            root.addView(TextView(this@BroadsheetReviewActivity).apply { text = "${sheet.classLabel}\n${sheet.subject}"; textSize = 23f; setTextColor(Color.rgb(16, 36, 62)) })
            root.addView(TextView(this@BroadsheetReviewActivity).apply {
                val expected = if (sheet.expectedPageCount > 0) "${sheet.expectedPageCount} expected" else "dynamic page count"
                text = "${sheet.reviewStatus}  •  ${pages.size} page(s) captured  •  $expected  •  ${sheet.layoutFamily}"
                setTextColor(Color.rgb(83, 101, 121)); setPadding(0, 6, 0, 16)
            })
            if (rows.isEmpty()) root.addView(TextView(this@BroadsheetReviewActivity).apply { text = "No score readings are available yet."; setPadding(0, 18, 0, 18) })
            else addGrid(rows)
            val exports = LinearLayout(this@BroadsheetReviewActivity).apply { orientation = LinearLayout.HORIZONTAL }
            exports.addView(exportButton("JSON") { export(rows, "json") }, LinearLayout.LayoutParams(0, -2, 1f))
            exports.addView(exportButton("CSV") { export(rows, "csv") }, LinearLayout.LayoutParams(0, -2, 1f))
            exports.addView(exportButton("EXCEL") { export(rows, "xlsx") }, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(exports)
            root.addView(TextView(this@BroadsheetReviewActivity).apply { text = "Confirmed values stay quiet. Tap a value to inspect its source crop, confidence and correction history."; textSize = 12f; setTextColor(Color.rgb(83, 101, 121)); setPadding(0, 12, 0, 0) })
        }
    }

    private fun addGrid(rows: List<ScoreReadingEntity>) {
        val assessments = rows.map { it.assessmentId }.distinct()
        val table = TableLayout(this).apply { isStretchAllColumns = false; setPadding(0, 4, 0, 12) }
        val header = TableRow(this).apply { setBackgroundColor(Color.rgb(16, 36, 62)) }
        header.addView(cell("Student Name", 190, true, Color.WHITE))
        assessments.forEach { header.addView(cell(label(it), 92, true, Color.WHITE)) }
        table.addView(header)
        rows.groupBy { it.studentId }.forEach { (_, studentRows) ->
            val byAssessment = studentRows.associateBy { it.assessmentId }
            val row = TableRow(this)
            row.addView(cell(studentRows.first().studentName, 190, false, Color.rgb(16, 36, 62)))
            assessments.forEach { assessment ->
                val reading = byAssessment[assessment]
                row.addView(scoreCell(reading))
            }
            table.addView(row)
        }
        root.addView(HorizontalScrollView(this).apply { addView(table) })
    }

    private fun cell(text: String, widthDp: Int, header: Boolean, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = if (header) 12f else 13f
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(10, 10, 10, 10)
        minWidth = dp(widthDp)
        if (!header) setBackgroundColor(Color.rgb(248, 250, 252))
    }

    private fun scoreCell(reading: ScoreReadingEntity?): TextView = cell(displayValue(reading), 92, false, Color.rgb(16, 36, 62)).apply {
        gravity = Gravity.CENTER
        setOnClickListener { reading?.let(::edit) }
        when (reading?.state) {
            "REVIEW_REQUIRED" -> setTextColor(Color.rgb(181, 116, 0))
            "INVALID", "UNREADABLE" -> setTextColor(Color.rgb(190, 50, 50))
            "MANUALLY_CORRECTED" -> setTextColor(Color.rgb(31, 114, 76))
        }
    }

    private fun displayValue(reading: ScoreReadingEntity?): String {
        if (reading == null) return "—"
        val value = reading.reviewedValue ?: reading.rawValue
        return when (reading.state) {
            "BLANK" -> "—"
            "INVALID" -> "${value?.toInt() ?: "?"} !"
            "REVIEW_REQUIRED" -> "${value?.toInt() ?: "?"} ?"
            "UNREADABLE" -> "?"
            else -> value?.toInt()?.toString() ?: "?"
        }
    }

    private fun label(id: String): String = id.replace('_', ' ').uppercase()

    private fun edit(reading: ScoreReadingEntity) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 0) }
        reading.cropPath?.let { path ->
            box.addView(android.widget.ImageView(this).apply { setImageBitmap(BitmapFactory.decodeFile(path)); adjustViewBounds = true; maxHeight = dp(220) })
        }
        box.addView(TextView(this).apply { text = "${reading.studentName}\n${label(reading.assessmentId)} / ${reading.maximum.toInt()}\nDetected: ${reading.rawValue ?: "—"}  •  confidence ${"%.2f".format(reading.confidence)}\nStatus: ${reading.state}"; setPadding(0, 8, 0, 8) })
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(reading.reviewedValue?.toInt()?.toString() ?: reading.rawValue?.toInt()?.toString().orEmpty()); hint = "Correct score (leave blank for unreadable)" }
        box.addView(input)
        AlertDialog.Builder(this).setTitle("Review score").setView(box).setPositiveButton("SAVE") { _, _ ->
            val value = input.text.toString().toDoubleOrNull()
            lifecycleScope.launch {
                dao.correctReading(reading.id, value, System.currentTimeMillis())
                dao.saveCorrection(CorrectionEntity(UUID.randomUUID().toString(), reading.id, reading.reviewedValue ?: reading.rawValue, value, "Manual review", System.currentTimeMillis()))
                load()
            }
        }.setNegativeButton("CANCEL", null).show()
    }

    private fun export(rows: List<ScoreReadingEntity>, type: String) {
        exec.execute {
            val dir = File(filesDir, "exports").apply { mkdirs() }
            val file = when (type) {
                "json" -> File(dir, "$sheetId.json").also { JsonScoreExporter.export(it, sheetId, rows) }
                "csv" -> File(dir, "$sheetId.csv").also { CsvScoreExporter.export(it, rows) }
                else -> File(dir, "$sheetId.xlsx").also { XlsxScoreExporter.export(it, sheetId, rows) }
            }
            runOnUiThread { Toast.makeText(this, "Saved ${file.name}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun exportButton(label: String, click: () -> Unit) = androidx.appcompat.widget.AppCompatButton(this).apply { text = label; setOnClickListener { click() } }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() { super.onDestroy(); exec.shutdown() }
}
