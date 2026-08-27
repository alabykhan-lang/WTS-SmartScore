package com.wts.smartscore.export

import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import com.wts.smartscore.data.ScoreReadingEntity
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object CsvScoreExporter {
    fun export(file: File, rows: List<ScoreReadingEntity>) {
        file.parentFile?.mkdirs()
        file.printWriter().use { w ->
            w.println("student_id,student_name,assessment_id,raw_value,reviewed_value,confidence,state")
            rows.forEach { r ->
                w.println(listOf(r.studentId, quote(r.studentName), r.assessmentId, r.rawValue ?: "", r.reviewedValue ?: "", r.confidence, r.state).joinToString(","))
            }
        }
    }
    private fun quote(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
}

object JsonScoreExporter {
    fun export(file: File, sheetId: String, rows: List<ScoreReadingEntity>) {
        file.parentFile?.mkdirs()
        file.writeText(buildString {
            append("{\"sheet_id\":\"").append(sheetId).append("\",\"readings\":[")
            rows.forEachIndexed { i, r ->
                if (i > 0) append(',')
                append("{\"student_id\":\"").append(r.studentId)
                    .append("\",\"student_name\":\"").append(r.studentName.replace("\"", "\\\""))
                    .append("\",\"assessment_id\":\"").append(r.assessmentId)
                    .append("\",\"raw_value\":").append(r.rawValue ?: "null")
                    .append(",\"reviewed_value\":").append(r.reviewedValue ?: "null")
                    .append(",\"confidence\":").append(r.confidence)
                    .append(",\"state\":\"").append(r.state).append("\"}")
            }
            append("]}")
        })
    }
}

object PdfImageExporter {
    fun export(file: File, imagePaths: List<String>) {
        file.parentFile?.mkdirs()
        val doc = PdfDocument()
        imagePaths.forEachIndexed { i, p ->
            val b = BitmapFactory.decodeFile(p) ?: return@forEachIndexed
            val info = PdfDocument.PageInfo.Builder(b.width, b.height, i + 1).create()
            val page = doc.startPage(info)
            page.canvas.drawBitmap(b, 0f, 0f, null)
            doc.finishPage(page)
            b.recycle()
        }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}

object ImageZipExporter {
    fun export(
        file: File,
        imagePaths: List<String>,
        metadata: String? = null,
        textEntries: Map<String, String> = emptyMap()
    ) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { z ->
            imagePaths.forEachIndexed { i, p ->
                val f = File(p)
                if (f.exists()) {
                    z.putNextEntry(ZipEntry("pages/page-${(i + 1).toString().padStart(3, '0')}.jpg"))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
            if (metadata != null) {
                z.putNextEntry(ZipEntry("metadata.json"))
                z.write(metadata.toByteArray())
                z.closeEntry()
            }
            textEntries.forEach { (name, value) ->
                z.putNextEntry(ZipEntry(name))
                z.write(value.toByteArray())
                z.closeEntry()
            }
        }
    }
}
