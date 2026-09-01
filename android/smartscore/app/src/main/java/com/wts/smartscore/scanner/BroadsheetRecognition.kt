package com.wts.smartscore.scanner

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import com.wts.smartscore.model.ReadingState

data class DigitGuess(
    val value: Int?,
    val confidence: Double,
    val blank: Boolean = false,
    val rawText: String = "",
    val normalizedText: String = ""
)

/** Alternative numeric recognizers can be added without changing ROI processing. */
interface DigitRecognizer {
    val engineName: String
    fun recognize(crop: Bitmap): DigitGuess
    fun close() = Unit
}

/** Current on-device implementation; deliberately kept behind DigitRecognizer. */
class MlKitDigitRecognizer(private val recognizer: TextRecognizer) : DigitRecognizer {
    override val engineName: String = "ML_KIT_TEXT_FALLBACK"

    override fun recognize(crop: Bitmap): DigitGuess {
        val raw = runCatching { Tasks.await(recognizer.process(InputImage.fromBitmap(crop, 0))).text }.getOrDefault("")
        val normalized = raw.filter(Char::isDigit)
        val value = normalized.singleOrNull()?.digitToIntOrNull()
        val confidence = when {
            value != null -> 0.80
            normalized.isNotBlank() -> 0.45
            else -> 0.20
        }
        return DigitGuess(value, confidence, rawText = raw, normalizedText = normalized)
    }
}

/** Per-digit observation retained in debug JSON and used for score assembly. */
data class DigitObservation(
    val index: Int,
    val value: Int?,
    val confidence: Double,
    val blank: Boolean,
    val sourcePath: String?,
    val preprocessedPath: String?,
    val inkPixels: Int,
    val inkRatio: Double,
    val connectedComponents: Int,
    val contrast: Double,
    val rawOcrText: String,
    val normalizedOcrText: String,
    val alignmentValid: Boolean = true,
    val recognizerEngine: String = ""
)

data class ScoreAssembly(
    val value: Double?,
    val state: String,
    val confidence: Double
)

object BroadsheetScoreAssembler {
    fun assemble(digits: List<DigitObservation>, maximum: Double, roiInkPresent: Boolean = false): ScoreAssembly {
        if (digits.isEmpty()) return ScoreAssembly(null, "MISALIGNED", 0.0)
        val ordered = digits.sortedBy { it.index }
        if (ordered.any { !it.alignmentValid }) return ScoreAssembly(null, "MISALIGNED", 0.0)
        if (ordered.all { it.blank }) {
            // A handwritten mark in the score ROI but outside the mapped digit
            // boxes is evidence of alignment trouble or an unusual mark, not a
            // genuine blank. Keep it reviewable instead of hiding it as "—".
            return if (roiInkPresent) ScoreAssembly(null, "DOUBTFUL", 0.20)
            else ScoreAssembly(null, "BLANK", 1.0)
        }
        val confidence = ordered.filterNot { it.blank }.minOfOrNull { it.confidence } ?: 0.0
        // Ink exists but one or more boxes could not be read. This is doubtful,
        // never blank: the source crop remains available for review.
        if (ordered.any { it.blank } || ordered.any { it.value == null }) {
            return ScoreAssembly(null, "DOUBTFUL", confidence)
        }
        val value = ordered.fold(0) { total, digit -> total * 10 + (digit.value ?: 0) }.toDouble()
        val state = when {
            value < 0.0 || value > maximum -> "INVALID"
            confidence >= 0.90 -> "CONFIRMED"
            else -> "DOUBTFUL"
        }
        return ScoreAssembly(value, state, confidence)
    }
}

/** Compatibility façade retained for callers from the first scanner pass. */
class BroadsheetRecognition {
    data class LegacyDigitGuess(val value: Int?, val confidence: Double, val blank: Boolean = false)
    interface NumericRecognizer { fun recognize(cropPath: String): LegacyDigitGuess }

    fun classify(guess: LegacyDigitGuess, maximum: Double): Pair<Double?, ReadingState> {
        if (guess.blank) return null to ReadingState.BLANK
        val value = guess.value?.toDouble() ?: return null to ReadingState.DOUBTFUL
        if (value < 0 || value > maximum) return value to ReadingState.INVALID
        return if (guess.confidence >= 0.90) value to ReadingState.CONFIRMED else value to ReadingState.DOUBTFUL
    }
}
