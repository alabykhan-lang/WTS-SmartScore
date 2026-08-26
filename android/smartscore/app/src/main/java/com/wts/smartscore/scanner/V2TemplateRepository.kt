package com.wts.smartscore.scanner

data class DigitBoxDef(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val index: Int
)

data class ScoreRoiDef(
    val assessmentId: String,
    val maximum: Double,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val digitBoxes: List<DigitBoxDef>
)

data class RowDef(
    val rowNo: Int,
    val studentId: String,
    val studentName: String,
    val rois: List<ScoreRoiDef>
)

data class SideTemplateDef(
    val sheetId: String,
    val sideId: String,
    val sideNumber: Int,
    val totalSides: Int,
    val rowStart: Int,
    val rowEnd: Int,
    val pageW: Double,
    val pageH: Double,
    val rows: List<RowDef>
)

class V2TemplateRepository(@Suppress("UNUSED_PARAMETER") context: android.content.Context) {
    val templateVersion = "2.0-prototype"
    val classLabel = "TEST SS1"
    val subject = "Economics"
    val sheetId = "WTS-SM-V2-DEMO-0001"

    private val names = listOf(
        "Adigun Bazim", "Bakare Fathiat", "Oyelami Muiz", "Adam Kashfat", "Usman Toheebat",
        "Hassan Ibrahim", "Adebayo Mariam", "Ojo Samuel", "Akinola Temilade", "Lawal Hammed",
        "Oyediran Zainab", "Afolabi Daniel", "Salami Khadijat", "Olatunji Victor", "Adeleke Rofiat",
        "Ibrahim Sodiq", "Ajibade Deborah", "Amoo Ridwan", "Oyeniyi Faruq", "Balogun Aminat",
        "Oluwaseun Martins", "Adeniyi Barakat", "Ogundele Praise", "Yusuf Lateefah", "Fashina Habeeb",
        "Oladapo Faith", "Aderibigbe Halimat", "Ogunleye Malik", "Taiwo Kemi", "Kareem Mubashir"
    )

    private data class AssessmentDef(val id: String, val x: Double, val maximum: Double)

    private val defs = listOf(
        AssessmentDef("ca1", 266.46, 10.0),
        AssessmentDef("ca2", 354.33, 10.0),
        AssessmentDef("ca3", 442.20, 10.0),
        AssessmentDef("exam", 530.08, 70.0)
    )

    fun sideByNumber(number: Int): SideTemplateDef? = sides().firstOrNull { it.sideNumber == number }
    fun sideById(id: String): SideTemplateDef? = sides().firstOrNull { it.sideId == id }

    fun sides(): List<SideTemplateDef> {
        return (1..2).map { sideNo ->
            val start = if (sideNo == 1) 1 else 16
            val end = if (sideNo == 1) 15 else 30
            val rows = (start..end).mapIndexed { idx, rowNo ->
                val y = 446.74 - (idx * 24.66)
                val rois = defs.map { def ->
                    ScoreRoiDef(
                        assessmentId = def.id,
                        maximum = def.maximum,
                        x = def.x,
                        y = y,
                        w = 87.87,
                        h = 24.66,
                        digitBoxes = listOf(
                            DigitBoxDef(def.x + 18.42, y + 3.40, 22.68, 17.86, 0),
                            DigitBoxDef(def.x + 46.77, y + 3.40, 22.68, 17.86, 1)
                        )
                    )
                }
                RowDef(
                    rowNo = rowNo,
                    studentId = "TEST-STU-${rowNo.toString().padStart(3, '0')}",
                    studentName = names[rowNo - 1],
                    rois = rois
                )
            }
            SideTemplateDef(
                sheetId = sheetId,
                sideId = "$sheetId-S$sideNo",
                sideNumber = sideNo,
                totalSides = 2,
                rowStart = start,
                rowEnd = end,
                pageW = 841.89,
                pageH = 595.28,
                rows = rows
            )
        }
    }
}
