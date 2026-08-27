package com.wts.smartscore.scanner

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.File

/** OCR is used here only to suggest script identity. It is not an academic authority. */
data class ScriptIdentity(
    val studentName: String?,
    val studentId: String?,
    val classLabel: String?,
    val subject: String?,
    val examTitle: String?,
    val rawText: String,
    val confidence: Double
) {
    fun displayStudent(): String = studentName ?: studentId ?: "Unidentified student"
}

object ScriptIdentityExtractor {
    private val knownSubjects = listOf(
        "ECONOMICS", "MATHEMATICS", "ENGLISH", "CHEMISTRY", "PHYSICS", "BIOLOGY",
        "COMMERCE", "ACCOUNTING", "FINANCIAL ACCOUNTING", "CIVIC EDUCATION", "GOVERNMENT",
        "GEOGRAPHY", "AGRICULTURAL SCIENCE", "BUSINESS STUDIES", "YORUBA", "HISTORY",
        "LITERATURE", "ISLAMIC STUDIES", "CHRISTIAN RELIGIOUS STUDIES", "COMPUTER STUDIES"
    )

    fun extract(context: Context, imagePath: String): ScriptIdentity {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val image = InputImage.fromFilePath(context, Uri.fromFile(File(imagePath)))
            val raw = Tasks.await(recognizer.process(image)).text.trim()
            parse(raw)
        } finally {
            recognizer.close()
        }
    }

    fun extractText(context: Context, imagePath: String): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val image = InputImage.fromFilePath(context, Uri.fromFile(File(imagePath)))
            Tasks.await(recognizer.process(image)).text.trim()
        } finally {
            recognizer.close()
        }
    }

    fun save(file: File, identity: ScriptIdentity) {
        file.parentFile?.mkdirs()
        file.writeText(toJson(identity).toString(2))
    }

    fun read(file: File): ScriptIdentity? {
        if (!file.exists()) return null
        return runCatching {
            val j = JSONObject(file.readText())
            ScriptIdentity(
                j.optString("student_name").takeIf { it.isNotBlank() },
                j.optString("student_id").takeIf { it.isNotBlank() },
                j.optString("class").takeIf { it.isNotBlank() },
                j.optString("subject").takeIf { it.isNotBlank() },
                j.optString("exam_title").takeIf { it.isNotBlank() },
                j.optString("raw_text"),
                j.optDouble("confidence", 0.0)
            )
        }.getOrNull()
    }

    fun toJson(identity: ScriptIdentity): JSONObject = JSONObject().apply {
        put("student_name", identity.studentName ?: JSONObject.NULL)
        put("student_id", identity.studentId ?: JSONObject.NULL)
        put("class", identity.classLabel ?: JSONObject.NULL)
        put("subject", identity.subject ?: JSONObject.NULL)
        put("exam_title", identity.examTitle ?: JSONObject.NULL)
        put("confidence", identity.confidence)
        put("raw_text", identity.rawText)
    }

    private fun parse(raw: String): ScriptIdentity {
        val lines = raw.lines().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotBlank() }
        val (labeledName, nameScore) = labeled(lines, listOf("student name", "candidate name", "name"))
        val (labeledId, idScore) = labeled(lines, listOf("admission no", "admission number", "adm no", "matric no", "matric number", "registration no", "reg no", "student id", "candidate no"))
        val (labeledSubject, subjectScore) = labeled(lines, listOf("subject"))
        val (labeledClass, classScore) = labeled(lines, listOf("class", "grade", "level"))

        val name = cleanName(labeledName) ?: fallbackName(lines)
        val id = cleanId(labeledId) ?: fallbackId(raw)
        val subject = cleanField(labeledSubject)?.uppercase() ?: knownSubjects.firstOrNull { raw.uppercase().contains(it) }
        val classLabel = cleanField(labeledClass) ?: Regex("(?i)\\b(?:JSS|SS|GRADE|CLASS)\\s*[1-6](?:\\s*[A-Z])?\\b").find(raw)?.value
        val examTitle = lines.firstOrNull { line -> Regex("(?i)\\b(exam|examination|test|assessment|continuous assessment)\\b").containsMatchIn(line) }

        val scores = mutableListOf<Double>()
        if (name != null) scores += if (labeledName != null) nameScore else 0.65
        if (id != null) scores += if (labeledId != null) idScore else 0.62
        if (subject != null) scores += if (labeledSubject != null) subjectScore else 0.70
        if (classLabel != null) scores += if (labeledClass != null) classScore else 0.62
        val confidence = if (scores.isEmpty()) 0.25 else scores.average().coerceIn(0.25, 0.95)

        return ScriptIdentity(name, id, classLabel, subject, examTitle, raw, confidence)
    }

    private fun labeled(lines: List<String>, labels: List<String>): Pair<String?, Double> {
        val labelPattern = labels.joinToString("|") { Regex.escape(it) }
        val regex = Regex("(?i)^\\s*(?:$labelPattern)\\s*[:=\\-]?\\s*(.+?)\\s*$")
        lines.forEach { line ->
            regex.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return it to 0.90 }
        }
        lines.forEach { line ->
            labels.firstOrNull { line.contains(it, ignoreCase = true) }?.let { label ->
                val tail = line.substringAfter(label, "").trim(' ', ':', '-', '=')
                if (tail.isNotBlank()) return tail to 0.82
            }
        }
        return null to 0.0
    }

    private fun fallbackName(lines: List<String>): String? {
        val excluded = Regex("(?i)school|college|exam|test|assessment|subject|class|name|candidate|admission|matric|date|term|session")
        return lines.firstOrNull { line ->
            val letters = line.count { it.isLetter() }
            val words = line.split(' ').count { it.any(Char::isLetter) }
            words in 2..5 && letters >= 6 && letters.toDouble() / line.length.coerceAtLeast(1) > 0.68 && !excluded.containsMatchIn(line)
        }?.let(::cleanName)
    }

    private fun fallbackId(raw: String): String? {
        val patterns = listOf(
            Regex("\\b[A-Z]{2,}[A-Z0-9/-]*\\d{2,}[A-Z0-9/-]*\\b", RegexOption.IGNORE_CASE),
            Regex("\\b[A-Z]?\\d{4,}[A-Z]?\\b", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { it.find(raw)?.value }?.uppercase()
    }

    private fun cleanName(value: String?): String? {
        val v = value?.replace(Regex("[^A-Za-z .'-]"), " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return v.takeIf { it.length >= 4 && it.any(Char::isLetter) }?.uppercase()
    }

    private fun cleanId(value: String?): String? = cleanField(value)?.replace(" ", "")?.uppercase()?.takeIf { it.length >= 3 }
    private fun cleanField(value: String?): String? = value?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotBlank() }
}
