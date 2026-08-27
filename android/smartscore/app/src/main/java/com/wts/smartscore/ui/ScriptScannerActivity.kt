package com.wts.smartscore.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.wts.smartscore.data.*
import com.wts.smartscore.export.ImageZipExporter
import com.wts.smartscore.export.PdfImageExporter
import com.wts.smartscore.scanner.MlKitDocumentScan
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class ScriptScannerActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var thumbs: LinearLayout
    private lateinit var student: EditText
    private lateinit var subject: EditText
    private lateinit var note: EditText
    private val dao by lazy { SmartScoreDatabase.get(this).dao() }
    private val scanner by lazy { MlKitDocumentScan.client(50) }
    private var scriptId = ""
    private var createdAt = 0L
    private val pagePaths = mutableListOf<String>()

    private val launcher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { activityResult ->
        if (activityResult.resultCode != Activity.RESULT_OK) {
            status.text = "Scan cancelled — script remains open"
            return@registerForActivityResult
        }
        try {
            val result = GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
                ?: throw IllegalStateException("Scanner returned no result")
            status.text = "Saving script pages…"
            val scan = MlKitDocumentScan.persistResult(this, result, File(filesDir, "scripts/$scriptId/scans"))
            lifecycleScope.launch {
                val existing = dao.scriptPages(scriptId)
                var next = existing.size + 1
                scan.pages.forEach { page ->
                    dao.saveScriptPage(ScriptPageEntity(UUID.randomUUID().toString(), scriptId, next++, page.imagePath, page.imagePath, System.currentTimeMillis()))
                }
                saveScriptState("IN_PROGRESS", null)
                loadPages()
                rebuildExports()
                status.text = "${scan.pageCount} PAGE${if (scan.pageCount == 1) "" else "S"} ADDED ✓"
            }
        } catch (t: Throwable) {
            status.text = "Unable to save script scan: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scriptId = intent.getStringExtra("scriptId") ?: UUID.randomUUID().toString()
        createdAt = System.currentTimeMillis()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 20, 18, 18) }
        root.addView(TextView(this).apply { text = "SCRIPT SCANNER"; textSize = 22f })
        student = EditText(this).apply { hint = "Student / Test identifier" }
        subject = EditText(this).apply { hint = "Subject" }
        note = EditText(this).apply { hint = "Optional note" }
        root.addView(student); root.addView(subject); root.addView(note)
        status = TextView(this).apply { text = "Enter script details, then scan all pages"; gravity = Gravity.CENTER; setPadding(0, 16, 0, 16) }
        root.addView(status)
        root.addView(Button(this).apply { text = "SCAN SCRIPT PAGES"; setOnClickListener { startScan() } })
        thumbs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { addView(thumbs) }, LinearLayout.LayoutParams(-1, 190))
        root.addView(Button(this).apply { text = "DONE / FINISH SCRIPT"; setOnClickListener { finishScript() } })
        setContentView(ScrollView(this).apply { addView(root) })

        lifecycleScope.launch {
            val existing = dao.script(scriptId)
            if (existing != null) {
                createdAt = existing.createdAt
                student.setText(existing.studentRef ?: "")
                subject.setText(existing.subject ?: "")
                note.setText(existing.testRef ?: "")
            } else {
                dao.saveScript(ScriptEntity(scriptId, null, null, null, createdAt, null, "IN_PROGRESS", 0))
            }
            loadPages()
        }
    }

    private fun startScan() {
        status.text = "Preparing document scanner…"
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { e -> status.text = "Scanner unavailable: ${e.message ?: e.javaClass.simpleName}" }
    }

    private suspend fun loadPages() {
        val pages = dao.scriptPages(scriptId)
        pagePaths.clear(); pagePaths.addAll(pages.map { it.normalizedPath ?: it.imagePath })
        renderPages()
    }

    private fun renderPages() {
        thumbs.removeAllViews()
        pagePaths.forEachIndexed { index, path ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(5, 5, 5, 5) }
            box.addView(ImageView(this).apply {
                setImageBitmap(BitmapFactory.decodeFile(path)); scaleType = ImageView.ScaleType.CENTER_CROP
            }, LinearLayout.LayoutParams(120, 130))
            box.addView(TextView(this).apply { text = "Page ${index + 1}"; gravity = Gravity.CENTER })
            box.addView(Button(this).apply {
                text = "DELETE"
                setOnClickListener {
                    lifecycleScope.launch {
                        val pages = dao.scriptPages(scriptId)
                        pages.getOrNull(index)?.let { dao.deleteScriptPage(it.pageId) }
                        dao.scriptPages(scriptId).forEachIndexed { i, p -> dao.setPageNumber(p.pageId, i + 1) }
                        loadPages(); rebuildExports(); saveScriptState("IN_PROGRESS", null)
                    }
                }
            })
            thumbs.addView(box)
        }
    }

    private suspend fun saveScriptState(state: String, completedAt: Long?) {
        val count = dao.scriptPages(scriptId).size
        dao.saveScript(ScriptEntity(scriptId, student.text.toString().ifBlank { null }, subject.text.toString().ifBlank { null }, note.text.toString().ifBlank { null }, createdAt, completedAt, state, count))
    }

    private suspend fun rebuildExports() {
        val paths = dao.scriptPages(scriptId).map { it.normalizedPath ?: it.imagePath }
        if (paths.isEmpty()) return
        val dir = File(filesDir, "exports").apply { mkdirs() }
        PdfImageExporter.export(File(dir, "script-$scriptId.pdf"), paths)
        ImageZipExporter.export(File(dir, "script-$scriptId.zip"), paths, "{\"script_id\":\"$scriptId\",\"student_ref\":\"${student.text}\",\"subject\":\"${subject.text}\",\"page_count\":${paths.size}}")
    }

    private fun finishScript() {
        lifecycleScope.launch {
            val paths = dao.scriptPages(scriptId)
            if (paths.isEmpty()) { status.text = "Scan at least one page first"; return@launch }
            saveScriptState("COMPLETE", System.currentTimeMillis())
            rebuildExports()
            Toast.makeText(this@ScriptScannerActivity, "Script saved: ${paths.size} pages", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
