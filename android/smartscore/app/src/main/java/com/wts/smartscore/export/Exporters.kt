package com.wts.smartscore.export
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import com.wts.smartscore.data.ScoreReadingEntity
import java.io.File

object CsvScoreExporter { fun export(file:File,rows:List<ScoreReadingEntity>){ file.printWriter().use{w->w.println("student_id,student_name,assessment_id,raw_value,reviewed_value,confidence,state"); rows.forEach{r->w.println(listOf(r.studentId,quote(r.studentName),r.assessmentId,r.rawValue?:"",r.reviewedValue?:"",r.confidence,r.state).joinToString(","))}}}; private fun quote(v:String)="\""+v.replace("\"","\"\"")+"\"" }
object PdfImageExporter { fun export(file:File,imagePaths:List<String>){ val doc=PdfDocument(); imagePaths.forEachIndexed{i,p-> val b=BitmapFactory.decodeFile(p)?:return@forEachIndexed; val info=PdfDocument.PageInfo.Builder(b.width,b.height,i+1).create(); val page=doc.startPage(info);page.canvas.drawBitmap(b,0f,0f,null);doc.finishPage(page)}; file.outputStream().use{doc.writeTo(it)};doc.close() } }
