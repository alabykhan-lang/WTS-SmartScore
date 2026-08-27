package com.wts.smartscore.export

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.wts.smartscore.data.ScoreReadingEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object CsvScoreExporter {
    fun export(file: File, rows: List<ScoreReadingEntity>) {
        file.parentFile?.mkdirs()
        file.printWriter().use { writer ->
            writer.println("student_id,student_name,assessment_id,maximum,raw_value,reviewed_value,confidence,state")
            rows.forEach { row ->
                writer.println(listOf(row.studentId, row.studentName, row.assessmentId, row.maximum, row.rawValue ?: "", row.reviewedValue ?: "", row.confidence, row.state).joinToString(",") { quote(it.toString()) })
            }
        }
    }

    private fun quote(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
object JsonScoreExporter {
    fun export(file: File, sheetId: String, rows: List<ScoreReadingEntity>) {
        file.parentFile?.mkdirs()
        val json = JSONObject().apply {
            put("schema_version", "3.0")
            put("sheet_id", sheetId)
            put("readings", JSONArray().apply {
                rows.forEach { row -> put(JSONObject().apply {
                    put("reading_id", row.id)
                    put("student_id", row.studentId)
                    put("student_name", row.studentName)
                    put("assessment_id", row.assessmentId)
                    put("maximum", row.maximum)
                    put("raw_value", row.rawValue ?: JSONObject.NULL)
                    put("reviewed_value", row.reviewedValue ?: JSONObject.NULL)
                    put("confidence", row.confidence)
                    put("state", row.state)
                    put("source_crop", row.cropPath ?: JSONObject.NULL)
                }) }
            })
        }
        file.writeText(json.toString(2))
    }
}

object PdfImageExporter {
    data class OcrPage(val imagePath: String, val text: String)

    fun export(file: File, imagePaths: List<String>) {
        file.parentFile?.mkdirs()
        val document = PdfDocument()
        imagePaths.forEachIndexed { index, path ->
            val bitmap = BitmapFactory.decodeFile(path) ?: return@forEachIndexed
            val info = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
            val page = document.startPage(info)
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            document.finishPage(page)
            bitmap.recycle()
        }
        file.outputStream().use { document.writeTo(it) }
        document.close()
    }

    /** Adds a very small, nearly invisible text layer for page search/copy. */
    fun exportSearchable(file: File, pages: List<OcrPage>) {
        file.parentFile?.mkdirs()
        val document = PdfDocument()
        pages.forEachIndexed { index, ocrPage ->
            val bitmap = BitmapFactory.decodeFile(ocrPage.imagePath) ?: return@forEachIndexed
            val info = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
            val page = document.startPage(info)
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(2, 255, 255, 255)
                textSize = 1f
            }
            ocrPage.text.split('\n').take(120).forEachIndexed { line, value ->
                page.canvas.drawText(value.take(150), 2f, bitmap.height - 2f - line * 1.2f, textPaint)
            }
            document.finishPage(page)
            bitmap.recycle()
        }
        file.outputStream().use { document.writeTo(it) }
        document.close()
    }
}

object ImageZipExporter {
    fun export(file: File, imagePaths: List<String>, metadata: String? = null, textEntries: Map<String, String> = emptyMap()) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            imagePaths.forEachIndexed { index, path ->
                val source = File(path)
                if (source.exists()) {
                    zip.putNextEntry(ZipEntry("pages/page-${(index + 1).toString().padStart(3, '0')}.jpg"))
                    source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            metadata?.let { writeEntry(zip, "metadata.json", it) }
            textEntries.forEach { (name, value) -> writeEntry(zip, name, value) }
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, value: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(value.toByteArray())
        zip.closeEntry()
    }
}

/** Minimal, portable Word export for OCR text; images remain in the AI package. */
object DocxExporter {
    fun export(file: File, title: String, pages: List<Pair<Int, String>>) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            write(zip, "[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
            """.trimIndent())
            write(zip, "_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
            """.trimIndent())
            val body = buildString {
                append("<w:p><w:pPr><w:pStyle w:val=\"Title\"/></w:pPr><w:r><w:t>${xml(title)}</w:t></w:r></w:p>")
                pages.forEachIndexed { index, page ->
                    if (index > 0) append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                    append("<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Page ${page.first}</w:t></w:r></w:p>")
                    page.second.split('\n').forEach { line ->
                        append("<w:p><w:r><w:t xml:space=\"preserve\">${xml(line)}</w:t></w:r></w:p>")
                    }
                }
            }
            write(zip, "word/document.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>
            """.trimIndent())
        }
    }

    private fun write(zip: ZipOutputStream, name: String, value: String) {
        zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
    }

    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}

/** Lightweight Office Open XML workbook for reliable structured broadsheet rows. */
object XlsxScoreExporter {
    fun export(file: File, sheetId: String, rows: List<ScoreReadingEntity>) {
        file.parentFile?.mkdirs()
        val headers = listOf("student_id", "student_name", "assessment_id", "maximum", "raw_value", "reviewed_value", "confidence", "state")
        val values = rows.map { row -> listOf(row.studentId, row.studentName, row.assessmentId, row.maximum.toString(), row.rawValue?.toString().orEmpty(), row.reviewedValue?.toString().orEmpty(), row.confidence.toString(), row.state) }
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            write(zip, "[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>
            """.trimIndent())
            write(zip, "_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>
            """.trimIndent())
            write(zip, "xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>
            """.trimIndent())
            write(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"${xml(sheetId.take(31))}\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            val sheet = buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
                append(rowXml(1, headers, true))
                values.forEachIndexed { index, row -> append(rowXml(index + 2, row, false)) }
                append("</sheetData></worksheet>")
            }
            write(zip, "xl/worksheets/sheet1.xml", sheet)
        }
    }

    private fun rowXml(row: Int, values: List<String>, header: Boolean): String = buildString {
        append("<row r=\"$row\">")
        values.forEachIndexed { index, value ->
            val ref = "${column(index + 1)}$row"
            if (!header && index in listOf(3, 4, 5, 6) && value.toDoubleOrNull() != null) append("<c r=\"$ref\" t=\"n\"><v>$value</v></c>")
            else append("<c r=\"$ref\" t=\"inlineStr\"><is><t>${xml(value)}</t></is></c>")
        }
        append("</row>")
    }

    private fun column(number: Int): String {
        var n = number
        val result = StringBuilder()
        while (n > 0) { val remainder = (n - 1) % 26; result.insert(0, ('A'.code + remainder).toChar()); n = (n - 1) / 26 }
        return result.toString()
    }

    private fun write(zip: ZipOutputStream, name: String, value: String) { zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
