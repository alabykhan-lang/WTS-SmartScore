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
import com.wts.smartscore.export.PdfImageExporter
import com.wts.smartscore.scanner.*
import java.io.File
import java.util.concurrent.Executors

class GeneralScannerActivity:AppCompatActivity(),SmartScanEngine.Listener{
 private lateinit var preview:PreviewView;private lateinit var status:TextView;private lateinit var engine:SmartScanEngine;private val exec=Executors.newSingleThreadExecutor();private val pages=mutableListOf<String>()
 override fun onCreate(b:Bundle?){super.onCreate(b);if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.CAMERA),31);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};status=TextView(this).apply{text="SEARCHING";gravity=Gravity.CENTER;textSize=18f;setPadding(12,20,12,20)};root.addView(status);preview=PreviewView(this);root.addView(preview,LinearLayout.LayoutParams(-1,0,1f));val done=Button(this).apply{text="DONE / EXPORT PDF";setOnClickListener{exportPdf()}};root.addView(done);setContentView(root);engine=SmartScanEngine(this).also{it.listener=this};startCamera()}
 private fun startCamera(){val f=ProcessCameraProvider.getInstance(this);f.addListener({val p=f.get();val pv=Preview.Builder().build().also{it.setSurfaceProvider(preview.surfaceProvider)};engine.bind(p,this,pv,ImageAnalysis.Analyzer{image->try{val m=ImageProxyTools.lumaMat(image);val a=OpenCvDocumentDetector().detect(m);m.release();engine.submitAssessment(a)}finally{image.close()}})},ContextCompat.getMainExecutor(this))}
 override fun onState(state:String){runOnUiThread{status.text=state}}
 override fun onCaptured(path:String){exec.execute{val b=BitmapFactory.decodeFile(path)?:return@execute;val n=ImageProcessor.normalize(b);val f=File(filesDir,"documents/${System.currentTimeMillis()}.jpg");ImageProcessor.saveJpeg(n,f);pages.add(f.absolutePath);b.recycle();n.recycle();runOnUiThread{status.text="SCANNED - ${pages.size} PAGE(S). REMOVE PAGE, THEN PRESENT NEXT."}}}
 override fun onError(message:String){runOnUiThread{status.text=message}}
 private fun exportPdf(){if(pages.isEmpty()){Toast.makeText(this,"No pages scanned",Toast.LENGTH_SHORT).show();return};val f=File(filesDir,"exports/general-${System.currentTimeMillis()}.pdf");f.parentFile?.mkdirs();PdfImageExporter.export(f,pages);Toast.makeText(this,"Saved: ${f.name}",Toast.LENGTH_LONG).show()}
 override fun onDestroy(){super.onDestroy();exec.shutdown()}
}
