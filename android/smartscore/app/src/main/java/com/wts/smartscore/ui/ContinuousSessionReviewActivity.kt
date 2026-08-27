package com.wts.smartscore.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

class ContinuousSessionReviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manifestPath = intent.getStringExtra("manifest")
        val manifest = manifestPath?.let { runCatching { JSONObject(File(it).readText()) }.getOrNull() }
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
    }

    private fun renderScripts(root: LinearLayout, manifest: JSONObject) {
        val scripts = manifest.optJSONArray("scripts")
        root.addView(TextView(this).apply { text = "${manifest.optInt("script_count")} STUDENTS / SCRIPTS DETECTED"; textSize = 19f; setPadding(0, 0, 0, 12) })
        if (scripts != null) for (i in 0 until scripts.length()) {
            val s = scripts.getJSONObject(i)
            root.addView(TextView(this).apply {
                val review = if (s.optBoolean("boundary_review_required")) "\n⚠ POSSIBLE BOUNDARY — REVIEW REQUIRED" else ""
                text = "${s.optString("display_name")}\n${s.optInt("page_count")} pages$review"
                textSize = 16f; setPadding(12, 12, 12, 16)
            })
        }
        root.addView(TextView(this).apply { text = "Uncertain boundaries are not silently split. Open Saved Scripts to inspect pages before external AI handoff."; setPadding(0, 8, 0, 12) })
        root.addView(Button(this).apply { text = "OPEN SAVED SCRIPTS"; setOnClickListener { startActivity(Intent(this@ContinuousSessionReviewActivity, SavedScriptsActivity::class.java)) } })
    }

    private fun renderBroadsheets(root: LinearLayout, manifest: JSONObject) {
        val sheets = manifest.optJSONArray("broadsheets")
        root.addView(TextView(this).apply { text = "${manifest.optInt("broadsheet_count")} BROADSHEETS IDENTIFIED"; textSize = 19f; setPadding(0, 0, 0, 12) })
        if (sheets != null) for (i in 0 until sheets.length()) {
            val s = sheets.getJSONObject(i)
            root.addView(TextView(this).apply {
                text = "${s.optString("sheet_id")}\nSide 1 ${if (s.optBoolean("side_1")) "✓" else "missing"}  •  Side 2 ${if (s.optBoolean("side_2")) "✓" else "missing"}\n${s.optString("status")}"
                textSize = 16f; setPadding(12, 12, 12, 16)
            })
        }
        val uncertain = manifest.optJSONArray("uncertain_pages")
        if (uncertain != null && uncertain.length() > 0) root.addView(TextView(this).apply { text = "${uncertain.length()} page(s) need identity review. The scans were retained even though identification was uncertain."; setPadding(0, 8, 0, 16) })
    }
}
