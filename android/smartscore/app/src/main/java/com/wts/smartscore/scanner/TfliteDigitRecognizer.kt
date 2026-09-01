package com.wts.smartscore.scanner

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * Optional model-backed recognizer for the constrained 0–9 digit-cell task.
 *
 * The application deliberately does not ship an unlabelled model. A model is
 * used only when the documented `models/digit_classifier.tflite` asset exists.
 * Its contract is a [1, 28, 28, 1] float input and a [1, 10] output ordered
 * from digit 0 through digit 9. Until that asset is supplied, ML Kit remains
 * the explicit fallback so scans are still reviewable.
 */
class TfliteHandwrittenDigitRecognizer private constructor(
    private val interpreter: Interpreter
) : DigitRecognizer {
    override val engineName: String = "TFLITE_HANDWRITTEN_DIGIT"

    override fun recognize(crop: Bitmap): DigitGuess {
        val input = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(crop.width * crop.height)
        crop.getPixels(pixels, 0, crop.width, 0, 0, crop.width, crop.height)
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val sourceX = (x * crop.width / INPUT_SIZE).coerceIn(0, crop.width - 1)
                val sourceY = (y * crop.height / INPUT_SIZE).coerceIn(0, crop.height - 1)
                val red = (pixels[sourceY * crop.width + sourceX] shr 16) and 0xff
                input.putFloat(1f - red / 255f)
            }
        }
        val output = Array(1) { FloatArray(10) }
        return runCatching {
            interpreter.run(input, output)
            val scores = output[0]
            val probabilities = probabilities(scores)
            val best = probabilities.indices.maxByOrNull { probabilities[it] } ?: return@runCatching DigitGuess(null, 0.0)
            DigitGuess(best, probabilities[best].toDouble(), rawText = "TFLITE:$best", normalizedText = best.toString())
        }.getOrElse { DigitGuess(null, 0.0, rawText = "TFLITE_ERROR:${it.javaClass.simpleName}") }
    }

    override fun close() {
        interpreter.close()
    }

    private fun probabilities(scores: FloatArray): FloatArray {
        if (scores.all { it in 0f..1f } && scores.sum() in 0.90f..1.10f) return scores
        val maxScore = scores.maxOrNull() ?: 0f
        val exponentials = scores.map { exp((it - maxScore).toDouble()).toFloat() }
        val total = exponentials.sum().coerceAtLeast(0.0001f)
        return exponentials.map { it / total }.toFloatArray()
    }

    companion object {
        private const val INPUT_SIZE = 28
        const val MODEL_ASSET = "models/digit_classifier.tflite"

        fun tryCreate(context: Context): TfliteDigitRecognizer? = runCatching {
            context.assets.openFd(MODEL_ASSET).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    val mapped = channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        descriptor.startOffset,
                        descriptor.declaredLength
                    )
            TfliteHandwrittenDigitRecognizer(Interpreter(mapped))
                }
            }
        }.getOrNull()
    }
}

/** Compatibility name for callers from the recovery build. */
typealias TfliteDigitRecognizer = TfliteHandwrittenDigitRecognizer

object DigitRecognizerFactory {
    fun create(context: Context, mlKitFallback: com.google.mlkit.vision.text.TextRecognizer): DigitRecognizer =
        TfliteHandwrittenDigitRecognizer.tryCreate(context) ?: MlKitDigitRecognizer(mlKitFallback)
}
