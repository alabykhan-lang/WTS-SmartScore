package com.wts.smartscore.scanner

import android.content.Context
import com.wts.smartscore.data.BroadsheetEntity
import com.wts.smartscore.data.ScanEntity
import com.wts.smartscore.data.ScriptEntity
import com.wts.smartscore.data.ScriptPageEntity
import com.wts.smartscore.data.SheetSideEntity
import com.wts.smartscore.data.SmartScoreDatabase
import com.wts.smartscore.export.ImageZipExporter
import com.wts.smartscore.export.PdfImageExporter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ContinuousSessionProcessor(
    private val context: Context,
    val mode: String,
    val sessionId: String = UUID.randomUUID().toString(),
    private val onProgress: (Int, Int) -> Unit = { _, _ -> }
) {
    companion object {
        const val MODE_DOCUMENT = "DOCUMENT"
        const val MODE_SCRIPT = "SCRIPT"
        const val MODE_BROADSHEET = "BROADSHEET"
    }

    data class PageResult(
        val index: Int,
        val rawPath: String,
        val correctedPath: String,
        val ocrText: String,
        val pageClass: String,
        val identity: ScriptIdentity?,
        val sheetId: String?,
        val sideId: String?,
        val uncertain: Boolean
    )

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val processed = mutableListOf<PageResult>()
    private val accepted = AtomicInteger(0)
    private val completed = AtomicInteger(0)
    private val root = File(context.filesDir, "continuous/$sessionId").apply { mkdirs() }
    private val originals = File(root, "originals").apply { mkdirs() }
    private val corrected = File(root, "corrected").apply { mkdirs() }

    fun rawFile(index: Int): File = File(originals, "page-${index.toString().padStart(4, '0')}.jpg")

    fun enqueue(index: Int, rawPath: String) {
        accepted.incrementAndGet()
        worker.execute {
            val result = runCatching { processPage(index, rawPath) }.getOrElse {
                PageResult(index, rawPath, rawPath, "", "UNCERTAIN", null, null, null, true)
            }
            synchronized(processed) { processed += result }
            val done = completed.incrementAndGet()
            onProgress(done, accepted.get())
        }
    }

    fun finish(onDone: (File) -> Unit) {
        worker.execute {
            val manifest = buildSessionArtifacts()
            onDone(manifest)
        }
    }

    fun shutdown() = worker.shutdown()

    private fun processPage(index: Int, rawPath: String): PageResult {
        val bitmap = HighResImageLoader.load(rawPath)
        val normalized = try { ImageProcessor.normalize(bitmap) } finally { bitmap.recycle() }
        val out = File(corrected, "page-${index.toString().padStart(4, '0')}.jpg")
        ImageProcessor.saveJpeg(normalized, out)
        val ocrText = runCatching { ScriptIdentityExtractor.extractText(context, out.absolutePath) }.getOrDefault("")
        val result = when (mode) {
            MODE_SCRIPT -> classifyScript(index, rawPath, out.absolutePath, ocrText)
            MODE_BROADSHEET -> classifyBroadsheet(index, rawPath, out.absolutePath, normalized, ocrText)
            else -> PageResult(index, rawPath, out.absolutePath, ocrText, "DOCUMENT", null, null, null, false)
        }
        normalized.recycle()
        return result
    }

    private fun classifyScript(index: Int, rawPath: String, correctedPath: String, text: String): PageResult {
        val identity = runCatching { ScriptIdentityExtractor.extract(context, correctedPath) }.getOrNull()
        var signals = 0
        if (identity?.studentName != null) signals++
        if (identity?.studentId != null) signals++
        if (identity?.classLabel != null) signals++
        if (identity?.subject != null) signals++
        if (identity?.examTitle != null) signals++
        val labeled = Regex("(?i)(student|candidate)\\s*name|admission|registration|matric|subject\\s*[:=-]|class\\s*[:=-]").findAll(text).count()
        if (labeled >= 2) signals++
        val strongCover = signals >= 4 && (identity?.confidence ?: 0.0) >= 0.60
        val possibleCover = !strongCover && signals >= 2
        val clazz = when {
            strongCover -> "SCRIPT_COVER"
            possibleCover -> "UNCERTAIN"
            else -> "SCRIPT_CONTINUATION"
        }
        return PageResult(index, rawPath, correctedPath, text, clazz, identity, null, null, possibleCover)
    }

    private fun classifyBroadsheet(index: Int, rawPath: String, correctedPath: String, bitmap: android.graphics.Bitmap, text: String): PageResult {
        val sideId = runCatching { SheetIdentityResolver.resolveSideId(bitmap) }.getOrNull()
            ?: Regex("WTS-SM-[A-Z0-9-]+-S[12]", RegexOption.IGNORE_CASE).find(text)?.value?.uppercase()
        val sheetId = sideId?.substringBeforeLast("-S")
            ?: Regex("WTS-SM-[A-Z0-9-]+", RegexOption.IGNORE_CASE).find(text)?.value?.uppercase()
        val looksLikeSheet = text.contains("BROADSHEET", true) || text.contains("SMARTSCORE", true) || sideId != null
        return PageResult(index, rawPath, correctedPath, text, "BROADSHEET", null, sheetId, sideId, !looksLikeSheet || sheetId == null)
    }

    private fun buildSessionArtifacts(): File {
        val pages = synchronized(processed) { processed.sortedBy { it.index }.toList() }
        val manifest = JSONObject().apply {
            put("session_id", sessionId)
            put("mode", mode)
            put("page_count", pages.size)
            put("processed_count", completed.get())
        }
        manifest.put("pages", JSONArray(pages.map { p -> JSONObject().apply {
            put("page_number", p.index); put("original_path", p.rawPath); put("corrected_path", p.correctedPath)
            put("classification", p.pageClass); put("uncertain", p.uncertain); put("ocr_text", p.ocrText)
            put("sheet_id", p.sheetId ?: JSONObject.NULL); put("side_id", p.sideId ?: JSONObject.NULL)
            p.identity?.let { put("identity", ScriptIdentityExtractor.toJson(it)) }
        }}))
        when (mode) {
            MODE_DOCUMENT -> buildDocumentExports(pages, manifest)
            MODE_SCRIPT -> buildScriptGroups(pages, manifest)
            MODE_BROADSHEET -> buildBroadsheetGroups(pages, manifest)
        }
        return File(root, "session.json").also { it.writeText(manifest.toString(2)) }
    }

    private fun buildDocumentExports(pages: List<PageResult>, manifest: JSONObject) {
        val paths = pages.map { it.correctedPath }.filter { File(it).exists() }
        if (paths.isEmpty()) return
        val pdf = File(root, "document-session.pdf")
        val zip = File(root, "document-pages.zip")
        PdfImageExporter.export(pdf, paths); ImageZipExporter.export(zip, paths)
        manifest.put("pdf_path", pdf.absolutePath); manifest.put("images_zip_path", zip.absolutePath)
    }

    private fun buildScriptGroups(pages: List<PageResult>, manifest: JSONObject) {
        data class Group(val id: String, val pages: MutableList<PageResult> = mutableListOf(), var identity: ScriptIdentity? = null, var uncertainBoundary: Boolean = false)
        val groups = mutableListOf<Group>(); var current: Group? = null
        pages.forEach { page ->
            if (current == null || page.pageClass == "SCRIPT_COVER") {
                current = Group(UUID.randomUUID().toString()).also { groups += it }; current!!.identity = page.identity
            } else if (page.pageClass == "UNCERTAIN") current!!.uncertainBoundary = true
            current!!.pages += page
            if (current!!.identity == null && page.identity != null) current!!.identity = page.identity
        }
        val groupArray = JSONArray(); val exportRoot = File(root, "scripts").apply { mkdirs() }; val dao = SmartScoreDatabase.get(context).dao()
        groups.forEachIndexed { i, g ->
            val identity = g.identity; val display = identity?.displayStudent()?.takeIf { it.isNotBlank() } ?: "Unidentified ${i + 1}"
            val safe = display.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "script-${i + 1}" }; val dir = File(exportRoot, safe).apply { mkdirs() }
            val paths = g.pages.map { it.correctedPath }.filter { File(it).exists() }; val pdf = File(dir, "script.pdf"); val zip = File(dir, "ai-package.zip"); val txt = File(dir, "ocr.txt"); val ocrJson = File(dir, "ocr.json")
            PdfImageExporter.export(pdf, paths); txt.writeText(g.pages.joinToString("\n\n") { "===== PAGE ${it.index} =====\n${it.ocrText}" })
            ocrJson.writeText(JSONObject().apply { put("script_id", g.id); put("pages", JSONArray(g.pages.map { JSONObject().put("page_number", it.index).put("text", it.ocrText) })) }.toString(2))
            val metadata = JSONObject().apply {
                put("script_id", g.id); put("student_identity", identity?.let { ScriptIdentityExtractor.toJson(it) } ?: JSONObject.NULL); put("subject", identity?.subject ?: JSONObject.NULL); put("page_count", paths.size)
                put("question_paper_reference", JSONObject.NULL); put("marking_scheme_reference", JSONObject.NULL); put("boundary_review_required", g.uncertainBoundary)
            }.toString(2)
            ImageZipExporter.export(zip, paths, metadata)
            runBlocking {
                dao.saveScript(ScriptEntity(g.id, identity?.studentId ?: identity?.studentName, identity?.subject, identity?.examTitle, System.currentTimeMillis(), System.currentTimeMillis(), if (g.uncertainBoundary) "REVIEW_REQUIRED" else "COMPLETE", paths.size))
                g.pages.forEachIndexed { pageIndex, p -> dao.saveScriptPage(ScriptPageEntity(UUID.randomUUID().toString(), g.id, pageIndex + 1, p.rawPath, p.correctedPath, System.currentTimeMillis())) }
            }
            groupArray.put(JSONObject().apply {
                put("script_id", g.id); put("display_name", display); put("page_count", paths.size); put("boundary_review_required", g.uncertainBoundary)
                put("pdf_path", pdf.absolutePath); put("ai_package_path", zip.absolutePath); put("ocr_text_path", txt.absolutePath); put("ocr_json_path", ocrJson.absolutePath)
            })
        }
        manifest.put("script_count", groups.size); manifest.put("scripts", groupArray)
    }

    private fun buildBroadsheetGroups(pages: List<PageResult>, manifest: JSONObject) {
        val groups = linkedMapOf<String, MutableList<PageResult>>(); val uncertain = JSONArray(); val dao = SmartScoreDatabase.get(context).dao(); val templates = V2TemplateRepository(context)
        pages.forEach { p -> if (p.sheetId == null) uncertain.put(p.index) else groups.getOrPut(p.sheetId) { mutableListOf() }.add(p) }
        val arr = JSONArray()
        groups.forEach { (sheetId, ps) ->
            val sides = ps.mapNotNull { it.sideId?.substringAfterLast("-S")?.toIntOrNull() }.toSet()
            var readingCount = 0; var exceptionCount = 0
            ps.forEach { p ->
                val side = p.sideId?.let { templates.sideById(it) } ?: return@forEach
                val bitmap = runCatching { HighResImageLoader.load(p.correctedPath) }.getOrNull() ?: return@forEach
                val scanId = UUID.randomUUID().toString()
                val readings = try { BroadsheetProcessor(context).process(bitmap, side, scanId) } finally { bitmap.recycle() }
                readingCount += readings.size; exceptionCount += readings.count { it.state != "CONFIRMED" && it.state != "BLANK" }
                runBlocking {
                    dao.saveBroadsheet(BroadsheetEntity(sheetId, templates.classLabel, templates.subject, templates.templateVersion, side.totalSides, if (exceptionCount > 0) "REVIEW_REQUIRED" else "SCANNED", System.currentTimeMillis()))
                    dao.saveSide(SheetSideEntity(side.sideId, side.sheetId, side.sideNumber, side.totalSides, side.rowStart, side.rowEnd, System.currentTimeMillis(), p.rawPath, p.correctedPath, "CONTINUOUS_AUTO"))
                    dao.saveScan(ScanEntity(scanId, side.sideId, "SMART_BROADSHEET", p.index, System.currentTimeMillis(), p.rawPath, p.correctedPath, "{}"))
                    dao.deleteReadingsForSide(side.sideId); dao.saveReadings(readings)
                }
            }
            arr.put(JSONObject().apply {
                put("sheet_id", sheetId); put("pages", JSONArray(ps.map { it.index })); put("side_1", 1 in sides); put("side_2", 2 in sides)
                put("status", if (1 in sides && 2 in sides) "COMPLETE" else "MISSING_SIDE"); put("score_readings", readingCount); put("exceptions", exceptionCount)
            })
        }
        manifest.put("broadsheet_count", groups.size); manifest.put("broadsheets", arr); manifest.put("uncertain_pages", uncertain)
    }
}
