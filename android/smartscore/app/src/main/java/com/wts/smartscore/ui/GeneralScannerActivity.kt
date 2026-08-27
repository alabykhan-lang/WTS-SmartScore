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
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.wts.smartscore.scanner.MlKitDocumentScan
import com.wts.smartscore.scanner.SmartScanResult
import java.io.File

class GeneralScannerActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var thumbs: LinearLayout
    private lateinit var openPdf: Button
    private var current: SmartScanResult? = null
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
            text = "Scan ordinary documents to clean corrected pages and a multipage PDF."
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
        root.addView(Button(this).apply { text = "SCAN DOCUMENT"; setOnClickListener { startScan() } })
        thumbs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { addView(thumbs) }, LinearLayout.LayoutParams(-1, 220))
        openPdf = Button(this).apply {
            text = "OPEN PDF"
            isEnabled = false
            setOnClickListener { current?.pdfPath?.let(::openPdfFile) }
        }
        root.addView(openPdf)
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
    }

    private fun openPdfFile(path: String) {
        val file = File(path)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) } catch (_: Throwable) {
            Toast.makeText(this, "PDF saved at ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }
}
