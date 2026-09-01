package com.wts.smartscore.scanner

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs

/** Registration of a canonical template page into the actual Google scan. */
data class TemplateRegistration(
    val sourceRect: RectF,
    val method: String,
    val confidence: Double,
    val expectedAspectRatio: Double,
    val observedAspectRatio: Double,
    val anchorId: String? = null
) {
    fun toJson() = org.json.JSONObject().apply {
        put("method", method)
        put("confidence", confidence)
        put("expected_aspect_ratio", expectedAspectRatio)
        put("observed_aspect_ratio", observedAspectRatio)
        put("anchor_id", anchorId ?: org.json.JSONObject.NULL)
        put("source_rect", org.json.JSONObject().apply {
            put("left", sourceRect.left)
            put("top", sourceRect.top)
            put("width", sourceRect.width())
            put("height", sourceRect.height())
        })
    }
}

/**
 * Google returns a corrected page, but its crop can still differ slightly from
 * the generator's canonical page. Start with aspect registration and refine it
 * from a known printed anchor when the QR detector supplies its bounds.
 */
object TemplateRegistrar {
    fun register(pageBitmap: Bitmap, page: SheetPageTemplate, qrBounds: RectF? = null): TemplateRegistration {
        val expectedAspect = (page.pageW / page.pageH).coerceAtLeast(0.01)
        val observedAspect = pageBitmap.width.toDouble() / pageBitmap.height.toDouble().coerceAtLeast(1.0)
        val aspectRect = aspectFitRect(pageBitmap.width, pageBitmap.height, expectedAspect)
        val qrAnchor = page.registrationAnchors.firstOrNull { it.id.equals("qr", true) }
        if (qrBounds != null && qrAnchor != null) {
            val refined = refineFromAnchor(pageBitmap, page, qrAnchor, qrBounds, expectedAspect, observedAspect)
            if (refined != null) return refined
        }
        val aspectError = abs(observedAspect - expectedAspect) / expectedAspect
        return TemplateRegistration(
            sourceRect = aspectRect,
            method = "ASPECT_REGISTERED",
            confidence = (1.0 - aspectError).coerceIn(0.55, 0.96),
            expectedAspectRatio = expectedAspect,
            observedAspectRatio = observedAspect
        )
    }

    private fun refineFromAnchor(
        bitmap: Bitmap,
        page: SheetPageTemplate,
        anchor: RegistrationAnchorDef,
        qrBounds: RectF,
        expectedAspect: Double,
        observedAspect: Double
    ): TemplateRegistration? {
        if (qrBounds.width() < 2f || qrBounds.height() < 2f) return null
        val pixelsPerUnitX = qrBounds.width().toDouble() / anchor.w
        val pixelsPerUnitY = qrBounds.height().toDouble() / anchor.h
        val scale = ((pixelsPerUnitX + pixelsPerUnitY) / 2.0).takeIf { it.isFinite() && it > 0.0 } ?: return null
        val anchorTop = if (page.coordinateOrigin.equals("TOP_LEFT", true)) {
            anchor.y
        } else {
            page.pageH - (anchor.y + anchor.h)
        }
        val anchorCenterX = anchor.x + anchor.w / 2.0
        val anchorCenterY = anchorTop + anchor.h / 2.0
        val left = qrBounds.centerX().toDouble() - anchorCenterX * scale
        val top = qrBounds.centerY().toDouble() - anchorCenterY * scale
        val rect = RectF(
            left.toFloat(),
            top.toFloat(),
            (left + page.pageW * scale).toFloat(),
            (top + page.pageH * scale).toFloat()
        )
        val imageRect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        val overlap = RectF(rect)
        if (!overlap.intersect(imageRect)) return null
        val overlapFraction = overlap.width() * overlap.height() / (rect.width() * rect.height()).coerceAtLeast(1f)
        if (overlapFraction < 0.82f) return null
        return TemplateRegistration(
            sourceRect = rect,
            method = "QR_ANCHOR_REGISTERED",
            confidence = (0.90 + overlapFraction * 0.09).coerceAtMost(0.99),
            expectedAspectRatio = expectedAspect,
            observedAspectRatio = observedAspect,
            anchorId = anchor.id
        )
    }

    private fun aspectFitRect(width: Int, height: Int, expectedAspect: Double): RectF {
        val observedAspect = width.toDouble() / height.toDouble().coerceAtLeast(1.0)
        return if (observedAspect > expectedAspect) {
            val fittedWidth = height * expectedAspect
            RectF(((width - fittedWidth) / 2.0).toFloat(), 0f, ((width + fittedWidth) / 2.0).toFloat(), height.toFloat())
        } else {
            val fittedHeight = width / expectedAspect
            RectF(0f, ((height - fittedHeight) / 2.0).toFloat(), width.toFloat(), ((height + fittedHeight) / 2.0).toFloat())
        }
    }
}
