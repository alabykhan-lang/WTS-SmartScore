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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * Fast, orientation-aware document boundary detection for the continuous camera.
 *
 * The detector intentionally works on a downscaled analysis frame. The final page is
 * always taken from ImageCapture and is normalized later at full resolution.
 */
class OpenCvDocumentDetector(
    private val maxAnalysisDimension: Int = 960
) {
    companion object {
        private const val STABLE_DELTA_FRACTION = 0.018f
        private const val MIN_CONTOUR_AREA_FRACTION = 0.035f
    }

    private data class Candidate(
        val points: Array<Point>,
        val method: String
    )

    private var previous: Quad? = null
    private var previousFrameWidth = 0
    private var previousFrameHeight = 0
    private var stableFrames = 0

    /**
     * @param rotationDegrees ImageProxy.imageInfo.rotationDegrees. The returned quad is
     * in display-oriented coordinates, which is also the coordinate space used by the
     * PreviewView overlay.
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

                val best = chooseCandidate(candidates, small.cols(), small.rows())
                val q = best?.let {
                    orderedQuad(
                        it.points,
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
                    stateHint = best?.method ?: "NO_QUADRILATERAL",
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    pageWidthFraction = pageWidthFraction.coerceIn(0f, 1.5f),
                    pageHeightFraction = pageHeightFraction.coerceIn(0f, 1.5f),
                    aspectRatio = aspectRatio,
                    edgeMargin = edgeMargin,
                    stabilityScore = stability.second,
                    detectorMethod = best?.method ?: "NONE",
                    rotationDegrees = ((rotationDegrees % 360) + 360) % 360
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
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        try {
            Imgproc.GaussianBlur(small, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 40.0, 120.0)
            collectContours(edges, "CANNY", candidates)
            Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
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
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        try {
            // A white sheet on a darker desk is often invisible to a single Canny pass.
            // Otsu creates a second, contrast-adaptive route for that ordinary setup.
            Imgproc.threshold(small, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(binary, closedBinary, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 1)
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
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
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
            val hullPoints = indices.map { index -> source[index] }.toTypedArray()
            if (hullPoints.size >= 4) addApproximations(hullPoints, "${method}_HULL", candidates)
        } finally {
            contour.release()
            hullIndices.release()
        }
    }

    private fun chooseCandidate(candidates: List<Candidate>, width: Int, height: Int): Candidate? {
        return candidates
            .asSequence()
            .map { candidate -> candidate to orderedQuad(candidate.points, 1f, 1f) }
            .filter { (_, q) -> q.area() / (width.toFloat() * height.toFloat()).coerceAtLeast(1f) >= MIN_CONTOUR_AREA_FRACTION }
            .filter { (_, q) -> q.edgeMargin(width, height) > 0.002f || q.area() / (width.toFloat() * height.toFloat()).coerceAtLeast(1f) < 0.95f }
            .maxByOrNull { (_, q) ->
                val fraction = q.area() / (width.toFloat() * height.toFloat()).coerceAtLeast(1f)
                val rectangleArea = (q.width() * q.height()).coerceAtLeast(1f)
                val rectangularity = (q.area() / rectangleArea).coerceIn(0f, 1f)
                val margin = q.edgeMargin(width, height)
                val edgePenalty = when {
                    margin < 0.002f -> 0.15f
                    margin < 0.008f -> 0.65f
                    else -> 1f
                }
                fraction * (0.62 + rectangularity * 0.38) * edgePenalty
            }
            ?.first
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
     * Global white-pixel percentage is not glare: an ordinary white sheet is expected to
     * contain many clipped-looking pixels. This score only reports clipped highlights
     * inside the page and suppresses a uniformly white page baseline.
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
