package com.wts.smartscore.ui

import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wts.smartscore.data.SmartScoreDatabase
import kotlinx.coroutines.launch

class AiMarkerActivity : AppCompatActivity() {
    private val dao by lazy { SmartScoreDatabase.get(this).dao() }
    private lateinit var scripts: Spinner
    private lateinit var question: TextView
    private lateinit var scheme: TextView
    private var questionUri: Uri? = null
    private var schemeUri: Uri? = null

    private val pickQuestion = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { questionUri = uri; question.text = "Question paper selected" }
    }
    private val pickScheme = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { schemeUri = uri; scheme.text = "Marking scheme selected" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 30, 24, 24) }
        root.addView(TextView(this).apply { text = "AI Workspace"; textSize = 26f })
        root.addView(TextView(this).apply {
            text = "Prepare a saved script for AI-assisted marking. AI results remain proposals and scanning/export works without a provider."
            textSize = 14f
            setPadding(0, 8, 0, 20)
        })
        scripts = Spinner(this)
        root.addView(scripts)
        question = TextView(this).apply { text = "No question paper selected" }
        scheme = TextView(this).apply { text = "No marking scheme selected" }
        root.addView(Button(this).apply { text = "SELECT QUESTION PAPER"; setOnClickListener { pickQuestion.launch(arrayOf("application/pdf", "image/*")) } })
        root.addView(question)
        root.addView(Button(this).apply { text = "SELECT MARKING SCHEME"; setOnClickListener { pickScheme.launch(arrayOf("application/pdf", "image/*")) } })
        root.addView(scheme)
        root.addView(Button(this).apply {
            text = "AI PROVIDER NOT CONFIGURED"
            setOnClickListener { Toast.makeText(this@AiMarkerActivity, "AI provider not configured. You can export a script package and use it with an external AI system.", Toast.LENGTH_LONG).show() }
        })
        setContentView(ScrollView(this).apply { addView(root) })
        lifecycleScope.launch {
            val all = dao.scriptsNow()
            scripts.adapter = ArrayAdapter(
                this@AiMarkerActivity,
                android.R.layout.simple_spinner_dropdown_item,
                if (all.isEmpty()) listOf("No saved scripts") else all.map { "${it.studentRef ?: it.scriptId} — ${it.subject ?: ""}" }
            )
        }
    }
}
