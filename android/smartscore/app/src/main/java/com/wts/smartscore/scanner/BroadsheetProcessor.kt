package com.wts.smartscore.scanner

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.wts.smartscore.data.ScoreReadingEntity
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.UUID

class BroadsheetProcessor(private val context:Context){
 data class Digit(val value:Int?,val confidence:Double,val blank:Boolean,val cropPath:String?)
 fun process(bitmap:Bitmap,side:SideTemplateDef,scanId:String):List<ScoreReadingEntity>{
  val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
  return try{side.rows.flatMap{row->row.rois.map{roi->
   val digits=roi.digitBoxes.sortedBy{it.index}.map{recognizeDigit(bitmap,side,it,recognizer,row.rowNo,roi.assessmentId)}
   val (value,state)=assemble(digits,roi.maximum);val confidence=digits.filter{!it.blank}.minOfOrNull{it.confidence}?:1.0
   ScoreReadingEntity(UUID.randomUUID().toString(),side.sheetId,side.sideId,scanId,row.studentId,row.studentName,roi.assessmentId,roi.maximum,value,value,confidence,state,digits.firstOrNull{it.cropPath!=null}?.cropPath,null)
  }}}finally{recognizer.close()}
 }
 private fun recognizeDigit(page:Bitmap,side:SideTemplateDef,b:DigitBoxDef,recognizer:com.google.mlkit.vision.text.TextRecognizer,row:Int,assessment:String):Digit{
  val x=(b.x/side.pageW*page.width).toInt().coerceIn(0,page.width-2);val y=((side.pageH-(b.y+b.h))/side.pageH*page.height).toInt().coerceIn(0,page.height-2)
  val w=(b.w/side.pageW*page.width).toInt().coerceIn(2,page.width-x);val h=(b.h/side.pageH*page.height).toInt().coerceIn(2,page.height-y)
  val crop=Bitmap.createBitmap(page,x,y,w,h);val mx=(w*0.12).toInt();val my=(h*0.12).toInt();val iw=(w-2*mx).coerceAtLeast(1);val ih=(h-2*my).coerceAtLeast(1);val inner=Bitmap.createBitmap(crop,mx.coerceAtMost(w-1),my.coerceAtMost(h-1),iw.coerceAtMost(w-mx),ih.coerceAtMost(h-my));crop.recycle()
  val m=Mat();Utils.bitmapToMat(inner,m);val g=Mat();Imgproc.cvtColor(m,g,Imgproc.COLOR_RGBA2GRAY);val mean=MatOfDouble();val std=MatOfDouble();Core.meanStdDev(g,mean,std);val variance=std.toArray().firstOrNull()?:0.0
  if(variance<12.0){listOf(m,g,mean,std).forEach{it.release()};inner.recycle();return Digit(null,1.0,true,null)}
  val bw=Mat();Imgproc.threshold(g,bw,0.0,255.0,Imgproc.THRESH_BINARY_INV+Imgproc.THRESH_OTSU);val enlarged=Mat();Imgproc.resize(bw,enlarged,Size(bw.cols()*4.0,bw.rows()*4.0),0.0,0.0,Imgproc.INTER_CUBIC);val rgba=Mat();Imgproc.cvtColor(enlarged,rgba,Imgproc.COLOR_GRAY2RGBA);val out=Bitmap.createBitmap(rgba.cols(),rgba.rows(),Bitmap.Config.ARGB_8888);Utils.matToBitmap(rgba,out)
  val dir=File(context.filesDir,"broadsheet-crops").apply{mkdirs()};val cropFile=File(dir,"r${row}-${assessment}-${b.index}-${System.nanoTime()}.jpg");ImageProcessor.saveJpeg(out,cropFile)
  val txt=Tasks.await(recognizer.process(InputImage.fromBitmap(out,0))).text.filter{it.isDigit()};val value=txt.firstOrNull()?.digitToIntOrNull();val confidence=if(txt.length==1)0.92 else if(value!=null)0.65 else 0.35
  listOf(m,g,mean,std,bw,enlarged,rgba).forEach{it.release()};inner.recycle();out.recycle();return Digit(value,confidence,false,cropFile.absolutePath)
 }
 private fun assemble(d:List<Digit>,maximum:Double):Pair<Double?,String>{if(d.all{it.blank})return null to "BLANK";if(d.any{!it.blank&&it.value==null})return null to "UNREADABLE";val vals=d.mapNotNull{it.value};if(vals.isEmpty())return null to "UNREADABLE";val v=if(vals.size==1)vals[0].toDouble() else (vals.takeLast(2)[0]*10+vals.takeLast(2)[1]).toDouble();val conf=d.filter{!it.blank}.minOfOrNull{it.confidence}?:0.0;return v to when{v>maximum->"INVALID";conf>=0.90->"CONFIRMED";else->"REVIEW_REQUIRED"}}
}
