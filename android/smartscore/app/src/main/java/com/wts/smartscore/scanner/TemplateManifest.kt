package com.wts.smartscore.scanner

import org.json.JSONArray
import org.json.JSONObject

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
    val digitBoxes: List<DigitBoxDef>,
    val subjectGroup: String? = null,
    val label: String? = null
)

data class RowDef(
    val rowNo: Int,
    val studentId: String,
    val studentName: String,
    val rois: List<ScoreRoiDef>
)

/** A physical page/segment belonging to one logical Smart Broadsheet. */
data class SheetPageTemplate(
    val sheetId: String,
    val pageId: String,
    val pageNumber: Int,
    val expectedPageCount: Int?,
    val rowStart: Int,
    val rowEnd: Int,
    val pageW: Double,
    val pageH: Double,
    val rows: List<RowDef>,
    val layoutId: String,
    val layoutFamily: String,
    val subjectGroup: String,
    val templateVersion: String
) {
    // Compatibility accessors for the pre-v3 scanner code. These are page
    // identities now; they are not a duplex/side requirement.
    val sideId: String get() = pageId
    val sideNumber: Int get() = pageNumber
    val totalSides: Int get() = expectedPageCount ?: 0
}

data class TemplateManifest(
    val templateVersion: String,
    val sheetId: String,
    val classLabel: String,
    val subjectGroup: String,
    val layoutId: String,
    val layoutFamily: String,
    val session: String? = null,
    val term: String? = null,
    val expectedPageIds: List<String>? = null,
    val pages: List<SheetPageTemplate>
) {
    fun pageById(pageId: String): SheetPageTemplate? = pages.firstOrNull { it.pageId == pageId }
    fun pageByNumber(pageNumber: Int): SheetPageTemplate? = pages.firstOrNull { it.pageNumber == pageNumber }
    fun missingPageIds(scanned: Set<String>): List<String> = expectedPageIds.orEmpty().filterNot(scanned::contains)
    fun isComplete(scanned: Set<String>): Boolean = expectedPageIds != null && missingPageIds(scanned).isEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("template_version", templateVersion)
        put("sheet_id", sheetId)
        put("class", classLabel)
        put("subject_group", subjectGroup)
        put("layout_id", layoutId)
        put("layout_family", layoutFamily)
        put("session", session ?: JSONObject.NULL)
        put("term", term ?: JSONObject.NULL)
        put("expected_page_ids", expectedPageIds?.let { JSONArray(it) } ?: JSONObject.NULL)
        put("pages", JSONArray().apply {
            pages.forEach { page ->
                put(JSONObject().apply {
                    put("page_id", page.pageId)
                    put("page_number", page.pageNumber)
                    put("expected_page_count", page.expectedPageCount ?: JSONObject.NULL)
                    put("row_start", page.rowStart)
                    put("row_end", page.rowEnd)
                    put("page_width", page.pageW)
                    put("page_height", page.pageH)
                    put("layout_id", page.layoutId)
                    put("layout_family", page.layoutFamily)
                    put("subject_group", page.subjectGroup)
                    put("template_version", page.templateVersion)
                    put("rows", JSONArray().apply {
                        page.rows.forEach { row ->
                            put(JSONObject().apply {
                                put("row_no", row.rowNo)
                                put("student_id", row.studentId)
                                put("student_name", row.studentName)
                                put("rois", JSONArray().apply {
                                    row.rois.forEach { roi ->
                                        put(JSONObject().apply {
                                            put("assessment_id", roi.assessmentId)
                                            put("label", roi.label ?: JSONObject.NULL)
                                            put("subject_group", roi.subjectGroup ?: JSONObject.NULL)
                                            put("maximum", roi.maximum)
                                            put("x", roi.x)
                                            put("y", roi.y)
                                            put("w", roi.w)
                                            put("h", roi.h)
                                            put("digit_boxes", JSONArray().apply {
                                                roi.digitBoxes.forEach { digit ->
                                                    put(JSONObject().apply {
                                                        put("x", digit.x)
                                                        put("y", digit.y)
                                                        put("w", digit.w)
                                                        put("h", digit.h)
                                                        put("index", digit.index)
                                                    })
                                                }
                                            })
                                        })
                                    }
                                })
                            })
                        }
                    })
                })
            }
        })
    }
}
