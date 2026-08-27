package com.wts.smartscore.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wts.smartscore.data.ScriptEntity
import com.wts.smartscore.data.SmartScoreDatabase
import com.wts.smartscore.export.ImageZipExporter
import com.wts.smartscore.export.PdfImageExporter
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class ScriptReviewActivity : AppCompatActivity() {
    private val dao by lazy { SmartScoreDatabase.get(this).dao() }
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private var id = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra("scriptId") ?: return finish()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 18, 18, 18) }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() {
        lifecycleScope.launch {
            root.removeAllViews()
            val script = dao.script(id) ?: return@launch
            val pages = dao.scriptPages(id)
            root.addView(TextView(this@ScriptReviewActivity).apply {
                text = script.studentRef ?: "Unidentified Script"
                textSize = 23f
            })
            root.addView(TextView(this@ScriptReviewActivity).apply {
                text = listOfNotNull(script.subject, script.testRef).joinToString(" • ").ifBlank { "Identity can be corrected after scanning" }
                setPadding(0, 4, 0, 12)
            })

            val topActions = LinearLayout(this@ScriptReviewActivity).apply { orientation = LinearLayout.HORIZONTAL }
            topActions.addView(Button(this@ScriptReviewActivity).apply {
                text = "CORRECT IDENTITY"
                setOnClickListener { editIdentity(script) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            topActions.addView(Button(this@ScriptReviewActivity).apply {
                text = "MERGE SCRIPT"
                setOnClickListener { chooseMergeTarget(script) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(topActions)

            pages.forEachIndexed { index, page ->
                val row = LinearLayout(this@ScriptReviewActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 8) }
                row.addView(ImageView(this@ScriptReviewActivity).apply {
                    setImageBitmap(BitmapFactory.decodeFile(page.normalizedPath ?: page.imagePath))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, LinearLayout.LayoutParams(160, 190))
                val controls = LinearLayout(this@ScriptReviewActivity).apply { orientation = LinearLayout.VERTICAL }
                controls.addView(TextView(this@ScriptReviewActivity).apply { text = "Page ${index + 1}" })
                val order = LinearLayout(this@ScriptReviewActivity).apply { orientation = LinearLayout.HORIZONTAL }
                order.addView(Button(this@ScriptReviewActivity).apply {
                    text = "←"; isEnabled = index > 0
                    setOnClickListener { lifecycleScope.launch { swapPages(index, index - 1); load() } }
                })
                order.addView(Button(this@ScriptReviewActivity).apply {
                    text = "→"; isEnabled = index < pages.lastIndex
                    setOnClickListener { lifecycleScope.launch { swapPages(index, index + 1); load() } }
                })
                controls.addView(order)
                controls.addView(Button(this@ScriptReviewActivity).apply {
                    text = "MOVE TO ANOTHER SCRIPT"
                    setOnClickListener { chooseMoveTarget(page.pageId) }
                })
                if (index > 0) controls.addView(Button(this@ScriptReviewActivity).apply {
                    text = "SPLIT SCRIPT HERE"
                    setOnClickListener { lifecycleScope.launch { splitAt(index); load() } }
                })
                controls.addView(Button(this@ScriptReviewActivity).apply {
                    text = "DELETE"
                    setOnClickListener { lifecycleScope.launch { dao.deleteScriptPage(page.pageId); resequence(this@ScriptReviewActivity.id); refreshScript(this@ScriptReviewActivity.id); load() } }
                })
                row.addView(controls)
                root.addView(row)
            }

            root.addView(Button(this@ScriptReviewActivity).apply {
                text = "RESCAN / ADD PAGE"
                setOnClickListener { startActivity(Intent(this@ScriptReviewActivity, ScriptScannerActivity::class.java).putExtra("scriptId", id)) }
            })
            root.addView(Button(this@ScriptReviewActivity).apply {
                text = "EXPORT PDF + IMAGE ZIP"
                setOnClickListener {
                    val paths = pages.map { it.normalizedPath ?: it.imagePath }
                    exec.execute {
                        val dir = File(filesDir, "exports")
                        PdfImageExporter.export(File(dir, "script-$id.pdf"), paths)
                        ImageZipExporter.export(File(dir, "script-$id.zip"), paths, "{\"script_id\":\"$id\",\"page_count\":${paths.size}}")
                        runOnUiThread { Toast.makeText(this@ScriptReviewActivity, "Exports refreshed", Toast.LENGTH_LONG).show() }
                    }
                }
            })
        }
    }

    private fun editIdentity(script: ScriptEntity) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 8, 28, 0) }
        val student = EditText(this).apply { hint = "Student name / ID"; setText(script.studentRef ?: "") }
        val subject = EditText(this).apply { hint = "Subject"; setText(script.subject ?: "") }
        val context = EditText(this).apply { hint = "Class / examination note"; setText(script.testRef ?: "") }
        box.addView(student); box.addView(subject); box.addView(context)
        AlertDialog.Builder(this).setTitle("Correct Script Identity").setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                lifecycleScope.launch {
                    dao.saveScript(script.copy(
                        studentRef = student.text.toString().trim().ifBlank { null },
                        subject = subject.text.toString().trim().ifBlank { null },
                        testRef = context.text.toString().trim().ifBlank { null }
                    ))
                    load()
                }
            }.show()
    }

    private fun chooseMoveTarget(pageId: String) {
        lifecycleScope.launch {
            val targets = dao.scriptsNow().filter { it.scriptId != id }
            if (targets.isEmpty()) { Toast.makeText(this@ScriptReviewActivity, "No other saved script available", Toast.LENGTH_SHORT).show(); return@launch }
            val labels = targets.map { it.studentRef ?: "Script ${it.scriptId.take(8)}" }.toTypedArray()
            runOnUiThread {
                AlertDialog.Builder(this@ScriptReviewActivity).setTitle("Move Page To")
                    .setItems(labels) { _, which -> lifecycleScope.launch { movePage(pageId, targets[which].scriptId); load() } }.show()
            }
        }
    }

    private suspend fun movePage(pageId: String, targetId: String) {
        val targetNext = dao.scriptPages(targetId).size + 1
        dao.moveScriptPage(pageId, targetId, targetNext)
        resequence(id); resequence(targetId); refreshScript(id); refreshScript(targetId)
    }

    private fun chooseMergeTarget(script: ScriptEntity) {
        lifecycleScope.launch {
            val targets = dao.scriptsNow().filter { it.scriptId != id }
            if (targets.isEmpty()) { Toast.makeText(this@ScriptReviewActivity, "No other saved script available", Toast.LENGTH_SHORT).show(); return@launch }
            val labels = targets.map { it.studentRef ?: "Script ${it.scriptId.take(8)}" }.toTypedArray()
            runOnUiThread {
                AlertDialog.Builder(this@ScriptReviewActivity).setTitle("Merge Into")
                    .setItems(labels) { _, which ->
                        lifecycleScope.launch {
                            val target = targets[which]
                            val pages = dao.scriptPages(id)
                            var next = dao.scriptPages(target.scriptId).size + 1
                            pages.forEach { dao.moveScriptPage(it.pageId, target.scriptId, next++) }
                            dao.deleteScript(id)
                            refreshScript(target.scriptId)
                            Toast.makeText(this@ScriptReviewActivity, "Scripts merged", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }.show()
            }
        }
    }

    private suspend fun splitAt(index: Int) {
        val source = dao.script(id) ?: return
        val pages = dao.scriptPages(id)
        if (index !in 1 until pages.size) return
        val newId = UUID.randomUUID().toString()
        val moving = pages.drop(index)
        dao.saveScript(source.copy(scriptId = newId, createdAt = System.currentTimeMillis(), completedAt = source.completedAt, completionState = "REVIEW_REQUIRED", pageCount = moving.size))
        moving.forEachIndexed { i, p -> dao.moveScriptPage(p.pageId, newId, i + 1) }
        resequence(id); refreshScript(id); refreshScript(newId)
        Toast.makeText(this, "Split created a second script for review", Toast.LENGTH_LONG).show()
    }

    private suspend fun swapPages(a: Int, b: Int) {
        val pages = dao.scriptPages(id)
        val pa = pages.getOrNull(a) ?: return
        val pb = pages.getOrNull(b) ?: return
        dao.setPageNumber(pa.pageId, pb.pageNumber)
        dao.setPageNumber(pb.pageId, pa.pageNumber)
    }

    private suspend fun resequence(scriptId: String) {
        dao.scriptPages(scriptId).forEachIndexed { index, page -> dao.setPageNumber(page.pageId, index + 1) }
    }

    private suspend fun refreshScript(scriptId: String) {
        val script = dao.script(scriptId) ?: return
        dao.saveScript(script.copy(pageCount = dao.scriptPages(scriptId).size))
    }

    override fun onDestroy() { super.onDestroy(); exec.shutdown() }
}
