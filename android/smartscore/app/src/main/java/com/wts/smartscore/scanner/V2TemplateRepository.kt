package com.wts.smartscore.scanner

import android.content.Context
import org.json.JSONObject

data class DigitBoxDef(val x:Double,val y:Double,val w:Double,val h:Double,val index:Int)
data class ScoreRoiDef(val assessmentId:String,val maximum:Double,val x:Double,val y:Double,val w:Double,val h:Double,val digitBoxes:List<DigitBoxDef>)
data class RowDef(val rowNo:Int,val studentId:String,val studentName:String,val rois:List<ScoreRoiDef>)
data class SideTemplateDef(val sheetId:String,val sideId:String,val sideNumber:Int,val totalSides:Int,val rowStart:Int,val rowEnd:Int,val pageW:Double,val pageH:Double,val rows:List<RowDef>)

class V2TemplateRepository(private val context:Context){
 private val root:JSONObject by lazy{JSONObject(context.assets.open("templates/WTS-SMARTMARK-V2-DUPLEX.template.json").bufferedReader().readText())}
 val templateVersion:String get()=root.getString("template_version")
 val classLabel:String get()=root.getJSONObject("logical_sheet").optString("class_label","TEST CLASS")
 val subject:String get()=root.getJSONObject("logical_sheet").optString("subject","TEST SUBJECT")
 val sheetId:String get()=root.getJSONObject("logical_sheet").getString("sheet_id")
 fun sideByNumber(number:Int):SideTemplateDef?=sides().firstOrNull{it.sideNumber==number}
 fun sideById(id:String):SideTemplateDef?=sides().firstOrNull{it.sideId==id}
 fun sides():List<SideTemplateDef>{val out=mutableListOf<SideTemplateDef>();val ss=root.getJSONArray("sides");for(i in 0 until ss.length()){val s=ss.getJSONObject(i);val assessmentMax=mutableMapOf<String,Double>();val aa=s.getJSONArray("assessments");for(j in 0 until aa.length()){val a=aa.getJSONObject(j);assessmentMax[a.getString("id")]=a.getDouble("max")};val rows=mutableListOf<RowDef>();val rr=s.getJSONArray("rows");for(j in 0 until rr.length()){val r=rr.getJSONObject(j);val rois=mutableListOf<ScoreRoiDef>();val rs=r.getJSONArray("rois");for(k in 0 until rs.length()){val q=rs.getJSONObject(k);val boxes=mutableListOf<DigitBoxDef>();val db=q.getJSONArray("digit_boxes");for(d in 0 until db.length()){val b=db.getJSONObject(d);boxes.add(DigitBoxDef(b.getDouble("x"),b.getDouble("y"),b.getDouble("w"),b.getDouble("h"),b.getInt("index")))};val aid=q.getString("assessment_id");rois.add(ScoreRoiDef(aid,assessmentMax[aid]?:100.0,q.getDouble("x"),q.getDouble("y"),q.getDouble("w"),q.getDouble("h"),boxes))};rows.add(RowDef(r.getInt("row_no"),r.getString("student_uid"),r.getString("student_name"),rois))};val p=s.getJSONObject("page_size_pt");out.add(SideTemplateDef(s.getString("sheet_id"),s.getString("side_id"),s.getInt("side_number"),s.getInt("total_sides"),s.getInt("row_start"),s.getInt("row_end"),p.getDouble("w"),p.getDouble("h"),rows))};return out}
}
