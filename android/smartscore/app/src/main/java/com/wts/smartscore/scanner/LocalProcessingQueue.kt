package com.wts.smartscore.scanner

import android.content.Context
import android.graphics.BitmapFactory
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wts.smartscore.BuildConfig
import com.wts.smartscore.data.BroadsheetEntity
import com.wts.smartscore.data.ExportEntity
import com.wts.smartscore.data.ProcessingTaskEntity
import com.wts.smartscore.data.ScriptEntity
import com.wts.smartscore.data.ScriptPageEntity
import com.wts.smartscore.data.ScoreReadingEntity
import com.wts.smartscore.data.SheetSideEntity
import com.wts.smartscore.data.SmartScoreDatabase
import com.wts.smartscore.export.DocxExporter
import com.wts.smartscore.export.ImageZipExporter
import com.wts.smartscore.export.PdfImageExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max

object ProcessingTaskTypes {
    const val IDENTIFY_DOCUMENT = "IDENTIFY_DOCUMENT"
    const val REGISTER_TEMPLATE = "REGISTER_TEMPLATE"
    const val READ_SCORES = "READ_SCORES"
    const val EXTRACT_SCRIPT_IDENTITY = "EXTRACT_SCRIPT_IDENTITY"
    const val SEGMENT_SCRIPTS = "SEGMENT_SCRIPTS"
    const val TRANSCRIBE_SCRIPT = "TRANSCRIBE_SCRIPT"
}

/** Small durable queue. WorkManager is only the scheduler; Room is the source of truth. */
object LocalProcessingQueue {
    private const val WORK_NAME = "smartscore-local-processing"

    suspend fun enqueue(context: Context, type: String, parentId: String, payload: JSONObject? = null) {
        val dao = SmartScoreDatabase.get(context).dao()
        val payloadJson = payload?.toString()
        if (dao.openTask(type, parentId, payloadJson) == null) {
            val now = System.currentTimeMillis()
            dao.saveTask(ProcessingTaskEntity(UUID.randomUUID().toString(), type, parentId, payloadJson, "PENDING", 0, now, now))
        }
        schedule(context)
    }

    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<LocalProcessingWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}

class LocalProcessingWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val dao = SmartScoreDatabase.get(appContext).dao()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val runner = LocalTaskRunner(applicationContext)
        while (true) {
            val task = dao.nextPendingTask() ?: break
            val now = System.currentTimeMillis()
            dao.saveTask(task.copy(status = "PROCESSING", attempts = task.attempts + 1, updatedAt = now))
            try {
                runner.run(task)
                dao.saveTask(task.copy(status = "COMPLETED", attempts = task.attempts + 1, updatedAt = System.currentTimeMillis(), lastError = null))
            } catch (error: Throwable) {
                dao.saveTask(task.copy(status = "FAILED", attempts = task.attempts + 1, updatedAt = System.currentTimeMillis(), lastError = error.message ?: error.javaClass.simpleName))
                runner.markFailed(task, error)
            }
        }
        Result.success()
    }
}

private class LocalTaskRunner(private val context: Context) {
    private val dao = SmartScoreDatabase.get(context).dao()
    private val repository by lazy { V2TemplateRepository(context) }

    suspend fun run(task: ProcessingTaskEntity) {
        when (task.taskType) {
            ProcessingTaskTypes.IDENTIFY_DOCUMENT -> identifyBroadsheet(task.parentId, task.payload())
            ProcessingTaskTypes.REGISTER_TEMPLATE -> registerTemplate(task.parentId, task.payload())
            ProcessingTaskTypes.READ_SCORES -> readScores(task.parentId, task.payload())
            ProcessingTaskTypes.EXTRACT_SCRIPT_IDENTITY -> extractScriptIdentity(task.parentId, task.payload())
            ProcessingTaskTypes.SEGMENT_SCRIPTS -> segmentScripts(task.parentId)
            ProcessingTaskTypes.TRANSCRIBE_SCRIPT -> transcribeScript(task.parentId)
        }
    }

    suspend fun markFailed(task: ProcessingTaskEntity, error: Throwable) {
        val pageId = task.payload()?.optString("page_id").orEmpty()
        if (task.taskType == ProcessingTaskTypes.EXTRACT_SCRIPT_IDENTITY) {
            dao.scriptPage(pageId)?.let { dao.saveScriptPage(it.copy(analysisState = "FAILED")) }
        } else if (pageId.isNotBlank()) {
            dao.side(pageId)?.let { dao.saveSide(it.copy(pageState = "FAILED")) }
            updateBroadsheetAggregate(task.parentId)
        } else if (task.taskType == ProcessingTaskTypes.SEGMENT_SCRIPTS || task.taskType == ProcessingTaskTypes.TRANSCRIBE_SCRIPT) {
            dao.script(task.parentId)?.let { dao.saveScript(it.copy(completionState = "FAILED", completedAt = null)) }
        }
    }

    private suspend fun identifyBroadsheet(parentId: String, payload: JSONObject?) {
        val pageId = payload?.optString("page_id").orEmpty()
        val side = dao.side(pageId) ?: return
        val path = side.normalizedPath ?: side.imagePath
        val bitmap = BitmapFactory.decodeFile(path) ?: error("Saved page could not be opened")
        try {
            val qr = runCatching { SheetIdentityResolver.resolvePageIdentity(bitmap) }.getOrNull()
            val text = runCatching { ScriptIdentityExtractor.extractText(context, path) }.getOrDefault("")
            val ocrPageId = Regex("(?i)((?:WTS|SMB)-[A-Z0-9-]+)-(?:P|S)([0-9]+)").find(text)?.let {
                "${it.groupValues[1].uppercase()}-P${it.groupValues[2]}"
            }
            val qrTemplate = qr?.let(repository::pageForIdentity)
            val ocrTemplate = ocrPageId?.let(repository::pageById)
            val headingManifest = repository.allManifests().firstOrNull { manifest ->
                text.contains(manifest.sheetId, true) ||
                    (text.contains(manifest.classLabel, true) && text.contains(manifest.subjectGroup.split(" • ").first(), true))
            }
            val template = qrTemplate ?: ocrTemplate ?: headingManifest?.pages?.singleOrNull()
            val actualSheetId = template?.sheetId ?: qr?.sheetId ?: ocrPageId?.substringBeforeLast("-P")
            val classLabel = template?.let { repository.manifestFor(it.sheetId)?.classLabel }
            val subject = template?.subjectGroup
            val term = template?.let { repository.manifestFor(it.sheetId)?.term } ?: "FIRST"
            val confidence = when {
                qrTemplate != null -> 0.99
                ocrTemplate != null -> 0.88
                template != null -> 0.74
                qr != null -> 0.62
                else -> 0.0
            }
            val identityMethod = when {
                qrTemplate != null -> "QR_ASSISTED"
                ocrTemplate != null -> "HEADING_OCR"
                template != null -> "LAYOUT_FINGERPRINT"
                else -> "UNIDENTIFIED"
            }
            val identity = JSONObject().apply {
                put("actual_sheet_id", actualSheetId ?: JSONObject.NULL)
                put("template_page_id", template?.pageId ?: JSONObject.NULL)
                put("page_number", template?.pageNumber ?: qr?.pageNumber ?: JSONObject.NULL)
                put("class", classLabel ?: JSONObject.NULL)
                put("subject", subject ?: JSONObject.NULL)
                put("term", term ?: JSONObject.NULL)
                put("method", identityMethod)
                put("confidence", confidence)
                put("heading_ocr", text.take(1200))
            }
            val retainedPageState = when (side.pageState) {
                "READY", "REVIEW_REQUIRED", "FAILED" -> side.pageState
                else -> "SCANNED"
            }
            dao.saveSide(side.copy(
                sideNumber = template?.pageNumber ?: side.sideNumber,
                totalSides = template?.let { repository.manifestFor(it.sheetId)?.expectedPageIds?.size ?: 0 } ?: side.totalSides,
                rowStart = template?.rowStart ?: side.rowStart,
                rowEnd = template?.rowEnd ?: side.rowEnd,
                identityMethod = identityMethod,
                layoutId = template?.layoutId ?: side.layoutId,
                subjectGroup = subject ?: side.subjectGroup,
                templateVersion = template?.templateVersion ?: side.templateVersion,
                // Identity is evidence for later mapping only. It never
                // changes a saved page into an extraction blocker.
                pageState = retainedPageState,
                identityConfidence = confidence,
                identityJson = identity.toString()
            ))
            val sheet = dao.broadsheet(parentId) ?: return
            dao.saveBroadsheet(sheet.copy(
                classLabel = classLabel ?: sheet.classLabel,
                subject = subject ?: sheet.subject,
                templateVersion = template?.templateVersion ?: sheet.templateVersion,
                expectedPageCount = template?.let { repository.manifestFor(it.sheetId)?.expectedPageIds?.size ?: 0 } ?: sheet.expectedPageCount,
                reviewStatus = if (template == null) sheet.reviewStatus else "PROCESSING",
                layoutFamily = template?.layoutFamily ?: "GENERIC_SCORE_SHEET",
                term = term,
                documentType = if (template == null) "GENERIC_BROADSHEET" else "SMART_TEMPLATE",
                identityConfidence = max(sheet.identityConfidence, confidence),
                lastUpdatedAt = System.currentTimeMillis()
            ))
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun registerTemplate(parentId: String, payload: JSONObject?) {
        val pageId = payload?.optString("page_id").orEmpty()
        val side = dao.side(pageId) ?: return
        if (side.pageState == "FAILED") return
        val templatePageId = side.identityJson?.let { runCatching { JSONObject(it).optString("template_page_id") }.getOrDefault("") }.orEmpty()
        dao.saveSide(side.copy(pageState = if (templatePageId.isBlank()) "SCANNED" else "PROCESSING"))
        updateBroadsheetAggregate(parentId)
    }

    private suspend fun readScores(parentId: String, payload: JSONObject?) {
        val pageId = payload?.optString("page_id").orEmpty()
        val side = dao.side(pageId) ?: return
        if (side.pageState == "FAILED") return
        dao.saveSide(side.copy(pageState = "PROCESSING"))
        updateBroadsheetAggregate(parentId)
        val identity = side.identityJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val templatePageId = identity?.optString("template_page_id").orEmpty()
        val runtime = OpenCvRuntime.initialize(context)
        if (runtime.state != OpenCvRuntime.State.OPENCV_READY) error("Score processing is unavailable on this device")
        val path = side.normalizedPath ?: side.imagePath
        val bitmap = BitmapFactory.decodeFile(path) ?: error("Saved page could not be opened")
        try {
            val diagnostics = if (BuildConfig.DEBUG) File(context.filesDir, "broadsheets/diagnostics/$parentId/$pageId").apply { mkdirs() } else null
            val processor = BroadsheetProcessor(context)
            // A known template is an extraction optimization, not an identity
            // requirement. Shape matching lets the recovered physical V2 page
            // use its exact geometry even when its QR/heading is absent.
            val table = runCatching { GenericTableDetector.detect(bitmap) }.getOrNull()
            var template = templatePageId.takeIf { it.isNotBlank() }?.let(repository::pageById)
            if (template == null) template = repository.pageForExtractionShape(bitmap, side.sideNumber, table)
            val output = if (template != null) {
                processor.processDetailed(bitmap, template.copy(sheetId = parentId), pageId, diagnostics, side.imagePath)
                    .let { result -> result.copy(readings = result.readings.map { it.copy(sheetId = parentId, sideId = pageId, scanId = pageId) }) }
            } else {
                processor.processGenericDetailed(bitmap, pageId, pageId, diagnostics, side.imagePath)
                    .let { result -> result.copy(readings = result.readings.map { reading -> reading.copy(sheetId = parentId, sideId = pageId, scanId = pageId) }) }
            }
            val readings = output.readings
            dao.deleteReadingsForSide(pageId)
            dao.saveReadings(readings)
            val needsReview = readings.any { it.state in setOf("DOUBTFUL", "REVIEW_REQUIRED", "MISALIGNED", "INVALID", "UNREADABLE") }
            val extraction = JSONObject().apply {
                put("path", if (template != null) "KNOWN_LAYOUT_SHAPE_OR_IDENTITY" else "GENERIC_GRID")
                put("template_page_id", template?.pageId ?: JSONObject.NULL)
                put("template_layout_id", template?.layoutId ?: JSONObject.NULL)
                put("detected_cell_count", readings.size)
                put("recognized_count", readings.count { it.rawValue != null || it.reviewedValue != null })
                put("doubtful_count", readings.count { it.state in setOf("DOUBTFUL", "REVIEW_REQUIRED", "MISALIGNED", "INVALID", "UNREADABLE") })
                put("diagnostic_file", output.diagnosticFile ?: JSONObject.NULL)
                put("table", table?.toJson() ?: JSONObject.NULL)
            }
            val nextState = when {
                readings.isEmpty() -> "FAILED"
                needsReview -> "REVIEW_REQUIRED"
                else -> "READY"
            }
            dao.saveSide(side.copy(
                pageState = nextState,
                layoutId = template?.layoutId ?: side.layoutId,
                templateVersion = template?.templateVersion ?: side.templateVersion,
                extractionJson = extraction.toString()
            ))
            updateBroadsheetAggregate(parentId)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun updateBroadsheetAggregate(parentId: String) {
        val sheet = dao.broadsheet(parentId) ?: return
        val pages = dao.pages(parentId)
        val readings = dao.readings(parentId)
        val reviewCount = readings.count { it.state in setOf("DOUBTFUL", "REVIEW_REQUIRED", "MISALIGNED", "INVALID", "UNREADABLE") }
        val recognizedCount = readings.count { it.rawValue != null || it.reviewedValue != null }
        val status = when {
            pages.any { it.pageState == "FAILED" } -> "FAILED"
            pages.any { it.pageState == "SCANNED" || it.pageState == "PROCESSING" } -> "PROCESSING"
            reviewCount > 0 -> "REVIEW_REQUIRED"
            else -> "READY"
        }
        val identity = pages.maxOfOrNull { it.identityConfidence } ?: sheet.identityConfidence
        dao.saveBroadsheet(sheet.copy(
            reviewStatus = if (pages.isEmpty()) "SCANNED" else status,
            pageCount = pages.size,
            recognizedCount = recognizedCount,
            reviewCount = reviewCount,
            identityConfidence = max(sheet.identityConfidence, identity),
            lastUpdatedAt = System.currentTimeMillis()
        ))
    }

    private suspend fun extractScriptIdentity(sessionId: String, payload: JSONObject?) {
        val pageId = payload?.optString("page_id").orEmpty()
        val page = dao.scriptPage(pageId) ?: return
        val path = page.normalizedPath ?: page.imagePath
        val identity = ScriptIdentityExtractor.extract(context, path)
        val classification = ScriptPageClassifier.classify(identity, identity.rawText)
        dao.saveScriptPage(page.copy(
            analysisState = "READY",
            pageClass = classification.pageClass,
            boundaryScore = classification.coverScore,
            identityJson = ScriptIdentityExtractor.toJson(identity).toString(),
            ocrText = identity.rawText
        ))
        dao.script(sessionId)?.let { script ->
            dao.saveScript(script.copy(completionState = "PROCESSING", pageCount = dao.scriptPagesForSession(sessionId).size))
        }
    }

    private suspend fun segmentScripts(sessionId: String) {
        val provisional = dao.script(sessionId) ?: return
        var pages = dao.scriptPagesForSession(sessionId).sortedBy { it.pageNumber }
        if (pages.isEmpty()) return
        pages.filter { it.analysisState == "PENDING" }.forEach { page ->
            extractScriptIdentity(sessionId, JSONObject().put("page_id", page.pageId))
        }
        pages = dao.scriptPagesForSession(sessionId).sortedBy { it.pageNumber }
        data class Group(val id: String, val pages: MutableList<ScriptPageEntity> = mutableListOf(), var identity: ScriptIdentity? = null, var needsReview: Boolean = false)
        val groups = mutableListOf<Group>()
        var current: Group? = null
        pages.forEach { page ->
            val identity = page.identityJson?.let { runCatching { ScriptIdentityExtractor.fromJson(JSONObject(it)) }.getOrNull() }
            if (current == null || page.pageClass == "NEW_SCRIPT_START" && current?.pages?.isNotEmpty() == true) {
                current = Group("$sessionId-SCRIPT-${groups.size + 1}").also { groups += it }
            }
            if (page.pageClass == "UNCERTAIN_BOUNDARY" || (current?.pages?.isEmpty() == true && page.pageClass != "NEW_SCRIPT_START")) current!!.needsReview = true
            current!!.pages += page
            if (current!!.identity == null && identity != null) current!!.identity = identity
        }

        groups.forEach { group ->
            val identity = group.identity
            val confidentIdentity = identity != null && identity.confidence >= 0.72 && (identity.studentName != null || identity.studentId != null)
            val needsReview = group.needsReview || !confidentIdentity
            val state = if (needsReview) "NEEDS_REVIEW" else "OCR_READY"
            val status = if (confidentIdentity) "CONFIDENT" else "NEEDS_REVIEW"
            dao.deleteScriptPagesForScript(group.id)
            dao.deleteScript(group.id)
            dao.saveScript(ScriptEntity(
                scriptId = group.id,
                studentRef = identity?.studentName ?: identity?.studentId,
                subject = identity?.subject,
                testRef = identity?.examTitle,
                createdAt = provisional.createdAt,
                completedAt = System.currentTimeMillis(),
                completionState = state,
                pageCount = group.pages.size,
                sessionId = sessionId,
                identityStatus = status,
                identityConfidence = identity?.confidence ?: 0.0
            ))
            group.pages.forEachIndexed { index, page ->
                dao.saveScriptPage(page.copy(scriptId = group.id, pageNumber = index + 1, sessionId = sessionId))
            }
            exportScriptGroup(group.id, identity, group.pages)
            LocalProcessingQueue.enqueue(context, ProcessingTaskTypes.TRANSCRIBE_SCRIPT, group.id)
        }
        dao.deleteScriptPagesForScript(sessionId)
        dao.deleteScript(sessionId)
    }

    private suspend fun transcribeScript(scriptId: String) {
        val script = dao.script(scriptId) ?: return
        val pages = dao.scriptPages(scriptId)
        pages.filter { it.ocrText.isBlank() }.forEach { page ->
            val text = runCatching { ScriptIdentityExtractor.extractText(context, page.normalizedPath ?: page.imagePath) }.getOrDefault("")
            dao.saveScriptPage(page.copy(ocrText = text, analysisState = "READY"))
        }
        dao.saveScript(script.copy(completionState = if (script.identityStatus == "CONFIDENT") "OCR_READY" else "NEEDS_REVIEW"))
    }

    private suspend fun exportScriptGroup(scriptId: String, identity: ScriptIdentity?, sourcePages: List<ScriptPageEntity>) {
        val pages = sourcePages.sortedBy { it.pageNumber }.filter { File(it.normalizedPath ?: it.imagePath).exists() }
        if (pages.isEmpty()) return
        val dir = File(context.filesDir, "scripts/$scriptId").apply { mkdirs() }
        val paths = pages.map { it.normalizedPath ?: it.imagePath }
        val texts = pages.mapIndexed { index, page -> index + 1 to (page.ocrText.ifBlank { runCatching { ScriptIdentityExtractor.extractText(context, paths[index]) }.getOrDefault("") }) }
        val display = identity?.displayStudent() ?: "Unidentified script"
        val pdf = File(dir, "script.pdf")
        val searchable = File(dir, "searchable-script.pdf")
        val docx = File(dir, "script.docx")
        val images = File(dir, "images.zip")
        val ai = File(dir, "ai-ready.zip")
        val txt = File(dir, "ocr.txt")
        val json = File(dir, "ocr.json")
        PdfImageExporter.export(pdf, paths)
        PdfImageExporter.exportSearchable(searchable, texts.mapIndexed { index, text -> PdfImageExporter.OcrPage(paths[index], text.second) })
        txt.writeText(texts.joinToString("\n\n") { (page, text) -> "===== PAGE $page =====\n$text" })
        val metadata = JSONObject().apply {
            put("schema_version", "4.0")
            put("script_id", scriptId)
            put("student_identity", identity?.let(ScriptIdentityExtractor::toJson) ?: JSONObject.NULL)
            put("page_count", paths.size)
            put("exports", JSONObject().put("pdf", pdf.name).put("searchable_pdf", searchable.name).put("docx", docx.name))
            put("pages", JSONArray().apply { pages.forEachIndexed { index, page -> put(JSONObject().put("page_number", index + 1).put("capture_page_id", page.pageId).put("image", "pages/page-${(index + 1).toString().padStart(3, '0')}.jpg").put("text", texts[index].second)) } })
        }
        json.writeText(JSONObject().put("script_id", scriptId).put("pages", JSONArray().apply { texts.forEach { (page, text) -> put(JSONObject().put("page_number", page).put("text", text)) } }).toString(2))
        DocxExporter.export(docx, "$display script", texts)
        ImageZipExporter.export(images, paths, metadata.toString(2))
        ImageZipExporter.export(ai, paths, metadata.toString(2), mapOf("ocr.txt" to txt.readText(), "ocr.json" to json.readText()))
        val now = System.currentTimeMillis()
        listOf("PDF" to pdf, "SEARCHABLE_PDF" to searchable, "DOCX" to docx, "IMAGES" to images, "AI_READY_ZIP" to ai).forEach { (type, file) ->
            dao.saveExport(ExportEntity("$scriptId-$type", scriptId, type, file.absolutePath, now, "READY"))
        }
    }

    private fun ProcessingTaskEntity.payload(): JSONObject? = payloadJson?.let { runCatching { JSONObject(it) }.getOrNull() }
}
