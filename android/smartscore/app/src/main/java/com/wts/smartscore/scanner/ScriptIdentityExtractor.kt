package com.wts.smartscore.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.File

data class HandwritingGuess(val text: String, val confidence: Double)

/**
 * Text recognition is deliberately behind this boundary. A future on-device
 * handwriting model or an explicitly configured cloud adapter can replace the
 * fallback without changing script segmentation or field extraction.
 */
interface HandwritingRecognizer {
    val engineName: String
    fun recognize(crop: Bitmap): HandwritingGuess
    fun close() = Unit
}

class MlKitFallbackHandwritingRecognizer(private val recognizer: TextRecognizer) : HandwritingRecognizer {
    override val engineName: String = "ML_KIT_TEXT_FALLBACK"

    override fun recognize(crop: Bitmap): HandwritingGuess {
        val text = runCatching { Tasks.await(recognizer.process(InputImage.fromBitmap(crop, 0))).text.trim() }.getOrDefault("")
        return HandwritingGuess(text, if (text.isBlank()) 0.20 else 0.58)
    }
}

object HandwritingRecognizerFactory {
    fun create(mlKitRecognizer: TextRecognizer): HandwritingRecognizer = MlKitFallbackHandwritingRecognizer(mlKitRecognizer)
}

/** OCR is used as evidence; field regions, not arbitrary page tokens, drive identity. */
data class ScriptIdentity(
    val studentName: String?,
    val studentId: String?,
    val classLabel: String?,
    val subject: String?,
    val examTitle: String?,
    val rawText: String,
    val confidence: Double,
    val fieldEvidence: Map<String, String> = emptyMap(),
    val coverSignals: Int = 0,
    val recognizerEngine: String = "ML_KIT_TEXT_FALLBACK"
) {
    fun displayStudent(): String = studentName ?: studentId ?: "Identity pending"
}

object ScriptIdentityExtractor {
    private val knownSubjects = listOf(
        "FINANCIAL ACCOUNTING", "AGRICULTURAL SCIENCE", "CIVIC EDUCATION", "BUSINESS STUDIES",
        "ISLAMIC STUDIES", "CHRISTIAN RELIGIOUS STUDIES", "ENGLISH LANGUAGE", "COMPUTER STUDIES",
        "ECONOMICS", "MATHEMATICS", "ENGLISH", "CHEMISTRY", "PHYSICS", "BIOLOGY", "COMMERCE",
        "GOVERNMENT", "GEOGRAPHY", "YORUBA", "HISTORY", "LITERATURE"
    )

    fun extract(context: Context, imagePath: String): ScriptIdentity {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val handwriting = HandwritingRecognizerFactory.create(recognizer)
        val bitmap = BitmapFactory.decodeFile(imagePath)
        return try {
            if (bitmap == null) return emptyIdentity()
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            parseFieldAware(text, bitmap, handwriting)
        } catch (_: Throwable) {
            emptyIdentity()
        } finally {
            bitmap?.recycle()
            handwriting.close()
            recognizer.close()
        }
    }

    fun extractText(context: Context, imagePath: String): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val image = InputImage.fromFilePath(context, Uri.fromFile(File(imagePath)))
            Tasks.await(recognizer.process(image)).text.trim()
        } catch (_: Throwable) {
            ""
        } finally {
            recognizer.close()
        }
    }

    fun save(file: File, identity: ScriptIdentity) {
        file.parentFile?.mkdirs()
        file.writeText(toJson(identity).toString(2))
    }

    fun read(file: File): ScriptIdentity? = if (!file.exists()) null else runCatching { fromJson(JSONObject(file.readText())) }.getOrNull()

    fun fromJson(j: JSONObject): ScriptIdentity = ScriptIdentity(
        j.optString("student_name").takeIf { it.isNotBlank() && it != "null" },
        j.optString("student_id").takeIf { it.isNotBlank() && it != "null" },
        j.optString("class").takeIf { it.isNotBlank() && it != "null" },
        j.optString("subject").takeIf { it.isNotBlank() && it != "null" },
        j.optString("exam_title").takeIf { it.isNotBlank() && it != "null" },
        j.optString("raw_text"),
        j.optDouble("confidence", 0.0),
        buildMap {
            j.optJSONObject("field_evidence")?.keys()?.forEach { key -> put(key, j.optJSONObject("field_evidence")?.optString(key).orEmpty()) }
        },
        j.optInt("cover_signals", 0),
        j.optString("recognizer_engine", "ML_KIT_TEXT_FALLBACK")
    )

    fun toJson(identity: ScriptIdentity): JSONObject = JSONObject().apply {
        put("student_name", identity.studentName ?: JSONObject.NULL)
        put("student_id", identity.studentId ?: JSONObject.NULL)
        put("class", identity.classLabel ?: JSONObject.NULL)
        put("subject", identity.subject ?: JSONObject.NULL)
        put("exam_title", identity.examTitle ?: JSONObject.NULL)
        put("confidence", identity.confidence)
        put("cover_signals", identity.coverSignals)
        put("recognizer_engine", identity.recognizerEngine)
        put("field_evidence", JSONObject(identity.fieldEvidence))
        put("raw_text", identity.rawText)
    }

    private data class Field(val value: String, val score: Double)

    private fun parseFieldAware(result: Text, bitmap: Bitmap, handwriting: HandwritingRecognizer): ScriptIdentity {
        val lines = result.textBlocks.flatMap { it.lines }
            .sortedWith(compareBy<Text.Line> { it.boundingBox?.top ?: Int.MAX_VALUE }.thenBy { it.boundingBox?.left ?: Int.MAX_VALUE })
        val raw = result.text.trim()
        val evidence = linkedMapOf<String, String>()
        val nameField = field(lines, bitmap, handwriting, NAME_LABELS)
        val idField = field(lines, bitmap, handwriting, ID_LABELS)
        val classField = field(lines, bitmap, handwriting, CLASS_LABELS)
        val subjectField = field(lines, bitmap, handwriting, SUBJECT_LABELS)
        val termField = field(lines, bitmap, handwriting, TERM_LABELS)
        nameField?.let { evidence["student_name"] = it.value }
        idField?.let { evidence["student_id"] = it.value }
        classField?.let { evidence["class"] = it.value }
        subjectField?.let { evidence["subject"] = it.value }
        termField?.let { evidence["term"] = it.value }

        val upper = raw.uppercase()
        val name = cleanName(nameField?.value)
        val id = cleanId(idField?.value)
        val subject = cleanSubject(subjectField?.value)
            ?: knownSubjects.firstOrNull { upper.take(1400).contains(it) }
        val classLabel = cleanField(classField?.value)
            ?: Regex("(?i)\\b(?:JSS|SS|GRADE|CLASS|FORM)\\s*[1-6](?:\\s*[A-Z])?\\b").find(raw.take(1800))?.value?.uppercase()
        val examTitle = lines.take(14).map { it.text.trim() }.firstOrNull { line ->
            Regex("(?i)\\b(exam|examination|test|assessment|mid-term|terminal)\\b").containsMatchIn(line)
        }
        val labelCount = lines.take(24).count { line -> COVER_LABELS.any { label -> line.text.contains(label, true) } }
        val coverSignals = listOf(
            name != null,
            id != null,
            classLabel != null,
            subject != null,
            examTitle != null,
            labelCount >= 3
        ).count { it }
        val fieldScores = listOfNotNull(nameField, idField, classField, subjectField).map { it.score }
        val confidence = if (fieldScores.isEmpty()) {
            (labelCount * 0.07 + if (subject != null) 0.15 else 0.0).coerceIn(0.0, 0.45)
        } else {
            (fieldScores.average() * 0.72 + (labelCount.coerceAtMost(6) / 6.0) * 0.18 + if (examTitle != null) 0.06 else 0.0).coerceIn(0.20, 0.97)
        }
        return ScriptIdentity(name, id, classLabel, subject, examTitle, raw, confidence, evidence, coverSignals, handwriting.engineName)
    }

    private fun field(lines: List<Text.Line>, bitmap: Bitmap, handwriting: HandwritingRecognizer, labels: List<String>): Field? {
        val alternatives = labels.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val pattern = Regex("(?i)^\\s*(?:$alternatives)\\s*(?::|=|-)??\\s*(.*)$")
        lines.forEachIndexed { index, line ->
            val match = pattern.find(line.text.trim()) ?: return@forEachIndexed
            val inline = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (inline.isNotBlank() && !looksLikeLabel(inline)) return Field(inline, 0.96)
            val next = lines.getOrNull(index + 1)
            val nextText = next?.text?.trim().orEmpty()
            if (next != null && nextText.isNotBlank() && !looksLikeLabel(nextText) && verticallyClose(line.boundingBox, next.boundingBox)) {
                return Field(nextText, 0.84)
            }
            val crop = valueCrop(bitmap, line.boundingBox)
            if (crop != null) {
                return try {
                    val guess = handwriting.recognize(crop)
                    guess.text.takeIf { it.isNotBlank() }?.let { Field(it, guess.confidence.coerceAtLeast(0.52)) }
                } finally {
                    crop.recycle()
                }
            }
        }
        return null
    }

    private fun valueCrop(bitmap: Bitmap, bounds: Rect?): Bitmap? {
        if (bounds == null || bitmap.width < 8 || bitmap.height < 8) return null
        val left = (bounds.right + (bounds.width() * 0.08f).toInt()).coerceIn(0, bitmap.width - 2)
        val top = (bounds.top - (bounds.height() * 0.55f).toInt()).coerceIn(0, bitmap.height - 2)
        val right = (bitmap.width - (bitmap.width * 0.03f).toInt()).coerceAtLeast(left + 2).coerceAtMost(bitmap.width)
        val bottom = (bounds.bottom + (bounds.height() * 0.55f).toInt()).coerceAtMost(bitmap.height).coerceAtLeast(top + 2)
        return runCatching { Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top) }.getOrNull()
    }

    private fun verticallyClose(a: Rect?, b: Rect?): Boolean {
        if (a == null || b == null) return false
        return kotlin.math.abs(a.centerY() - b.centerY()) <= maxOf(a.height() * 2, b.height() * 2)
    }

    private fun looksLikeLabel(value: String): Boolean = COVER_LABELS.any { value.equals(it, true) || value.startsWith("$it ", true) }

    private fun cleanName(value: String?): String? {
        val v = value?.replace(Regex("[^A-Za-z .'-]"), " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val words = v.split(' ').filter { it.isNotBlank() }
        return v.takeIf { words.size in 2..6 && it.length >= 4 && it.any(Char::isLetter) && it.none(Char::isDigit) }?.uppercase()
    }

    private fun cleanId(value: String?): String? {
        val v = cleanField(value)?.replace(Regex("[^A-Za-z0-9/-]"), "")?.uppercase() ?: return null
        return v.takeIf { it.length >= 3 && !(it.all(Char::isDigit) && it.length == 4) }
    }

    private fun cleanSubject(value: String?): String? {
        val upper = cleanField(value)?.uppercase() ?: return null
        return knownSubjects.firstOrNull { upper.contains(it) } ?: upper.takeIf { it.length in 3..40 }
    }

    private fun cleanField(value: String?): String? = value?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotBlank() }

    private fun emptyIdentity() = ScriptIdentity(null, null, null, null, null, "", 0.0, emptyMap(), 0)

    private val NAME_LABELS = listOf("student name", "candidate name", "name")
    private val ID_LABELS = listOf("admission number", "admission no", "adm no", "matric number", "matric no", "registration number", "registration no", "reg no", "student id", "candidate no")
    private val CLASS_LABELS = listOf("class", "grade", "level", "form")
    private val SUBJECT_LABELS = listOf("subject", "paper", "course")
    private val TERM_LABELS = listOf("term", "session")
    private val COVER_LABELS = (NAME_LABELS + ID_LABELS + CLASS_LABELS + SUBJECT_LABELS + TERM_LABELS + listOf("examination", "exam", "test")).distinct()
}
