package com.wts.smartscore.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.wts.smartscore.export.ImageZipExporter
import com.wts.smartscore.export.PdfImageExporter
import com.wts.smartscore.scanner.*
import java.io.File
import java.util.concurrent.Executors

class GeneralScannerActivity:AppCompatActivity(),SmartScanEngine.Listener{
 private lateinit var preview:PreviewView;private lateinit var status:TextView;private lateinit var engine:SmartScanEngine;private lateinit var thumbs:LinearLayout
 private val detector=OpenCvDocumentDetector();private val exec=Executors.newSingleThreadExecutor();private val pages=mutableListOf<String>()
 override fun onCreate(b:Bundle?){super.onCreate(b);if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.CAMERA),31)
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};status=TextView(this).apply{text="SEARCHING";gravity=Gravity.CENTER;textSize=18f;setPadding(12,16,12,16)};root.addView(status)
  preview=PreviewView(this);root.addView(preview,LinearLayout.LayoutParams(-1,0,1f));root.addView(TextView(this).apply{text="Automatic capture: place one page at a time. Remove or turn the page after the beep.";setPadding(16,8,16,8)})
  thumbs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};root.addView(HorizontalScrollView(this).apply{addView(thumbs)},LinearLayout.LayoutParams(-1,180))
  val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};actions.addView(Button(this).apply{text="FINISH PDF";setOnClickListener{exportPdf()}},LinearLayout.LayoutParams(0,-2,1f));actions.addView(Button(this).apply{text="EXPORT IMAGES";setOnClickListener{exportImages()}},LinearLayout.LayoutParams(0,-2,1f));root.addView(actions);setContentView(root)
  engine=SmartScanEngine(this).also{it.listener=this};startCamera()
 }
 private fun startCamera(){val f=ProcessCameraProvider.getInstance(this);f.addListener({val p=f.get();val pv=Preview.Builder().build().also{it.setSurfaceProvider(preview.surfaceProvider)};engine.bind(p,this,pv,ImageAnalysis.Analyzer{image->try{val m=ImageProxyTools.lumaMat(image);val a=detector.detect(m);m.release();engine.submitAssessment(a)}finally{image.close()}})},ContextCompat.getMainExecutor(this))}
 override fun onState(state:String){runOnUiThread{status.text=when(state){"DOCUMENT FOUND"->"HOLD STEADY";"ALIGN"->"REDUCE GLARE / ALIGN";"MOVE CLOSER"->"MOVE CLOSER";"MOVE BACK"->"MOVE BACK";"CAPTURING"->"HOLD STEADY";else->state}}}
 override fun onCaptured(path:String){exec.execute{val b=BitmapFactory.decodeFile(path)?:return@execute;val n=ImageProcessor.normalize(b);val f=File(filesDir,"documents/${System.currentTimeMillis()}.jpg");ImageProcessor.saveJpeg(n,f);b.recycle();n.recycle();pages.add(f.absolutePath);runOnUiThread{status.text="SCANNED ✓ — PAGE ${pages.size}. REMOVE PAGE, THEN PRESENT NEXT.";renderPages()}}}
 override fun onError(message:String){runOnUiThread{status.text=message}}
 private fun renderPages(){thumbs.removeAllViews();pages.forEachIndexed{i,p->val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(5,5,5,5)};box.addView(ImageView(this).apply{setImageBitmap(BitmapFactory.decodeFile(p));scaleType=ImageView.ScaleType.CENTER_CROP},LinearLayout.LayoutParams(120,120));box.addView(TextView(this).apply{text="Page ${i+1}";gravity=Gravity.CENTER});val row=LinearLayout(this);row.addView(Button(this).apply{text="←";isEnabled=i>0;setOnClickListener{java.util.Collections.swap(pages,i,i-1);renderPages()}});row.addView(Button(this).apply{text="×";setOnClickListener{File(p).delete();pages.removeAt(i);renderPages()}});row.addView(Button(this).apply{text="→";isEnabled=i<pages.lastIndex;setOnClickListener{java.util.Collections.swap(pages,i,i+1);renderPages()}});box.addView(row);thumbs.addView(box)} }
 private fun exportPdf(){if(pages.isEmpty()){Toast.makeText(this,"No pages scanned",Toast.LENGTH_SHORT).show();return};exec.execute{val f=File(filesDir,"exports/document-${System.currentTimeMillis()}.pdf");PdfImageExporter.export(f,pages);runOnUiThread{Toast.makeText(this,"PDF saved: ${f.absolutePath}",Toast.LENGTH_LONG).show()}}}
 private fun exportImages(){if(pages.isEmpty()){Toast.makeText(this,"No pages scanned",Toast.LENGTH_SHORT).show();return};exec.execute{val f=File(filesDir,"exports/document-images-${System.currentTimeMillis()}.zip");ImageZipExporter.export(f,pages);runOnUiThread{Toast.makeText(this,"Images package saved: ${f.absolutePath}",Toast.LENGTH_LONG).show()}}}
 override fun onDestroy(){super.onDestroy();engine.shutdown();exec.shutdown()}
}
