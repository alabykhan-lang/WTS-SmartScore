package com.wts.smartscore.scanner

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * Optional model-backed recognizer for the constrained 0–9 digit-cell task.
 *
 * The bundled model is the official TensorFlow Lite MNIST digit-classifier
 * example model. It accepts a [1, 28, 28, 3] float RGB tensor and returns a
 * [1, 10] probability vector ordered from digit 0 through digit 9. The
 * normalized crop is converted from black-on-white to the model's
 * white-ink-on-black convention before inference.
 */
class TfliteHandwrittenDigitRecognizer private constructor(
    private val interpreter: Interpreter,
    private val inputWidth: Int,
    private val inputHeight: Int,
    private val inputChannels: Int,
    private val outputClasses: Int
) : DigitRecognizer {
    override val engineName: String = "TFLITE_HANDWRITTEN_DIGIT"

    override fun recognize(crop: Bitmap): DigitGuess {
        val input = ByteBuffer.allocateDirect(inputWidth * inputHeight * inputChannels * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(crop.width * crop.height)
        crop.getPixels(pixels, 0, crop.width, 0, 0, crop.width, crop.height)
        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                val sourceX = (x * crop.width / inputWidth).coerceIn(0, crop.width - 1)
                val sourceY = (y * crop.height / inputHeight).coerceIn(0, crop.height - 1)
                val red = (pixels[sourceY * crop.width + sourceX] shr 16) and 0xff
                val ink = 1f - red / 255f
                repeat(inputChannels) { input.putFloat(ink) }
            }
        }
        val output = Array(1) { FloatArray(outputClasses) }
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
        private const val FLOAT_BYTES = 4
        const val MODEL_ASSET = "models/digit_classifier.tflite"

        fun tryCreate(context: Context): TfliteHandwrittenDigitRecognizer? = runCatching {
            val model = runCatching {
                context.assets.openFd(MODEL_ASSET).use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                        channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
                            .order(ByteOrder.nativeOrder())
                    }
                }
            }.getOrElse {
                // Some build configurations compress unknown asset types;
                // openFd then fails even though the model is present.
                val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
                ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                    put(bytes)
                    rewind()
                }
            }
            val interpreter = Interpreter(model)
            val input = interpreter.getInputTensor(0)
            val output = interpreter.getOutputTensor(0)
            val shape = input.shape()
            val outputShape = output.shape()
            require(shape.size == 4 && shape[0] == 1 && shape[1] > 0 && shape[2] > 0 && shape[3] in 1..4) {
                "Unsupported digit model input shape ${shape.contentToString()}"
            }
            require(input.dataType() == DataType.FLOAT32 && output.dataType() == DataType.FLOAT32) {
                "Unsupported digit model types ${input.dataType()} -> ${output.dataType()}"
            }
            require(outputShape.size == 2 && outputShape[0] == 1 && outputShape[1] >= 10) {
                "Unsupported digit model output shape ${outputShape.contentToString()}"
            }
            TfliteHandwrittenDigitRecognizer(interpreter, shape[1], shape[2], shape[3], outputShape[1])
        }.getOrNull()
    }
}

/** Compatibility name for callers from the recovery build. */
typealias TfliteDigitRecognizer = TfliteHandwrittenDigitRecognizer

object DigitRecognizerFactory {
    fun create(context: Context, mlKitFallback: com.google.mlkit.vision.text.TextRecognizer): DigitRecognizer {
        val fallback = MlKitDigitRecognizer(mlKitFallback)
        val primary = TfliteHandwrittenDigitRecognizer.tryCreate(context)
        return if (primary == null) fallback else ModelFirstDigitRecognizer(primary, fallback)
    }
}

/** Keeps the dedicated model primary while retaining an OCR escape hatch. */
private class ModelFirstDigitRecognizer(
    private val primary: DigitRecognizer,
    private val fallback: DigitRecognizer
) : DigitRecognizer {
    override val engineName: String = "${primary.engineName}+${fallback.engineName}"

    override fun recognize(crop: Bitmap): DigitGuess {
        val model = runCatching { primary.recognize(crop) }.getOrElse { DigitGuess(null, 0.0, rawText = "MODEL_ERROR:${it.javaClass.simpleName}") }
        if (model.value != null && model.confidence >= 0.72) return model
        val ocr = runCatching { fallback.recognize(crop) }.getOrElse { DigitGuess(null, 0.0) }
        return when {
            ocr.value != null && ocr.confidence > model.confidence -> ocr.copy(rawText = "MLKIT:${ocr.rawText}")
            model.value != null -> model
            else -> ocr
        }
    }

    override fun close() {
        primary.close()
        fallback.close()
    }
}
