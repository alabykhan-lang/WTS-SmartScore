package com.wts.smartscore.scanner

data class ScriptPageClassification(
    val pageClass: String,
    val coverScore: Double,
    val reason: String
)

/** Combines labelled identity fields and cover structure; a random answer name is not enough. */
object ScriptPageClassifier {
    fun classify(identity: ScriptIdentity?, ocrText: String): ScriptPageClassification {
        val upper = ocrText.uppercase()
        val labels = listOf(
            Regex("\\b(STUDENT|CANDIDATE)\\s+NAME\\b"),
            Regex("\\b(ADMISSION|ADM|REGISTRATION|REG|MATRIC|STUDENT)\\s*(NO|NUMBER|ID)\\b"),
            Regex("\\bCLASS\\b|\\bGRADE\\b|\\bLEVEL\\b|\\bFORM\\b"),
            Regex("\\bSUBJECT\\b|\\bPAPER\\b|\\bCOURSE\\b"),
            Regex("\\b(EXAMINATION|EXAM|TEST|ASSESSMENT)\\b"),
            Regex("\\bTERM\\b|\\bSESSION\\b")
        ).count { it.containsMatchIn(upper) }
        val identityFields = listOf(identity?.studentName, identity?.studentId, identity?.classLabel, identity?.subject).count { it != null }
        val headingEvidence = listOf(identity?.examTitle != null, labels >= 3, (identity?.coverSignals ?: 0) >= 3).count { it }
        val score = (identityFields * 0.12 + labels * 0.08 + headingEvidence * 0.12 + (identity?.confidence ?: 0.0) * 0.25).coerceIn(0.0, 1.0)
        return when {
            identity != null && identityFields >= 2 && labels >= 2 && headingEvidence >= 2 && score >= 0.58 -> ScriptPageClassification("SCRIPT_COVER", score, "labelled identity plus cover heading structure")
            identity != null && (identityFields >= 1 || labels >= 1) && score >= 0.30 -> ScriptPageClassification("UNCERTAIN", score, "some identity evidence, but not enough cover structure")
            else -> ScriptPageClassification("SCRIPT_CONTINUATION", score, "no strong cover boundary evidence")
        }
    }
}
