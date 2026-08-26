package com.wts.smartscore.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wts.smartscore.data.*
import com.wts.smartscore.scanner.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class BroadsheetScannerActivity : AppCompatActivity(), SmartScanEngine.Listener {
    companion object { private const val TAG = "SmartScoreBroadsheet" }
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var engine: SmartScanEngine
    private val detector = OpenCvDocumentDetector()
    private val exec = Executors.newSingleThreadExecutor()
    private val dao by lazy { SmartScoreDatabase.get(this).dao() }
    private val repo by lazy { V2TemplateRepository(this) }
    private var lastCapturedPath: String? = null
    private var lastNormalizedPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 51)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { text = "OPENCV INITIALIZING"; gravity = Gravity.CENTER; textSize = 18f; setPadding(10,12,10,12) }
        root.addView(status)
        root.addView(Button(this).apply { text = "DETAILS"; setOnClickListener { details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE } })
        details = TextView(this).apply { visibility = View.GONE; textSize = 11f; setPadding(12,6,12,8) }
        root.addView(details)
        preview = PreviewView(this); root.addView(preview, LinearLayout.LayoutParams(-1,0,1f))
        root.addView(TextView(this).apply { text = "Automatic capture. QR identifies the side when possible; manual side selection remains available."; setPadding(18,10,18,10) })
        setContentView(root)
        engine = SmartScanEngine(this).also { it.listener = this }
        initializeOpenCvAndStart()
    }

    private fun initializeOpenCvAndStart() {
        status.text = "OPENCV INITIALIZING"
        exec.execute {
            val result = OpenCvRuntime.initialize(this)
            runOnUiThread {
                details.text = result.details
                if (result.state == OpenCvRuntime.State.OPENCV_READY) {
                    status.text = "OPENCV READY — SEARCHING FOR SHEET"
                    startCamera()
                } else {
                    status.text = "SCANNER ERROR — Tap DETAILS"
                    Toast.makeText(this, "OpenCV failed to initialize. Tap DETAILS.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                engine.bind(provider, this, cameraPreview, ImageAnalysis.Analyzer { image ->
                    try {
                        if (!OpenCvRuntime.isReady()) return@Analyzer
                        val mat = ImageProxyTools.lumaMat(image)
                        val assessment = detector.detect(mat)
                        mat.release(); engine.submitAssessment(assessment)
                    } catch (t: Throwable) {
                        Log.e(TAG, "frame analysis failed", t); showScannerError("analysis", t)
                    } finally { image.close() }
                })
            } catch (t: Throwable) { Log.e(TAG, "camera bind failed", t); showScannerError("camera", t) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showScannerError(stage: String, t: Throwable) {
        runOnUiThread {
            details.text = "stage=$stage\n${t.javaClass.name}: ${t.message}\n${OpenCvRuntime.diagnostics()}"
            status.text = "SCANNER ERROR — Tap DETAILS"
        }
    }

    override fun onState(state: String) { runOnUiThread { status.text = when(state){ "DOCUMENT FOUND","CAPTURING"->"HOLD STEADY"; "SEARCHING"->"SEARCHING FOR SHEET"; else->state } } }

    override fun onCaptured(path: String) {
        lastCapturedPath = path; status.post { status.text = "SCANNED ✓ — PROCESSING" }
        exec.execute {
            var stage = "decode"; var original: Bitmap? = null; var normalized: Bitmap? = null
            try {
                original = BitmapFactory.decodeFile(path) ?: throw IllegalStateException("Captured sheet could not be decoded")
                stage = "normalize"; normalized = ImageProcessor.normalize(original)
                stage = "save-normalized"; val normalizedFile = File(filesDir,"broadsheets/${System.currentTimeMillis()}.jpg"); ImageProcessor.saveJpeg(normalized,normalizedFile); lastNormalizedPath = normalizedFile.absolutePath
                stage = "identify-template"; val resolved = try { SheetIdentityResolver.resolveSideId(original, normalized) } catch (_: Throwable) { null }
                val side = resolved?.let { repo.sideById(it) }
                if (side != null) { processSide(side,path,normalizedFile.absolutePath,normalized); normalized=null }
                else { val retained=normalized; normalized=null; askSide(path,normalizedFile.absolutePath,retained) }
            } catch (t: Throwable) { normalized?.recycle(); showProcessingError(stage,t) }
            finally { original?.recycle() }
        }
    }

    private fun showProcessingError(stage:String,error:Throwable){ runOnUiThread { details.text="stage=$stage\n${error.javaClass.name}: ${error.message}\n${OpenCvRuntime.diagnostics()}"; status.text="SCANNER ERROR — Tap DETAILS"; AlertDialog.Builder(this).setTitle("Smart Broadsheet processing error").setMessage("Processing failed at $stage. The scanner session remains open.").setPositiveButton("RETRY"){_,_->status.text="PRESENT SHEET TO RETRY"}.setNeutralButton("USE CAPTURED SHEET"){_,_->tryUseCapturedSheet()}.setNegativeButton("CANCEL"){_,_->status.text="SEARCHING FOR SHEET"}.show() } }

    private fun tryUseCapturedSheet(){ val originalPath=lastCapturedPath; val normalizedPath=lastNormalizedPath; if(originalPath==null||normalizedPath==null){status.text="SCANNER ERROR — Tap DETAILS";return}; exec.execute{try{val bitmap=BitmapFactory.decodeFile(normalizedPath)?:throw IllegalStateException("Saved normalized sheet could not be decoded");askSide(originalPath,normalizedPath,bitmap)}catch(t:Throwable){showScannerError("captured-sheet",t)}} }

    private fun askSide(originalPath:String, normalizedPath:String, bitmap:Bitmap){ runOnUiThread { AlertDialog.Builder(this).setTitle("Select broadsheet side").setMessage("QR identity was not resolved. Choose the captured side without retaking.").setItems(arrayOf("Side 1","Side 2")){_,which->exec.execute{try{val side=repo.sideByNumber(which+1)?:throw IllegalStateException("V2 Side ${which+1} template unavailable");processSide(side,originalPath,normalizedPath,bitmap)}catch(t:Throwable){bitmap.recycle();showProcessingError("manual-side",t)}}}.setNegativeButton("Cancel"){_,_->bitmap.recycle();status.text="SEARCHING FOR SHEET"}.show() } }

    private fun processSide(side:SideTemplateDef, originalPath:String, normalizedPath:String, bitmap:Bitmap){ val scanId=UUID.randomUUID().toString(); val start=System.currentTimeMillis(); val readings=try{BroadsheetProcessor(this).process(bitmap,side,scanId)}catch(t:Throwable){bitmap.recycle();showProcessingError("roi-ocr",t);return};bitmap.recycle();lifecycleScope.launch{try{dao.saveBroadsheet(BroadsheetEntity(side.sheetId,repo.classLabel,repo.subject,repo.templateVersion,side.totalSides,"REVIEW_REQUIRED",System.currentTimeMillis(),"LOCAL_ONLY"));dao.deleteReadingsForSide(side.sideId);dao.saveSide(SheetSideEntity(side.sideId,side.sheetId,side.sideNumber,side.totalSides,side.rowStart,side.rowEnd,System.currentTimeMillis(),originalPath,normalizedPath,"QR_OR_MANUAL"));dao.saveScan(ScanEntity(scanId,side.sideId,"SMART_BROADSHEET",side.sideNumber,System.currentTimeMillis(),originalPath,normalizedPath,"{\"processing_ms\":${System.currentTimeMillis()-start}}"));dao.saveReadings(readings);val count=dao.sideCount(side.sheetId);if(count>=side.totalSides){status.text="BROADSHEET COMPLETE ✓ — REVIEW";startActivity(Intent(this@BroadsheetScannerActivity,BroadsheetReviewActivity::class.java).putExtra("sheetId",side.sheetId))}else status.text="SIDE ${side.sideNumber} SAVED ✓ — PRESENT OTHER SIDE"}catch(t:Throwable){showProcessingError("local-save",t)}} }

    override fun onError(message:String){showScannerError("capture",IllegalStateException(message))}
    override fun onDestroy(){super.onDestroy();engine.shutdown();exec.shutdown()}
}
