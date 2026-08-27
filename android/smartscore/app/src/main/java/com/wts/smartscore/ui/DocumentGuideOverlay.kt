package com.wts.smartscore.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.wts.smartscore.scanner.Quad

class DocumentGuideOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF62A8FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeJoin = Paint.Join.ROUND
    }
    private var quad: Quad? = null
    private var sourceWidth = 1f
    private var sourceHeight = 1f

    fun show(q: Quad?, imageWidth: Int, imageHeight: Int) {
        quad = q
        sourceWidth = imageWidth.coerceAtLeast(1).toFloat()
        sourceHeight = imageHeight.coerceAtLeast(1).toFloat()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val q = quad ?: return
        val sx = width / sourceWidth
        val sy = height / sourceHeight
        val p = Path().apply {
            moveTo(q.tl.x * sx, q.tl.y * sy)
            lineTo(q.tr.x * sx, q.tr.y * sy)
            lineTo(q.br.x * sx, q.br.y * sy)
            lineTo(q.bl.x * sx, q.bl.y * sy)
            close()
        }
        canvas.drawPath(p, paint)
    }
}
