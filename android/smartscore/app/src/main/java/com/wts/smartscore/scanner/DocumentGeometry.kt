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
        val twiceArea =
            (tl.x * tr.y - tl.y * tr.x) +
            (tr.x * br.y - tr.y * br.x) +
            (br.x * bl.y - br.y * bl.x) +
            (bl.x * tl.y - bl.y * tl.x)
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
