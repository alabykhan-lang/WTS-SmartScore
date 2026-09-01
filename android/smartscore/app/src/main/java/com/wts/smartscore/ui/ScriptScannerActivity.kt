package com.wts.smartscore.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.wts.smartscore.data.*
import com.wts.smartscore.export.ImageZipExporter
import com.wts.smartscore.export.PdfImageExporter
import com.wts.smartscore.export.DocxExporter
import com.wts.smartscore.scanner.MlKitDocumentScan
import com.wts.smartscore.scanner.ScriptIdentity
import com.wts.smartscore.scanner.ScriptIdentityExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ScriptScannerActivity : AppCompatActivity() {
    companion object { const val EXTRA_BATCH_SCAN = "batch_scan" }

    private lateinit var status: TextView
    private lateinit var identitySummary: TextView
    private lateinit var thumbs: LinearLayout
    private lateinit var firstPageButton: Button
    private lateinit var addPagesButton: Button
    private lateinit var editIdentityButton: Button
    private lateinit var finishButton: Button
    private lateinit var openPdfButton: Button
    private lateinit var openSearchablePdfButton: Button
    private lateinit var openDocxButton: Button
    private lateinit var sharePackageButton: Button
    private lateinit var nextScriptButton: Button

    private val dao by lazy { SmartScoreDatabase.get(this).dao() }
    private val batchMode by lazy { intent.getBooleanExtra(EXTRA_BATCH_SCAN, false) || intent.getBooleanExtra("batch_scan", false) }
    private val firstPageScanner by lazy { MlKitDocumentScan.client(1) }
    private val multiPageScanner by lazy { MlKitDocumentScan.client(50) }

    private var scriptId = ""
    private var createdAt = 0L
    private var detectedIdentity: ScriptIdentity? = null
    private val pagePaths = mutableListOf<String>()

    private val firstPageLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { activityResult ->
        if (activityResult.resultCode != Activity.RESULT_OK) {
            status.text = "First-page scan cancelled — tap Scan First Page when ready"
            return@registerForActivityResult
        }
        handleScanResult(activityResult.data, firstPage = true)
    }

    private val morePagesLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { activityResult ->
        if (activityResult.resultCode != Activity.RESULT_OK) {
            status.text = "Page scan cancelled — your script remains saved"
            return@registerForActivityResult
        }
        handleScanResult(activityResult.data, firstPage = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scriptId = intent.getStringExtra("scriptId") ?: UUID.randomUUID().toString()
        createdAt = System.currentTimeMillis()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 26, 22, 22) }
        root.addView(TextView(this).apply { text = "Script Scanner"; textSize = 26f })
        root.addView(TextView(this).apply {
            text = if (batchMode) {
                "Batch Scan uses Google's multipage document scanner. Capture the script pages together; SmartScore will read the first page identity after capture."
            } else {
                "Scan the first page first. SmartScore will suggest the student's identity and subject from the page, then you can add the remaining pages."
            }
            textSize = 14f
            setPadding(0, 6, 0, 18)
        })

        identitySummary = TextView(this).apply {
            text = "Identity: not detected yet"
            textSize = 15f
            setPadding(16, 16, 16, 16)
        }
        root.addView(identitySummary)
        editIdentityButton = Button(this).apply {
            text = "EDIT IDENTITY"
            visibility = View.GONE
            setOnClickListener { showIdentityEditor(detectedIdentity) }
        }
        root.addView(editIdentityButton)

        status = TextView(this).apply {
            text = "Ready to scan first page"
            gravity = Gravity.CENTER
            textSize = 15f
            setPadding(0, 16, 0, 16)
        }
        root.addView(status)

        firstPageButton = Button(this).apply {
            text = if (batchMode) "START BATCH SCAN" else "SCAN FIRST PAGE"
            setOnClickListener { if (batchMode) startBatchScan() else startFirstPageScan() }
        }
        root.addView(firstPageButton)
        addPagesButton = Button(this).apply {
            text = "ADD PAGES"
            isEnabled = false
            setOnClickListener { startMorePagesScan() }
        }
        root.addView(addPagesButton)

        thumbs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { addView(thumbs) }, LinearLayout.LayoutParams(-1, 190))

        finishButton = Button(this).apply {
            text = "FINISH SCRIPT"
            isEnabled = false
            setOnClickListener { finishScript() }
        }
        root.addView(finishButton)
        openPdfButton = Button(this).apply {
            text = "OPEN PDF"
            visibility = View.GONE
            setOnClickListener { openExport("script-$scriptId.pdf", "application/pdf") }
        }
        openSearchablePdfButton = Button(this).apply {
            text = "OPEN SEARCHABLE PDF"
            visibility = View.GONE
            setOnClickListener { openExport("script-$scriptId-searchable.pdf", "application/pdf") }
        }
        openDocxButton = Button(this).apply {
            text = "OPEN DOCX / WORD"
            visibility = View.GONE
            setOnClickListener { openExport("script-$scriptId.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document") }
        }
        sharePackageButton = Button(this).apply {
            text = "SHARE AI-READY PACKAGE"
            visibility = View.GONE
            setOnClickListener { shareAiPackage() }
        }
        nextScriptButton = Button(this).apply {
            text = "START NEXT SCRIPT"
            visibility = View.GONE
            setOnClickListener {
                startActivity(Intent(this@ScriptScannerActivity, ScriptScannerActivity::class.java))
                finish()
            }
        }
        root.addView(openPdfButton); root.addView(openSearchablePdfButton); root.addView(openDocxButton); root.addView(sharePackageButton); root.addView(nextScriptButton)

        setContentView(ScrollView(this).apply { addView(root) })

        lifecycleScope.launch {
            val existing = dao.script(scriptId)
            if (existing != null) {
                createdAt = existing.createdAt
                detectedIdentity = ScriptIdentityExtractor.read(identityFile()) ?: ScriptIdentity(
                    existing.studentRef, null, null, existing.subject, existing.testRef, "", if (existing.studentRef != null) 0.75 else 0.0
                )
            } else {
                dao.saveScript(ScriptEntity(scriptId, null, null, null, createdAt, null, "IN_PROGRESS", 0))
            }
            loadPages()
            updateIdentityUi()
            val reopen = intent.hasExtra("scriptId")
            if (!reopen && pagePaths.isEmpty() && savedInstanceState == null) {
                if (batchMode) startBatchScan() else startFirstPageScan()
            }
        }
    }

    private fun startFirstPageScan() {
        status.text = "Opening scanner for first page…"
        firstPageScanner.getStartScanIntent(this)
            .addOnSuccessListener { sender -> firstPageLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { e -> status.text = "Scanner unavailable: ${e.message ?: e.javaClass.simpleName}" }
    }

    private fun startBatchScan() {
        status.text = "Opening Google scanner for script pages…"
        multiPageScanner.getStartScanIntent(this)
            .addOnSuccessListener { sender -> firstPageLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { e -> status.text = "Scanner unavailable: ${e.message ?: e.javaClass.simpleName}" }
    }

    private fun startMorePagesScan() {
        status.text = "Opening scanner — add the remaining pages"
        multiPageScanner.getStartScanIntent(this)
            .addOnSuccessListener { sender -> morePagesLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { e -> status.text = "Scanner unavailable: ${e.message ?: e.javaClass.simpleName}" }
    }

    private fun handleScanResult(data: Intent?, firstPage: Boolean) {
        try {
            val result = GmsDocumentScanningResult.fromActivityResultIntent(data)
                ?: throw IllegalStateException("Scanner returned no result")
            status.text = if (firstPage) "Saving first page…" else "Saving script pages…"
            val scan = MlKitDocumentScan.persistResult(this, result, File(filesDir, "scripts/$scriptId/scans"))
            lifecycleScope.launch {
                val existing = dao.scriptPages(scriptId)
                var next = existing.size + 1
                scan.pages.forEach { page ->
                    dao.saveScriptPage(ScriptPageEntity(
                        UUID.randomUUID().toString(), scriptId, next++, page.imagePath, page.imagePath, System.currentTimeMillis()
                    ))
                }
                saveScriptState("IN_PROGRESS", null)
                loadPages()
                if (firstPage && scan.pages.isNotEmpty()) extractFirstPageIdentity(scan.pages.first().imagePath)
                else status.text = "${scan.pageCount} page${if (scan.pageCount == 1) "" else "s"} added ✓"
            }
        } catch (t: Throwable) {
            status.text = "Unable to save script scan: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun extractFirstPageIdentity(path: String) {
        status.text = "Reading first-page identity…"
        lifecycleScope.launch {
            val identity = withContext(Dispatchers.IO) { ScriptIdentityExtractor.extract(this@ScriptScannerActivity, path) }
            detectedIdentity = identity
            ScriptIdentityExtractor.save(identityFile(), identity)
            saveScriptState("IN_PROGRESS", null)
            updateIdentityUi()
            val strong = identity.confidence >= 0.78 && (identity.studentName != null || identity.studentId != null) && identity.subject != null
            if (strong) {
                status.text = "Identity detected ✓ — add the remaining pages"
            } else {
                status.text = "Identity needs a quick check"
                showIdentityEditor(identity)
            }
        }
    }

    private fun showIdentityEditor(identity: ScriptIdentity?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 0) }
        val name = EditText(this).apply { hint = "Student name"; setText(identity?.studentName ?: "") }
        val id = EditText(this).apply { hint = "Admission / student ID"; setText(identity?.studentId ?: "") }
        val klass = EditText(this).apply { hint = "Class"; setText(identity?.classLabel ?: "") }
        val subject = EditText(this).apply { hint = "Subject"; setText(identity?.subject ?: "") }
        val title = EditText(this).apply { hint = "Exam / test title"; setText(identity?.examTitle ?: "") }
        listOf(name, id, klass, subject, title).forEach(box::addView)
        AlertDialog.Builder(this)
            .setTitle("Confirm script identity")
            .setMessage("Correct only fields that need attention.")
            .setView(box)
            .setPositiveButton("CONFIRM") { _, _ ->
                val revised = ScriptIdentity(
                    name.text.toString().trim().ifBlank { null },
                    id.text.toString().trim().ifBlank { null },
                    klass.text.toString().trim().ifBlank { null },
                    subject.text.toString().trim().ifBlank { null },
                    title.text.toString().trim().ifBlank { null },
                    identity?.rawText ?: "",
                    if (identity == null) 1.0 else identity.confidence
                )
                detectedIdentity = revised
                ScriptIdentityExtractor.save(identityFile(), revised)
                lifecycleScope.launch { saveScriptState("IN_PROGRESS", null) }
                updateIdentityUi()
                status.text = "Identity confirmed ✓ — add remaining pages"
            }
            .setNegativeButton("LATER", null)
            .show()
    }

    private suspend fun loadPages() {
        val pages = dao.scriptPages(scriptId)
        pagePaths.clear(); pagePaths.addAll(pages.map { it.normalizedPath ?: it.imagePath })
        renderPages()
        val hasPages = pages.isNotEmpty()
        firstPageButton.visibility = if (hasPages) View.GONE else View.VISIBLE
        addPagesButton.isEnabled = hasPages
        finishButton.isEnabled = hasPages
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
                        loadPages(); saveScriptState("IN_PROGRESS", null)
                    }
                }
            })
            thumbs.addView(box)
        }
    }

    private fun updateIdentityUi() {
        val i = detectedIdentity
        if (i == null) {
            identitySummary.text = "Identity: not detected yet"
            editIdentityButton.visibility = View.GONE
            return
        }
        identitySummary.text = buildString {
            append(i.displayStudent())
            i.studentId?.let { append("  •  ").append(it) }
            i.subject?.let { append("\n").append(it) }
            i.classLabel?.let { append("  •  ").append(it) }
            i.examTitle?.let { append("\n").append(it) }
        }
        editIdentityButton.visibility = View.VISIBLE
    }

    private suspend fun saveScriptState(state: String, completedAt: Long?) {
        val count = dao.scriptPages(scriptId).size
        val identity = detectedIdentity
        dao.saveScript(ScriptEntity(
            scriptId,
            identity?.studentName ?: identity?.studentId,
            identity?.subject,
            identity?.examTitle,
            createdAt,
            completedAt,
            state,
            count
        ))
    }

    private suspend fun rebuildExports() = withContext(Dispatchers.IO) {
        val paths = dao.scriptPages(scriptId).map { it.normalizedPath ?: it.imagePath }
        if (paths.isEmpty()) return@withContext
        val dir = File(filesDir, "exports").apply { mkdirs() }
        val pdf = File(dir, "script-$scriptId.pdf")
        PdfImageExporter.export(pdf, paths)

        val texts = paths.mapIndexed { index, path ->
            val text = runCatching { ScriptIdentityExtractor.extractText(this@ScriptScannerActivity, path) }.getOrDefault("")
            index + 1 to text
        }
        val plainText = texts.joinToString("\n\n") { (page, text) -> "===== PAGE $page =====\n$text" }
        val identity = detectedIdentity ?: ScriptIdentityExtractor.read(identityFile())
        val searchablePdf = File(dir, "script-$scriptId-searchable.pdf")
        PdfImageExporter.exportSearchable(searchablePdf, paths.mapIndexed { index, path -> PdfImageExporter.OcrPage(path, texts[index].second) })
        val docx = File(dir, "script-$scriptId.docx")
        DocxExporter.export(docx, "${identity?.displayStudent() ?: "SmartScore"} script", texts)
        val ocrJson = JSONObject().apply {
            put("script_id", scriptId)
            put("pages", JSONArray().apply {
                texts.forEach { (page, text) -> put(JSONObject().put("page_number", page).put("text", text)) }
            })
        }.toString(2)
        File(dir, "script-$scriptId.txt").writeText(plainText)
        File(dir, "script-$scriptId-ocr.json").writeText(ocrJson)

        val metadata = JSONObject().apply {
            put("script_id", scriptId)
            put("student_identity", identity?.let { ScriptIdentityExtractor.toJson(it) } ?: JSONObject())
            put("subject", identity?.subject ?: JSONObject.NULL)
            put("page_count", paths.size)
            put("ordered_pages", JSONArray().apply {
                paths.forEachIndexed { index, path -> put(JSONObject().put("page_number", index + 1).put("file_name", File(path).name)) }
            })
            put("ocr_text_file", "ocr.txt")
            put("ocr_json_file", "ocr.json")
            put("searchable_pdf_file", searchablePdf.name)
            put("docx_file", docx.name)
            put("question_paper_reference", JSONObject.NULL)
            put("marking_scheme_reference", JSONObject.NULL)
            put("ai_result_status", "NOT_MARKED")
        }.toString(2)
        ImageZipExporter.export(
            File(dir, "script-$scriptId-ai-package.zip"),
            paths,
            metadata,
            mapOf("ocr.txt" to plainText, "ocr.json" to ocrJson)
        )
    }

    private fun finishScript() {
        lifecycleScope.launch {
            val pages = dao.scriptPages(scriptId)
            if (pages.isEmpty()) { status.text = "Scan at least one page first"; return@launch }
            status.text = "Preparing PDF and AI-ready package…"
            saveScriptState("COMPLETE", System.currentTimeMillis())
            rebuildExports()
            status.text = "Script complete ✓ — PDF, images, OCR text and AI-ready package are ready"
            openPdfButton.visibility = View.VISIBLE
            openSearchablePdfButton.visibility = View.VISIBLE
            openDocxButton.visibility = View.VISIBLE
            sharePackageButton.visibility = View.VISIBLE
            nextScriptButton.visibility = View.VISIBLE
            addPagesButton.isEnabled = true
            finishButton.isEnabled = false
        }
    }

    private fun identityFile() = File(filesDir, "scripts/$scriptId/identity.json")

    private fun openExport(name: String, mime: String) {
        val file = File(filesDir, "exports/$name")
        if (!file.exists()) { Toast.makeText(this, "Export is not ready yet", Toast.LENGTH_SHORT).show(); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        runCatching { startActivity(intent) }.onFailure { Toast.makeText(this, "File saved at ${file.absolutePath}", Toast.LENGTH_LONG).show() }
    }

    private fun shareAiPackage() {
        val file = File(filesDir, "exports/script-$scriptId-ai-package.zip")
        if (!file.exists()) { Toast.makeText(this, "AI-ready package is not ready yet", Toast.LENGTH_SHORT).show(); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Share digitized script"))
    }
}
