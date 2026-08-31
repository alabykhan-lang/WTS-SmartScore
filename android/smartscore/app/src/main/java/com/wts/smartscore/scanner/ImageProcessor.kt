package com.wts.smartscore.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
import kotlin.math.max

data class NormalizationResult(
    val bitmap: Bitmap,
    val assessment: FrameAssessment
)

object ImageProcessor {
    fun normalize(source: Bitmap): Bitmap = normalizeDetailed(source).bitmap

    fun normalizeDetailed(source: Bitmap): NormalizationResult {
        val rgba = Mat()
        Utils.bitmapToMat(source, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val assessment = try {
            OpenCvDocumentDetector().detect(gray)
        } finally {
            gray.release()
        }
        val q = assessment.quad
        if (q == null) {
            rgba.release()
            return NormalizationResult(source.copy(Bitmap.Config.ARGB_8888, false), assessment)
        }

        fun dist(a: PointF, b: PointF) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
        val w = max(dist(q.tl, q.tr), dist(q.bl, q.br)).toInt().coerceAtLeast(300)
        val h = max(dist(q.tl, q.bl), dist(q.tr, q.br)).toInt().coerceAtLeast(400)
        val srcPts = MatOfPoint2f(
            Point(q.tl.x.toDouble(), q.tl.y.toDouble()),
            Point(q.tr.x.toDouble(), q.tr.y.toDouble()),
            Point(q.br.x.toDouble(), q.br.y.toDouble()),
            Point(q.bl.x.toDouble(), q.bl.y.toDouble())
        )
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0),
            Point((w - 1).toDouble(), 0.0),
            Point((w - 1).toDouble(), (h - 1).toDouble()),
            Point(0.0, (h - 1).toDouble())
        )
        val warped = Mat()
        Imgproc.warpPerspective(
            rgba,
            warped,
            Imgproc.getPerspectiveTransform(srcPts, dstPts),
            Size(w.toDouble(), h.toDouble())
        )
        val rgb = Mat()
        Imgproc.cvtColor(warped, rgb, Imgproc.COLOR_RGBA2RGB)
        val channels = mutableListOf<Mat>()
        Core.split(rgb, channels)
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        clahe.apply(channels[0], channels[0])
        Core.merge(channels, rgb)
        val output = Mat()
        Imgproc.cvtColor(rgb, output, Imgproc.COLOR_RGB2RGBA)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(output, bitmap)
        listOf(rgba, warped, rgb, output, srcPts, dstPts).forEach { it.release() }
        channels.forEach { it.release() }
        return NormalizationResult(bitmap, assessment)
    }

    /**
     * Normalizes a high-resolution continuous capture and, only when requested,
     * preserves the evidence needed to diagnose document selection and alignment.
     */
    fun normalizeWithDiagnostics(
        source: Bitmap,
        diagnosticDir: File,
        inputPath: String? = null,
        sourceRotationDegrees: Int = 0
    ): Bitmap {
        diagnosticDir.mkdirs()
        val result = normalizeDetailed(source)
        val inputFile = File(diagnosticDir, "original-highres.jpg")
        if (inputPath != null && File(inputPath).exists()) {
            File(inputPath).copyTo(inputFile, overwrite = true)
        } else {
            saveJpeg(source, inputFile, 98)
        }
        saveJpeg(drawDetection(source, result.assessment), File(diagnosticDir, "detected-document.jpg"), 98)
        saveJpeg(result.bitmap, File(diagnosticDir, "corrected-master.jpg"), 98)
        File(diagnosticDir, "detection.json").writeText(
            detectionJson(result.assessment, source, sourceRotationDegrees).toString(2)
        )
        return result.bitmap
    }

    private fun drawDetection(source: Bitmap, assessment: FrameAssessment): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (assessment.quad == null) Color.RED else Color.rgb(0, 220, 125)
            style = Paint.Style.STROKE
            strokeWidth = max(4f, minOf(output.width, output.height) / 260f)
            strokeJoin = Paint.Join.ROUND
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 80, 45)
            textSize = max(22f, minOf(output.width, output.height) / 34f)
            typeface = Typeface.DEFAULT_BOLD
            style = Paint.Style.FILL
        }
        val q = assessment.quad
        if (q == null) {
            canvas.drawText("NO COMPLETE DOCUMENT QUADRILATERAL", 24f, label.textSize + 24f, label)
            return output
        }
        val path = Path().apply {
            moveTo(q.tl.x, q.tl.y)
            lineTo(q.tr.x, q.tr.y)
            lineTo(q.br.x, q.br.y)
            lineTo(q.bl.x, q.bl.y)
            close()
        }
        canvas.drawPath(path, stroke)
        listOf(q.tl, q.tr, q.br, q.bl).forEachIndexed { index, point ->
            canvas.drawCircle(point.x, point.y, stroke.strokeWidth * 1.7f, stroke)
            canvas.drawText("${index + 1}", point.x + stroke.strokeWidth, point.y - stroke.strokeWidth, label)
        }
        return output
    }

    private fun detectionJson(assessment: FrameAssessment, source: Bitmap, sourceRotationDegrees: Int): JSONObject = JSONObject().apply {
        put("source_width", source.width)
        put("source_height", source.height)
        put("rotation_degrees", sourceRotationDegrees)
        put("detector_rotation_degrees", assessment.rotationDegrees)
        put("frame_width", assessment.frameWidth)
        put("frame_height", assessment.frameHeight)
        put("coverage", assessment.coverage)
        put("page_width_fraction", assessment.pageWidthFraction)
        put("page_height_fraction", assessment.pageHeightFraction)
        put("aspect_ratio", assessment.aspectRatio)
        put("blur_score", assessment.blurScore)
        put("glare_score", assessment.glare)
        put("stability_score", assessment.stabilityScore)
        put("detector_method", assessment.detectorMethod)
        put("candidate_count", assessment.candidateCount)
        put("selected_candidate_score", assessment.selectedCandidateScore)
        put("selected_corners", assessment.quad?.let(::quadJson) ?: JSONObject.NULL)
        put("candidate_diagnostics", JSONArray().apply {
            assessment.candidateDiagnostics.forEach { candidate ->
                put(JSONObject().apply {
                    put("method", candidate.method)
                    put("area_fraction", candidate.areaFraction)
                    put("aspect_ratio", candidate.aspectRatio)
                    put("rectangularity", candidate.rectangularity)
                    put("edge_margin", candidate.edgeMargin)
                    put("boundary_contrast", candidate.boundaryContrast)
                    put("contained_candidate_count", candidate.containedCandidateCount)
                    put("largest_contained_fraction", candidate.largestContainedFraction)
                    put("score", candidate.score)
                    put("accepted", candidate.accepted)
                    put("selected", candidate.selected)
                    put("rejection_reason", candidate.rejectionReason ?: JSONObject.NULL)
                })
            }
        })
    }

    private fun quadJson(q: Quad): JSONObject = JSONObject().apply {
        put("top_left", pointJson(q.tl))
        put("top_right", pointJson(q.tr))
        put("bottom_right", pointJson(q.br))
        put("bottom_left", pointJson(q.bl))
    }

    private fun pointJson(point: PointF): JSONObject = JSONObject().apply {
        put("x", point.x)
        put("y", point.y)
    }

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 94) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), it) }
    }
}
