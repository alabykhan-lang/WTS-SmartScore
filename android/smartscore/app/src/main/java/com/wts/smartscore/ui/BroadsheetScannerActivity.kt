package com.wts.smartscore.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wts.smartscore.data.BroadsheetEntity
import com.wts.smartscore.data.ScanEntity
import com.wts.smartscore.data.SheetSideEntity
import com.wts.smartscore.data.SmartScoreDatabase
import com.wts.smartscore.scanner.BroadsheetProcessor
import com.wts.smartscore.scanner.ImageProcessor
import com.wts.smartscore.scanner.ImageProxyTools
import com.wts.smartscore.scanner.OpenCvDocumentDetector
import com.wts.smartscore.scanner.SheetIdentityResolver
import com.wts.smartscore.scanner.SideTemplateDef
import com.wts.smartscore.scanner.SmartScanEngine
import com.wts.smartscore.scanner.V2TemplateRepository
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class BroadsheetScannerActivity : AppCompatActivity(), SmartScanEngine.Listener {
    companion object { private const val TAG = "SmartScoreBroadsheet" }

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var engine: SmartScanEngine
    private val detector = OpenCvDocumentDetector()
    private val exec = Executors.newSingleThreadExecutor()
    private val dao by lazy { SmartScoreDatabase.get(this).dao() }
    private val repo by lazy { V2TemplateRepository(this) }
    private var lastCapturedPath: String? = null
    private var lastNormalizedPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "activity created")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 51)
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply {
            text = "SEARCHING FOR SMART BROADSHEET"
            gravity = Gravity.CENTER
            textSize = 18f
            setPadding(10, 18, 10, 18)
        }
        root.addView(status)
        preview = PreviewView(this)
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "Automatic capture. QR identifies the side when possible; if QR fails, select Side 1 or Side 2 without retaking the secured page."
            setPadding(18, 12, 18, 12)
        })
        setContentView(root)

        engine = SmartScanEngine(this).also { it.listener = this }
        startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                engine.bind(provider, this, cameraPreview, ImageAnalysis.Analyzer { image ->
                    try {
                        val mat = ImageProxyTools.lumaMat(image)
                        val assessment = detector.detect(mat)
                        mat.release()
                        engine.submitAssessment(assessment)
                    } catch (t: Throwable) {
                        Log.e(TAG, "frame analysis failed", t)
                        runOnUiThread { status.text = "SCANNER ERROR [analysis]: ${t.message ?: t.javaClass.simpleName}" }
                    } finally {
                        image.close()
                    }
                })
                Log.i(TAG, "camera bound")
            } catch (t: Throwable) {
                Log.e(TAG, "camera bind failed", t)
                status.text = "SCANNER ERROR [camera]: ${t.message ?: t.javaClass.simpleName}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onState(state: String) {
        runOnUiThread {
            status.text = when (state) {
                "DOCUMENT FOUND", "CAPTURING" -> "HOLD STEADY..."
                "SEARCHING" -> "SEARCHING FOR SHEET"
                else -> state
            }
        }
    }

    override fun onCaptured(path: String) {
        lastCapturedPath = path
        Log.i(TAG, "capture callback path=$path")
        status.post { status.text = "SCANNED ✓ — PROCESSING" }
        exec.execute {
            var stage = "decode"
            var original: Bitmap? = null
            var normalized: Bitmap? = null
            try {
                original = BitmapFactory.decodeFile(path) ?: throw IllegalStateException("Captured sheet could not be decoded")
                Log.i(TAG, "image saved/decoded")
                stage = "normalize"
                normalized = ImageProcessor.normalize(original)
                Log.i(TAG, "normalized image created ${normalized.width}x${normalized.height}")
                stage = "save-normalized"
                val normalizedFile = File(filesDir, "broadsheets/${System.currentTimeMillis()}.jpg")
                ImageProcessor.saveJpeg(normalized, normalizedFile)
                lastNormalizedPath = normalizedFile.absolutePath
                Log.i(TAG, "normalized sheet saved path=${normalizedFile.absolutePath}")
                stage = "identify-template"
                val resolved = try {
                    SheetIdentityResolver.resolveSideId(original, normalized)
                } catch (e: Throwable) {
                    Log.w(TAG, "QR/template identity failed; manual selection remains available", e)
                    null
                }
                val side = resolved?.let { repo.sideById(it) }
                if (side != null) {
                    Log.i(TAG, "template resolved side=${side.sideId}")
                    processSide(side, path, normalizedFile.absolutePath, normalized)
                    normalized = null
                } else {
                    Log.i(TAG, "template not resolved; opening manual side selector")
                    val retained = normalized
                    normalized = null
                    askSide(path, normalizedFile.absolutePath, retained)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "post-capture failure stage=$stage", t)
                normalized?.recycle()
                showProcessingError(stage, t)
            } finally {
                original?.recycle()
            }
        }
    }

    private fun showProcessingError(stage: String, error: Throwable) {
        runOnUiThread {
            val detail = error.message ?: error.javaClass.simpleName
            status.text = "PROCESSING ERROR [$stage]: $detail"
            AlertDialog.Builder(this)
                .setTitle("Smart Broadsheet processing error")
                .setMessage("Stage: $stage\nError: $detail\n\nThe scanner session is still open and the captured image has not been intentionally discarded.")
                .setPositiveButton("RETRY") { _, _ -> status.text = "REMOVE / RE-PRESENT SHEET TO RETRY" }
                .setNeutralButton("USE CAPTURED SHEET") { _, _ -> tryUseCapturedSheet() }
                .setNegativeButton("CANCEL") { _, _ -> status.text = "SCAN SESSION ACTIVE — PRESENT SHEET WHEN READY" }
                .show()
        }
    }

    private fun tryUseCapturedSheet() {
        val originalPath = lastCapturedPath
        val normalizedPath = lastNormalizedPath
        if (originalPath == null || normalizedPath == null) {
            status.text = "Captured sheet cannot be manually reviewed before normalization succeeds. RETRY the scan."
            return
        }
        exec.execute {
            try {
                val bitmap = BitmapFactory.decodeFile(normalizedPath)
                    ?: throw IllegalStateException("Saved normalized sheet could not be decoded")
                askSide(originalPath, normalizedPath, bitmap)
            } catch (t: Throwable) {
                Log.e(TAG, "use captured sheet failed", t)
                runOnUiThread { status.text = "CAPTURED-SHEET ERROR: ${t.message ?: t.javaClass.simpleName}" }
            }
        }
    }

    private fun askSide(originalPath: String, normalizedPath: String, bitmap: Bitmap) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Select broadsheet side")
                .setMessage("QR identity was not resolved. The captured page is secure; choose its side without retaking.")
                .setItems(arrayOf("Side 1", "Side 2")) { _, which ->
                    exec.execute {
                        try {
                            val side = repo.sideByNumber(which + 1)
                                ?: throw IllegalStateException("V2 Side ${which + 1} template is unavailable")
                            processSide(side, originalPath, normalizedPath, bitmap)
                        } catch (t: Throwable) {
                            Log.e(TAG, "manual side processing failed", t)
                            bitmap.recycle()
                            showProcessingError("manual-side", t)
                        }
                    }
                }
                .setNegativeButton("Cancel") { _, _ ->
                    bitmap.recycle()
                    status.text = "SCAN SAVED — SESSION REMAINS OPEN"
                }
                .show()
        }
    }

    private fun processSide(side: SideTemplateDef, originalPath: String, normalizedPath: String, bitmap: Bitmap) {
        val scanId = UUID.randomUUID().toString()
        val start = System.currentTimeMillis()
        val readings = try {
            Log.i(TAG, "ROI/OCR processing start side=${side.sideId}")
            BroadsheetProcessor(this).process(bitmap, side, scanId)
        } catch (t: Throwable) {
            Log.e(TAG, "ROI/OCR processing failed", t)
            bitmap.recycle()
            showProcessingError("roi-ocr", t)
            return
        }
        bitmap.recycle()
        Log.i(TAG, "ROI/OCR processing complete readings=${readings.size}")

        lifecycleScope.launch {
            try {
                dao.saveBroadsheet(BroadsheetEntity(side.sheetId, repo.classLabel, repo.subject, repo.templateVersion, side.totalSides, "REVIEW_REQUIRED", System.currentTimeMillis(), "LOCAL_ONLY"))
                dao.deleteReadingsForSide(side.sideId)
                dao.saveSide(SheetSideEntity(side.sideId, side.sheetId, side.sideNumber, side.totalSides, side.rowStart, side.rowEnd, System.currentTimeMillis(), originalPath, normalizedPath, "QR_OR_MANUAL"))
                dao.saveScan(ScanEntity(scanId, side.sideId, "SMART_BROADSHEET", side.sideNumber, System.currentTimeMillis(), originalPath, normalizedPath, "{\"processing_ms\":${System.currentTimeMillis() - start}}"))
                dao.saveReadings(readings)
                Log.i(TAG, "local broadsheet/session saved side=${side.sideId}")
                val count = dao.sideCount(side.sheetId)
                if (count >= side.totalSides) {
                    status.text = "BROADSHEET COMPLETE ✓ — REVIEW"
                    Log.i(TAG, "launching BroadsheetReviewActivity sheet=${side.sheetId}")
                    startActivity(Intent(this@BroadsheetScannerActivity, BroadsheetReviewActivity::class.java).putExtra("sheetId", side.sheetId))
                } else {
                    status.text = "SIDE ${side.sideNumber} SAVED ✓ — FLIP / PRESENT OTHER SIDE"
                }
            } catch (t: Throwable) {
                Log.e(TAG, "local save/navigation failed", t)
                showProcessingError("local-save", t)
            }
        }
    }

    override fun onError(message: String) {
        Log.e(TAG, "capture error: $message")
        runOnUiThread { status.text = "SCANNER ERROR [capture]: $message" }
    }

    override fun onDestroy() {
        Log.i(TAG, "activity destroyed finishing=$isFinishing changingConfig=$isChangingConfigurations")
        super.onDestroy()
        engine.shutdown()
        exec.shutdown()
    }
}
