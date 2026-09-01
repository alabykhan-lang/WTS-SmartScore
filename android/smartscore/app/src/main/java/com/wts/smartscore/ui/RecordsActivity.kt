package com.wts.smartscore.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.wts.smartscore.R
import com.wts.smartscore.data.SmartScoreDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** One calm home for every locally captured SmartScore record. */
class RecordsActivity : AppCompatActivity() {
    private enum class Filter(val label: String) {
        ALL("All"), BROADSHEETS("Broadsheets"), SCRIPTS("Scripts"), DOCUMENTS("Documents")
    }

    private enum class Kind { BROADSHEET, SCRIPT, DOCUMENT }

    private data class RecordItem(
        val key: String,
        val kind: Kind,
        val title: String,
        val subtitle: String,
        val detail: String,
        val status: String,
        val timestamp: Long,
        val id: String,
        val documentDirectory: String? = null
    )

    private val dao by lazy { SmartScoreDatabase.get(this).dao() }
    private val archivedPreferences by lazy { getSharedPreferences("records", MODE_PRIVATE) }
    private lateinit var list: LinearLayout
    private lateinit var countSummary: TextView
    private lateinit var query: EditText
    private val filterViews = linkedMapOf<Filter, TextView>()
    private var selectedFilter = Filter.ALL
    private var items: List<RecordItem> = emptyList()
    private var archivedKeys: MutableSet<String> = mutableSetOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        archivedKeys = archivedPreferences.getStringSet("archived_keys", emptySet()).orEmpty().toMutableSet()

        val background = ContextCompat.getColor(this, R.color.smartscore_background)
        val text = ContextCompat.getColor(this, R.color.smartscore_text)
        val muted = ContextCompat.getColor(this, R.color.smartscore_text_muted)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(28))
            setBackgroundColor(background)
        }
        root.addView(TextView(this).apply {
            this.text = "RECORDS"
            textSize = 12f
            letterSpacing = 0.12f
            setTextColor(muted)
        })
        root.addView(TextView(this).apply {
            this.text = "Your scans, together"
            textSize = 28f
            setTextColor(text)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(TextView(this).apply {
            this.text = "Find a broadsheet, script or document from one simple local library."
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(5), 0, dp(16))
        })

        countSummary = TextView(this).apply {
            textSize = 13f
            setTextColor(muted)
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(countSummary)

        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Filter.entries.forEach { filter ->
            val tab = TextView(this).apply {
                this.text = filter.label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(9), dp(14), dp(9))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedFilter = filter
                    updateFilterAppearance()
                    render()
                }
            }
            filterViews[filter] = tab
            tabRow.addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply {
                marginEnd = dp(6)
            })
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        })

        query = EditText(this).apply {
            hint = "Search records"
            textSize = 14f
            isSingleLine = true
            setPadding(dp(14), 0, dp(14), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(query, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10); bottomMargin = dp(12) })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })
        updateFilterAppearance()
    }

    override fun onResume() {
        super.onResume()
        loadRecords()
    }

    private fun loadRecords() {
        lifecycleScope.launch {
            items = withContext(Dispatchers.IO) {
                val result = mutableListOf<RecordItem>()
                dao.broadsheetsNow().forEach { sheet ->
                    val pages = dao.pages(sheet.sheetId)
                    result += RecordItem(
                        key = "broadsheet:${sheet.sheetId}",
                        kind = Kind.BROADSHEET,
                        title = sheet.classLabel.takeUnless { it.isBlank() || it.equals("Broadsheet", true) } ?: "Unidentified broadsheet",
                        subtitle = listOf(sheet.subject, sheet.term.takeIf { it.isNotBlank() })
                            .filterNotNull().filter { it.isNotBlank() && !it.equals("Identity pending", true) }.joinToString(" • ")
                            .ifBlank { "Identity pending" },
                        detail = "${pages.size} page${if (pages.size == 1) "" else "s"} • ${sheet.recognizedCount} score${if (sheet.recognizedCount == 1) "" else "s"} recognised${if (sheet.reviewCount > 0) " • ${sheet.reviewCount} need review" else ""}",
                        status = broadsheetStatus(sheet),
                        timestamp = sheet.createdAt,
                        id = sheet.sheetId
                    )
                }
                dao.scriptsNow().forEach { script ->
                    result += RecordItem(
                        key = "script:${script.scriptId}",
                        kind = Kind.SCRIPT,
                        title = script.studentRef?.ifBlank { null } ?: "Unnamed script",
                        subtitle = script.subject?.ifBlank { null } ?: "Identity pending",
                        detail = "${script.pageCount} page${if (script.pageCount == 1) "" else "s"}",
                        status = if (script.identityStatus.equals("CONFIDENT", true) && script.completionState.equals("OCR_READY", true)) "OCR ready" else if (script.completionState.equals("FAILED", true)) "Processing failed" else "Needs identity review",
                        timestamp = script.createdAt,
                        id = script.scriptId
                    )
                }
                result += documentRecords()
                result
            }
            countSummary.text = "${items.size} saved record${if (items.size == 1) "" else "s"}"
            render()
        }
    }

    private fun documentRecords(): List<RecordItem> {
        val parent = File(filesDir, "documents")
        return parent.listFiles()
            ?.filter { it.isDirectory }
            ?.map { directory ->
                val pageCount = directory.listFiles()?.count { file -> file.extension.equals("jpg", true) } ?: 0
                val ready = File(directory, "document.pdf").exists() || File(directory, "document-searchable.pdf").exists()
                RecordItem(
                    key = "document:${directory.name}",
                    kind = Kind.DOCUMENT,
                    title = "Scanned document",
                    subtitle = if (ready) "PDF and OCR package" else "Captured pages",
                    detail = "${pageCount.coerceAtLeast(1)} page${if (pageCount == 1) "" else "s"}",
                    status = if (ready) "Ready" else "Processing",
                    timestamp = directory.lastModified(),
                    id = directory.name,
                    documentDirectory = directory.absolutePath
                )
            }
            .orEmpty()
    }

    private fun render() {
        if (!::list.isInitialized) return
        val needle = query.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val filtered = items
            .filter { item ->
                selectedFilter == Filter.ALL || when (selectedFilter) {
                    Filter.BROADSHEETS -> item.kind == Kind.BROADSHEET
                    Filter.SCRIPTS -> item.kind == Kind.SCRIPT
                    Filter.DOCUMENTS -> item.kind == Kind.DOCUMENT
                    Filter.ALL -> true
                }
            }
            .filter { item ->
                needle.isBlank() || listOf(item.title, item.subtitle, item.detail, item.status)
                    .any { it.lowercase(Locale.ROOT).contains(needle) }
            }
            .sortedByDescending { it.timestamp }

        list.removeAllViews()
        if (filtered.isEmpty()) {
            list.addView(emptyState())
            return
        }
        filtered.forEach { item -> list.addView(recordCard(item)) }
    }

    private fun recordCard(item: RecordItem): View {
        val primaryText = ContextCompat.getColor(this, R.color.smartscore_text)
        val muted = ContextCompat.getColor(this, R.color.smartscore_text_muted)
        val accent = when (item.kind) {
            Kind.BROADSHEET -> ContextCompat.getColor(this, R.color.smartscore_primary)
            Kind.SCRIPT -> ContextCompat.getColor(this, R.color.smartscore_accent)
            Kind.DOCUMENT -> Color.rgb(112, 87, 211)
        }
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = dp(1).toFloat()
            strokeWidth = 1
            strokeColor = ContextCompat.getColor(this@RecordsActivity, R.color.smartscore_border)
            setCardBackgroundColor(ContextCompat.getColor(this@RecordsActivity, R.color.smartscore_surface))
            setContentPadding(dp(14), dp(14), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = when (item.kind) { Kind.BROADSHEET -> "BROADSHEET"; Kind.SCRIPT -> "SCRIPT"; Kind.DOCUMENT -> "DOCUMENT" }
            textSize = 10f
            letterSpacing = 0.1f
            setTextColor(accent)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val overflow = TextView(this).apply {
            text = "⋮"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(dp(10), 0, dp(2), 0)
            setOnClickListener { showActions(item, this) }
        }
        top.addView(overflow, LinearLayout.LayoutParams(dp(38), dp(38)))
        column.addView(top)
        column.addView(TextView(this).apply {
            text = item.title
            textSize = 18f
            setTextColor(primaryText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(2), 0, 0)
        })
        column.addView(TextView(this).apply {
            text = item.subtitle
            textSize = 14f
            setTextColor(primaryText)
            setPadding(0, dp(2), 0, 0)
        })
        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(TextView(this).apply {
            text = "${item.detail} • ${formatDate(item.timestamp)}"
            textSize = 12f
            setTextColor(muted)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(statusChip(if (archivedKeys.contains(item.key)) "Archived" else item.status, accent))
        column.addView(bottom, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val open = MaterialButton(this).apply {
            text = "OPEN"
            textSize = 12f
            minHeight = dp(38)
            setOnClickListener { openRecord(item) }
        }
        column.addView(open, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(10) })
        card.addView(column)
        card.setOnClickListener { openRecord(item) }
        overflow.setOnClickListener { showActions(item, overflow) }
        return card
    }

    private fun statusChip(status: String, accent: Int): TextView = TextView(this).apply {
        text = status
        textSize = 11f
        setTextColor(accent)
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = pillBackground((accent and 0x00FFFFFF) or 0x18000000)
    }

    private fun emptyState(): View = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        setCardBackgroundColor(ContextCompat.getColor(this@RecordsActivity, R.color.smartscore_surface))
        setContentPadding(dp(20), dp(24), dp(20), dp(24))
        addView(LinearLayout(this@RecordsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@RecordsActivity).apply { text = "Nothing here yet"; textSize = 18f })
            addView(TextView(this@RecordsActivity).apply {
                text = "Scanned broadsheets, scripts and documents will appear here."
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@RecordsActivity, R.color.smartscore_text_muted))
                setPadding(0, dp(5), 0, 0)
            })
        })
    }

    private fun showActions(item: RecordItem, anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(if (archivedKeys.contains(item.key)) "Restore" else "Archive")
        popup.menu.add("Delete")
        popup.setOnMenuItemClickListener { menuItem ->
            if (menuItem.title.toString() == "Delete") confirmDelete(item) else toggleArchive(item)
            true
        }
        popup.show()
    }

    private fun toggleArchive(item: RecordItem) {
        if (!archivedKeys.add(item.key)) archivedKeys.remove(item.key)
        archivedPreferences.edit().putStringSet("archived_keys", archivedKeys).apply()
        render()
    }

    private fun confirmDelete(item: RecordItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete record?")
            .setMessage("This removes the saved local copy from SmartScore.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("DELETE") { _, _ -> deleteRecord(item) }
            .show()
    }

    private fun deleteRecord(item: RecordItem) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                when (item.kind) {
                    Kind.BROADSHEET -> {
                        dao.deleteReadingsForSheet(item.id)
                        dao.pages(item.id).forEach { dao.deletePage(it.sideId) }
                        dao.deleteBroadsheet(item.id)
                    }
                    Kind.SCRIPT -> {
                        dao.deleteScriptPagesForScript(item.id)
                        dao.deleteScript(item.id)
                    }
                    Kind.DOCUMENT -> {
                        val directory = item.documentDirectory?.let(::File)
                        val parent = File(filesDir, "documents").canonicalFile
                        if (directory != null && directory.isDirectory && directory.canonicalFile.parentFile == parent) {
                            directory.deleteRecursively()
                        }
                    }
                }
            }
            archivedKeys.remove(item.key)
            archivedPreferences.edit().putStringSet("archived_keys", archivedKeys).apply()
            Toast.makeText(this@RecordsActivity, "Record deleted", Toast.LENGTH_SHORT).show()
            loadRecords()
        }
    }

    private fun openRecord(item: RecordItem) {
        when (item.kind) {
            Kind.BROADSHEET -> startActivity(Intent(this, BroadsheetReviewActivity::class.java).putExtra("sheetId", item.id))
            Kind.SCRIPT -> startActivity(Intent(this, ScriptReviewActivity::class.java).putExtra("scriptId", item.id))
            Kind.DOCUMENT -> {
                val directory = item.documentDirectory?.let(::File) ?: return
                val file = listOf(File(directory, "document-searchable.pdf"), File(directory, "document.pdf"))
                    .firstOrNull(File::exists) ?: return
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (_: Throwable) {
                    Toast.makeText(this, "Document saved at ${file.absolutePath}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateFilterAppearance() {
        filterViews.forEach { (filter, view) ->
            view.setTextColor(if (filter == selectedFilter) Color.WHITE else ContextCompat.getColor(this, R.color.smartscore_text_muted))
            view.background = pillBackground(if (filter == selectedFilter) ContextCompat.getColor(this, R.color.smartscore_primary) else Color.TRANSPARENT)
        }
    }

    private fun broadsheetStatus(sheet: com.wts.smartscore.data.BroadsheetEntity): String = when (sheet.reviewStatus) {
        "SCANNED" -> "Saved locally"
        "PROCESSING" -> "Processing"
        "READY" -> "Ready"
        "REVIEW_REQUIRED" -> "Needs review"
        "UNIDENTIFIED" -> "Identity needs attention"
        "FAILED" -> "Processing failed"
        else -> "Saved locally"
    }

    private fun formatDate(timestamp: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
    private fun pillBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(40).toFloat()
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
