package com.wts.smartscore.scanner

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class Quad(
    val tl: PointF,
    val tr: PointF,
    val br: PointF,
    val bl: PointF
) {
    fun area(): Float {
        val twiceArea =
            (tl.x * tr.y - tl.y * tr.x) +
            (tr.x * br.y - tr.y * br.x) +
            (br.x * bl.y - br.y * bl.x) +
            (bl.x * tl.y - bl.y * tl.x)
        return abs(twiceArea / 2f)
    }

    fun topWidth(): Float = distance(tl, tr)
    fun bottomWidth(): Float = distance(bl, br)
    fun leftHeight(): Float = distance(tl, bl)
    fun rightHeight(): Float = distance(tr, br)

    fun width(): Float = (topWidth() + bottomWidth()) / 2f
    fun height(): Float = (leftHeight() + rightHeight()) / 2f

    fun aspectRatio(): Float {
        val width = width().coerceAtLeast(1f)
        val height = height().coerceAtLeast(1f)
        return max(width, height) / min(width, height)
    }

    /** Smallest normalized distance from a detected corner to the analysis frame edge. */
    fun edgeMargin(frameWidth: Int, frameHeight: Int): Float {
        val w = frameWidth.coerceAtLeast(1).toFloat()
        val h = frameHeight.coerceAtLeast(1).toFloat()
        return listOf(tl, tr, br, bl).minOf { point ->
            min(
                min(point.x / w, (w - point.x) / w),
                min(point.y / h, (h - point.y) / h)
            )
        }.coerceIn(0f, 0.5f)
    }

    private fun distance(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)
}

data class FrameAssessment(
    val quad: Quad?,
    val coverage: Float,
    val blurScore: Double,
    val stable: Boolean,
    val glare: Double,
    val stateHint: String,
    val frameWidth: Int = 1,
    val frameHeight: Int = 1,
    val pageWidthFraction: Float = 0f,
    val pageHeightFraction: Float = 0f,
    val aspectRatio: Float = 0f,
    val edgeMargin: Float = 1f,
    val stabilityScore: Float = 0f,
    val detectorMethod: String = "NONE",
    val rotationDegrees: Int = 0
)
