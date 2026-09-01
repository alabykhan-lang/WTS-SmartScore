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
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.android.material.card.MaterialCardView
import com.wts.smartscore.R
import com.wts.smartscore.data.BroadsheetEntity
import com.wts.smartscore.data.ScanEntity
import com.wts.smartscore.data.SheetSideEntity
import com.wts.smartscore.data.SmartScoreDatabase
import com.wts.smartscore.scanner.LocalProcessingQueue
import com.wts.smartscore.scanner.ProcessingTaskTypes
import com.wts.smartscore.scanner.MlKitDocumentScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Capture only. Recognition is durable, local background work after this screen. */
class BroadsheetScannerActivity : AppCompatActivity() {
    private val dao by lazy { SmartScoreDatabase.get(this).dao() }
    private val scanner by lazy { MlKitDocumentScan.client(50) }
    private var sessionId: String = ""
    private lateinit var status: TextView
    private lateinit var identitySummary: TextView
    private lateinit var pageList: LinearLayout
    private lateinit var addPageButton: Button
    private lateinit var doneButton: Button

    private val launcher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            status.text = "Scan cancelled. Any saved pages are still safe."
            addPageButton.isEnabled = true
            return@registerForActivityResult
        }
        val document = runCatching { GmsDocumentScanningResult.fromActivityResultIntent(result.data) }.getOrNull()
        if (document == null) {
            status.text = "The scanner returned no page. Try again when ready."
            addPageButton.isEnabled = true
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
            renderOverview()
            if (savedInstanceState == null && dao.pages(sessionId).isEmpty()) startScan()
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
        root.addView(TextView(this).apply {
            text = "SMART BROADSHEET"
            textSize = 12f
            letterSpacing = 0.12f
            setTextColor(muted)
        })
        root.addView(TextView(this).apply {
            text = "Scan a sheet"
            textSize = 28f
            setTextColor(text)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(3))
        })
        root.addView(TextView(this).apply {
            text = "Google Scan cleans each page first. SmartScore saves it immediately, then reads and organises it in the background."
            textSize = 14f
            setTextColor(muted)
            setPadding(0, 0, 0, dp(16))
        })
        identitySummary = TextView(this).apply {
            text = "Identity will be detected when possible"
            textSize = 15f
            setTextColor(text)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(ContextCompat.getColor(this@BroadsheetScannerActivity, R.color.smartscore_surface))
        }
        root.addView(identitySummary, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        status = TextView(this).apply {
            text = "Ready to scan"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(status)
        addPageButton = Button(this).apply {
            text = "+  ADD PAGE"
            setOnClickListener { startScan() }
        }
        root.addView(addPageButton)
        pageList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pageList)
        doneButton = Button(this).apply {
            text = "DONE"
            setOnClickListener { finishToRecords() }
        }
        root.addView(doneButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })
        root.addView(TextView(this).apply {
            text = "Saved locally • processing continues after you leave this screen"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(12), 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun startScan() {
        addPageButton.isEnabled = false
        status.text = "Opening Google document scanner…"
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { error ->
                status.text = "Document scanner unavailable: ${error.message ?: error.javaClass.simpleName}"
                addPageButton.isEnabled = true
            }
    }

    private fun saveScan(document: GmsDocumentScanningResult) {
        addPageButton.isEnabled = false
        doneButton.isEnabled = false
        status.text = "Saving corrected pages locally…"
        lifecycleScope.launch {
            runCatching {
                val scan = withContext(Dispatchers.IO) {
                    MlKitDocumentScan.persistResult(this@BroadsheetScannerActivity, document, File(filesDir, "broadsheets/sessions/$sessionId"))
                }
                withContext(Dispatchers.IO) {
                    val existing = dao.pages(sessionId)
                    var next = existing.maxOfOrNull { it.sideNumber }?.plus(1) ?: 1
                    val now = System.currentTimeMillis()
                    scan.pages.forEach { page ->
                        val pageId = "$sessionId-page-${next.toString().padStart(3, '0')}"
                        val side = SheetSideEntity(
                            sideId = pageId,
                            sheetId = sessionId,
                            sideNumber = next,
                            totalSides = 0,
                            rowStart = 0,
                            rowEnd = 0,
                            scanTimestamp = now,
                            imagePath = page.imagePath,
                            normalizedPath = page.imagePath,
                            identityMethod = "PENDING",
                            layoutId = "PENDING",
                            subjectGroup = null,
                            templateVersion = null,
                            pageState = "SCANNED",
                            identityConfidence = 0.0,
                            identityJson = null,
                            sessionId = sessionId
                        )
                        dao.saveSide(side)
                        dao.saveScan(ScanEntity("$pageId-scan", sessionId, "SMART_BROADSHEET", next, now, page.imagePath, page.imagePath, "{\"source\":\"GOOGLE_ML_KIT_DOCUMENT_SCANNER\"}"))
                        val payload = JSONObject().put("page_id", pageId)
                        LocalProcessingQueue.enqueue(this@BroadsheetScannerActivity, ProcessingTaskTypes.IDENTIFY_DOCUMENT, sessionId, payload)
                        LocalProcessingQueue.enqueue(this@BroadsheetScannerActivity, ProcessingTaskTypes.REGISTER_TEMPLATE, sessionId, payload)
                        LocalProcessingQueue.enqueue(this@BroadsheetScannerActivity, ProcessingTaskTypes.READ_SCORES, sessionId, payload)
                        next++
                    }
                    val sheet = dao.broadsheet(sessionId) ?: BroadsheetEntity(
                        sheetId = sessionId,
                        classLabel = "Broadsheet",
                        subject = "Identity pending",
                        templateVersion = "",
                        expectedPageCount = 0,
                        reviewStatus = "SCANNED",
                        createdAt = now,
                        layoutFamily = "GENERIC_SCORE_SHEET",
                        documentType = "GENERIC_SCORE_SHEET",
                        pageCount = dao.pages(sessionId).size,
                        lastUpdatedAt = now
                    )
                    dao.saveBroadsheet(sheet.copy(reviewStatus = "SCANNED", pageCount = dao.pages(sessionId).size, lastUpdatedAt = now))
                }
            }.onSuccess {
                status.text = "Saved locally ✓  •  ready for another page"
                doneButton.isEnabled = true
                addPageButton.isEnabled = true
                renderOverview()
            }.onFailure { error ->
                status.text = "Page was not added: ${error.message ?: error.javaClass.simpleName}"
                doneButton.isEnabled = true
                addPageButton.isEnabled = true
            }
        }
    }

    private fun renderOverview() {
        lifecycleScope.launch {
            val sheet = withContext(Dispatchers.IO) { dao.broadsheet(sessionId) }
            val pages = withContext(Dispatchers.IO) { dao.pages(sessionId) }
            val title = listOf(sheet?.classLabel, sheet?.subject).orEmpty().filter { !it.isNullOrBlank() && it !in listOf("Broadsheet", "Identity pending") }.joinToString(" • ")
            identitySummary.text = if (title.isBlank()) "Identity will be detected when possible" else title
            pageList.removeAllViews()
            pages.forEach { page -> pageList.addView(pageCard(page)) }
            doneButton.isEnabled = pages.isNotEmpty()
            status.text = if (pages.isEmpty()) "Ready to scan" else "${pages.size} page${if (pages.size == 1) "" else "s"} saved locally"
        }
    }

    private fun pageCard(page: SheetSideEntity): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            strokeWidth = 1
            strokeColor = ContextCompat.getColor(this@BroadsheetScannerActivity, R.color.smartscore_border)
            setCardBackgroundColor(ContextCompat.getColor(this@BroadsheetScannerActivity, R.color.smartscore_surface))
            setContentPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, dp(142)).apply { bottomMargin = dp(10) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(this).apply {
            setImageBitmap(BitmapFactory.decodeFile(page.normalizedPath ?: page.imagePath))
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, LinearLayout.LayoutParams(dp(92), dp(118)))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        labels.addView(TextView(this).apply { text = "Page ${page.sideNumber}"; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(ContextCompat.getColor(this@BroadsheetScannerActivity, R.color.smartscore_text)) })
        labels.addView(TextView(this).apply { text = pageStateLabel(page.pageState); textSize = 14f; setTextColor(ContextCompat.getColor(this@BroadsheetScannerActivity, R.color.smartscore_text_muted)); setPadding(0, dp(5), 0, 0) })
        labels.addView(TextView(this).apply { text = "Saved locally"; textSize = 12f; setTextColor(ContextCompat.getColor(this@BroadsheetScannerActivity, R.color.smartscore_text_muted)); setPadding(0, dp(8), 0, 0) })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
        return card
    }

    private fun pageStateLabel(state: String): String = when (state) {
        "SCANNED" -> "Waiting to process"
        "PROCESSING" -> "Processing"
        "READY" -> "Ready"
        "REVIEW_REQUIRED" -> "Needs review"
        "UNIDENTIFIED" -> "Identity uncertain"
        "FAILED" -> "Processing failed"
        else -> "Saved"
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
