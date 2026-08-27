package com.wts.smartscore.scanner

/**
 * Local invented templates used until the read-only Result Portal template
 * endpoint is introduced. The repository deliberately models a manifest of
 * pages, not a permanent two-sided sheet.
 */
class V2TemplateRepository(@Suppress("UNUSED_PARAMETER") context: android.content.Context) {
    val templateVersion = "3.0-test-manifest"
    val classLabel = "TEST SS1"
    val subject = "Economics"
    val sheetId = "WTS-SM-V2-DEMO-0001"

    private val names = listOf(
        "Adigun Bazim", "Bakare Fathiat", "Oyelami Muiz", "Adam Kashfat", "Usman Toheebat",
        "Hassan Ibrahim", "Adebayo Mariam", "Ojo Samuel", "Akinola Temilade", "Lawal Hammed",
        "Oyediran Zainab", "Afolabi Daniel", "Salami Khadijat", "Olatunji Victor", "Adeleke Rofiat",
        "Ibrahim Sodiq", "Ajibade Deborah", "Amoo Ridwan", "Oyeniyi Faruq", "Balogun Aminat",
        "Oluwaseun Martins", "Adeniyi Barakat", "Ogundele Praise", "Yusuf Lateefah", "Fashina Habeeb",
        "Oladapo Faith", "Aderibigbe Halimat", "Ogunleye Malik", "Taiwo Kemi", "Kareem Mubashir",
        "Adekunle Seyi", "Bello Mariam", "Ogunbiyi Peter", "Sanni Rukayat", "Adewale Tobi",
        "Ishola Zainab", "Akinyemi David", "Yusuf Amina", "Ojo Favour", "Babatunde Kola"
    )

    private data class AssessmentDef(val id: String, val x: Double, val width: Double, val maximum: Double, val label: String)

    private val secondaryAssessments = listOf(
        AssessmentDef("ca1", 340.16, 76.54, 10.0, "CA1"),
        AssessmentDef("ca2", 416.70, 76.54, 10.0, "CA2"),
        AssessmentDef("ca3", 493.24, 76.54, 10.0, "CA3"),
        AssessmentDef("exam", 569.78, 93.54, 70.0, "EXAM")
    )

    private val manifests: List<TemplateManifest> by lazy {
        listOf(
            secondaryManifest(sheetId, classLabel, subject, 30, 2, "secondary-economics-v3"),
            secondaryManifest("WTS-SS-SECONDARY-ONE-001", "TEST JSS1", "English Language", 8, 1, "secondary-english-v3"),
            secondaryManifest("WTS-SS-SECONDARY-LARGE-001", "TEST SS2", "Mathematics", 36, 3, "secondary-mathematics-v3"),
            primaryManifest("WTS-SS-PRIMARY-MULTI-001", "TEST PRIMARY 4", "English • Mathematics • Basic Science • Social Studies", "primary-four-subject-v1"),
            dynamicManifest()
        )
    }

    fun allManifests(): List<TemplateManifest> = manifests
    fun manifestFor(id: String): TemplateManifest? = manifests.firstOrNull { it.sheetId == id }
    fun currentManifest(): TemplateManifest = requireNotNull(manifestFor(sheetId))

    fun pageById(id: String): SheetPageTemplate? {
        val direct = manifests.asSequence().mapNotNull { it.pageById(id) }.firstOrNull()
        if (direct != null) return direct
        // Accept the legacy V2 side QR while migrating old locally printed test sheets.
        val legacy = Regex("^(.+)-S([0-9]+)$").find(id) ?: return null
        val legacySheet = legacy.groupValues[1]
        val number = legacy.groupValues[2].toIntOrNull() ?: return null
        return manifestFor(legacySheet)?.pageByNumber(number)
    }

    fun pageByNumber(number: Int): SheetPageTemplate? = currentManifest().pageByNumber(number)
    fun sideById(id: String): SheetPageTemplate? = pageById(id)
    fun sideByNumber(number: Int): SheetPageTemplate? = pageByNumber(number)
    fun pages(): List<SheetPageTemplate> = currentManifest().pages
    fun sides(): List<SheetPageTemplate> = pages()

    fun qrPayload(page: SheetPageTemplate): String {
        val manifest = manifestFor(page.sheetId)
        return "{" +
        "\"v\":1," +
        "\"s\":\"${page.sheetId}\"," +
        "\"p\":\"${page.pageId}\"," +
        "\"n\":${page.pageNumber}," +
        "\"l\":\"${page.layoutId}\"," +
        "\"c\":\"${manifest?.classLabel ?: "TEST"}\"," +
        "\"u\":\"${page.subjectGroup}\"," +
        "\"t\":\"${manifest?.term ?: "TEST"}\"}"
    }

    private fun secondaryManifest(
        id: String,
        className: String,
        subjectGroup: String,
        population: Int,
        pages: Int,
        layoutId: String
    ): TemplateManifest {
        val expected = (1..pages).map { "$id-P$it" }
        val pageTemplates = (1..pages).map { number ->
            val start = ((number - 1) * 15 + 1).coerceAtMost(population)
            val end = minOf(number * 15, population)
            SheetPageTemplate(
                sheetId = id,
                pageId = "$id-P$number",
                pageNumber = number,
                expectedPageCount = pages,
                rowStart = start,
                rowEnd = end,
                pageW = 841.89,
                pageH = 595.28,
                rows = secondaryRows(start, end),
                layoutId = layoutId,
                layoutFamily = "SECONDARY_SINGLE_SUBJECT",
                subjectGroup = subjectGroup,
                templateVersion = templateVersion
            )
        }
        return TemplateManifest(templateVersion, id, className, subjectGroup, layoutId, "SECONDARY_SINGLE_SUBJECT", "2026/2027", "FIRST", expected, pageTemplates)
    }

    private fun primaryManifest(id: String, className: String, subjectGroup: String, layoutId: String): TemplateManifest {
        val pageId = "$id-P1"
        val rows = (1..12).map { rowNo ->
            val y = 387.19 - ((rowNo - 1) * 28.346)
            val subjects = listOf("English", "Mathematics", "Basic Science", "Social Studies")
            val rois = subjects.flatMapIndexed { subjectIndex, subjectName ->
                listOf("CA", "EXAM").mapIndexed { assessmentIndex, label ->
                    val x = 215.43 + subjectIndex * 96.38 + assessmentIndex * 48.19
                    scoreRoi("${subjectName.lowercase().replace(' ', '_')}_${label.lowercase()}", label, 20.0, x, y, 44.19, 25.0, subjectName)
                }
            }
            RowDef(rowNo, "TEST-PRI-${rowNo.toString().padStart(3, '0')}", primaryNames[rowNo - 1], rois)
        }
        val page = SheetPageTemplate(id, pageId, 1, 1, 1, rows.size, 841.89, 595.28, rows, layoutId, "PRIMARY_MULTI_SUBJECT", subjectGroup, templateVersion)
        return TemplateManifest(templateVersion, id, className, subjectGroup, layoutId, "PRIMARY_MULTI_SUBJECT", "2026/2027", "FIRST", listOf(pageId), listOf(page))
    }

    private fun dynamicManifest(): TemplateManifest {
        val id = "WTS-SS-DYNAMIC-001"
        val rows = secondaryRows(1, 15)
        val page = SheetPageTemplate(id, "$id-P1", 1, null, 1, 15, 841.89, 595.28, rows, "dynamic-secondary-v1", "SECONDARY_SINGLE_SUBJECT", "Test Subject", templateVersion)
        return TemplateManifest(templateVersion, id, "TEST DYNAMIC CLASS", "Test Subject", "dynamic-secondary-v1", "SECONDARY_SINGLE_SUBJECT", "2026/2027", "FIRST", null, listOf(page))
    }

    private fun secondaryRows(start: Int, end: Int): List<RowDef> = (start..end).map { rowNo ->
        // Coordinates are in the landscape-A4 PDF coordinate space used by the
        // generated test sheets: bottom-left origin, 10 mm rows, 15 rows/page.
        val y = 407.20 - ((rowNo - start) * 28.346)
        RowDef(
            rowNo = rowNo,
            studentId = "TEST-STU-${rowNo.toString().padStart(3, '0')}",
            studentName = names[(rowNo - 1) % names.size],
            rois = secondaryAssessments.map { definition ->
                scoreRoi(definition.id, definition.label, definition.maximum, definition.x, y, definition.width - 4.0, 24.66, null)
            }
        )
    }

    private fun scoreRoi(id: String, label: String, maximum: Double, x: Double, y: Double, w: Double, h: Double, subject: String?): ScoreRoiDef = ScoreRoiDef(
        assessmentId = id,
        maximum = maximum,
        x = x,
        y = y,
        w = w,
        h = h,
        digitBoxes = listOf(
            DigitBoxDef(x + w * 0.21, y + h * 0.14, w * 0.26, h * 0.72, 0),
            DigitBoxDef(x + w * 0.54, y + h * 0.14, w * 0.26, h * 0.72, 1)
        ),
        subjectGroup = subject,
        label = label
    )

    companion object {
        private val primaryNames = listOf(
            "Aisha Bello", "David Adeyemi", "Favour Okoro", "Mariam Yusuf", "Peter Akin",
            "Tolu Ajayi", "Zainab Sanni", "Samuel Ojo", "Esther Lawal", "Daniel Musa",
            "Hauwa Ibrahim", "Michael Adewale"
        )
    }
}
