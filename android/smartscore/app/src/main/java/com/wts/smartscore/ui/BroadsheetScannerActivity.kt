package com.wts.smartscore.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.*
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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

class BroadsheetScannerActivity : AppCompatActivity() {
    companion object { private const val TAG = "SmartScoreBroadsheet" }

    private data class Candidate(val pageNumber: Int, val imagePath: String, var sideNumber: Int?, var method: String)
    private data class Processed(
        val side: SideTemplateDef,
        val scanId: String,
        val sourcePath: String,
        val canonicalPath: String,
        val readings: List<com.wts.smartscore.data.ScoreReadingEntity>,
        val processingMs: Long,
        val identityMethod: String
    )

    private lateinit var status: TextView
    private lateinit var scanButton: Button
    private val scanner by lazy { MlKitDocumentScan.client(2) }
    private val dao by lazy { SmartScoreDatabase.get(this).dao() }
    private val repo by lazy { V2TemplateRepository(this) }
    private val exec = Executors.newSingleThreadExecutor()

    private val launcher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { activityResult ->
        if (activityResult.resultCode != Activity.RESULT_OK) {
            status.text = "Scan cancelled — your saved sides are unchanged"
            scanButton.isEnabled = true
            return@registerForActivityResult
        }
        try {
            val result = GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
                ?: throw IllegalStateException("Scanner returned no pages")
            status.text = "Preparing scanned broadsheet…"
            scanButton.isEnabled = false
            val scan = MlKitDocumentScan.persistResult(this, result, File(filesDir, "broadsheets/mlkit-scans"))
            identifyPages(scan)
        } catch (t: Throwable) {
            Log.e(TAG, "ML Kit broadsheet result failed", t)
            status.text = "Unable to use this scan. Tap Scan Broadsheet to try again."
            scanButton.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 28, 22, 22) }
        root.addView(TextView(this).apply {
            text = "Smart Broadsheet"
            textSize = 26f
            setPadding(0, 0, 0, 6)
        })
        root.addView(TextView(this).apply {
            text = "Scan one or both sides like a normal document. SmartScore identifies the side and reads the score cells after the clean scan is returned."
            textSize = 14f
            setPadding(0, 0, 0, 22)
        })
        status = TextView(this).apply {
            text = "Ready to scan"
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(12, 18, 12, 18)
        }
        root.addView(status)
        scanButton = Button(this).apply {
            text = "SCAN BROADSHEET"
            setOnClickListener { startScan() }
        }
        root.addView(scanButton)
        root.addView(TextView(this).apply {
            text = "Tip: you can scan Side 1 and Side 2 in the same scanner session. If the QR cannot be read, SmartScore will ask which side you scanned instead of forcing a retake."
            textSize = 13f
            setPadding(0, 18, 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })

        lifecycleScope.launch { refreshSavedState() }
        if (savedInstanceState == null) startScan()
    }

    private fun startScan() {
        status.text = "Opening document scanner…"
        scanButton.isEnabled = false
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { e ->
                Log.e(TAG, "scanner unavailable", e)
                status.text = "Document scanner unavailable. Please try again."
                scanButton.isEnabled = true
            }
    }

    private fun identifyPages(scan: SmartScanResult) {
        exec.execute {
            val candidates = scan.pages.map { page ->
                val bitmap = BitmapFactory.decodeFile(page.imagePath)
                val resolved = try {
                    if (bitmap != null) SheetIdentityResolver.resolveSideId(bitmap) else null
                } catch (t: Throwable) {
                    Log.w(TAG, "QR identity unavailable for page ${page.pageNumber}", t)
                    null
                } finally {
                    bitmap?.recycle()
                }
                val side = resolved?.let { repo.sideById(it) }
                Candidate(page.pageNumber, page.imagePath, side?.sideNumber, if (side != null) "MLKIT_QR" else "MLKIT_MANUAL")
            }.toMutableList()
            runOnUiThread { resolveUnknownSides(candidates) }
        }
    }

    private fun resolveUnknownSides(candidates: MutableList<Candidate>) {
        val unknown = candidates.filter { it.sideNumber == null }
        if (unknown.isEmpty()) {
            processCandidates(candidates)
            return
        }

        val knownNumbers = candidates.mapNotNull { it.sideNumber }.toSet()
        if (unknown.size == 1 && knownNumbers.size == 1) {
            unknown.first().sideNumber = if (knownNumbers.contains(1)) 2 else 1
            processCandidates(candidates)
            return
        }

        if (unknown.size == 2 && candidates.size == 2 && knownNumbers.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Which side was scanned first?")
                .setMessage("The scan is already saved. QR identity was unclear, so choose the first page only; SmartScore will assign the second page to the other side.")
                .setItems(arrayOf("First page is Side 1", "First page is Side 2")) { _, which ->
                    val first = if (which == 0) 1 else 2
                    unknown[0].sideNumber = first
                    unknown[1].sideNumber = if (first == 1) 2 else 1
                    processCandidates(candidates)
                }
                .setNegativeButton("Cancel") { _, _ ->
                    status.text = "Scan saved but side not selected"
                    scanButton.isEnabled = true
                }
                .show()
            return
        }

        promptSideForCandidate(candidates, unknown.first())
    }

    private fun promptSideForCandidate(candidates: MutableList<Candidate>, candidate: Candidate) {
        AlertDialog.Builder(this)
            .setTitle("Select broadsheet side")
            .setMessage("Page ${candidate.pageNumber} was scanned successfully, but its QR identity was not clear.")
            .setItems(arrayOf("Side 1", "Side 2")) { _, which ->
                candidate.sideNumber = which + 1
                val remaining = candidates.firstOrNull { it.sideNumber == null }
                if (remaining != null) promptSideForCandidate(candidates, remaining) else processCandidates(candidates)
            }
            .setNegativeButton("Cancel") { _, _ ->
                status.text = "Scan saved but side not selected"
                scanButton.isEnabled = true
            }
            .show()
    }

    private fun processCandidates(candidates: List<Candidate>) {
        status.text = "Reading scores…"
        scanButton.isEnabled = false
        exec.execute {
            val cv = OpenCvRuntime.initialize(this)
            if (cv.state != OpenCvRuntime.State.OPENCV_READY) {
                Log.e(TAG, "OpenCV unavailable for score ROI processing: ${cv.details}")
                runOnUiThread {
                    status.text = "The sheet was scanned, but score processing is unavailable. Try again."
                    scanButton.isEnabled = true
                }
                return@execute
            }

            val processed = mutableListOf<Processed>()
            try {
                candidates.forEach { candidate ->
                    val side = repo.sideByNumber(candidate.sideNumber ?: error("Missing side assignment"))
                        ?: error("Broadsheet side template is unavailable")
                    val source = BitmapFactory.decodeFile(candidate.imagePath)
                        ?: error("Scanned page could not be opened")
                    val targetW = 1684
                    val targetH = 1191
                    val canonical: Bitmap = Bitmap.createScaledBitmap(source, targetW, targetH, true)
                    if (canonical !== source) source.recycle()
                    val canonicalFile = File(filesDir, "broadsheets/canonical/${side.sideId}-${System.currentTimeMillis()}.jpg")
                    ImageProcessor.saveJpeg(canonical, canonicalFile)
                    val scanId = UUID.randomUUID().toString()
                    val started = System.currentTimeMillis()
                    val readings = BroadsheetProcessor(this).process(canonical, side, scanId)
                    val elapsed = System.currentTimeMillis() - started
                    canonical.recycle()
                    processed += Processed(side, scanId, candidate.imagePath, canonicalFile.absolutePath, readings, elapsed, candidate.method)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "broadsheet processing failed", t)
                runOnUiThread {
                    status.text = "The page was scanned, but score reading failed. You can scan again without changing saved records."
                    scanButton.isEnabled = true
                }
                return@execute
            }

            runOnUiThread {
                lifecycleScope.launch {
                    try {
                        processed.forEach { p ->
                            dao.saveBroadsheet(BroadsheetEntity(
                                p.side.sheetId, repo.classLabel, repo.subject, repo.templateVersion,
                                p.side.totalSides, "REVIEW_REQUIRED", System.currentTimeMillis(), "LOCAL_ONLY"
                            ))
                            dao.deleteReadingsForSide(p.side.sideId)
                            dao.saveSide(SheetSideEntity(
                                p.side.sideId, p.side.sheetId, p.side.sideNumber, p.side.totalSides,
                                p.side.rowStart, p.side.rowEnd, System.currentTimeMillis(), p.sourcePath,
                                p.canonicalPath, p.identityMethod
                            ))
                            dao.saveScan(ScanEntity(
                                p.scanId, p.side.sideId, "SMART_BROADSHEET", p.side.sideNumber,
                                System.currentTimeMillis(), p.sourcePath, p.canonicalPath,
                                "{\"processing_ms\":${p.processingMs}}"
                            ))
                            dao.saveReadings(p.readings)
                        }
                        val count = dao.sideCount(repo.sheetId)
                        if (count >= 2) {
                            status.text = "Broadsheet complete ✓ — opening review"
                            startActivity(Intent(this@BroadsheetScannerActivity, BroadsheetReviewActivity::class.java).putExtra("sheetId", repo.sheetId))
                        } else {
                            val saved = dao.sides(repo.sheetId).firstOrNull()?.sideNumber ?: 1
                            status.text = "Side $saved saved ✓ — scan the other side when ready"
                            scanButton.text = "SCAN OTHER SIDE"
                            scanButton.isEnabled = true
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "broadsheet local save failed", t)
                        status.text = "The scan was processed but could not be saved. Please retry."
                        scanButton.isEnabled = true
                    }
                }
            }
        }
    }

    private suspend fun refreshSavedState() {
        val sides = dao.sides(repo.sheetId)
        when (sides.size) {
            0 -> status.text = "Ready to scan Side 1 or Side 2"
            1 -> {
                status.text = "Side ${sides.first().sideNumber} is already saved — scan the other side"
                scanButton.text = "SCAN OTHER SIDE"
            }
            else -> status.text = "Both sides are saved — scan again to replace a side or open Saved Broadsheets"
        }
        scanButton.isEnabled = true
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown()
    }
}
