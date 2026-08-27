package com.wts.smartscore.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.wts.smartscore.data.BroadsheetEntity
import com.wts.smartscore.data.ScanEntity
import com.wts.smartscore.data.SheetSideEntity
import com.wts.smartscore.data.SmartScoreDatabase
import com.wts.smartscore.scanner.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Quick/Normal scanning path. It shares the flexible page manifest with Continuous Scan. */
class BroadsheetScannerActivity : AppCompatActivity() {
    companion object { private const val TAG = "SmartScoreBroadsheet" }

    private data class Candidate(val scanPageNumber: Int, val imagePath: String, val pageId: String?, val method: String)
    private data class Processed(val page: SheetPageTemplate, val scanId: String, val sourcePath: String, val canonicalPath: String, val readings: List<com.wts.smartscore.data.ScoreReadingEntity>, val processingMs: Long, val identityMethod: String)

    private lateinit var status: TextView
    private lateinit var scanButton: Button
    private val scanner by lazy { MlKitDocumentScan.client(50) }
    private val dao by lazy { SmartScoreDatabase.get(this).dao() }
    private val repo by lazy { V2TemplateRepository(this) }
    private val exec = Executors.newSingleThreadExecutor()

    private val launcher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            status.text = "Scan cancelled — saved pages are unchanged"
            scanButton.isEnabled = true
            return@registerForActivityResult
        }
        runCatching {
            val document = GmsDocumentScanningResult.fromActivityResultIntent(result.data) ?: error("Scanner returned no pages")
            status.text = "Preparing scanned broadsheet…"
            scanButton.isEnabled = false
            val scan = MlKitDocumentScan.persistResult(this, document, File(filesDir, "broadsheets/mlkit-scans"))
            identifyPages(scan.pages.map { it.pageNumber to it.imagePath })
        }.onFailure {
            Log.e(TAG, "ML Kit broadsheet result failed", it)
            status.text = "Unable to use this scan. Tap Scan Broadsheet to try again."
            scanButton.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 28, 22, 22) }
        root.addView(TextView(this).apply { text = "Smart Broadsheet"; textSize = 26f; setPadding(0, 0, 0, 6) })
        root.addView(TextView(this).apply {
            text = "Quick Scan uses Google's document review flow. SmartScore identifies each captured page after scanning, then maps it to the generated template."
            textSize = 14f; setPadding(0, 0, 0, 22)
        })
        status = TextView(this).apply { text = "Ready to scan"; gravity = Gravity.CENTER; textSize = 16f; setPadding(12, 18, 12, 18) }
        root.addView(status)
        scanButton = Button(this).apply { text = "SCAN BROADSHEET"; setOnClickListener { startScan() } }
        root.addView(scanButton)
        root.addView(TextView(this).apply { text = "QR identifies a page when available. If QR fails, the image is retained and identity is resolved from page order/template evidence after capture."; textSize = 13f; setPadding(0, 18, 0, 0) })
        setContentView(ScrollView(this).apply { addView(root) })
        lifecycleScope.launch { refreshSavedState() }
        if (savedInstanceState == null) startScan()
    }

    private fun startScan() {
        status.text = "Opening document scanner…"
        scanButton.isEnabled = false
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { error -> status.text = "Document scanner unavailable: ${error.message ?: error.javaClass.simpleName}"; scanButton.isEnabled = true }
    }

    private fun identifyPages(pages: List<Pair<Int, String>>) {
        exec.execute {
            val candidates = pages.map { (number, path) ->
                val bitmap = BitmapFactory.decodeFile(path)
                val identity = try { if (bitmap != null) SheetIdentityResolver.resolvePageIdentity(bitmap) else null } catch (t: Throwable) { Log.w(TAG, "QR identity unavailable", t); null } finally { bitmap?.recycle() }
                val ocr = runCatching { ScriptIdentityExtractor.extractText(this, path) }.getOrDefault("")
                val qrPage = identity?.pageId?.uppercase()?.let(repo::pageById)
                val ocrPageId = Regex("(?i)(WTS-[A-Z0-9-]+)-(?:P|S)([0-9]+)").find(ocr)?.value
                    ?.uppercase()?.replace(Regex("-S([0-9]+)$"), "-P$1")
                val ocrPage = ocrPageId?.let(repo::pageById)
                val orderedPage = qrPage ?: ocrPage ?: pageFromPrintedHeading(ocr, number, pages.size)
                Candidate(number, path, orderedPage?.pageId, when {
                    qrPage != null -> "QUICK_SCAN_QR"
                    ocrPage != null -> "QUICK_SCAN_OCR_ID"
                    orderedPage != null -> "QUICK_SCAN_TEMPLATE_FALLBACK"
                    else -> "QUICK_SCAN_IDENTITY_UNCERTAIN"
                })
            }
            runOnUiThread { processCandidates(candidates) }
        }
    }

    private fun pageFromPrintedHeading(text: String, scanNumber: Int, scannedCount: Int): SheetPageTemplate? {
        val upper = text.uppercase()
        val manifest = repo.allManifests().firstOrNull { candidate ->
            upper.contains(candidate.sheetId.uppercase()) ||
                (upper.contains(candidate.classLabel.uppercase()) && upper.contains(candidate.subjectGroup.uppercase().split(" • ").first()))
        } ?: return null
        val pageNumber = Regex("(?i)(?:PAGE|P|S)\\s*([0-9]+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return when {
            pageNumber != null -> manifest.pageByNumber(pageNumber)
            scannedCount == manifest.pages.size -> manifest.pageByNumber(scanNumber)
            manifest.pages.size == 1 -> manifest.pages.first()
            else -> null
        }
    }

    private fun processCandidates(candidates: List<Candidate>) {
        status.text = "Reading scores…"
        scanButton.isEnabled = false
        exec.execute {
            val known = candidates.mapNotNull { candidate -> candidate.pageId?.let(repo::pageById)?.let { candidate to it } }
            val unknown = candidates.filter { it.pageId == null }
            if (known.isEmpty()) {
                val file = File(filesDir, "broadsheets/uncertain-pages/${System.currentTimeMillis()}.json").apply { parentFile?.mkdirs() }
                file.writeText("{\"pages\":${unknown.joinToString(prefix = "[", postfix = "]") { "{\"page_number\":${it.scanPageNumber},\"image\":\"${it.imagePath.replace("\\", "\\\\")}\"}" }}}")
                runOnUiThread { status.text = "${unknown.size} page(s) saved — identity needs review"; scanButton.isEnabled = true }
                return@execute
            }
            val cv = OpenCvRuntime.initialize(this)
            if (cv.state != OpenCvRuntime.State.OPENCV_READY) {
                runOnUiThread { status.text = "The pages were saved, but score processing is unavailable"; scanButton.isEnabled = true }
                return@execute
            }
            val processed = mutableListOf<Processed>()
            try {
                known.forEach { (candidate, page) ->
                    val source = BitmapFactory.decodeFile(candidate.imagePath) ?: error("Scanned page could not be opened")
                    // Keep the ML Kit corrected JPEG at its native resolution whenever
                    // practical; the primary layout deliberately contains smaller cells.
                    val scale = minOf(1f, 3200f / maxOf(source.width, source.height).toFloat())
                    val targetWidth = (source.width * scale).toInt().coerceAtLeast(2)
                    val targetHeight = (source.height * scale).toInt().coerceAtLeast(2)
                    val canonical: Bitmap = if (targetWidth == source.width && targetHeight == source.height) source else Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
                    if (canonical !== source) source.recycle()
                    val canonicalFile = File(filesDir, "broadsheets/canonical/${page.pageId}-${System.currentTimeMillis()}.jpg")
                    ImageProcessor.saveJpeg(canonical, canonicalFile)
                    val scanId = UUID.randomUUID().toString()
                    val started = System.currentTimeMillis()
                    val readings = BroadsheetProcessor(this).process(canonical, page, scanId)
                    processed += Processed(page, scanId, candidate.imagePath, canonicalFile.absolutePath, readings, System.currentTimeMillis() - started, candidate.method)
                    canonical.recycle()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "broadsheet processing failed", t)
                runOnUiThread { status.text = "The pages were scanned, but score reading failed"; scanButton.isEnabled = true }
                return@execute
            }
            runOnUiThread {
                lifecycleScope.launch {
                    try {
                        processed.forEach { result ->
                            val template = repo.manifestFor(result.page.sheetId)
                            val manifestPath = template?.let {
                                File(filesDir, "broadsheets/templates/${it.sheetId}.json").apply { parentFile?.mkdirs(); writeText(it.toJson().toString(2)) }.absolutePath
                            }
                            dao.saveBroadsheet(BroadsheetEntity(result.page.sheetId, template?.classLabel ?: "Unknown class", template?.subjectGroup ?: result.page.subjectGroup ?: "Unknown subject", template?.templateVersion ?: result.page.templateVersion, template?.expectedPageIds?.size ?: 0, "REVIEW_REQUIRED", System.currentTimeMillis(), "LOCAL_ONLY", result.page.layoutFamily, manifestPath))
                            dao.deleteReadingsForSide(result.page.pageId)
                            dao.saveSide(SheetSideEntity(result.page.pageId, result.page.sheetId, result.page.pageNumber, result.page.totalSides, result.page.rowStart, result.page.rowEnd, System.currentTimeMillis(), result.sourcePath, result.canonicalPath, result.identityMethod, result.page.layoutId, result.page.subjectGroup, result.page.templateVersion))
                            dao.saveScan(ScanEntity(result.scanId, result.page.pageId, "SMART_BROADSHEET", result.page.pageNumber, System.currentTimeMillis(), result.sourcePath, result.canonicalPath, "{\"processing_ms\":${result.processingMs},\"layout_id\":\"${result.page.layoutId}\"}"))
                            dao.saveReadings(result.readings)
                        }
                        val manifest = repo.manifestFor(processed.first().page.sheetId)
                        val saved = dao.pages(processed.first().page.sheetId).map { it.sideId }.toSet()
                        status.text = when {
                            unknown.isNotEmpty() -> "${processed.size} page(s) processed; ${unknown.size} need identity review"
                            manifest?.isComplete(saved) == true -> "Broadsheet complete ✓ — opening review"
                            else -> "${saved.size} page(s) saved — more may be required"
                        }
                        scanButton.isEnabled = true
                        if (unknown.isEmpty()) startActivity(Intent(this@BroadsheetScannerActivity, BroadsheetReviewActivity::class.java).putExtra("sheetId", processed.first().page.sheetId))
                    } catch (t: Throwable) {
                        Log.e(TAG, "broadsheet local save failed", t)
                        status.text = "The scan was processed but could not be saved"
                        scanButton.isEnabled = true
                    }
                }
            }
        }
    }

    private suspend fun refreshSavedState() {
        val pages = dao.pages(repo.sheetId)
        val expected = repo.currentManifest().expectedPageIds
        status.text = when {
            pages.isEmpty() -> "Ready to scan a broadsheet page"
            expected == null -> "${pages.size} page(s) saved — page count is dynamic"
            repo.currentManifest().isComplete(pages.map { it.sideId }.toSet()) -> "All ${expected.size} expected page(s) are saved"
            else -> "${pages.size} page(s) saved — ${repo.currentManifest().missingPageIds(pages.map { it.sideId }.toSet()).size} page(s) may be missing"
        }
        scanButton.isEnabled = true
    }

    override fun onDestroy() { super.onDestroy(); exec.shutdown() }
}
