package com.wts.smartscore.scanner

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.File

/** OCR suggests identity; the original page remains the authoritative source. */
data class ScriptIdentity(
    val studentName: String?,
    val studentId: String?,
    val classLabel: String?,
    val subject: String?,
    val examTitle: String?,
    val rawText: String,
    val confidence: Double,
    val fieldEvidence: Map<String, String> = emptyMap(),
    val coverSignals: Int = 0
) {
    fun displayStudent(): String = studentName ?: studentId ?: "Unidentified student"
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
        return try {
            val image = InputImage.fromFilePath(context, Uri.fromFile(File(imagePath)))
            parse(Tasks.await(recognizer.process(image)).text.trim())
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
        j.optInt("cover_signals", 0)
    )

    fun toJson(identity: ScriptIdentity): JSONObject = JSONObject().apply {
        put("student_name", identity.studentName ?: JSONObject.NULL)
        put("student_id", identity.studentId ?: JSONObject.NULL)
        put("class", identity.classLabel ?: JSONObject.NULL)
        put("subject", identity.subject ?: JSONObject.NULL)
        put("exam_title", identity.examTitle ?: JSONObject.NULL)
        put("confidence", identity.confidence)
        put("cover_signals", identity.coverSignals)
        put("field_evidence", JSONObject(identity.fieldEvidence))
        put("raw_text", identity.rawText)
    }

    private fun parse(raw: String): ScriptIdentity {
        val lines = raw.lines()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }

        val nameField = labeled(lines, NAME_LABELS)
        val idField = labeled(lines, ID_LABELS)
        val subjectField = labeled(lines, listOf("subject", "paper", "course"))
        val classField = labeled(lines, listOf("class", "grade", "level", "form"))
        val evidence = linkedMapOf<String, String>()
        nameField?.let { evidence["student_name"] = it.value }
        idField?.let { evidence["student_id"] = it.value }
        subjectField?.let { evidence["subject"] = it.value }
        classField?.let { evidence["class"] = it.value }

        val labelCount = evidence.size + lines.count { line -> COVER_LABELS.any { label -> line.contains(label, true) } }
        val explicitName = cleanName(nameField?.value)
        val name = explicitName ?: if (labelCount >= 3) fallbackName(lines) else null
        val id = cleanId(idField?.value)
        val subject = cleanSubject(subjectField?.value)
            ?: knownSubjects.firstOrNull { candidate -> lines.take(12).any { it.uppercase().contains(candidate) } }
        val classLabel = cleanField(classField?.value)
            ?: Regex("(?i)\\b(?:JSS|SS|GRADE|CLASS|FORM)\\s*[1-6](?:\\s*[A-Z])?\\b").find(lines.take(12).joinToString(" "))?.value?.uppercase()
        val examTitle = lines.take(12).firstOrNull { line -> Regex("(?i)\\b(exam|examination|test|assessment|mid-term|terminal)\\b").containsMatchIn(line) }

        val signals = listOf(
            name != null,
            id != null,
            classLabel != null,
            subject != null,
            examTitle != null,
            labelCount >= 2
        ).count { it }
        val scoreParts = mutableListOf<Double>()
        if (name != null) scoreParts += if (nameField != null) nameField.score else 0.62
        if (id != null) scoreParts += if (idField != null) idField.score else 0.0
        if (subject != null) scoreParts += if (subjectField != null) subjectField.score else 0.55
        if (classLabel != null) scoreParts += if (classField != null) classField.score else 0.50
        if (examTitle != null) scoreParts += 0.55
        val confidence = if (scoreParts.isEmpty()) 0.20 else scoreParts.average().coerceIn(0.20, 0.97)
        return ScriptIdentity(name, id, classLabel, subject, examTitle, raw, confidence, evidence, signals)
    }

    private data class Field(val value: String, val score: Double)

    private fun labeled(lines: List<String>, labels: List<String>): Field? {
        val alternatives = labels.joinToString("|") { Regex.escape(it) }
        val inline = Regex("(?i)^\\s*(?:$alternatives)\\s*[:=\\-]+\\s*(.+?)\\s*$")
        lines.forEachIndexed { index, line ->
            inline.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return Field(it, 0.94) }
            val exact = Regex("(?i)^\\s*(?:$alternatives)\\s*$").matches(line)
            if (exact) lines.getOrNull(index + 1)?.takeIf { next -> next.isNotBlank() && !COVER_LABELS.any { next.equals(it, true) } }?.let { return Field(it, 0.84) }
        }
        val loose = Regex("(?i)^\\s*(?:$alternatives)\\b\\s+(.+?)\\s*$")
        lines.forEach { line -> loose.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return Field(it, 0.80) } }
        return null
    }

    private fun fallbackName(lines: List<String>): String? {
        val excluded = Regex("(?i)school|college|exam|test|assessment|subject|class|name|candidate|admission|matric|date|term|session|score|total")
        return lines.take(12).firstOrNull { line ->
            val letters = line.count(Char::isLetter)
            val words = line.split(' ').count { it.any(Char::isLetter) }
            words in 2..5 && letters >= 6 && line.none(Char::isDigit) && letters.toDouble() / line.length.coerceAtLeast(1) > 0.68 && !excluded.containsMatchIn(line)
        }?.let(::cleanName)
    }

    private fun cleanName(value: String?): String? {
        val v = value?.replace(Regex("[^A-Za-z .'-]"), " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val words = v.split(' ').filter { it.isNotBlank() }
        return v.takeIf { words.size in 2..5 && it.length >= 4 && it.any(Char::isLetter) && it.none(Char::isDigit) }?.uppercase()
    }

    private fun cleanId(value: String?): String? {
        val v = cleanField(value)?.replace(Regex("[^A-Za-z0-9/-]"), "")?.uppercase() ?: return null
        if (v.length < 3 || (v.all(Char::isDigit) && v.length == 4)) return null
        return v
    }

    private fun cleanSubject(value: String?): String? {
        val upper = cleanField(value)?.uppercase() ?: return null
        return knownSubjects.firstOrNull { upper.contains(it) } ?: upper.takeIf { it.length in 3..40 }
    }

    private fun cleanField(value: String?): String? = value?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotBlank() }

    private val NAME_LABELS = listOf("student name", "candidate name", "candidate", "student", "name")
    private val ID_LABELS = listOf("admission number", "admission no", "adm no", "matric number", "matric no", "registration number", "registration no", "reg no", "student id", "candidate no")
    private val COVER_LABELS = listOf("student name", "candidate name", "admission", "registration", "matric", "subject", "class", "examination", "exam", "term")
}
