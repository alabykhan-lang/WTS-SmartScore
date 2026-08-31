package com.wts.smartscore.scanner

import android.graphics.PointF
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Fast, orientation-aware document boundary detection for the continuous camera.
 *
 * Analysis frames are intentionally downscaled for speed, but the returned
 * quadrilateral is expressed in the full oriented frame. The final page is
 * always taken from ImageCapture and is detected again at capture resolution.
 */
class OpenCvDocumentDetector(
    private val maxAnalysisDimension: Int = 960
) {
    companion object {
        private const val STABLE_DELTA_FRACTION = 0.018f
        private const val MIN_CONTOUR_AREA_FRACTION = 0.035f
        private const val MAX_PLAUSIBLE_ASPECT = 4.5f
        private const val MIN_RECTANGULARITY = 0.50f
    }

    private data class Candidate(
        val points: Array<Point>,
        val method: String
    )

    private data class BaseEvaluation(
        val candidate: Candidate,
        val quad: Quad,
        val areaFraction: Float,
        val aspectRatio: Float,
        val rectangularity: Float,
        val edgeMargin: Float,
        val boundaryContrast: Float,
        val rejectionReason: String?
    )

    private data class Evaluation(
        val base: BaseEvaluation,
        val containedCandidateCount: Int,
        val largestContainedFraction: Float,
        val score: Float,
        val accepted: Boolean
    )

    private data class Selection(
        val selected: Evaluation?,
        val candidateCount: Int,
        val diagnostics: List<CandidateDiagnostic>
    )

    private var previous: Quad? = null
    private var previousFrameWidth = 0
    private var previousFrameHeight = 0
    private var stableFrames = 0

    /**
     * @param rotationDegrees ImageProxy.imageInfo.rotationDegrees. The returned
     * quad is in display-oriented coordinates used by PreviewView.
     */
    fun detect(gray: Mat, rotationDegrees: Int = 0): FrameAssessment {
        require(!gray.empty()) { "Cannot detect a document in an empty frame" }

        val oriented = orient(gray, rotationDegrees)
        try {
            val frameWidth = oriented.cols().coerceAtLeast(1)
            val frameHeight = oriented.rows().coerceAtLeast(1)
            val scale = min(1.0, maxAnalysisDimension.toDouble() / max(frameWidth, frameHeight).toDouble())
            val smallWidth = max(1, round(frameWidth * scale).toInt())
            val smallHeight = max(1, round(frameHeight * scale).toInt())
            val small = Mat()
            Imgproc.resize(
                oriented,
                small,
                Size(smallWidth.toDouble(), smallHeight.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_AREA
            )

            try {
                val candidates = mutableListOf<Candidate>()
                collectEdgeCandidates(small, candidates)
                collectThresholdCandidates(small, candidates)

                val selection = chooseCandidate(candidates, small.cols(), small.rows(), small)
                val best = selection.selected
                val q = best?.base?.quad?.let {
                    scaleQuad(
                        it,
                        frameWidth.toFloat() / small.cols().toFloat(),
                        frameHeight.toFloat() / small.rows().toFloat()
                    )
                }
                val coverage = q?.area()?.div(frameWidth.toFloat() * frameHeight.toFloat()) ?: 0f
                val pageWidthFraction = q?.width()?.div(frameWidth.toFloat()) ?: 0f
                val pageHeightFraction = q?.height()?.div(frameHeight.toFloat()) ?: 0f
                val aspectRatio = q?.aspectRatio() ?: 0f
                val edgeMargin = q?.edgeMargin(frameWidth, frameHeight) ?: 1f
                val blurScore = blurScore(small)
                val glare = glareScore(small, q, scale)
                val stability = stability(q, frameWidth, frameHeight)

                return FrameAssessment(
                    quad = q,
                    coverage = coverage.coerceIn(0f, 1f),
                    blurScore = blurScore,
                    stable = stability.first,
                    glare = glare,
                    stateHint = best?.base?.candidate?.method ?: "NO_QUADRILATERAL",
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    pageWidthFraction = pageWidthFraction.coerceIn(0f, 1.5f),
                    pageHeightFraction = pageHeightFraction.coerceIn(0f, 1.5f),
                    aspectRatio = aspectRatio,
                    edgeMargin = edgeMargin,
                    stabilityScore = stability.second,
                    detectorMethod = best?.base?.candidate?.method ?: "NONE",
                    rotationDegrees = ((rotationDegrees % 360) + 360) % 360,
                    candidateCount = selection.candidateCount,
                    selectedCandidateScore = best?.score ?: 0f,
                    candidateDiagnostics = selection.diagnostics
                )
            } finally {
                small.release()
            }
        } finally {
            oriented.release()
        }
    }

    private fun orient(source: Mat, rotationDegrees: Int): Mat {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        val output = Mat()
        when (normalized) {
            90 -> Core.rotate(source, output, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(source, output, Core.ROTATE_180)
            270 -> Core.rotate(source, output, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> source.copyTo(output)
        }
        return output
    }

    private fun collectEdgeCandidates(small: Mat, candidates: MutableList<Candidate>) {
        val blurred = Mat()
        val edges = Mat()
        val closed = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        try {
            Imgproc.GaussianBlur(small, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 40.0, 120.0)
            collectContours(edges, "CANNY", candidates)
            // Connecting broken outer-page edges helps when paper is slightly
            // curved or a corner is softened by shadow. Internal table lines are
            // later penalised by boundary and enclosure evidence.
            Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 3)
            collectContours(closed, "CANNY_CLOSED", candidates)
        } finally {
            blurred.release()
            edges.release()
            closed.release()
            kernel.release()
        }
    }

    private fun collectThresholdCandidates(small: Mat, candidates: MutableList<Candidate>) {
        val binary = Mat()
        val inverted = Mat()
        val closedBinary = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(11.0, 11.0))
        try {
            // A white sheet on a darker desk is often invisible to a single Canny pass.
            Imgproc.threshold(small, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(binary, closedBinary, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
            collectContours(closedBinary, "OTSU_PAGE", candidates)

            Core.bitwise_not(binary, inverted)
            collectContours(inverted, "OTSU_INVERTED", candidates)
        } finally {
            binary.release()
            inverted.release()
            closedBinary.release()
            kernel.release()
        }
    }

    private fun collectContours(mask: Mat, method: String, candidates: MutableList<Candidate>) {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        try {
            // RETR_TREE keeps the nested structure available to the selector's
            // geometric containment pass, even though the final decision is not
            // based on raw contour area alone.
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
            contours.forEach { contour ->
                try {
                    val source = contour.toArray()
                    if (source.size >= 4) {
                        addApproximations(source, method, candidates)
                        addHullApproximations(source, method, candidates)
                    }
                } finally {
                    contour.release()
                }
            }
        } finally {
            hierarchy.release()
        }
    }

    private fun addApproximations(source: Array<Point>, method: String, candidates: MutableList<Candidate>) {
        val contour = MatOfPoint2f(*source)
        try {
            val perimeter = Imgproc.arcLength(contour, true)
            if (perimeter <= 0.0) return
            doubleArrayOf(0.012, 0.02, 0.035, 0.05).forEach { epsilonFraction ->
                val approximation = MatOfPoint2f()
                try {
                    Imgproc.approxPolyDP(contour, approximation, epsilonFraction * perimeter, true)
                    val points = approximation.toArray()
                    if (points.size == 4 && isConvex(points) && abs(Imgproc.contourArea(approximation)) > 0.0) {
                        candidates += Candidate(points, method)
                    }
                } finally {
                    approximation.release()
                }
            }
        } finally {
            contour.release()
        }
    }

    private fun addHullApproximations(source: Array<Point>, method: String, candidates: MutableList<Candidate>) {
        val contour = MatOfPoint(*source)
        val hullIndices = MatOfInt()
        try {
            Imgproc.convexHull(contour, hullIndices)
            val indices = hullIndices.toArray()
            if (indices.size < 4) return
            val hullPoints = indices.mapNotNull { index -> source.getOrNull(index) }.toTypedArray()
            if (hullPoints.size >= 4) addApproximations(hullPoints, "${method}_HULL", candidates)
        } finally {
            contour.release()
            hullIndices.release()
        }
    }

    /**
     * Select the physical page, not merely the largest dark rectangle. The
     * selector combines paper-like aspect, outer-edge contrast, usable area and
     * whether the candidate encloses substantial internal page structure.
     */
    private fun chooseCandidate(candidates: List<Candidate>, width: Int, height: Int, gray: Mat): Selection {
        val unique = candidates
            .mapNotNull { candidate ->
                if (candidate.points.distinctBy { "${round(it.x / 3.0)}:${round(it.y / 3.0)}" }.size < 4) null
                else candidate to orderedQuad(candidate.points, 1f, 1f)
            }
            .distinctBy { (_, q) -> quadSignature(q) }
            .take(180)

        val imageArea = (width.toFloat() * height.toFloat()).coerceAtLeast(1f)
        val bases = unique.map { (candidate, quad) ->
            val areaFraction = (quad.area() / imageArea).coerceIn(0f, 2f)
            val rectangleArea = (quad.width() * quad.height()).coerceAtLeast(1f)
            val rectangularity = (quad.area() / rectangleArea).coerceIn(0f, 1f)
            val aspect = quad.aspectRatio()
            val edgeMargin = quad.edgeMargin(width, height)
            val boundaryContrast = boundaryContrast(gray, quad)
            val reasons = mutableListOf<String>()
            if (areaFraction < MIN_CONTOUR_AREA_FRACTION) reasons += "AREA_TOO_SMALL"
            if (aspect < 1.05f || aspect > MAX_PLAUSIBLE_ASPECT) reasons += "ASPECT_IMPLAUSIBLE"
            if (rectangularity < MIN_RECTANGULARITY) reasons += "QUADRILATERAL_NOT_RECTANGULAR"
            if (edgeMargin < 0.0015f && areaFraction > 0.80f) reasons += "CLIPPED_AT_FRAME_EDGE"
            BaseEvaluation(candidate, quad, areaFraction, aspect, rectangularity, edgeMargin, boundaryContrast, reasons.takeIf { it.isNotEmpty() }?.joinToString("|"))
        }

        val evaluations = bases.map { base ->
            val contained = bases.filter { inner ->
                inner !== base &&
                    inner.areaFraction < base.areaFraction * 0.92f &&
                    inner.quad.area() > imageArea * 0.012f &&
                    contains(base.quad, inner.quad)
            }
            val largestContainedFraction = contained.maxOfOrNull { it.areaFraction / base.areaFraction.coerceAtLeast(0.001f) } ?: 0f
            val enclosureScore = (
                (largestContainedFraction / 0.55f).coerceIn(0f, 1f) * 0.75f +
                    (contained.size.coerceAtMost(3) / 3f) * 0.25f
                ).coerceIn(0f, 1f)
            val sizeScore = base.areaFraction.coerceIn(0f, 1f).toDouble().pow(0.5).toFloat()
            val aspectScore = exp(-abs(ln((base.aspectRatio / 1.4142f).coerceAtLeast(0.01f).toDouble())) / 0.75).toFloat().coerceIn(0f, 1f)
            val boundaryScore = (base.boundaryContrast / 70f).coerceIn(0f, 1f)
            val edgeScore = when {
                base.edgeMargin < 0.0015f -> 0f
                base.edgeMargin < 0.010f -> 0.45f
                else -> 1f
            }
            val score = (
                // Shape and enclosing evidence deliberately outweigh a strong
                // internal table line: the latter was the physical-test
                // failure that produced a partial blue polygon.
                sizeScore * 0.26f +
                    base.rectangularity * 0.14f +
                    aspectScore * 0.22f +
                    boundaryScore * 0.18f +
                    enclosureScore * 0.17f +
                    edgeScore * 0.03f
                ).coerceIn(0f, 1f)
            Evaluation(base, contained.size, largestContainedFraction, score, base.rejectionReason == null)
        }

        val selected = evaluations.filter { it.accepted }.maxByOrNull { it.score }
        val diagnostics = evaluations
            .sortedByDescending { it.score }
            .take(16)
            .map { evaluation ->
                val base = evaluation.base
                CandidateDiagnostic(
                    method = base.candidate.method,
                    areaFraction = base.areaFraction,
                    aspectRatio = base.aspectRatio,
                    rectangularity = base.rectangularity,
                    edgeMargin = base.edgeMargin,
                    boundaryContrast = base.boundaryContrast,
                    containedCandidateCount = evaluation.containedCandidateCount,
                    largestContainedFraction = evaluation.largestContainedFraction,
                    score = evaluation.score,
                    accepted = evaluation.accepted,
                    selected = evaluation === selected,
                    rejectionReason = base.rejectionReason
                )
            }
        return Selection(selected, unique.size, diagnostics)
    }

    private fun quadSignature(q: Quad): String = listOf(q.tl, q.tr, q.br, q.bl)
        .joinToString("|") { "${round(it.x / 3.0)}:${round(it.y / 3.0)}" }

    private fun scaleQuad(q: Quad, sx: Float, sy: Float): Quad = Quad(
        PointF(q.tl.x * sx, q.tl.y * sy),
        PointF(q.tr.x * sx, q.tr.y * sy),
        PointF(q.br.x * sx, q.br.y * sy),
        PointF(q.bl.x * sx, q.bl.y * sy)
    )

    private fun contains(container: Quad, inner: Quad): Boolean = listOf(inner.tl, inner.tr, inner.br, inner.bl).all { pointInside(container, it) }

    private fun pointInside(q: Quad, point: PointF): Boolean {
        val points = listOf(q.tl, q.tr, q.br, q.bl)
        var sign = 0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val cross = (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)
            if (abs(cross) < 0.5f) continue
            val current = if (cross > 0f) 1 else -1
            if (sign == 0) sign = current else if (sign != current) return false
        }
        return true
    }

    /** Contrast across the candidate edge: paper boundary tends to separate desk and page. */
    private fun boundaryContrast(gray: Mat, q: Quad): Float {
        val points = listOf(q.tl, q.tr, q.br, q.bl)
        val centerX = points.map { it.x }.average().toFloat()
        val centerY = points.map { it.y }.average().toFloat()
        val distance = max(2.0, min(gray.cols(), gray.rows()).toDouble() * 0.012)
        val contrasts = points.indices.mapNotNull { i ->
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val midX = (a.x + b.x) / 2f
            val midY = (a.y + b.y) / 2f
            val inwardX = centerX - midX
            val inwardY = centerY - midY
            val length = hypot(inwardX.toDouble(), inwardY.toDouble())
            if (length < 1.0) null else {
                val outwardX = -inwardX / length.toFloat()
                val outwardY = -inwardY / length.toFloat()
                val outside = grayAt(gray, midX + outwardX * distance, midY + outwardY * distance)
                val inside = grayAt(gray, midX - outwardX * distance, midY - outwardY * distance)
                abs(outside - inside)
            }
        }
        return contrasts.ifEmpty { listOf(0.0) }.average().toFloat().coerceIn(0f, 255f)
    }

    private fun grayAt(gray: Mat, x: Double, y: Double): Double {
        val px = x.roundToInt().coerceIn(0, gray.cols() - 1)
        val py = y.roundToInt().coerceIn(0, gray.rows() - 1)
        return gray.get(py, px)?.firstOrNull() ?: 0.0
    }

    private fun isConvex(points: Array<Point>): Boolean {
        val contour = MatOfPoint(*points)
        return try {
            Imgproc.isContourConvex(contour)
        } finally {
            contour.release()
        }
    }

    private fun blurScore(small: Mat): Double {
        val laplacian = Mat()
        val mean = MatOfDouble()
        val std = MatOfDouble()
        return try {
            Imgproc.Laplacian(small, laplacian, CvType.CV_64F)
            Core.meanStdDev(laplacian, mean, std)
            std.toArray().firstOrNull()?.pow(2) ?: 0.0
        } finally {
            laplacian.release()
            mean.release()
            std.release()
        }
    }

    /**
     * Global white-pixel percentage is not glare: an ordinary white sheet is
     * expected to contain many bright pixels. This score only reports clipped
     * highlights inside the selected page.
     */
    private fun glareScore(small: Mat, q: Quad?, scale: Double): Double {
        if (q == null) return 0.0
        val mask = Mat.zeros(small.size(), CvType.CV_8UC1)
        val clipped = Mat()
        val inside = Mat()
        val polygon = MatOfPoint(
            Point(q.tl.x * scale, q.tl.y * scale),
            Point(q.tr.x * scale, q.tr.y * scale),
            Point(q.br.x * scale, q.br.y * scale),
            Point(q.bl.x * scale, q.bl.y * scale)
        )
        val mean = MatOfDouble()
        val std = MatOfDouble()
        return try {
            Imgproc.fillConvexPoly(mask, polygon, Scalar(255.0))
            Imgproc.threshold(small, clipped, 252.0, 255.0, Imgproc.THRESH_BINARY)
            Core.bitwise_and(clipped, mask, inside)
            val pagePixels = Core.countNonZero(mask).coerceAtLeast(1)
            val clippedFraction = Core.countNonZero(inside).toDouble() / pagePixels.toDouble()
            Core.meanStdDev(small, mean, std, mask)
            val pageMean = mean.toArray().firstOrNull() ?: 0.0
            val pageStd = std.toArray().firstOrNull() ?: 0.0
            if (pageMean > 236.0 && pageStd < 18.0) 0.0 else clippedFraction.coerceIn(0.0, 1.0)
        } finally {
            mask.release()
            clipped.release()
            inside.release()
            polygon.release()
            mean.release()
            std.release()
        }
    }

    private fun stability(q: Quad?, frameWidth: Int, frameHeight: Int): Pair<Boolean, Float> {
        if (q == null || frameWidth != previousFrameWidth || frameHeight != previousFrameHeight) {
            stableFrames = 0
            previous = q
            previousFrameWidth = frameWidth
            previousFrameHeight = frameHeight
            return false to 0f
        }
        val old = previous
        val deltaFraction = if (old == null) 1f else meanDelta(old, q) / hypot(frameWidth.toFloat(), frameHeight.toFloat()).coerceAtLeast(1f)
        val score = (1f - deltaFraction / STABLE_DELTA_FRACTION).coerceIn(0f, 1f)
        if (deltaFraction <= STABLE_DELTA_FRACTION) stableFrames++ else stableFrames = 0
        previous = q
        previousFrameWidth = frameWidth
        previousFrameHeight = frameHeight
        return (stableFrames >= 3) to score
    }

    private fun meanDelta(a: Quad, b: Quad): Float {
        val aa = listOf(a.tl, a.tr, a.br, a.bl)
        val bb = listOf(b.tl, b.tr, b.br, b.bl)
        return aa.zip(bb).map { (x, y) -> hypot(x.x - y.x, x.y - y.y) }.average().toFloat()
    }

    private fun orderedQuad(points: Array<Point>, sx: Float, sy: Float): Quad {
        val pts = points.map { PointF(it.x.toFloat() * sx, it.y.toFloat() * sy) }
        val tl = pts.minBy { it.x + it.y }
        val br = pts.maxBy { it.x + it.y }
        val tr = pts.maxBy { it.x - it.y }
        val bl = pts.minBy { it.x - it.y }
        return Quad(tl, tr, br, bl)
    }
}
