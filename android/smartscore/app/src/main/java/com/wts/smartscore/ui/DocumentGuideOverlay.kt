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

    fun show(q: Quad?, imageWidth: Int, imageHeight: Int, positive: Boolean = false) {
        quad = q
        sourceWidth = imageWidth.coerceAtLeast(1).toFloat()
        sourceHeight = imageHeight.coerceAtLeast(1).toFloat()
        paint.color = if (positive) 0xFF42D392.toInt() else 0xFF62A8FF.toInt()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val q = quad ?: return
        // PreviewView.ScaleType.FIT_CENTER letterboxes the camera frame. Applying
        // independent sx/sy values would stretch the outline away from the paper.
        val scale = minOf(width / sourceWidth, height / sourceHeight)
        val dx = (width - sourceWidth * scale) / 2f
        val dy = (height - sourceHeight * scale) / 2f
        val p = Path().apply {
            moveTo(dx + q.tl.x * scale, dy + q.tl.y * scale)
            lineTo(dx + q.tr.x * scale, dy + q.tr.y * scale)
            lineTo(dx + q.br.x * scale, dy + q.br.y * scale)
            lineTo(dx + q.bl.x * scale, dy + q.bl.y * scale)
            close()
        }
        canvas.drawPath(p, paint)
    }
}
