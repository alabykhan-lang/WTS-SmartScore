package com.wts.smartscore.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.wts.smartscore.R
import com.wts.smartscore.scanner.LocalProcessingQueue

class MainActivity : AppCompatActivity() {
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
        LocalProcessingQueue.schedule(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun showHome() {
        val bg = ContextCompat.getColor(this, R.color.smartscore_background)
        val text = ContextCompat.getColor(this, R.color.smartscore_text)
        val muted = ContextCompat.getColor(this, R.color.smartscore_text_muted)
        val primary = ContextCompat.getColor(this, R.color.smartscore_primary)
        val accent = ContextCompat.getColor(this, R.color.smartscore_accent)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 34, 24, 30); setBackgroundColor(bg) }

        root.addView(TextView(this).apply { this.text = "WTS SMARTSCORE"; textSize = 29f; setTextColor(text); setTypeface(typeface, Typeface.BOLD) })
        root.addView(TextView(this).apply { this.text = "Smart Scanning • Score Capture • Script Digitization"; textSize = 14f; setTextColor(muted); setPadding(0, 6, 0, 24) })
        root.addView(section("SCAN & DIGITIZE"))
        root.addView(productCard("▦", "Smart Broadsheet", "Scan pages • process later", primary) { startActivity(Intent(this, BroadsheetScannerActivity::class.java)) })
        root.addView(productCard("▤", "Script Scanner", "Scan a pile • group students later", accent) { startActivity(Intent(this, ScriptScannerActivity::class.java)) })
        root.addView(productCard("▧", "Document Scanner", "Clean pages • export later", primary) { startActivity(Intent(this, GeneralScannerActivity::class.java)) })
        root.addView(productCard("✦", "AI Workspace", "Prepare or mark digitized scripts", accent) { startActivity(Intent(this, AiMarkerActivity::class.java)) })

        root.addView(section("RECORDS").apply { setPadding(0, 18, 0, 10) })
        root.addView(compactCard("All records", "Broadsheets • Scripts • Documents") {
            startActivity(Intent(this, RecordsActivity::class.java))
        })
        root.addView(TextView(this).apply { this.text = "Private workspace • Local-first scanning"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, 28, 0, 4) })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun section(label: String) = TextView(this).apply { text = label; textSize = 12f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.smartscore_text_muted)); setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.08f; setPadding(2, 0, 0, 10) }

    private fun productCard(icon: String, title: String, subtitle: String, accent: Int, onClick: () -> Unit): MaterialCardView {
        val card = MaterialCardView(this).apply { radius = 22f; cardElevation = 1.5f; strokeWidth = 1; strokeColor = ContextCompat.getColor(this@MainActivity, R.color.smartscore_border); setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.smartscore_surface)); setContentPadding(18, 17, 18, 17); isClickable = true; isFocusable = true }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = icon; gravity = Gravity.CENTER; textSize = 24f; setTextColor(accent); setTypeface(typeface, Typeface.BOLD); setBackgroundColor((accent and 0x00FFFFFF) or 0x16000000) }, LinearLayout.LayoutParams(54, 54).apply { marginEnd = 16 })
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply { text = title; textSize = 18f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.smartscore_text)); setTypeface(typeface, Typeface.BOLD) })
        labels.addView(TextView(this).apply { text = subtitle; textSize = 13f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.smartscore_text_muted)); setPadding(0, 4, 0, 0) })
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply { text = "›"; textSize = 28f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.smartscore_text_muted)) })
        card.addView(row); card.setOnClickListener { onClick() }; card.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 13 }
        return card
    }

    private fun compactCard(title: String, subtitle: String, onClick: () -> Unit): MaterialCardView {
        val card = MaterialCardView(this).apply { radius = 18f; cardElevation = 0.5f; strokeWidth = 1; strokeColor = ContextCompat.getColor(this@MainActivity, R.color.smartscore_border); setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.smartscore_surface)); setContentPadding(16, 15, 16, 15); isClickable = true }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = title; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(ContextCompat.getColor(this@MainActivity, R.color.smartscore_text)) })
        col.addView(TextView(this).apply { text = subtitle; textSize = 12f; setPadding(0, 4, 0, 0); setTextColor(ContextCompat.getColor(this@MainActivity, R.color.smartscore_text_muted)) })
        card.addView(col); card.setOnClickListener { onClick() }; return card
    }
}
