package com.wts.smartscore.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.wts.smartscore.export.DocxExporter
import com.wts.smartscore.export.ImageZipExporter
import com.wts.smartscore.export.PdfImageExporter
import com.wts.smartscore.scanner.MlKitDocumentScan
import com.wts.smartscore.scanner.SmartScanResult
import com.wts.smartscore.scanner.ScriptIdentityExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class GeneralScannerActivity : AppCompatActivity() {
    companion object { const val EXTRA_BATCH_SCAN = "batch_scan" }

    private lateinit var status: TextView
    private lateinit var thumbs: LinearLayout
    private lateinit var openPdf: Button
    private lateinit var openSearchablePdf: Button
    private lateinit var openDocx: Button
    private lateinit var sharePackage: Button
    private var current: SmartScanResult? = null
    private val batchMode by lazy { intent.getBooleanExtra(EXTRA_BATCH_SCAN, false) || intent.getBooleanExtra("batch_scan", false) }
    private val scanner by lazy { MlKitDocumentScan.client(50) }

    private val launcher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { activityResult ->
        if (activityResult.resultCode != Activity.RESULT_OK) {
            status.text = "Scan cancelled — tap Scan Document when ready"
            return@registerForActivityResult
        }
        try {
            val result = GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
                ?: throw IllegalStateException("Scanner returned no result")
            status.text = "Saving scanned pages…"
            current = MlKitDocumentScan.persistResult(this, result, File(filesDir, "documents"))
            renderResult()
        } catch (t: Throwable) {
            status.text = "Unable to save scan: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 28, 22, 22)
        }
        root.addView(TextView(this).apply { text = "Document Scanner"; textSize = 26f })
        root.addView(TextView(this).apply {
            text = if (batchMode) {
                "Batch Scan uses Google's multipage document scanner. Capture the whole stack, then review the corrected pages and exports together."
            } else {
                "Scan ordinary documents to clean corrected pages and a multipage PDF."
            }
            textSize = 14f
            setPadding(0, 6, 0, 18)
        })
        status = TextView(this).apply {
            text = "Opening document scanner…"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 18)
        }
        root.addView(status)
        root.addView(Button(this).apply { text = if (batchMode) "START BATCH SCAN" else "SCAN DOCUMENT"; setOnClickListener { startScan() } })
        thumbs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { addView(thumbs) }, LinearLayout.LayoutParams(-1, 220))
        openPdf = Button(this).apply {
            text = "OPEN PDF"
            isEnabled = false
            setOnClickListener { current?.pdfPath?.let(::openPdfFile) }
        }
        root.addView(openPdf)
        openSearchablePdf = Button(this).apply {
            text = "OPEN SEARCHABLE PDF"
            isEnabled = false
            setOnClickListener { current?.let { openFile(File(filesDir, "documents/${it.scanId}/document-searchable.pdf"), "application/pdf") } }
        }
        root.addView(openSearchablePdf)
        openDocx = Button(this).apply {
            text = "OPEN DOCX / WORD"
            isEnabled = false
            setOnClickListener { current?.let { openFile(File(filesDir, "documents/${it.scanId}/document.docx"), "application/vnd.openxmlformats-officedocument.wordprocessingml.document") } }
        }
        root.addView(openDocx)
        sharePackage = Button(this).apply {
            text = "SHARE IMAGE + OCR PACKAGE"
            isEnabled = false
            setOnClickListener { current?.let { openFile(File(filesDir, "documents/${it.scanId}/document-package.zip"), "application/zip", share = true) } }
        }
        root.addView(sharePackage)
        root.addView(TextView(this).apply {
            text = "Capture and review all pages inside the scanner, then tap Done. SmartScore keeps the corrected JPEG pages and PDF locally."
            setPadding(0, 16, 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
        if (savedInstanceState == null) startScan()
    }

    private fun startScan() {
        status.text = "Preparing scanner…"
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { e -> status.text = "Scanner unavailable: ${e.message ?: e.javaClass.simpleName}" }
    }

    private fun renderResult() {
        val scan = current ?: return
        thumbs.removeAllViews()
        scan.pages.forEach { page ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(6, 6, 6, 6) }
            box.addView(ImageView(this).apply {
                setImageBitmap(BitmapFactory.decodeFile(page.imagePath))
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, LinearLayout.LayoutParams(150, 170))
            box.addView(TextView(this).apply { text = "Page ${page.pageNumber}"; gravity = Gravity.CENTER })
            thumbs.addView(box)
        }
        status.text = "Scanned ✓ — ${scan.pageCount} page${if (scan.pageCount == 1) "" else "s"}"
        openPdf.isEnabled = scan.pdfPath != null
        openSearchablePdf.isEnabled = false
        openDocx.isEnabled = false
        sharePackage.isEnabled = false
        lifecycleScope.launch {
            status.text = "Scanned ✓ — preparing OCR exports…"
            withContext(Dispatchers.IO) { buildExports(scan) }
            openSearchablePdf.isEnabled = true
            openDocx.isEnabled = true
            sharePackage.isEnabled = true
            status.text = "Scanned ✓ — PDF, OCR and Word exports ready"
        }
    }

    private fun buildExports(scan: SmartScanResult) {
        val dir = File(filesDir, "documents/${scan.scanId}").apply { mkdirs() }
        val texts = scan.pages.map { page -> ScriptIdentityExtractor.extractText(this, page.imagePath) }
        PdfImageExporter.exportSearchable(File(dir, "document-searchable.pdf"), scan.pages.mapIndexed { index, page -> PdfImageExporter.OcrPage(page.imagePath, texts[index]) })
        val plain = texts.mapIndexed { index, text -> "===== PAGE ${index + 1} =====\n$text" }.joinToString("\n\n")
        File(dir, "ocr.txt").writeText(plain)
        File(dir, "ocr.json").writeText(JSONObject().apply {
            put("scan_id", scan.scanId)
            put("pages", JSONArray().apply { texts.forEachIndexed { index, text -> put(JSONObject().put("page_number", index + 1).put("image", File(scan.pages[index].imagePath).name).put("text", text)) } })
        }.toString(2))
        DocxExporter.export(File(dir, "document.docx"), "SmartScore document", texts.mapIndexed { index, text -> index + 1 to text })
        ImageZipExporter.export(File(dir, "document-package.zip"), scan.pages.map { it.imagePath }, "{\"scan_id\":\"${scan.scanId}\",\"page_count\":${scan.pageCount}}", mapOf("ocr.txt" to plain, "ocr.json" to File(dir, "ocr.json").readText()))
    }

    private fun openPdfFile(path: String) {
        openFile(File(path), "application/pdf")
    }

    private fun openFile(file: File, mime: String, share: Boolean = false) {
        if (!file.exists()) { Toast.makeText(this, "Export is not ready yet", Toast.LENGTH_SHORT).show(); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
            type = mime
            if (share) putExtra(Intent.EXTRA_STREAM, uri) else setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(if (share) Intent.createChooser(intent, "Share SmartScore export") else intent) } catch (_: Throwable) { Toast.makeText(this, "Export saved at ${file.absolutePath}", Toast.LENGTH_LONG).show() }
    }
}
