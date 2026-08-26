package com.wts.smartscore.scanner

import android.graphics.PointF
import kotlin.math.abs

data class Quad(
    val tl: PointF,
    val tr: PointF,
    val br: PointF,
    val bl: PointF
) {
    fun area(): Float {
        fun cross(a: PointF, b: PointF): Float = a.x * b.y - a.y * b.x
        val twiceArea = cross(tl, tr) + cross(tr, br) + cross(br, bl) + cross(bl, tl)
        return abs(twiceArea / 2f)
    }
}

data class FrameAssessment(
    val quad: Quad?,
    val coverage: Float,
    val blurScore: Double,
    val stable: Boolean,
    val glare: Double,
    val stateHint: String
)
