package com.wts.smartscore.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.wts.smartscore.export.DirectoryZipExporter
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class ContinuousSessionReviewActivity : AppCompatActivity() {
    private val exec = Executors.newSingleThreadExecutor()
    private var manifestFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manifestFile = intent.getStringExtra("manifest")?.let(::File)
        val manifest = manifestFile?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 28) }
        root.addView(TextView(this).apply { text = "SESSION COMPLETE"; textSize = 25f })
        if (manifest == null) {
            root.addView(TextView(this).apply { text = "The session was saved, but its summary could not be opened."; setPadding(0, 20, 0, 20) })
            root.addView(Button(this).apply { text = "DONE"; setOnClickListener { finish() } })
            setContentView(root); return
        }

        val mode = manifest.optString("mode")
        val pageCount = manifest.optInt("page_count")
        root.addView(TextView(this).apply { text = "$pageCount PAGES SCANNED"; textSize = 21f; setPadding(0, 12, 0, 18) })
        when (mode) {
            "SCRIPT" -> renderScripts(root, manifest)
            "BROADSHEET" -> renderBroadsheets(root, manifest)
            else -> renderDocuments(root, manifest)
        }
        root.addView(Button(this).apply { text = "DONE"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun renderDocuments(root: LinearLayout, manifest: JSONObject) {
        root.addView(TextView(this).apply {
            text = "All pages were secured as corrected images.\nPDF: ${if (manifest.has("pdf_path")) "READY" else "NOT CREATED"}\nImage package: ${if (manifest.has("images_zip_path")) "READY" else "NOT CREATED"}"
            textSize = 16f; setPadding(0, 0, 0, 18)
        })
        manifest.optString("pdf_path").takeIf { it.isNotBlank() }?.let { path ->
            root.addView(Button(this).apply { text = "SHARE PDF"; setOnClickListener { shareFile(File(path), "application/pdf") } })
        }
    }

    private fun renderScripts(root: LinearLayout, manifest: JSONObject) {
        val scripts = manifest.optJSONArray("scripts")
        root.addView(TextView(this).apply { text = "${manifest.optInt("script_count")} STUDENTS / SCRIPTS DETECTED"; textSize = 19f; setPadding(0, 0, 0, 12) })
        if (scripts != null) for (i in 0 until scripts.length()) {
            val s = scripts.getJSONObject(i)
            val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 10, 10, 12) }
            group.addView(TextView(this).apply {
                val review = if (s.optBoolean("boundary_review_required")) "\n⚠ POSSIBLE BOUNDARY — REVIEW REQUIRED" else ""
                text = "${s.optString("display_name")}\n${s.optInt("page_count")} pages$review"
                textSize = 16f
            })
            group.addView(Button(this).apply {
                text = "REVIEW SCRIPT"
                setOnClickListener { startActivity(Intent(this@ContinuousSessionReviewActivity, ScriptReviewActivity::class.java).putExtra("scriptId", s.optString("script_id"))) }
            })
            root.addView(group)
        }
        root.addView(TextView(this).apply { text = "Uncertain boundaries are not silently split. Use Review Script to correct identity, reorder, move, split, merge, delete or rescan pages."; setPadding(0, 8, 0, 12) })
        root.addView(Button(this).apply { text = "OPEN SAVED SCRIPTS"; setOnClickListener { startActivity(Intent(this@ContinuousSessionReviewActivity, SavedScriptsActivity::class.java)) } })
        root.addView(Button(this).apply { text = "EXPORT ALL SCRIPTS"; setOnClickListener { exportAllScripts() } })
    }

    private fun renderBroadsheets(root: LinearLayout, manifest: JSONObject) {
        val sheets = manifest.optJSONArray("broadsheets")
        root.addView(TextView(this).apply { text = "${manifest.optInt("broadsheet_count")} BROADSHEETS IDENTIFIED"; textSize = 19f; setPadding(0, 0, 0, 12) })
        if (sheets != null) for (i in 0 until sheets.length()) {
            val s = sheets.getJSONObject(i)
            root.addView(TextView(this).apply {
                val exceptions = s.optInt("exceptions")
                text = "${s.optString("sheet_id")}\nSide 1 ${if (s.optBoolean("side_1")) "✓" else "missing"}  •  Side 2 ${if (s.optBoolean("side_2")) "✓" else "missing"}\n${s.optString("status")}${if (exceptions > 0) " • $exceptions score exception(s)" else ""}"
                textSize = 16f; setPadding(12, 12, 12, 16)
            })
            root.addView(Button(this).apply {
                text = "OPEN SCORE REVIEW"
                setOnClickListener { startActivity(Intent(this@ContinuousSessionReviewActivity, BroadsheetReviewActivity::class.java).putExtra("sheetId", s.optString("sheet_id"))) }
            })
        }
        val uncertain = manifest.optJSONArray("uncertain_pages")
        if (uncertain != null && uncertain.length() > 0) root.addView(TextView(this).apply { text = "${uncertain.length()} page(s) need identity review. The high-quality scans were retained even though identification was uncertain."; setPadding(0, 8, 0, 16) })
    }

    private fun exportAllScripts() {
        val rootDir = manifestFile?.parentFile ?: return
        val scriptsDir = File(rootDir, "scripts")
        if (!scriptsDir.isDirectory) { Toast.makeText(this, "No script packages are available", Toast.LENGTH_SHORT).show(); return }
        exec.execute {
            runCatching {
                val zip = File(rootDir, "all-scripts.zip")
                DirectoryZipExporter.export(scriptsDir, zip)
                runOnUiThread { shareFile(zip, "application/zip") }
            }.onFailure { runOnUiThread { Toast.makeText(this, "Unable to export all scripts", Toast.LENGTH_LONG).show() } }
        }
    }

    private fun shareFile(file: File, mime: String) {
        if (!file.exists()) { Toast.makeText(this, "Export file is not available", Toast.LENGTH_SHORT).show(); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share SmartScore export"))
    }

    override fun onDestroy() { super.onDestroy(); exec.shutdown() }
}
