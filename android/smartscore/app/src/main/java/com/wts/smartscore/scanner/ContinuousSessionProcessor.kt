package com.wts.smartscore.scanner

import android.content.Context
import android.graphics.Bitmap
import com.wts.smartscore.data.BroadsheetEntity
import com.wts.smartscore.data.ScanEntity
import com.wts.smartscore.data.ScriptEntity
import com.wts.smartscore.data.ScriptPageEntity
import com.wts.smartscore.data.SheetSideEntity
import com.wts.smartscore.data.SmartScoreDatabase
import com.wts.smartscore.export.DocxExporter
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
        val pageId: String?,
        val layoutId: String?,
        val pageNumber: Int?,
        val subjectGroup: String?,
        val identityMethod: String,
        val uncertain: Boolean,
        val boundaryReason: String? = null,
        val coverScore: Double = 0.0
    )

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val processed = mutableListOf<PageResult>()
    private val accepted = AtomicInteger(0)
    private val completed = AtomicInteger(0)
    private val root = File(context.filesDir, "continuous/$sessionId").apply { mkdirs() }
    private val originals = File(root, "originals").apply { mkdirs() }
    private val corrected = File(root, "corrected").apply { mkdirs() }

    init {
        loadExistingManifest()
    }

    fun existingPageCount(): Int = synchronized(processed) { processed.maxOfOrNull { it.index } ?: 0 }

    fun rawFile(index: Int): File = File(originals, "page-${index.toString().padStart(4, '0')}.jpg")

    fun enqueue(index: Int, rawPath: String) {
        accepted.incrementAndGet()
        worker.execute {
            val result = runCatching { processPage(index, rawPath) }.getOrElse {
                PageResult(index, rawPath, rawPath, "", "UNCERTAIN", null, null, null, null, null, null, "PROCESSING_ERROR", true, it.message)
            }
            synchronized(processed) {
                processed.removeAll { existing -> existing.index == result.index }
                processed += result
            }
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

    private fun loadExistingManifest() {
        val file = File(root, "session.json")
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readText())
            val pages = json.optJSONArray("pages") ?: return@runCatching
            for (i in 0 until pages.length()) {
                val p = pages.optJSONObject(i) ?: continue
                val identity = p.optJSONObject("identity")?.let(ScriptIdentityExtractor::fromJson)
                synchronized(processed) { processed += pageFromJson(p, identity) }
            }
            val count = synchronized(processed) { processed.size }
            accepted.set(count)
            completed.set(count)
        }
    }

    private fun pageFromJson(p: JSONObject, identity: ScriptIdentity?): PageResult = PageResult(
        index = p.optInt("page_number", p.optInt("index", 0)),
        rawPath = p.optString("original_path"),
        correctedPath = p.optString("corrected_path").ifBlank { p.optString("original_path") },
        ocrText = p.optString("ocr_text"),
        pageClass = p.optString("classification", "UNCERTAIN"),
        identity = identity,
        sheetId = p.optString("sheet_id").takeIf { it.isNotBlank() && it != "null" },
        pageId = p.optString("page_id", p.optString("side_id")).takeIf { it.isNotBlank() && it != "null" }?.uppercase()?.replace(Regex("-S([0-9]+)$"), "-P$1"),
        layoutId = p.optString("layout_id").takeIf { it.isNotBlank() && it != "null" },
        pageNumber = p.optInt("document_page_number", p.optInt("page_template_number", 0)).takeIf { it > 0 },
        subjectGroup = p.optString("subject_group").takeIf { it.isNotBlank() && it != "null" },
        identityMethod = p.optString("identity_method", "LOADED"),
        uncertain = p.optBoolean("uncertain", true),
        boundaryReason = p.optString("boundary_reason").takeIf { it.isNotBlank() && it != "null" },
        coverScore = p.optDouble("cover_score", 0.0)
    )

    private fun processPage(index: Int, rawPath: String): PageResult {
        val bitmap = HighResImageLoader.load(rawPath)
        val normalized = try { ImageProcessor.normalize(bitmap) } finally { bitmap.recycle() }
        val out = File(corrected, "page-${index.toString().padStart(4, '0')}.jpg")
        ImageProcessor.saveJpeg(normalized, out)
        val ocrText = runCatching { ScriptIdentityExtractor.extractText(context, out.absolutePath) }.getOrDefault("")
        val result = when (mode) {
            MODE_SCRIPT -> classifyScript(index, rawPath, out.absolutePath, ocrText)
            MODE_BROADSHEET -> classifyBroadsheet(index, rawPath, out.absolutePath, normalized, ocrText)
            else -> PageResult(index, rawPath, out.absolutePath, ocrText, "DOCUMENT", null, null, null, null, null, null, "NONE", false)
        }
        normalized.recycle()
        return result
    }

    private fun classifyScript(index: Int, rawPath: String, correctedPath: String, text: String): PageResult {
        val identity = runCatching { ScriptIdentityExtractor.extract(context, correctedPath) }.getOrNull()
        val classification = ScriptPageClassifier.classify(identity, text)
        return PageResult(
            index = index,
            rawPath = rawPath,
            correctedPath = correctedPath,
            ocrText = text,
            pageClass = classification.pageClass,
            identity = identity,
            sheetId = null,
            pageId = null,
            layoutId = null,
            pageNumber = null,
            subjectGroup = identity?.subject,
            identityMethod = if (identity != null) "OCR_LABELLED_FIELDS" else "OCR_UNAVAILABLE",
            uncertain = classification.pageClass == "UNCERTAIN",
            boundaryReason = classification.reason,
            coverScore = classification.coverScore
        )
    }

    private fun classifyBroadsheet(index: Int, rawPath: String, correctedPath: String, bitmap: Bitmap, text: String): PageResult {
        val qr = runCatching { SheetIdentityResolver.resolvePageIdentity(bitmap) }.getOrNull()
        val ocrPage = Regex("(?i)(WTS-[A-Z0-9-]+)-(?:P|S)([0-9]+)").find(text)
        val repository = V2TemplateRepository(context)
        val qrPageId = qr?.pageId?.uppercase()
            ?: ocrPage?.value?.uppercase()?.replace(Regex("-S([0-9]+)$"), "-P$1")
        val directTemplate = qrPageId?.let(repository::pageById)
        val headingManifest = repository.allManifests().firstOrNull { candidate ->
            text.contains(candidate.sheetId, true) ||
                (text.contains(candidate.classLabel, true) && text.contains(candidate.subjectGroup.split(" • ").first(), true))
        }
        val pageId = qrPageId ?: if (headingManifest?.pages?.size == 1) headingManifest.pages.first().pageId else null
        val sheetId = qr?.sheetId?.uppercase()
            ?: directTemplate?.sheetId
            ?: headingManifest?.sheetId
            ?: pageId?.replace(Regex("-P[0-9]+$"), "")
            ?: Regex("(?i)WTS-[A-Z0-9-]+(?=-P[0-9]+)").find(text)?.value?.uppercase()
        val template = pageId?.let(repository::pageById) ?: directTemplate
        val pageNumber = qr?.pageNumber ?: template?.pageNumber ?: ocrPage?.groupValues?.getOrNull(1)?.toIntOrNull()
        val layoutId = qr?.layoutId ?: template?.layoutId
        val subject = qr?.subjectGroup ?: template?.subjectGroup
        val looksLikeSheet = text.contains("BROADSHEET", true) || text.contains("SMARTSCORE", true) || qr != null || pageId != null || headingManifest != null
        val uncertain = !looksLikeSheet || sheetId == null || pageId == null
        return PageResult(
            index, rawPath, correctedPath, text, "BROADSHEET", null, sheetId, pageId, layoutId,
            pageNumber, subject, qr?.method ?: if (pageId != null) "OCR_ID" else "UNRESOLVED", uncertain,
            if (uncertain) "QR/template identity was not resolved" else null
        )
    }

    private fun buildSessionArtifacts(): File {
        val pages = synchronized(processed) { processed.sortedBy { it.index }.toList() }
        val manifest = JSONObject().apply {
            put("schema_version", "3.0")
            put("session_id", sessionId)
            put("mode", mode)
            put("page_count", pages.size)
            put("processed_count", completed.get())
            put("capture_policy", "CAPTURE_FIRST_REVIEW_AFTER_FINISH")
        }
        val pageArray = JSONArray()
        pages.forEach { p ->
            pageArray.put(JSONObject().apply {
                put("page_number", p.index)
                put("original_path", p.rawPath)
                put("corrected_path", p.correctedPath)
                put("classification", p.pageClass)
                put("uncertain", p.uncertain)
                put("boundary_reason", p.boundaryReason ?: JSONObject.NULL)
                put("cover_score", p.coverScore)
                put("ocr_text", p.ocrText)
                put("sheet_id", p.sheetId ?: JSONObject.NULL)
                put("page_id", p.pageId ?: JSONObject.NULL)
                put("layout_id", p.layoutId ?: JSONObject.NULL)
                put("document_page_number", p.pageNumber ?: JSONObject.NULL)
                put("subject_group", p.subjectGroup ?: JSONObject.NULL)
                put("identity_method", p.identityMethod)
                p.identity?.let { put("identity", ScriptIdentityExtractor.toJson(it)) }
            })
        }
        manifest.put("pages", pageArray)
        when (mode) {
            MODE_DOCUMENT -> buildDocumentExports(pages, manifest)
            MODE_SCRIPT -> buildScriptGroups(pages, manifest)
            MODE_BROADSHEET -> buildBroadsheetGroups(pages, manifest)
        }
        return File(root, "session.json").also { it.writeText(manifest.toString(2)) }
    }

    private fun buildDocumentExports(pages: List<PageResult>, manifest: JSONObject) {
        val valid = pages.filter { File(it.correctedPath).exists() }
        if (valid.isEmpty()) return
        val paths = valid.map { it.correctedPath }
        val pdf = File(root, "document-session.pdf")
        val searchable = File(root, "document-session-searchable.pdf")
        val images = File(root, "document-pages.zip")
        val txt = File(root, "ocr.txt")
        val json = File(root, "ocr.json")
        val docx = File(root, "document-session.docx")
        PdfImageExporter.export(pdf, paths)
        PdfImageExporter.exportSearchable(searchable, valid.map { PdfImageExporter.OcrPage(it.correctedPath, it.ocrText) })
        ImageZipExporter.export(images, paths)
        txt.writeText(valid.joinToString("\n\n") { "===== PAGE ${it.index} =====\n${it.ocrText}" })
        json.writeText(ocrJson("document-$sessionId", valid).toString(2))
        DocxExporter.export(docx, "SmartScore document session", valid.map { it.index to it.ocrText })
        manifest.put("pdf_path", pdf.absolutePath)
        manifest.put("searchable_pdf_path", searchable.absolutePath)
        manifest.put("images_zip_path", images.absolutePath)
        manifest.put("ocr_text_path", txt.absolutePath)
        manifest.put("ocr_json_path", json.absolutePath)
        manifest.put("docx_path", docx.absolutePath)
    }

    private fun buildScriptGroups(pages: List<PageResult>, manifest: JSONObject) {
        data class Group(val id: String, val pages: MutableList<PageResult> = mutableListOf(), var identity: ScriptIdentity? = null, var uncertainBoundary: Boolean = false)
        val groups = mutableListOf<Group>()
        var current: Group? = null
        pages.forEach { page ->
            when {
                current == null -> {
                    current = Group("$sessionId-SCRIPT-${groups.size + 1}").also { groups += it }
                    current!!.uncertainBoundary = page.pageClass != "SCRIPT_COVER"
                }
                page.pageClass == "SCRIPT_COVER" -> {
                    current = Group("$sessionId-SCRIPT-${groups.size + 1}").also { groups += it }
                }
                page.pageClass == "UNCERTAIN" -> current!!.uncertainBoundary = true
            }
            current!!.pages += page
            if (current!!.identity == null && page.identity != null) current!!.identity = page.identity
        }

        val groupArray = JSONArray()
        val exportRoot = File(root, "scripts").apply { mkdirs() }
        val dao = SmartScoreDatabase.get(context).dao()
        groups.forEachIndexed { i, group ->
            val identity = group.identity
            val display = identity?.displayStudent()?.takeIf { it.isNotBlank() } ?: "Unidentified ${i + 1}"
            val safe = display.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "script-${i + 1}" }
            val dir = File(exportRoot, safe).apply { mkdirs() }
            val valid = group.pages.filter { File(it.correctedPath).exists() }
            val paths = valid.map { it.correctedPath }
            val pdf = File(dir, "script.pdf")
            val searchable = File(dir, "searchable-script.pdf")
            val txt = File(dir, "ocr.txt")
            val ocrJson = File(dir, "ocr.json")
            val docx = File(dir, "script.docx")
            val zip = File(dir, "ai-package.zip")
            PdfImageExporter.export(pdf, paths)
            PdfImageExporter.exportSearchable(searchable, valid.map { PdfImageExporter.OcrPage(it.correctedPath, it.ocrText) })
            txt.writeText(valid.joinToString("\n\n") { "===== PAGE ${it.index} =====\n${it.ocrText}" })
            ocrJson.writeText(ocrJson(group.id, valid).toString(2))
            DocxExporter.export(docx, "${display} script", valid.map { it.index to it.ocrText })
            val metadata = JSONObject().apply {
                put("schema_version", "3.0")
                put("script_id", group.id)
                put("student_identity", identity?.let(ScriptIdentityExtractor::toJson) ?: JSONObject.NULL)
                put("subject", identity?.subject ?: JSONObject.NULL)
                put("page_count", paths.size)
                put("completion_state", if (group.uncertainBoundary) "REVIEW_REQUIRED" else "COMPLETE")
                put("boundary_review_required", group.uncertainBoundary)
                put("pages", JSONArray().apply {
                    valid.forEachIndexed { pageIndex, page -> put(JSONObject().apply {
                        put("page_number", pageIndex + 1)
                        put("capture_page_number", page.index)
                        put("page_id", "${group.id}-P${pageIndex + 1}")
                        put("classification", page.pageClass)
                        put("cover_score", page.coverScore)
                        put("image", "pages/page-${(pageIndex + 1).toString().padStart(3, '0')}.jpg")
                        put("ocr_text", page.ocrText)
                    }) }
                })
                put("question_paper_reference", JSONObject.NULL)
                put("marking_scheme_reference", JSONObject.NULL)
            }.toString(2)
            ImageZipExporter.export(zip, paths, metadata, mapOf("ocr.txt" to txt.readText(), "ocr.json" to ocrJson.readText()))
            runBlocking {
                dao.deleteScriptPagesForScript(group.id)
                dao.deleteScript(group.id)
                dao.saveScript(ScriptEntity(group.id, identity?.studentId ?: identity?.studentName, identity?.subject, identity?.examTitle, System.currentTimeMillis(), System.currentTimeMillis(), if (group.uncertainBoundary) "REVIEW_REQUIRED" else "COMPLETE", paths.size))
                valid.forEachIndexed { pageIndex, page ->
                    dao.saveScriptPage(ScriptPageEntity("${group.id}-P${pageIndex + 1}", group.id, pageIndex + 1, page.rawPath, page.correctedPath, System.currentTimeMillis()))
                }
            }
            groupArray.put(JSONObject().apply {
                put("script_id", group.id)
                put("display_name", display)
                put("subject", identity?.subject ?: JSONObject.NULL)
                put("page_count", paths.size)
                put("boundary_review_required", group.uncertainBoundary)
                put("pdf_path", pdf.absolutePath)
                put("searchable_pdf_path", searchable.absolutePath)
                put("docx_path", docx.absolutePath)
                put("ai_package_path", zip.absolutePath)
                put("ocr_text_path", txt.absolutePath)
                put("ocr_json_path", ocrJson.absolutePath)
                put("page_numbers", JSONArray(valid.map { it.index }))
            })
        }
        manifest.put("script_count", groups.size)
        manifest.put("scripts", groupArray)
    }

    private fun buildBroadsheetGroups(pages: List<PageResult>, manifest: JSONObject) {
        val repository = V2TemplateRepository(context)
        val groups = linkedMapOf<String, MutableList<PageResult>>()
        val uncertain = JSONArray()
        pages.forEach { page ->
            if (page.sheetId == null) uncertain.put(pageJson(page))
            else groups.getOrPut(page.sheetId) { mutableListOf() }.add(page)
        }
        val arr = JSONArray()
        val dao = SmartScoreDatabase.get(context).dao()
        groups.forEach { (sheetId, sheetPages) ->
            val template = repository.manifestFor(sheetId)
            val scannedPageIds = sheetPages.mapNotNull { it.pageId }.toSet()
            val knownPages = sheetPages.mapNotNull { page -> page.pageId?.let(repository::pageById)?.let { page to it } }
            val unknownPages = sheetPages.filter { page -> page.pageId == null || repository.pageById(page.pageId) == null }
            unknownPages.forEach { uncertain.put(pageJson(it)) }
            var readingCount = 0
            var exceptionCount = 0
            runBlocking { dao.deleteReadingsForSheet(sheetId) }
            knownPages.forEach { (page, pageTemplate) ->
                val bitmap = runCatching { HighResImageLoader.load(page.correctedPath) }.getOrNull() ?: return@forEach
                val scanId = UUID.randomUUID().toString()
                val readings = try { BroadsheetProcessor(context).process(bitmap, pageTemplate, scanId) } finally { bitmap.recycle() }
                readingCount += readings.size
                exceptionCount += readings.count { it.state != "CONFIRMED" && it.state != "BLANK" }
                runBlocking {
                    dao.saveSide(SheetSideEntity(
                        pageTemplate.pageId, pageTemplate.sheetId, pageTemplate.pageNumber, pageTemplate.totalSides,
                        pageTemplate.rowStart, pageTemplate.rowEnd, System.currentTimeMillis(), page.rawPath,
                        page.correctedPath, page.identityMethod, pageTemplate.layoutId, pageTemplate.subjectGroup, pageTemplate.templateVersion
                    ))
                    dao.saveScan(ScanEntity(scanId, pageTemplate.pageId, "SMART_BROADSHEET", page.index, System.currentTimeMillis(), page.rawPath, page.correctedPath, "{\"layout_id\":\"${pageTemplate.layoutId}\"}"))
                    dao.saveReadings(readings)
                }
            }
            val expected = template?.expectedPageIds
            val missing = template?.missingPageIds(scannedPageIds).orEmpty()
            val status = when {
                template == null || unknownPages.isNotEmpty() -> "IDENTITY_UNCERTAIN"
                expected == null -> "REVIEW_REQUIRED"
                missing.isNotEmpty() -> "MISSING_PAGE"
                exceptionCount > 0 -> "REVIEW_REQUIRED"
                else -> "COMPLETE"
            }
            val className = template?.classLabel ?: sheetPages.firstNotNullOfOrNull { it.subjectGroup } ?: "Unknown class"
            val subject = template?.subjectGroup ?: sheetPages.firstNotNullOfOrNull { it.subjectGroup } ?: "Unknown subject"
            val layoutFamily = template?.layoutFamily ?: "UNKNOWN"
            runBlocking {
                dao.saveBroadsheet(BroadsheetEntity(sheetId, className, subject, template?.templateVersion ?: "UNKNOWN", expected?.size ?: 0, status, System.currentTimeMillis(), "LOCAL_ONLY", layoutFamily, File(root, "template-$sheetId.json").absolutePath))
            }
            template?.let { File(root, "template-$sheetId.json").writeText(it.toJson().toString(2)) }
            arr.put(JSONObject().apply {
                put("sheet_id", sheetId)
                put("class", className)
                put("subject_group", subject)
                put("layout_family", layoutFamily)
                put("page_ids", JSONArray(sheetPages.mapNotNull { it.pageId }))
                put("pages", JSONArray(sheetPages.map { it.index }))
                put("expected_page_ids", expected?.let { JSONArray(it) } ?: JSONObject.NULL)
                put("missing_page_ids", JSONArray(missing))
                put("status", status)
                put("score_readings", readingCount)
                put("exceptions", exceptionCount)
            })
        }
        manifest.put("broadsheet_count", groups.size)
        manifest.put("broadsheets", arr)
        manifest.put("uncertain_pages", uncertain)
    }

    private fun pageJson(page: PageResult): JSONObject = JSONObject().apply {
        put("page_number", page.index)
        put("sheet_id", page.sheetId ?: JSONObject.NULL)
        put("page_id", page.pageId ?: JSONObject.NULL)
        put("reason", page.boundaryReason ?: "Identity uncertain")
        put("corrected_path", page.correctedPath)
    }

    private fun ocrJson(id: String, pages: List<PageResult>): JSONObject = JSONObject().apply {
        put("document_id", id)
        put("pages", JSONArray().apply {
            pages.forEachIndexed { index, page -> put(JSONObject().apply {
                put("page_number", index + 1)
                put("capture_page_number", page.index)
                put("image", File(page.correctedPath).name)
                put("text", page.ocrText)
                put("classification", page.pageClass)
            }) }
        })
    }
}
