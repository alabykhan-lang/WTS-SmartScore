package com.wts.smartscore.scanner

import com.wts.smartscore.model.ScanState
import java.util.Locale

class AutoCaptureController(private val requiredStableFrames: Int = 5) {
    companion object {
        // These describe a usable page in the analysis stream, not a requirement that
        // the paper fill the phone screen. The high-resolution ImageCapture frame is
        // used for the stored master page.
        const val MIN_COVERAGE = 0.14f
        const val MIN_PAGE_WIDTH = 0.32f
        const val MIN_PAGE_HEIGHT = 0.18f
        const val MAX_COVERAGE = 0.92f
        const val MIN_EDGE_MARGIN = 0.012f
        const val MAX_GLARE = 0.90
        const val MIN_BLUR_SCORE = 30.0
    }

    private var stableFrames = 0
    private var armed = true
    private var waitingForExit = false

    var blockReason: String = "FIND A DOCUMENT"
        private set

    var state: ScanState = ScanState.SEARCHING
        private set

    fun onFrame(a: FrameAssessment): Boolean {
        if (waitingForExit) {
            state = ScanState.WAITING_FOR_PAGE_EXIT
            blockReason = "WAITING FOR PAGE EXIT — REMOVE THE PAGE"
            if (a.quad == null || a.coverage < MIN_COVERAGE / 2f) {
                waitingForExit = false
                armed = true
                state = ScanState.SEARCHING
                blockReason = "READY FOR NEXT PAGE"
            }
            return false
        }
        if (!armed) return false

        val hasSizeMetrics = a.pageWidthFraction > 0f && a.pageHeightFraction > 0f
        val pageTooSmall = a.quad != null && (
            a.coverage < MIN_COVERAGE ||
                (hasSizeMetrics && (a.pageWidthFraction < MIN_PAGE_WIDTH || a.pageHeightFraction < MIN_PAGE_HEIGHT))
            )
        val pageTouchesEdge = a.quad != null && a.edgeMargin < MIN_EDGE_MARGIN
        val pageTooLarge = a.quad != null && (a.coverage > MAX_COVERAGE || pageTouchesEdge)

        state = when {
            a.quad == null -> ScanState.SEARCHING
            pageTooSmall -> ScanState.MOVE_CLOSER
            pageTooLarge -> ScanState.MOVE_BACK
            a.glare > MAX_GLARE -> ScanState.ALIGN
            a.blurScore < MIN_BLUR_SCORE -> ScanState.HOLD_STEADY
            !a.stable -> ScanState.HOLD_STEADY
            else -> ScanState.DOCUMENT_FOUND
        }

        blockReason = when {
            a.quad == null -> "NO FOUR-CORNER PAGE BOUNDARY"
            pageTooSmall -> String.format(
                Locale.US,
                "PAGE TOO SMALL — %.1f%% area; %.1f%% × %.1f%% of frame",
                a.coverage * 100f,
                a.pageWidthFraction * 100f,
                a.pageHeightFraction * 100f
            )
            pageTouchesEdge -> "PAGE EDGE TOUCHING FRAME — MOVE BACK"
            a.coverage > MAX_COVERAGE -> "PAGE TOO LARGE — MOVE BACK"
            a.glare > MAX_GLARE -> "STRONG CLIPPED HIGHLIGHT — ADJUST LIGHT"
            a.blurScore < MIN_BLUR_SCORE -> "FOCUS/BLUR TOO LOW — HOLD STEADY"
            !a.stable -> "PAGE MOVING — HOLD STEADY"
            else -> "READY — STABILITY PASSED"
        }

        if (state == ScanState.DOCUMENT_FOUND) stableFrames++ else stableFrames = 0
        if (stableFrames >= requiredStableFrames) {
            armed = false
            stableFrames = 0
            state = ScanState.CAPTURING
            blockReason = "CAPTURE TRIGGERED"
            return true
        }
        return false
    }

    fun captured() {
        state = ScanState.SCANNED
        waitingForExit = true
        blockReason = "WAITING FOR PAGE EXIT — REMOVE THE PAGE"
    }

    fun captureFailed() {
        stableFrames = 0
        waitingForExit = false
        armed = true
        state = ScanState.SEARCHING
        blockReason = "CAPTURE FAILED — FIND DOCUMENT AGAIN"
    }
}
