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

    private val legacyV2Version = "2.0-prototype"
    private val smbTestVersion = "a4-landscape-v1"

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

    /** Names printed by the physical WTS SMARTMARK V2 test sheet. */
    private val legacyV2Names = listOf(
        "Adigun Bazim", "Bakare Fathiat", "Oyelami Muiz", "Adam Kashfat", "Usman Toheebat",
        "Hassan Ibrahim", "Adebayo Mariam", "Ojo Samuel", "Akinola Temilade", "Lawal Hammed",
        "Oyediran Zainab", "Afolabi Daniel", "Salami Khadijat", "Olatunji Victor", "Adeleke Rofiat",
        "Ibrahim Sodiq", "Ajibade Deborah", "Amoo Ridwan", "Oyeniyi Faruq", "Balogun Aminat",
        "Oluwaseun Martins", "Adeniyi Barakat", "Ogundele Praise", "Yusuf Lateefah", "Fashina Habeeb",
        "Oladapo Faith", "Aderibigbe Halimat", "Ogunleye Malik", "Taiwo Kemi", "Kareem Mubashir"
    )

    /** Exact roster printed by the recovered one-page SMB-TEST-0001 fixture. */
    private val smbTestNames = listOf(
        "ADIGUN BAZIM", "BAKARE FATHIAT", "OYELAMI MUIZ", "ADAM KASHFAT", "USMAN TOHEEBAT",
        "HASSAN IBRAHIM", "ADEYEMI SAMAD", "RAJI MARIAM", "AKANDE ABDULLAH", "SALAMI ZAINAB",
        "OLATUNJI RIDWAN", "BELLO AMINAT", "LAWAL MUHAMMAD", "AJIBOLA RUKAYAT", "FOLORUNSO HABEEB",
        "YUSUF BARAKAT", "ADEBAYO KHALID", "HAMMED RAHMAT"
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
            // This ID is the physical V2 sheet used in the phone test. Its
            // coordinates are intentionally frozen to the printed artifact;
            // applying the newer generated geometry makes every crop miss.
            legacyV2Manifest(),
            // A second recovered fixture exercises the one-page manifest path.
            smbTestManifest(),
            secondaryManifest("WTS-SS-SECONDARY-ONE-001", "TEST JSS1", "English Language", 8, 1, "secondary-english-v3"),
            secondaryManifest("WTS-SS-SECONDARY-LARGE-001", "TEST SS2", "Mathematics", 36, 3, "secondary-mathematics-v3"),
            primaryManifest("WTS-SS-PRIMARY-MULTI-001", "TEST PRIMARY 4", "English • Mathematics • Basic Science • Social Studies", "primary-four-subject-v1"),
            dynamicManifest()
        )
    }

    fun allManifests(): List<TemplateManifest> = manifests
    fun manifestFor(id: String): TemplateManifest? = manifests.firstOrNull { it.sheetId.equals(id.trim(), ignoreCase = true) }
    fun currentManifest(): TemplateManifest = requireNotNull(manifestFor(sheetId))

    /** Resolve a QR/OCR identity without requiring the QR to carry a page id. */
    fun pageForIdentity(identity: SheetPageIdentity): SheetPageTemplate? {
        identity.pageId?.let { pageById(it)?.let { page -> return page } }
        val manifest = manifestFor(identity.sheetId) ?: return null
        identity.pageNumber?.let { manifest.pageByNumber(it)?.let { page -> return page } }
        return manifest.pages.singleOrNull()
    }

    fun pageById(id: String): SheetPageTemplate? {
        val normalized = id.trim().uppercase()
        if (normalized.isBlank()) return null
        val direct = manifests.asSequence().mapNotNull { manifest ->
            manifest.pages.firstOrNull { it.pageId.equals(normalized, ignoreCase = true) }
        }.firstOrNull()
        if (direct != null) return direct
        // A one-page manifest can be identified by its sheet id alone.
        manifestFor(normalized)?.pages?.singleOrNull()?.let { return it }
        // Accept both the legacy V2 side QR and page IDs while migrating old
        // locally printed test sheets.
        val legacy = Regex("^(.+)-(?:S|P)([0-9]+)$").find(normalized) ?: return null
        val legacySheet = legacy.groupValues[1]
        val number = legacy.groupValues[2].toIntOrNull() ?: return null
        return manifestFor(legacySheet)?.pageByNumber(number)
    }

    fun pageByNumber(number: Int): SheetPageTemplate? = currentManifest().pageByNumber(number)
    fun sideById(id: String): SheetPageTemplate? = pageById(id)
    fun sideByNumber(number: Int): SheetPageTemplate? = pageByNumber(number)
    fun pages(): List<SheetPageTemplate> = currentManifest().pages
    fun sides(): List<SheetPageTemplate> = pages()

    /**
     * Best-effort extraction geometry for an unbranded page. This is not
     * document identity: it is only a shape match used to get the recovered
     * V2 physical sheet onto the score path when its QR/heading is absent.
     */
    fun pageForExtractionShape(bitmap: android.graphics.Bitmap, pageNumber: Int, table: GenericTableDetection?): SheetPageTemplate? {
        val aspect = bitmap.width.toDouble() / bitmap.height.toDouble().coerceAtLeast(1.0)
        if (aspect !in 1.20..1.65) return null
        val rows = table?.rowCount ?: 0
        val scoreColumns = table?.scoreColumns?.size ?: 0
        return when {
            rows in 14..17 && scoreColumns >= 4 -> currentManifest().pageByNumber(pageNumber.coerceIn(1, 2))
            rows in 17..21 && scoreColumns in 3..4 -> manifestFor("SMB-TEST-0001")?.pages?.singleOrNull()
            table == null -> currentManifest().pageByNumber(pageNumber.coerceIn(1, 2))
            else -> null
        }
    }

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

    /** Frozen geometry for the physical WTS SMARTMARK V2 test sheet. */
    private fun legacyV2Manifest(): TemplateManifest {
        val id = sheetId
        val pageCount = 2
        val pages = (1..pageCount).map { pageNumber ->
            val start = if (pageNumber == 1) 1 else 16
            val end = if (pageNumber == 1) 15 else 30
            SheetPageTemplate(
                sheetId = id,
                pageId = "$id-P$pageNumber",
                pageNumber = pageNumber,
                expectedPageCount = pageCount,
                rowStart = start,
                rowEnd = end,
                pageW = 841.89,
                pageH = 595.28,
                rows = legacyV2Rows(start, end),
                layoutId = "legacy-v2-frozen",
                layoutFamily = "LEGACY_SECONDARY_SINGLE_SUBJECT",
                subjectGroup = subject,
                templateVersion = legacyV2Version
            )
        }
        return TemplateManifest(
            templateVersion = legacyV2Version,
            sheetId = id,
            classLabel = classLabel,
            subjectGroup = subject,
            layoutId = "legacy-v2-frozen",
            layoutFamily = "LEGACY_SECONDARY_SINGLE_SUBJECT",
            session = "2026/2027",
            term = "FIRST",
            expectedPageIds = pages.map { it.pageId },
            pages = pages
        )
    }

    private fun legacyV2Rows(start: Int, end: Int): List<RowDef> {
        val assessments = listOf(
            Triple("ca1", 266.46, 10.0),
            Triple("ca2", 354.33, 10.0),
            Triple("ca3", 442.20, 10.0),
            Triple("exam", 530.08, 70.0)
        )
        return (start..end).mapIndexed { offset, rowNumber ->
            val y = 446.74 - (offset * 24.66)
            val rois = assessments.map { (assessmentId, x, maximum) ->
                ScoreRoiDef(
                    assessmentId = assessmentId,
                    maximum = maximum,
                    x = x,
                    y = y,
                    w = 87.87,
                    h = 24.66,
                    digitBoxes = listOf(
                        DigitBoxDef(x + 18.42, y + 3.40, 22.68, 17.86, 0),
                        DigitBoxDef(x + 46.77, y + 3.40, 22.68, 17.86, 1)
                    ),
                    label = assessmentId.uppercase()
                )
            }
            RowDef(
                rowNo = rowNumber,
                studentId = "TEST-STU-${rowNumber.toString().padStart(3, '0')}",
                studentName = legacyV2Names[rowNumber - 1],
                rois = rois
            )
        }
    }

    /** Exact geometry for the one-page recovered A4-landscape fixture. */
    private fun smbTestManifest(): TemplateManifest {
        val id = "SMB-TEST-0001"
        val pageId = "$id-P1"
        val assessments = listOf(
            Triple("ca1", 178.0, 10.0),
            Triple("ca2", 197.5, 10.0),
            Triple("exam", 217.0, 70.0)
        )
        val rows = (1..smbTestNames.size).map { rowNumber ->
            val y = 53.45 + ((rowNumber - 1) * 7.70)
            RowDef(
                rowNo = rowNumber,
                studentId = "SMB-TEST-STU-${rowNumber.toString().padStart(3, '0')}",
                studentName = smbTestNames[rowNumber - 1],
                rois = assessments.map { (assessmentId, x, maximum) ->
                    ScoreRoiDef(
                        assessmentId = assessmentId,
                        maximum = maximum,
                        x = x,
                        y = y,
                        w = 17.0,
                        h = 6.80,
                        digitBoxes = listOf(
                            DigitBoxDef(x, y, 8.0, 6.80, 0),
                            DigitBoxDef(x + 9.0, y, 8.0, 6.80, 1)
                        ),
                        label = assessmentId.uppercase()
                    )
                }
            )
        }
        val page = SheetPageTemplate(
            sheetId = id,
            pageId = pageId,
            pageNumber = 1,
            expectedPageCount = 1,
            rowStart = 1,
            rowEnd = rows.size,
            pageW = 297.0,
            pageH = 210.0,
            rows = rows,
            layoutId = "legacy-a4-landscape-v1",
            layoutFamily = "LEGACY_SECONDARY_SINGLE_SUBJECT",
            subjectGroup = "ECONOMICS",
            templateVersion = smbTestVersion,
            coordinateOrigin = "TOP_LEFT",
            registrationAnchors = listOf(RegistrationAnchorDef("qr", 260.0, 14.0, 17.0, 17.0))
        )
        return TemplateManifest(
            templateVersion = smbTestVersion,
            sheetId = id,
            classLabel = "SS2",
            subjectGroup = "ECONOMICS",
            layoutId = "legacy-a4-landscape-v1",
            layoutFamily = "LEGACY_SECONDARY_SINGLE_SUBJECT",
            session = "2025/2026",
            term = "FIRST",
            expectedPageIds = listOf(pageId),
            pages = listOf(page)
        )
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
