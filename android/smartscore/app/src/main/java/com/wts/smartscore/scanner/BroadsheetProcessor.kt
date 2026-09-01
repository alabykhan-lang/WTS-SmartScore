package com.wts.smartscore.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.wts.smartscore.data.ScoreReadingEntity
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Maps a configured broadsheet page to score cells. The page image passed to
 * this class is already the corrected high-resolution master; preview frames
 * are never used for score recognition.
 */
class BroadsheetProcessor(private val context: Context) {
    data class ProcessOutput(
        val readings: List<ScoreReadingEntity>,
        val diagnosticFile: String?
    )

    private data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private data class PreparedCrop(
        val bitmap: Bitmap,
        val inkPixels: Int,
        val inkRatio: Double,
        val connectedComponents: Int,
        val contrast: Double,
        val blank: Boolean
    )

    fun process(bitmap: Bitmap, side: SheetPageTemplate, scanId: String): List<ScoreReadingEntity> =
        processDetailed(bitmap, side, scanId, null, null).readings

    /** Debug overload used by debug builds and physical-test diagnostics. */
    fun process(
        bitmap: Bitmap,
        side: SheetPageTemplate,
        scanId: String,
        diagnosticDir: File?,
        inputPath: String? = null,
        qrBounds: RectF? = null
    ): List<ScoreReadingEntity> = processDetailed(bitmap, side, scanId, diagnosticDir, inputPath, qrBounds).readings

    fun processDetailed(
        bitmap: Bitmap,
        side: SheetPageTemplate,
        scanId: String,
        diagnosticDir: File?,
        inputPath: String? = null,
        qrBounds: RectF? = null
    ): ProcessOutput {
        diagnosticDir?.mkdirs()
        val roiDir = (diagnosticDir?.let { File(it, "crops") }
            ?: File(context.filesDir, "broadsheet-crops")).apply { mkdirs() }
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val digitRecognizer = DigitRecognizerFactory.create(context, textRecognizer)
        val registration = TemplateRegistrar.register(bitmap, side, qrBounds)
        val diagnostics = JSONArray()
        try {
            if (diagnosticDir != null) {
                saveInputArtifact(bitmap, inputPath, File(diagnosticDir, "01-input-scan.jpg"))
                ImageProcessor.saveJpeg(bitmap, File(diagnosticDir, "02-canonical-page.jpg"), 98)
                ImageProcessor.saveJpeg(bitmap, File(diagnosticDir, "page-corrected.jpg"), 98)
                val overlay = renderTemplateOverlay(bitmap, side, registration)
                ImageProcessor.saveJpeg(overlay, File(diagnosticDir, "03-template-overlay.jpg"), 98)
                ImageProcessor.saveJpeg(overlay, File(diagnosticDir, "template-overlay.jpg"), 98)
                ImageProcessor.saveJpeg(overlay, File(diagnosticDir, "table-overlay.jpg"), 98)
                overlay.recycle()
            }

            val readings = side.rows.flatMap { row ->
                row.rois.mapIndexed { roiIndex, roi ->
                    val prefix = "student-${row.rowNo.toString().padStart(3, '0')}-${safePart(roi.assessmentId)}"
                    val scoreRect = pixelRect(roi.x, roi.y, roi.w, roi.h, side, bitmap, registration)
                    val scoreCrop = scoreRect?.let { Bitmap.createBitmap(bitmap, it.left, it.top, it.width, it.height) }
                    val scorePrepared = scoreCrop?.let(::prepareCrop)
                    val scoreSourcePath = scoreCrop?.let { crop ->
                        val file = File(roiDir, "$prefix-source.jpg")
                        ImageProcessor.saveJpeg(crop, file)
                        // Stable row/column names make the diagnostic folder
                        // useful without knowing the internal template IDs.
                        ImageProcessor.saveJpeg(
                            crop,
                            File(roiDir, "row${row.rowNo.toString().padStart(2, '0')}-col${(roiIndex + 1).toString().padStart(2, '0')}.jpg")
                        )
                        file.absolutePath
                    }
                    val scorePreprocessedPath = scorePrepared?.let { prepared ->
                        val file = File(roiDir, "$prefix-preprocessed.jpg")
                        ImageProcessor.saveJpeg(prepared.bitmap, file)
                        file.absolutePath
                    }

                    val observations = roi.digitBoxes.sortedBy { it.index }.map { digitBox ->
                        recognizeDigit(
                            bitmap = bitmap,
                            side = side,
                            digitBox = digitBox,
                            recognizer = digitRecognizer,
                            registration = registration,
                            outputDir = roiDir,
                            prefix = prefix
                        )
                    }
                    val roiInkPresent = scorePrepared?.blank == false
                    val assembly = BroadsheetScoreAssembler.assemble(observations, roi.maximum, roiInkPresent)
                    val reading = ScoreReadingEntity(
                        id = UUID.randomUUID().toString(),
                        sheetId = side.sheetId,
                        sideId = side.pageId,
                        scanId = scanId,
                        studentId = row.studentId,
                        studentName = row.studentName,
                        assessmentId = roi.assessmentId,
                        maximum = roi.maximum,
                        rawValue = assembly.value,
                        reviewedValue = assembly.value,
                        confidence = assembly.confidence,
                        state = assembly.state,
                        cropPath = scoreSourcePath,
                        reviewedAt = null,
                        recognizedText = displayText(observations, assembly),
                        digitDetailsJson = digitDetails(observations)
                    )
                    diagnostics.put(roiDiagnostic(row, roi, side, scoreRect, scorePrepared, scoreSourcePath, scorePreprocessedPath, observations, assembly, registration))
                    scorePrepared?.bitmap?.recycle()
                    scoreCrop?.recycle()
                    reading
                }
            }

            val diagnosticFile = diagnosticDir?.let { directory ->
                val cellOverlay = renderTemplateCellOverlay(bitmap, side, registration, readings)
                ImageProcessor.saveJpeg(cellOverlay, File(directory, "cell-overlay.jpg"), 98)
                cellOverlay.recycle()
                val output = JSONObject().apply {
                    put("schema_version", "2.0")
                    put("sheet_id", side.sheetId)
                    put("page_id", side.pageId)
                    put("layout_id", side.layoutId)
                    put("layout_family", side.layoutFamily)
                    put("template_version", side.templateVersion)
                    put("coordinate_origin", side.coordinateOrigin)
                    put("canonical_width", side.pageW)
                    put("canonical_height", side.pageH)
                    put("source_width", bitmap.width)
                    put("source_height", bitmap.height)
                    put("registration", registration.toJson())
                    put("recognizer_engine", digitRecognizer.engineName)
                    put("roi_count", diagnostics.length())
                    put("detected_cell_count", diagnostics.length())
                    put("recognized_count", readings.count { it.rawValue != null || it.reviewedValue != null })
                    put("doubtful_count", readings.count { it.state in setOf("DOUBTFUL", "REVIEW_REQUIRED", "MISALIGNED", "INVALID", "UNREADABLE") })
                    put("rois", diagnostics)
                }
                File(directory, "diagnostic.json").writeText(output.toString(2))
                File(directory, "recognition.json").also { it.writeText(output.toString(2)) }.absolutePath
            }
            return ProcessOutput(readings, diagnosticFile)
        } finally {
            digitRecognizer.close()
            textRecognizer.close()
        }
    }

    /**
     * Extracts a generic record sheet without requiring a QR, roster or
     * template identity. The detector supplies the table geometry and the
     * same per-digit recognizer used by known templates reads each cell.
     */
    fun processGenericDetailed(
        bitmap: Bitmap,
        scanId: String,
        pageId: String,
        diagnosticDir: File?,
        inputPath: String? = null
    ): ProcessOutput {
        diagnosticDir?.mkdirs()
        val cropDir = (diagnosticDir?.let { File(it, "crops") }
            ?: File(context.filesDir, "broadsheet-crops")).apply { mkdirs() }
        val detection = GenericTableDetector.detect(bitmap)
        if (detection == null) {
            val diagnostic = diagnosticDir?.let { directory ->
                saveInputArtifact(bitmap, inputPath, File(directory, "page-corrected.jpg"))
                val output = JSONObject().apply {
                    put("schema_version", "2.0")
                    put("page_id", pageId)
                    put("path", "GENERIC_TABLE_NOT_DETECTED")
                    put("detected_cell_count", 0)
                    put("recognized_count", 0)
                    put("doubtful_count", 0)
                    put("cells", JSONArray())
                }
                File(directory, "diagnostic.json").writeText(output.toString(2))
                File(directory, "recognition.json").also { it.writeText(output.toString(2)) }.absolutePath
            }
            return ProcessOutput(emptyList(), diagnostic)
        }

        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val digitRecognizer = DigitRecognizerFactory.create(context, textRecognizer)
        val diagnostics = JSONArray()
        try {
            if (diagnosticDir != null) {
                saveInputArtifact(bitmap, inputPath, File(diagnosticDir, "01-input-scan.jpg"))
                saveInputArtifact(bitmap, inputPath, File(diagnosticDir, "page-corrected.jpg"))
                ImageProcessor.saveJpeg(bitmap, File(diagnosticDir, "02-canonical-page.jpg"), 98)
                val tableOverlay = renderGenericTableOverlay(bitmap, detection)
                ImageProcessor.saveJpeg(tableOverlay, File(diagnosticDir, "table-overlay.jpg"), 98)
                tableOverlay.recycle()
            }

            val readings = detection.cells.map { cell ->
                val stem = "row${(cell.rowIndex + 1).toString().padStart(2, '0')}-col${(cell.columnIndex + 1).toString().padStart(2, '0')}"
                val sourceCrop = Bitmap.createBitmap(bitmap, cell.rect.left, cell.rect.top, cell.rect.width(), cell.rect.height())
                val sourcePath = File(cropDir, "$stem.jpg").also { ImageProcessor.saveJpeg(sourceCrop, it, 98) }
                val scorePrepared = prepareCrop(sourceCrop)
                val preprocessedPath = File(cropDir, "$stem-preprocessed.jpg").also { ImageProcessor.saveJpeg(scorePrepared.bitmap, it, 98) }
                val observations = recognizeGenericDigits(sourceCrop, digitRecognizer, cropDir, stem)
                val roiInkPresent = !scorePrepared.blank
                val assembly = BroadsheetScoreAssembler.assemble(observations, 100.0, roiInkPresent)
                val reading = ScoreReadingEntity(
                    id = UUID.randomUUID().toString(),
                    sheetId = pageId,
                    sideId = pageId,
                    scanId = scanId,
                    studentId = "GENERIC-ROW-${(cell.rowIndex + 1).toString().padStart(3, '0')}",
                    studentName = "Row ${cell.rowIndex + 1}",
                    assessmentId = "C${cell.columnIndex + 1}",
                    maximum = 100.0,
                    rawValue = assembly.value,
                    reviewedValue = assembly.value,
                    confidence = min(cell.confidence, assembly.confidence),
                    state = assembly.state,
                    cropPath = sourcePath.absolutePath,
                    reviewedAt = null,
                    recognizedText = displayText(observations, assembly),
                    digitDetailsJson = digitDetails(observations)
                )
                diagnostics.put(genericCellDiagnostic(cell, sourcePath.absolutePath, preprocessedPath.absolutePath, scorePrepared, observations, assembly))
                scorePrepared.bitmap.recycle()
                sourceCrop.recycle()
                reading
            }

            if (diagnosticDir != null) {
                val cellOverlay = renderGenericCellOverlay(bitmap, detection, readings)
                ImageProcessor.saveJpeg(cellOverlay, File(diagnosticDir, "cell-overlay.jpg"), 98)
                cellOverlay.recycle()
            }
            val output = JSONObject().apply {
                put("schema_version", "2.0")
                put("page_id", pageId)
                put("path", "GENERIC_GRID")
                put("recognizer_engine", digitRecognizer.engineName)
                put("table", detection.toJson())
                put("detected_cell_count", detection.cells.size)
                put("recognized_count", readings.count { it.rawValue != null || it.reviewedValue != null })
                put("doubtful_count", readings.count { it.state in setOf("DOUBTFUL", "REVIEW_REQUIRED", "MISALIGNED", "INVALID", "UNREADABLE") })
                put("cells", diagnostics)
            }
            val diagnosticFile = diagnosticDir?.let { directory ->
                File(directory, "diagnostic.json").writeText(output.toString(2))
                File(directory, "recognition.json").also { it.writeText(output.toString(2)) }.absolutePath
            }
            return ProcessOutput(readings, diagnosticFile)
        } finally {
            digitRecognizer.close()
            textRecognizer.close()
        }
    }

    private fun recognizeGenericDigits(
        sourceCrop: Bitmap,
        recognizer: DigitRecognizer,
        outputDir: File,
        stem: String
    ): List<DigitObservation> {
        val halfWidth = (sourceCrop.width / 2).coerceAtLeast(1)
        val parts = listOf(
            Bitmap.createBitmap(sourceCrop, 0, 0, halfWidth, sourceCrop.height),
            Bitmap.createBitmap(sourceCrop, halfWidth, 0, sourceCrop.width - halfWidth, sourceCrop.height)
        )
        data class Part(val source: Bitmap, val prepared: PreparedCrop)
        val prepared = parts.map { Part(it, prepareCrop(it)) }
        val inkParts = prepared.filter { !it.prepared.blank }
        val selected = when {
            inkParts.isEmpty() -> prepared
            inkParts.size == 1 -> inkParts
            else -> prepared
        }
        return selected.mapIndexed { index, part ->
            val sourceFile = File(outputDir, "$stem-digit-${index + 1}-source.jpg")
            val preprocessedFile = File(outputDir, "$stem-digit-${index + 1}-preprocessed.jpg")
            ImageProcessor.saveJpeg(part.source, sourceFile, 98)
            ImageProcessor.saveJpeg(part.prepared.bitmap, preprocessedFile, 98)
            val guess = if (part.prepared.blank) {
                DigitGuess(value = null, confidence = 1.0, blank = true)
            } else {
                recognizer.recognize(part.prepared.bitmap)
            }
            val observation = DigitObservation(
                index = index,
                value = guess.value,
                confidence = guess.confidence,
                blank = part.prepared.blank,
                sourcePath = sourceFile.absolutePath,
                preprocessedPath = preprocessedFile.absolutePath,
                inkPixels = part.prepared.inkPixels,
                inkRatio = part.prepared.inkRatio,
                connectedComponents = part.prepared.connectedComponents,
                contrast = part.prepared.contrast,
                rawOcrText = guess.rawText,
                normalizedOcrText = guess.normalizedText,
                recognizerEngine = recognizer.engineName
            )
            part.prepared.bitmap.recycle()
            part.source.recycle()
            observation
        }
    }

    private fun displayText(observations: List<DigitObservation>, assembly: ScoreAssembly): String {
        if (assembly.state == "BLANK") return "—"
        return observations.sortedBy { it.index }.joinToString("") { observation ->
            when {
                observation.blank -> "?"
                observation.value != null && observation.confidence >= 0.72 -> observation.value.toString()
                else -> "?"
            }
        }.ifBlank { "?" }
    }

    private fun digitDetails(observations: List<DigitObservation>): String = JSONArray().apply {
        observations.sortedBy { it.index }.forEach { observation ->
            put(JSONObject().apply {
                put("index", observation.index)
                put("value", observation.value ?: JSONObject.NULL)
                put("confidence", observation.confidence)
                put("blank", observation.blank)
                put("source_path", observation.sourcePath ?: JSONObject.NULL)
                put("preprocessed_path", observation.preprocessedPath ?: JSONObject.NULL)
                put("engine", observation.recognizerEngine)
                put("raw_text", observation.rawOcrText)
                put("normalized_text", observation.normalizedOcrText)
            })
        }
    }.toString()

    private fun recognizeDigit(
        bitmap: Bitmap,
        side: SheetPageTemplate,
        digitBox: DigitBoxDef,
        recognizer: DigitRecognizer,
        registration: TemplateRegistration,
        outputDir: File,
        prefix: String
    ): DigitObservation {
        val rect = pixelRect(digitBox.x, digitBox.y, digitBox.w, digitBox.h, side, bitmap, registration)
        if (rect == null) {
            return DigitObservation(
                index = digitBox.index,
                value = null,
                confidence = 0.0,
                blank = false,
                sourcePath = null,
                preprocessedPath = null,
                inkPixels = 0,
                inkRatio = 0.0,
                connectedComponents = 0,
                contrast = 0.0,
                rawOcrText = "",
                normalizedOcrText = "",
                alignmentValid = false,
                recognizerEngine = recognizer.engineName
            )
        }

        val sourceCrop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
        val prepared = prepareCrop(sourceCrop)
        val sourceFile = File(outputDir, "$prefix-digit-${digitBox.index + 1}-source.jpg")
        val preprocessedFile = File(outputDir, "$prefix-digit-${digitBox.index + 1}-preprocessed.jpg")
        ImageProcessor.saveJpeg(sourceCrop, sourceFile)
        ImageProcessor.saveJpeg(prepared.bitmap, preprocessedFile)

        val guess = if (prepared.blank) {
            DigitGuess(value = null, confidence = 1.0, blank = true)
        } else {
            recognizer.recognize(prepared.bitmap)
        }
        val observation = DigitObservation(
            index = digitBox.index,
            value = guess.value,
            confidence = guess.confidence,
            blank = prepared.blank,
            sourcePath = sourceFile.absolutePath,
            preprocessedPath = preprocessedFile.absolutePath,
            inkPixels = prepared.inkPixels,
            inkRatio = prepared.inkRatio,
            connectedComponents = prepared.connectedComponents,
            contrast = prepared.contrast,
            rawOcrText = guess.rawText,
            normalizedOcrText = guess.normalizedText,
            recognizerEngine = recognizer.engineName
        )
        prepared.bitmap.recycle()
        sourceCrop.recycle()
        return observation
    }

    /**
     * Remove long printed border lines while retaining components that touch a
     * crop edge. The latter matters for real handwriting written close to a
     * box boundary.
     */
    private fun prepareCrop(source: Bitmap): PreparedCrop {
        val rgba = Mat()
        val gray = Mat()
        val binary = Mat()
        val horizontal = Mat()
        val vertical = Mat()
        val withoutHorizontal = Mat()
        val cleaned = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)

            val horizontalKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(max(3, (binary.cols() * 0.55).roundToInt()).toDouble(), 1.0)
            )
            val verticalKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(1.0, max(3, (binary.rows() * 0.55).roundToInt()).toDouble())
            )
            try {
                Imgproc.morphologyEx(binary, horizontal, Imgproc.MORPH_OPEN, horizontalKernel)
                Core.subtract(binary, horizontal, withoutHorizontal)
                Imgproc.morphologyEx(withoutHorizontal, vertical, Imgproc.MORPH_OPEN, verticalKernel)
                Core.subtract(withoutHorizontal, vertical, cleaned)
            } finally {
                horizontalKernel.release()
                verticalKernel.release()
            }

            // Suppress only the outermost pixel. Printed lines are removed by
            // the long-line pass; this one-pixel mask avoids a residual frame
            // without erasing a handwritten stroke near that frame.
            if (cleaned.cols() > 2 && cleaned.rows() > 2) {
                Imgproc.rectangle(
                    cleaned,
                    org.opencv.core.Point(0.0, 0.0),
                    org.opencv.core.Point((cleaned.cols() - 1).toDouble(), (cleaned.rows() - 1).toDouble()),
                    Scalar(0.0),
                    1
                )
            }

            val componentCount = Imgproc.connectedComponentsWithStats(cleaned, labels, stats, centroids)
            val totalPixels = (cleaned.cols() * cleaned.rows()).coerceAtLeast(1)
            val minComponentArea = max(2.0, totalPixels * 0.0008).roundToInt()
            val kept = Mat.zeros(cleaned.size(), CvType.CV_8UC1)
            var keptComponents = 0
            try {
                for (label in 1 until componentCount) {
                    val area = stats.get(label, Imgproc.CC_STAT_AREA)?.firstOrNull()?.roundToInt() ?: 0
                    if (area >= minComponentArea) {
                        val mask = Mat()
                        try {
                            Core.inRange(labels, Scalar(label.toDouble()), Scalar(label.toDouble()), mask)
                            kept.setTo(Scalar(255.0), mask)
                            keptComponents++
                        } finally {
                            mask.release()
                        }
                    }
                }

                val inkPixels = Core.countNonZero(kept)
                val inkRatio = inkPixels.toDouble() / totalPixels.toDouble()
                val mean = org.opencv.core.MatOfDouble()
                val std = org.opencv.core.MatOfDouble()
                try {
                    Core.meanStdDev(gray, mean, std)
                    val contrast = std.toArray().firstOrNull() ?: 0.0
                    val blank = inkPixels < max(3, (totalPixels * 0.003).roundToInt())
                    val normalized = Mat()
                    val resized = Mat()
                    try {
                        // White background + black foreground is the format the
                        // current recognizer expects.
                        Core.bitwise_not(kept, normalized)
                        val target = 220
                        val scale = min(
                            (target - 24).toDouble() / normalized.cols().coerceAtLeast(1),
                            (target - 24).toDouble() / normalized.rows().coerceAtLeast(1)
                        ).coerceAtLeast(0.1)
                        val resizedWidth = max(2, (normalized.cols() * scale).roundToInt())
                        val resizedHeight = max(2, (normalized.rows() * scale).roundToInt())
                        Imgproc.resize(normalized, resized, Size(resizedWidth.toDouble(), resizedHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
                        val padded = Mat.zeros(target, target, CvType.CV_8UC1)
                        padded.setTo(Scalar(255.0))
                        val x = (target - resized.cols()) / 2
                        val y = (target - resized.rows()) / 2
                        val destination = padded.submat(y, y + resized.rows(), x, x + resized.cols())
                        try {
                            resized.copyTo(destination)
                        } finally {
                            destination.release()
                        }
                        val rgbaOut = Mat()
                        try {
                            Imgproc.cvtColor(padded, rgbaOut, Imgproc.COLOR_GRAY2RGBA)
                            val output = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
                            Utils.matToBitmap(rgbaOut, output)
                            return PreparedCrop(output, inkPixels, inkRatio, keptComponents, contrast, blank)
                        } finally {
                            rgbaOut.release()
                            padded.release()
                        }
                    } finally {
                        normalized.release()
                        resized.release()
                    }
                } finally {
                    mean.release()
                    std.release()
                }
            } finally {
                kept.release()
            }
        } finally {
            rgba.release()
            gray.release()
            binary.release()
            horizontal.release()
            vertical.release()
            withoutHorizontal.release()
            cleaned.release()
            labels.release()
            stats.release()
            centroids.release()
        }
    }

    private fun pixelRect(
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        side: SheetPageTemplate,
        bitmap: Bitmap,
        registration: TemplateRegistration
    ): PixelRect? {
        if (side.pageW <= 0.0 || side.pageH <= 0.0 || w <= 0.0 || h <= 0.0 || bitmap.width < 2 || bitmap.height < 2) return null
        val source = registration.sourceRect
        val sourceWidth = source.width().toDouble()
        val sourceHeight = source.height().toDouble()
        val left = (source.left + x / side.pageW * sourceWidth).roundToInt()
        val right = (source.left + (x + w) / side.pageW * sourceWidth).roundToInt()
        val topFraction = if (side.coordinateOrigin.equals("TOP_LEFT", true)) y / side.pageH else (side.pageH - (y + h)) / side.pageH
        val bottomFraction = if (side.coordinateOrigin.equals("TOP_LEFT", true)) (y + h) / side.pageH else (side.pageH - y) / side.pageH
        val top = (source.top + topFraction * sourceHeight).roundToInt()
        val bottom = (source.top + bottomFraction * sourceHeight).roundToInt()
        val safeLeft = left.coerceIn(0, bitmap.width - 1)
        val safeTop = top.coerceIn(0, bitmap.height - 1)
        val safeRight = right.coerceIn(safeLeft + 1, bitmap.width)
        val safeBottom = bottom.coerceIn(safeTop + 1, bitmap.height)
        if (safeRight - safeLeft < 2 || safeBottom - safeTop < 2) return null
        return PixelRect(safeLeft, safeTop, safeRight, safeBottom)
    }

    private fun renderTemplateOverlay(bitmap: Bitmap, side: SheetPageTemplate, registration: TemplateRegistration): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val roiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, min(output.width, output.height) / 700f)
            color = Color.rgb(0, 150, 255)
        }
        val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, min(output.width, output.height) / 1100f)
            color = Color.rgb(255, 40, 160)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(0, 70, 160)
            textSize = max(10f, min(output.width, output.height) / 120f)
        }
        side.rows.forEach { row ->
            row.rois.forEach { roi ->
                pixelRect(roi.x, roi.y, roi.w, roi.h, side, output, registration)?.let { rect ->
                    canvas.drawRect(RectF(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()), roiPaint)
                    canvas.drawText(
                        "${row.rowNo}/${roi.assessmentId}",
                        rect.left.toFloat(),
                        max(labelPaint.textSize, rect.top.toFloat() - 2f),
                        labelPaint
                    )
                }
                roi.digitBoxes.forEach { digit ->
                    pixelRect(digit.x, digit.y, digit.w, digit.h, side, output, registration)?.let { rect ->
                        canvas.drawRect(RectF(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()), digitPaint)
                    }
                }
            }
        }
        return output
    }

    private fun renderTemplateCellOverlay(
        bitmap: Bitmap,
        side: SheetPageTemplate,
        registration: TemplateRegistration,
        readings: List<ScoreReadingEntity>
    ): Bitmap {
        val output = renderTemplateOverlay(bitmap, side, registration)
        val canvas = Canvas(output)
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(0, 100, 40)
            textSize = max(11f, min(output.width, output.height) / 105f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val byKey = readings.associateBy { "${it.studentId}|${it.assessmentId}" }
        side.rows.forEach { row ->
            row.rois.forEach { roi ->
                val reading = byKey["${row.studentId}|${roi.assessmentId}"] ?: return@forEach
                pixelRect(roi.x, roi.y, roi.w, roi.h, side, output, registration)?.let { rect ->
                    canvas.drawText(
                        displayTextFromReading(reading),
                        rect.left.toFloat(),
                        (rect.top + valuePaint.textSize + 2f).coerceAtMost(output.height - 2f),
                        valuePaint
                    )
                }
            }
        }
        return output
    }

    private fun renderGenericTableOverlay(bitmap: Bitmap, detection: GenericTableDetection): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val tablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(3f, min(output.width, output.height) / 500f)
            color = Color.rgb(0, 120, 255)
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, min(output.width, output.height) / 1000f)
            color = Color.rgb(0, 190, 120)
        }
        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, min(output.width, output.height) / 850f)
            color = Color.rgb(240, 40, 140)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(0, 80, 160)
            textSize = max(10f, min(output.width, output.height) / 125f)
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawRect(
            detection.tableRect.left.toFloat(),
            detection.tableRect.top.toFloat(),
            detection.tableRect.right.toFloat(),
            detection.tableRect.bottom.toFloat(),
            tablePaint
        )
        detection.rowLines.forEach { y -> canvas.drawLine(0f, y.toFloat(), output.width.toFloat(), y.toFloat(), linePaint) }
        detection.columnLines.forEach { x -> canvas.drawLine(x.toFloat(), detection.tableRect.top.toFloat(), x.toFloat(), detection.tableRect.bottom.toFloat(), linePaint) }
        detection.cells.forEach { cell ->
            canvas.drawRect(
                cell.rect.left.toFloat(),
                cell.rect.top.toFloat(),
                cell.rect.right.toFloat(),
                cell.rect.bottom.toFloat(),
                cellPaint
            )
            canvas.drawText("R${cell.rowIndex + 1} C${cell.columnIndex + 1}", cell.rect.left.toFloat(), (cell.rect.top - 2f).coerceAtLeast(labelPaint.textSize), labelPaint)
        }
        return output
    }

    private fun renderGenericCellOverlay(
        bitmap: Bitmap,
        detection: GenericTableDetection,
        readings: List<ScoreReadingEntity>
    ): Bitmap {
        val output = renderGenericTableOverlay(bitmap, detection)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(0, 100, 40)
            textSize = max(12f, min(output.width, output.height) / 100f)
            typeface = Typeface.DEFAULT_BOLD
        }
        readings.zip(detection.cells).forEach { (reading, cell) ->
            canvas.drawText(
                displayTextFromReading(reading),
                cell.rect.left.toFloat(),
                (cell.rect.top + paint.textSize + 2f).coerceAtMost(output.height - 2f),
                paint
            )
        }
        return output
    }

    private fun displayTextFromReading(reading: ScoreReadingEntity): String = when {
        reading.state == "BLANK" -> "—"
        !reading.recognizedText.isNullOrBlank() -> reading.recognizedText!!
        reading.reviewedValue != null -> reading.reviewedValue.toInt().toString()
        reading.rawValue != null -> reading.rawValue.toInt().toString()
        else -> "?"
    }

    private fun genericCellDiagnostic(
        cell: GenericScoreCell,
        sourcePath: String,
        preprocessedPath: String,
        prepared: PreparedCrop,
        observations: List<DigitObservation>,
        assembly: ScoreAssembly
    ): JSONObject = JSONObject().apply {
        put("row", cell.rowIndex + 1)
        put("column", cell.columnIndex + 1)
        put("mapped_pixel_cell", rectJson(cell.rect))
        put("source_crop", sourcePath)
        put("preprocessed_crop", preprocessedPath)
        put("ink_pixels", prepared.inkPixels)
        put("ink_ratio", prepared.inkRatio)
        put("connected_components", prepared.connectedComponents)
        put("contrast", prepared.contrast)
        put("blank_score", if (prepared.blank) 1.0 else 0.0)
        put("recognition_state", assembly.state)
        put("final_value", assembly.value ?: JSONObject.NULL)
        put("displayed_value", displayText(observations, assembly))
        put("confidence", assembly.confidence)
        put("digits", JSONArray(digitDetails(observations)))
    }

    private fun rectJson(rect: android.graphics.Rect): JSONObject = JSONObject().apply {
        put("left", rect.left)
        put("top", rect.top)
        put("right", rect.right)
        put("bottom", rect.bottom)
        put("width", rect.width())
        put("height", rect.height())
    }

    private fun roiDiagnostic(
        row: RowDef,
        roi: ScoreRoiDef,
        side: SheetPageTemplate,
        pixelRect: PixelRect?,
        prepared: PreparedCrop?,
        sourcePath: String?,
        preprocessedPath: String?,
        observations: List<DigitObservation>,
        assembly: ScoreAssembly,
        registration: TemplateRegistration
    ): JSONObject = JSONObject().apply {
        put("student", JSONObject().apply {
            put("row", row.rowNo)
            put("student_id", row.studentId)
            put("student_name", row.studentName)
        })
        put("assessment", roi.assessmentId)
        put("maximum", roi.maximum)
        put("expected_roi_coordinates", JSONObject().apply {
            put("x", roi.x)
            put("y", roi.y)
            put("w", roi.w)
            put("h", roi.h)
            put("coordinate_origin", side.coordinateOrigin)
        })
        put("registration", registration.toJson())
        put("mapped_pixel_roi", pixelRect?.let {
            JSONObject().apply {
                put("x", it.left)
                put("y", it.top)
                put("w", it.width)
                put("h", it.height)
            }
        } ?: JSONObject.NULL)
        put("source_crop", sourcePath ?: JSONObject.NULL)
        put("preprocessed_crop", preprocessedPath ?: JSONObject.NULL)
        put("ink_pixels", prepared?.inkPixels ?: 0)
        put("ink_ratio", prepared?.inkRatio ?: 0.0)
        put("connected_components", prepared?.connectedComponents ?: 0)
        put("contrast", prepared?.contrast ?: 0.0)
        put("blank_score", if (prepared?.blank == true) 1.0 else 0.0)
        put("recognition_state", assembly.state)
        put("final_value", assembly.value ?: JSONObject.NULL)
        put("confidence", assembly.confidence)
        put("validation_result", validationResult(assembly, prepared))
        put("digits", JSONArray().apply {
            observations.sortedBy { it.index }.forEach { digit ->
                put(JSONObject().apply {
                    put("index", digit.index)
                    put("value", digit.value ?: JSONObject.NULL)
                    put("confidence", digit.confidence)
                    put("blank", digit.blank)
                    put("alignment_valid", digit.alignmentValid)
                    put("source_crop", digit.sourcePath ?: JSONObject.NULL)
                    put("preprocessed_crop", digit.preprocessedPath ?: JSONObject.NULL)
                    put("ink_pixels", digit.inkPixels)
                    put("ink_ratio", digit.inkRatio)
                    put("connected_components", digit.connectedComponents)
                    put("contrast", digit.contrast)
                    put("raw_ocr_text", digit.rawOcrText)
                    put("normalized_ocr_text", digit.normalizedOcrText)
                    put("recognizer_engine", digit.recognizerEngine)
                })
            }
        })
    }

    private fun validationResult(assembly: ScoreAssembly, prepared: PreparedCrop?): String = when {
        assembly.state == "MISALIGNED" -> "ALIGNMENT_NOT_AVAILABLE"
        assembly.state == "INVALID" -> "OVER_MAXIMUM"
        assembly.state == "BLANK" -> "NO_INK"
        assembly.state == "DOUBTFUL" && prepared?.blank == false -> "INK_UNRECOGNIZED_OR_LOW_CONFIDENCE"
        assembly.state == "CONFIRMED" -> "WITHIN_MAXIMUM"
        else -> assembly.state
    }

    private fun saveInputArtifact(bitmap: Bitmap, inputPath: String?, output: File) {
        val source = inputPath?.let(::File)
        if (source?.exists() == true) source.copyTo(output, overwrite = true)
        else ImageProcessor.saveJpeg(bitmap, output, 98)
    }

    private fun safePart(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").lowercase()
}
