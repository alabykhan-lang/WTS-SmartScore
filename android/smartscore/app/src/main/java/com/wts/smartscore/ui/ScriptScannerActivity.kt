package com.wts.smartscore.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.wts.smartscore.R
import com.wts.smartscore.data.ScriptEntity
import com.wts.smartscore.data.ScriptPageEntity
import com.wts.smartscore.data.SmartScoreDatabase
import com.wts.smartscore.scanner.LocalProcessingQueue
import com.wts.smartscore.scanner.MlKitDocumentScan
import com.wts.smartscore.scanner.ProcessingTaskTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One Google multipage session. Identity and grouping happen after capture. */
class ScriptScannerActivity : AppCompatActivity() {
    private val dao by lazy { SmartScoreDatabase.get(this).dao() }
    private val scanner by lazy { MlKitDocumentScan.client(50) }
    private var sessionId: String = ""
    private lateinit var status: TextView
    private lateinit var pageSummary: TextView
    private lateinit var pagesList: LinearLayout
    private lateinit var scanButton: Button
    private lateinit var doneButton: Button

    private val launcher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            status.text = "Scan cancelled. Saved pages remain available."
            scanButton.isEnabled = true
            return@registerForActivityResult
        }
        val document = runCatching { GmsDocumentScanningResult.fromActivityResultIntent(result.data) }.getOrNull()
        if (document == null) {
            status.text = "The scanner returned no pages. Try again when ready."
            scanButton.isEnabled = true
            return@registerForActivityResult
        }
        saveScan(document)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = savedInstanceState?.getString("session_id")
            ?: intent.getStringExtra("session_id")
            ?: UUID.randomUUID().toString()
        buildUi()
        lifecycleScope.launch {
            renderPages()
            if (savedInstanceState == null && dao.scriptPages(sessionId).isEmpty()) startScan()
        }
    }

    private fun buildUi() {
        val background = ContextCompat.getColor(this, R.color.smartscore_background)
        val text = ContextCompat.getColor(this, R.color.smartscore_text)
        val muted = ContextCompat.getColor(this, R.color.smartscore_text_muted)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(28))
            setBackgroundColor(background)
        }
        root.addView(TextView(this).apply { text = "SCRIPT SCANNER"; textSize = 12f; letterSpacing = 0.12f; setTextColor(muted) })
        root.addView(TextView(this).apply {
            text = "Scan a pile of scripts"
            textSize = 28f
            setTextColor(text)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(3))
        })
        root.addView(TextView(this).apply {
            text = "Capture cover pages and continuation pages together in Google's Quick Scan. SmartScore groups students afterwards."
            textSize = 14f
            setTextColor(muted)
            setPadding(0, 0, 0, dp(16))
        })
        pageSummary = TextView(this).apply { text = "No pages saved yet"; textSize = 15f; setTextColor(text); setPadding(dp(16), dp(14), dp(16), dp(14)); setBackgroundColor(ContextCompat.getColor(this@ScriptScannerActivity, R.color.smartscore_surface)) }
        root.addView(pageSummary, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        status = TextView(this).apply { text = "Ready to scan"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, dp(8), 0, dp(12)) }
        root.addView(status)
        scanButton = Button(this).apply { text = "SCAN PAGES"; setOnClickListener { startScan() } }
        root.addView(scanButton)
        pagesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pagesList)
        doneButton = Button(this).apply { text = "DONE"; isEnabled = false; setOnClickListener { finishToRecords() } }
        root.addView(doneButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })
        root.addView(TextView(this).apply { text = "Saved locally • identity review appears only for exceptions"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, dp(12), 0, 0) })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun startScan() {
        scanButton.isEnabled = false
        status.text = "Opening Google document scanner…"
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { error ->
                status.text = "Document scanner unavailable: ${error.message ?: error.javaClass.simpleName}"
                scanButton.isEnabled = true
            }
    }

    private fun saveScan(document: GmsDocumentScanningResult) {
        scanButton.isEnabled = false
        doneButton.isEnabled = false
        status.text = "Saving corrected pages locally…"
        lifecycleScope.launch {
            runCatching {
                val scan = withContext(Dispatchers.IO) {
                    MlKitDocumentScan.persistResult(this@ScriptScannerActivity, document, File(filesDir, "scripts/sessions/$sessionId"))
                }
                withContext(Dispatchers.IO) {
                    val existing = dao.scriptPages(sessionId)
                    var next = existing.maxOfOrNull { it.pageNumber }?.plus(1) ?: 1
                    val now = System.currentTimeMillis()
                    scan.pages.forEach { page ->
                        val pageId = "$sessionId-page-${next.toString().padStart(3, '0')}"
                        dao.saveScriptPage(ScriptPageEntity(
                            pageId = pageId,
                            scriptId = sessionId,
                            pageNumber = next,
                            imagePath = page.imagePath,
                            normalizedPath = page.imagePath,
                            capturedAt = now,
                            analysisState = "PENDING",
                            pageClass = "UNKNOWN",
                            boundaryScore = 0.0,
                            identityJson = null,
                            ocrText = "",
                            sessionId = sessionId
                        ))
                        LocalProcessingQueue.enqueue(this@ScriptScannerActivity, ProcessingTaskTypes.EXTRACT_SCRIPT_IDENTITY, sessionId, JSONObject().put("page_id", pageId))
                        next++
                    }
                    dao.saveScript(ScriptEntity(
                        scriptId = sessionId,
                        studentRef = null,
                        subject = null,
                        testRef = null,
                        createdAt = dao.script(sessionId)?.createdAt ?: now,
                        completedAt = null,
                        completionState = "SCANNED",
                        pageCount = dao.scriptPages(sessionId).size,
                        sessionId = sessionId,
                        identityStatus = "UNIDENTIFIED",
                        identityConfidence = 0.0
                    ))
                    LocalProcessingQueue.enqueue(this@ScriptScannerActivity, ProcessingTaskTypes.SEGMENT_SCRIPTS, sessionId)
                }
            }.onSuccess {
                scanButton.isEnabled = true
                doneButton.isEnabled = true
                status.text = "Saved locally ✓  •  you can add more pages"
                renderPages()
            }.onFailure { error ->
                scanButton.isEnabled = true
                doneButton.isEnabled = true
                status.text = "Pages were not added: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private fun renderPages() {
        lifecycleScope.launch {
            val pages = withContext(Dispatchers.IO) { dao.scriptPages(sessionId) }
            pageSummary.text = if (pages.isEmpty()) "No pages saved yet" else "${pages.size} page${if (pages.size == 1) "" else "s"} saved locally"
            pagesList.removeAllViews()
            pages.forEach { page -> pagesList.addView(pageCard(page)) }
            doneButton.isEnabled = pages.isNotEmpty()
        }
    }

    private fun pageCard(page: ScriptPageEntity): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            strokeWidth = 1
            strokeColor = ContextCompat.getColor(this@ScriptScannerActivity, R.color.smartscore_border)
            setCardBackgroundColor(ContextCompat.getColor(this@ScriptScannerActivity, R.color.smartscore_surface))
            setContentPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, dp(142)).apply { bottomMargin = dp(10) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(this).apply { setImageBitmap(BitmapFactory.decodeFile(page.normalizedPath ?: page.imagePath)); scaleType = ImageView.ScaleType.CENTER_CROP }, LinearLayout.LayoutParams(dp(92), dp(118)))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        labels.addView(TextView(this).apply { text = "Page ${page.pageNumber}"; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(ContextCompat.getColor(this@ScriptScannerActivity, R.color.smartscore_text)) })
        labels.addView(TextView(this).apply { text = pageState(page); textSize = 14f; setTextColor(ContextCompat.getColor(this@ScriptScannerActivity, R.color.smartscore_text_muted)); setPadding(0, dp(5), 0, 0) })
        labels.addView(TextView(this).apply { text = "Saved locally"; textSize = 12f; setTextColor(ContextCompat.getColor(this@ScriptScannerActivity, R.color.smartscore_text_muted)); setPadding(0, dp(8), 0, 0) })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
        return card
    }

    private fun pageState(page: ScriptPageEntity): String = when (page.analysisState) {
        "PENDING" -> "Waiting to organise"
        "READY" -> when (page.pageClass) {
            "NEW_SCRIPT_START" -> "Student cover detected"
            "UNCERTAIN_BOUNDARY" -> "Boundary needs review"
            else -> "Continuation page"
        }
        "FAILED" -> "Processing failed"
        else -> "Processing"
    }

    private fun finishToRecords() {
        LocalProcessingQueue.schedule(this)
        startActivity(Intent(this, RecordsActivity::class.java))
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("session_id", sessionId)
        super.onSaveInstanceState(outState)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
