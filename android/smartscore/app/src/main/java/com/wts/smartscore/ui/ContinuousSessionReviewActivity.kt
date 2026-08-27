package com.wts.smartscore.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.wts.smartscore.export.DirectoryZipExporter
import com.wts.smartscore.scanner.ContinuousSessionProcessor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Review is deliberately after acquisition; it never interrupts the capture pile. */
class ContinuousSessionReviewActivity : AppCompatActivity() {
    private val exec = Executors.newSingleThreadExecutor()
    private var manifestFile: File? = null
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manifestFile = intent.getStringExtra("manifest")?.let(::File)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 22, 18, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    private fun readManifest(): JSONObject? = manifestFile?.takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    private fun render() {
        root.removeAllViews()
        val manifest = readManifest()
        if (manifest == null) {
            root.addView(TextView(this).apply { text = "SESSION SAVED\nThe session summary could not be opened."; textSize = 22f })
            root.addView(Button(this).apply { text = "DONE"; setOnClickListener { finish() } })
            return
        }
        val mode = manifest.optString("mode")
        val pageCount = manifest.optInt("page_count")
        root.addView(TextView(this).apply { text = "SESSION COMPLETE"; textSize = 25f; setTextColor(0xFF10243E.toInt()) })
        root.addView(TextView(this).apply { text = "$pageCount PAGE${if (pageCount == 1) "" else "S"} SCANNED"; textSize = 20f; setPadding(0, 8, 0, 4) })
        root.addView(TextView(this).apply { text = "Review is optional. The original and corrected page images remain saved."; setTextColor(0xFF536579.toInt()); setPadding(0, 0, 0, 14) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply { text = "ADD / RESCAN"; setOnClickListener { addMorePages(manifest) } }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply { text = "PROCESS"; setOnClickListener { reprocess(manifest) } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)

        root.addView(TextView(this).apply { text = "CAPTURED PAGES"; textSize = 17f; setPadding(0, 20, 0, 8) })
        val pages = manifest.optJSONArray("pages") ?: JSONArray()
        for (i in 0 until pages.length()) renderPageCard(pages.optJSONObject(i) ?: continue, i, pages.length())

        when (mode) {
            ContinuousSessionProcessor.MODE_SCRIPT -> renderScripts(manifest)
            ContinuousSessionProcessor.MODE_BROADSHEET -> renderBroadsheets(manifest)
            else -> renderDocuments(manifest)
        }
        root.addView(Button(this).apply { text = "DONE"; setOnClickListener { finish() } })
    }

    private fun renderPageCard(page: JSONObject, index: Int, total: Int) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 10, 10, 12); setBackgroundColor(0xFFF2F5F8.toInt()) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val image = ImageView(this).apply {
            val path = page.optString("corrected_path").ifBlank { page.optString("original_path") }
            setImageBitmap(BitmapFactory.decodeFile(path))
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        row.addView(image, LinearLayout.LayoutParams(104, 124))
        row.addView(TextView(this).apply {
            text = "Page ${index + 1}\n${page.optString("classification", "UNCERTAIN")}\n${page.optString("identity_method", "")}".trim()
            textSize = 14f; setPadding(12, 0, 4, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(action("INSPECT") { inspect(page) }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(action("ROTATE") { rotatePage(index) }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(action("CROP") { showCropDialog(index) }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(actions)
        val actions2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions2.addView(action("UP") { movePage(index, -1) }, LinearLayout.LayoutParams(0, -2, 1f).apply { isEnabled = index > 0 })
        actions2.addView(action("DOWN") { movePage(index, 1) }, LinearLayout.LayoutParams(0, -2, 1f).apply { isEnabled = index < total - 1 })
        actions2.addView(action("DELETE") { deletePage(index) }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(actions2)
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
    }

    private fun action(label: String, click: () -> Unit) = Button(this).apply { text = label; textSize = 11f; setOnClickListener { click() } }

    private fun renderDocuments(manifest: JSONObject) {
        root.addView(sectionTitle("DOCUMENT EXPORTS"))
        shareButton(manifest.optString("pdf_path"), "SHARE PDF", "application/pdf")
        shareButton(manifest.optString("searchable_pdf_path"), "SHARE SEARCHABLE PDF", "application/pdf")
        shareButton(manifest.optString("docx_path"), "SHARE DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        shareButton(manifest.optString("images_zip_path"), "SHARE IMAGE PACKAGE", "application/zip")
    }

    private fun renderScripts(manifest: JSONObject) {
        root.addView(sectionTitle("SCRIPT GROUPING"))
        root.addView(TextView(this).apply {
            text = "${manifest.optInt("script_count")} script group(s) detected. Strong cover pages start groups; uncertain boundaries stay flagged for review."
            setPadding(0, 0, 0, 8)
        })
        val scripts = manifest.optJSONArray("scripts")
        if (scripts != null) for (i in 0 until scripts.length()) {
            val script = scripts.optJSONObject(i) ?: continue
            val review = if (script.optBoolean("boundary_review_required")) "\nBOUNDARY REVIEW REQUIRED" else ""
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 10, 10, 10); setBackgroundColor(0xFFF2F5F8.toInt()) }
            box.addView(TextView(this).apply { text = "${script.optString("display_name")}\n${script.optString("subject", "Subject uncertain")} • ${script.optInt("page_count")} pages$review"; textSize = 15f })
            box.addView(Button(this).apply { text = "REVIEW SCRIPT"; setOnClickListener { startActivity(Intent(this@ContinuousSessionReviewActivity, ScriptReviewActivity::class.java).putExtra("scriptId", script.optString("script_id"))) } })
            root.addView(box)
        }
        root.addView(Button(this).apply { text = "EXPORT ALL SCRIPTS"; setOnClickListener { exportAllScripts() } })
    }

    private fun renderBroadsheets(manifest: JSONObject) {
        root.addView(sectionTitle("BROADSHEET GROUPING"))
        root.addView(TextView(this).apply { text = "${manifest.optInt("broadsheet_count")} logical broadsheet(s) identified. Pages are matched by sheet_id and page_id, not by a fixed two-side rule."; setPadding(0, 0, 0, 8) })
        val sheets = manifest.optJSONArray("broadsheets")
        if (sheets != null) for (i in 0 until sheets.length()) {
            val sheet = sheets.optJSONObject(i) ?: continue
            val expected = sheet.optJSONArray("expected_page_ids")?.length()?.toString() ?: "dynamic"
            val missing = sheet.optJSONArray("missing_page_ids")?.length() ?: 0
            val details = "${sheet.optJSONArray("pages")?.length() ?: 0} captured page(s) • expected $expected"
            root.addView(TextView(this).apply {
                text = "${sheet.optString("class")}\n${sheet.optString("subject_group")}\n${sheet.optString("status")}\n$details${if (missing > 0) " • $missing missing" else ""}"
                textSize = 15f; setPadding(10, 10, 10, 10); setBackgroundColor(0xFFF2F5F8.toInt())
            })
            root.addView(Button(this).apply { text = "OPEN SCORE REVIEW"; setOnClickListener { startActivity(Intent(this@ContinuousSessionReviewActivity, BroadsheetReviewActivity::class.java).putExtra("sheetId", sheet.optString("sheet_id"))) } })
        }
        val uncertain = manifest.optJSONArray("uncertain_pages")
        if (uncertain != null && uncertain.length() > 0) root.addView(TextView(this).apply { text = "${uncertain.length()} page(s) need identity review. Their high-quality images were retained."; setPadding(0, 8, 0, 12) })
    }

    private fun sectionTitle(text: String) = TextView(this).apply { this.text = text; textSize = 17f; setPadding(0, 18, 0, 8) }

    private fun shareButton(path: String, label: String, mime: String) {
        if (path.isBlank()) return
        root.addView(Button(this).apply { text = label; setOnClickListener { shareFile(File(path), mime) } })
    }

    private fun addMorePages(manifest: JSONObject) {
        startActivity(Intent(this, ContinuousScanActivity::class.java)
            .putExtra(ContinuousScanActivity.EXTRA_MODE, manifest.optString("mode"))
            .putExtra(ContinuousScanActivity.EXTRA_SESSION_ID, manifest.optString("session_id")))
        finish()
    }

    private fun reprocess(manifest: JSONObject) {
        val file = manifestFile ?: return
        Toast.makeText(this, "Processing captured pages…", Toast.LENGTH_SHORT).show()
        exec.execute {
            val processor = ContinuousSessionProcessor(this, manifest.optString("mode"), manifest.optString("session_id"))
            processor.finish { rebuilt ->
                processor.shutdown()
                runOnUiThread {
                    manifestFile = rebuilt
                    Toast.makeText(this, "Processing complete", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
        }
    }

    private fun inspect(page: JSONObject) {
        val path = page.optString("corrected_path").ifBlank { page.optString("original_path") }
        val image = ImageView(this).apply { setImageBitmap(BitmapFactory.decodeFile(path)); adjustViewBounds = true }
        AlertDialog.Builder(this).setTitle("Page ${page.optInt("page_number")}").setView(image).setPositiveButton("CLOSE", null).show()
    }

    private fun deletePage(index: Int) {
        editPages { pages ->
            val page = pages.getOrNull(index) ?: return@editPages
            listOf("original_path", "corrected_path").map { page.optString(it) }.distinct().forEach { it.takeIf(String::isNotBlank)?.let(::deleteIfOwned) }
            pages.removeAt(index)
        }
    }

    private fun movePage(index: Int, delta: Int) {
        editPages { pages ->
            val target = index + delta
            if (index !in pages.indices || target !in pages.indices) return@editPages
            val page = pages.removeAt(index)
            pages.add(target, page)
        }
    }

    private fun rotatePage(index: Int) {
        val page = readManifest()?.optJSONArray("pages")?.optJSONObject(index) ?: return
        listOf("original_path", "corrected_path").map { page.optString(it) }.distinct().forEach { path -> if (path.isNotBlank()) rotateFile(File(path)) }
        Toast.makeText(this, "Page rotated", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun showCropDialog(index: Int) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 4, 24, 0) }
        val fields = listOf("Left %", "Top %", "Right %", "Bottom %").map { label -> EditText(this).apply { hint = label; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL } }
        fields.forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle("Crop page").setMessage("Enter crop bounds as percentages of the current page.").setView(box).setPositiveButton("CROP") { _, _ ->
            val values = fields.map { it.text.toString().toFloatOrNull() }
            if (values.any { it == null } || values[0]!! < 0f || values[1]!! < 0f || values[2]!! > 100f || values[3]!! > 100f || values[2]!! <= values[0]!! || values[3]!! <= values[1]!!) {
                Toast.makeText(this, "Crop bounds are invalid", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            val page = readManifest()?.optJSONArray("pages")?.optJSONObject(index) ?: return@setPositiveButton
            val path = page.optString("corrected_path").ifBlank { page.optString("original_path") }
            cropFile(File(path), values[0]!! / 100f, values[1]!! / 100f, values[2]!! / 100f, values[3]!! / 100f)
            Toast.makeText(this, "Page cropped", Toast.LENGTH_SHORT).show()
            render()
        }.setNegativeButton("CANCEL", null).show()
    }

    private fun editPages(edit: (MutableList<JSONObject>) -> Unit) {
        val file = manifestFile ?: return
        val manifest = readManifest() ?: return
        val old = manifest.optJSONArray("pages") ?: JSONArray()
        val pages = MutableList(old.length()) { old.optJSONObject(it) ?: JSONObject() }
        edit(pages)
        pages.forEachIndexed { i, page -> page.put("page_number", i + 1) }
        manifest.put("page_count", pages.size)
        manifest.put("pages", JSONArray().apply { pages.forEach(::put) })
        file.writeText(manifest.toString(2))
        render()
    }

    private fun rotateFile(file: File) {
        val source = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(90f) }, true)
        source.recycle()
        file.outputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        rotated.recycle()
    }

    private fun cropFile(file: File, left: Float, top: Float, right: Float, bottom: Float) {
        val source = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val x = (source.width * left).toInt().coerceIn(0, source.width - 2)
        val y = (source.height * top).toInt().coerceIn(0, source.height - 2)
        val w = (source.width * (right - left)).toInt().coerceIn(2, source.width - x)
        val h = (source.height * (bottom - top)).toInt().coerceIn(2, source.height - y)
        val cropped = Bitmap.createBitmap(source, x, y, w, h)
        source.recycle()
        file.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        cropped.recycle()
    }

    private fun deleteIfOwned(path: String) {
        val file = File(path)
        val sessionRoot = manifestFile?.parentFile ?: return
        if (file.absolutePath.startsWith(sessionRoot.absolutePath + File.separator)) file.delete()
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
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share SmartScore export"))
    }

    override fun onDestroy() { super.onDestroy(); exec.shutdown() }
}
