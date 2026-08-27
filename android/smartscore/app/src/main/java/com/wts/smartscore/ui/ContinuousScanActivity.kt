package com.wts.smartscore.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.wts.smartscore.BuildConfig
import com.wts.smartscore.model.ScanState
import com.wts.smartscore.scanner.AutoCaptureController
import com.wts.smartscore.scanner.ContinuousSessionProcessor
import com.wts.smartscore.scanner.ImageProxyTools
import com.wts.smartscore.scanner.OpenCvDocumentDetector
import com.wts.smartscore.scanner.OpenCvRuntime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ContinuousScanActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_DEBUG = "debug"
        private const val CAMERA_REQ = 6401
    }

    private lateinit var preview: PreviewView
    private lateinit var overlay: DocumentGuideOverlay
    private lateinit var status: TextView
    private lateinit var counter: TextView
    private lateinit var processing: TextView
    private lateinit var debugMetrics: TextView
    private lateinit var pauseButton: Button
    private lateinit var imageCapture: ImageCapture
    private lateinit var processor: ContinuousSessionProcessor
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private var controller = AutoCaptureController(4)
    private val detector = OpenCvDocumentDetector()
    private val capturing = AtomicBoolean(false)
    private var paused = false
    private var finishingSession = false
    private var pageCount = 0
    private var processedCount = 0
    private var debugMode = false
    @Volatile private var lastAnalysisError: String? = null
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }
    private val mode by lazy { intent.getStringExtra(EXTRA_MODE) ?: ContinuousSessionProcessor.MODE_DOCUMENT }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        debugMode = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEBUG, false)
        buildUi()
        processor = ContinuousSessionProcessor(this, mode) { done, total ->
            processedCount = done
            runOnUiThread { processing.text = if (done < total) "Processing $done / $total" else "Processed $done" }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            initializeScanner()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQ)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQ && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) initializeScanner()
        else { Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show(); finish() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0C1320.toInt()) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(18, 20, 18, 14) }
        val heading = TextView(this).apply {
            text = when (mode) {
                ContinuousSessionProcessor.MODE_SCRIPT -> "CONTINUOUS SCRIPTS"
                ContinuousSessionProcessor.MODE_BROADSHEET -> "CONTINUOUS BROADSHEETS"
                else -> "CONTINUOUS DOCUMENTS"
            }
            textSize = 19f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            setOnLongClickListener {
                if (!BuildConfig.DEBUG) return@setOnLongClickListener false
                debugMode = !debugMode
                debugMetrics.visibility = if (debugMode) View.VISIBLE else View.GONE
                Toast.makeText(
                    this@ContinuousScanActivity,
                    if (debugMode) "Scanner diagnostics ON" else "Scanner diagnostics OFF",
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
        }
        top.addView(heading)
        counter = TextView(this).apply { text = "0 pages"; textSize = 34f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER }
        top.addView(counter)
        status = TextView(this).apply { text = "STARTING CAMERA"; textSize = 17f; setTextColor(0xFF8FC3FF.toInt()); gravity = Gravity.CENTER; setPadding(0, 8, 0, 0) }
        top.addView(status)
        processing = TextView(this).apply { text = ""; textSize = 12f; setTextColor(0xFFAAB5C5.toInt()); gravity = Gravity.CENTER }
        top.addView(processing)
        debugMetrics = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(0xFF9FE8C0.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
            visibility = if (debugMode) View.VISIBLE else View.GONE
        }
        top.addView(debugMetrics)
        root.addView(top)

        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        overlay = DocumentGuideOverlay(this)
        val cameraFrame = FrameLayout(this).apply {
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(this@ContinuousScanActivity.overlay, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(cameraFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(12, 12, 12, 18) }
        pauseButton = Button(this).apply { text = "Pause"; setOnClickListener { togglePause() } }
        val manual = Button(this).apply { text = "Manual Capture"; setOnClickListener { if (!paused) capturePage(true) } }
        val finishButton = Button(this).apply { text = "Finish"; setOnClickListener { finishSession() } }
        actions.addView(pauseButton, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(manual, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(finishButton, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        setContentView(root)
    }

    private fun initializeScanner() {
        status.text = "PREPARING SCANNER"
        analysisExecutor.execute {
            val cv = OpenCvRuntime.initialize(this)
            runOnUiThread {
                if (cv.state == OpenCvRuntime.State.OPENCV_READY) startCamera()
                else { status.text = "SCANNER UNAVAILABLE"; Toast.makeText(this, "Continuous scanner could not initialize", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get(); cameraProvider = provider
            val targetRotation = preview.display?.rotation ?: Surface.ROTATION_0
            val previewUseCase = Preview.Builder()
                .setTargetRotation(targetRotation)
                .build()
                .also { it.setSurfaceProvider(preview.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(95)
                .setTargetRotation(targetRotation)
                .build()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setTargetRotation(targetRotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                try {
                    if (paused || finishingSession || !OpenCvRuntime.isReady()) return@setAnalyzer
                    val mat = ImageProxyTools.lumaMat(image)
                    val assessment = try {
                        detector.detect(mat, image.imageInfo.rotationDegrees)
                    } finally {
                        mat.release()
                    }
                    val shouldCapture = controller.onFrame(assessment)
                    runOnUiThread {
                        overlay.show(
                            assessment.quad,
                            assessment.frameWidth,
                            assessment.frameHeight,
                            positive = controller.state == ScanState.DOCUMENT_FOUND || controller.state == ScanState.CAPTURING
                        )
                        status.text = friendly(controller.state)
                        if (debugMode) debugMetrics.text = diagnostics(assessment)
                    }
                    if (shouldCapture) {
                        capturePage(false)
                    }
                } catch (t: Throwable) {
                    lastAnalysisError = "${t.javaClass.simpleName}: ${t.message ?: "analysis error"}"
                    runOnUiThread {
                        status.text = "FIND DOCUMENT"
                        if (debugMode) debugMetrics.text = "ANALYSIS ERROR\n${lastAnalysisError}"
                    }
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis, imageCapture)
            status.text = "FIND DOCUMENT"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun friendly(state: ScanState): String = when (state) {
        ScanState.SEARCHING -> "FIND DOCUMENT"
        ScanState.DOCUMENT_FOUND, ScanState.HOLD_STEADY, ScanState.CAPTURING -> "HOLD STEADY"
        ScanState.MOVE_CLOSER -> "MOVE CLOSER"
        ScanState.MOVE_BACK -> "MOVE BACK"
        ScanState.ALIGN -> "ADJUST DOCUMENT"
        ScanState.SCANNED -> "SCANNED ✓"
        ScanState.WAITING_FOR_PAGE_EXIT -> "READY FOR NEXT — REMOVE PAGE"
    }

    private fun diagnostics(a: com.wts.smartscore.scanner.FrameAssessment): String {
        fun pct(value: Float) = String.format(java.util.Locale.US, "%.1f%%", value * 100f)
        fun number(value: Double) = String.format(java.util.Locale.US, "%.1f", value)
        return buildString {
            append("frame=${a.frameWidth}×${a.frameHeight} rot=${a.rotationDegrees}° ")
            append("quad=${if (a.quad == null) "NO" else "YES"} method=${a.detectorMethod}\n")
            append("coverage=${pct(a.coverage)} size=${pct(a.pageWidthFraction)}×${pct(a.pageHeightFraction)} ")
            append("aspect=${number(a.aspectRatio.toDouble())}:1 edge=${pct(a.edgeMargin)}\n")
            append("blur=${number(a.blurScore)} glare=${number(a.glare)} ")
            append("stability=${number(a.stabilityScore.toDouble())} stable=${a.stable}\n")
            append("block=${controller.blockReason}")
            lastAnalysisError?.let { append("\nerror=$it") }
        }
    }

    private fun capturePage(manual: Boolean) {
        if (!::imageCapture.isInitialized || finishingSession || paused || !capturing.compareAndSet(false, true)) return
        val next = pageCount + 1
        val raw = processor.rawFile(next)
        val options = ImageCapture.OutputFileOptions.Builder(raw).build()
        imageCapture.takePicture(options, captureExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                pageCount = next
                controller.captured()
                capturing.set(false)
                processor.enqueue(next, raw.absolutePath)
                feedback()
                runOnUiThread {
                    counter.text = "$pageCount pages"
                    status.text = "SCANNED ✓"
                    processing.text = "Processing in background…"
                }
            }
            override fun onError(exception: ImageCaptureException) {
                capturing.set(false)
                controller.captureFailed()
                runOnUiThread { status.text = if (manual) "CAPTURE FAILED" else "FIND DOCUMENT" }
            }
        })
    }

    private fun feedback() {
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 130)
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") vibrator.vibrate(90)
    }

    private fun togglePause() {
        paused = !paused
        pauseButton.text = if (paused) "Resume" else "Pause"
        status.text = if (paused) "PAUSED" else "FIND DOCUMENT"
        overlay.visibility = if (paused) View.INVISIBLE else View.VISIBLE
    }

    private fun finishSession() {
        if (finishingSession) return
        if (capturing.get()) {
            status.text = "SAVING LAST PAGE"
            Handler(Looper.getMainLooper()).postDelayed({ finishSession() }, 180)
            return
        }
        finishingSession = true
        paused = true
        cameraProvider?.unbindAll()
        status.text = "FINISHING SESSION"
        processing.text = "Waiting for background processing…"
        processor.finish { manifest ->
            runOnUiThread {
                startActivity(Intent(this, ContinuousSessionReviewActivity::class.java).putExtra("manifest", manifest.absolutePath))
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
        processor.shutdown()
        tone.release()
    }
}
