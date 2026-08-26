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
import androidx.lifecycle.lifecycleScope
import com.wts.smartscore.data.*
import com.wts.smartscore.export.ImageZipExporter
import com.wts.smartscore.export.PdfImageExporter
import com.wts.smartscore.scanner.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class ScriptScannerActivity:AppCompatActivity(),SmartScanEngine.Listener{
 private lateinit var preview:PreviewView;private lateinit var status:TextView;private lateinit var engine:SmartScanEngine;private lateinit var thumbs:LinearLayout
 private lateinit var student:EditText;private lateinit var subject:EditText;private lateinit var note:EditText
 private val detector=OpenCvDocumentDetector();private val exec=Executors.newSingleThreadExecutor();private val dao by lazy{SmartScoreDatabase.get(this).dao()}
 private var scriptId="";private var createdAt=0L;private var page=0;private val pagePaths=mutableListOf<String>()
 override fun onCreate(b:Bundle?){super.onCreate(b);if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.CAMERA),41)
  scriptId=intent.getStringExtra("scriptId")?:UUID.randomUUID().toString();createdAt=System.currentTimeMillis()
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val form=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};student=EditText(this).apply{hint="Student/Test ID"};subject=EditText(this).apply{hint="Subject"};note=EditText(this).apply{hint="Note"};form.addView(student,LinearLayout.LayoutParams(0,-2,1f));form.addView(subject,LinearLayout.LayoutParams(0,-2,1f));form.addView(note,LinearLayout.LayoutParams(0,-2,1f));root.addView(form)
  status=TextView(this).apply{text="START SCRIPT — PRESENT PAGE 1";gravity=Gravity.CENTER;textSize=17f;setPadding(12,12,12,12)};root.addView(status);preview=PreviewView(this);root.addView(preview,LinearLayout.LayoutParams(-1,0,1f))
  thumbs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};root.addView(HorizontalScrollView(this).apply{addView(thumbs)},LinearLayout.LayoutParams(-1,150));root.addView(Button(this).apply{text="DONE / FINISH SCRIPT";setOnClickListener{finishScript()}});setContentView(root)
  lifecycleScope.launch{val existing=dao.script(scriptId);if(existing!=null){createdAt=existing.createdAt;student.setText(existing.studentRef?:"");subject.setText(existing.subject?:"");note.setText(existing.testRef?:"");val ps=dao.scriptPages(scriptId);page=ps.size;pagePaths.clear();pagePaths.addAll(ps.map{it.normalizedPath?:it.imagePath});renderPages()}else dao.saveScript(ScriptEntity(scriptId,null,null,null,createdAt,null,"IN_PROGRESS",0))}
  engine=SmartScanEngine(this).also{it.listener=this};startCamera()
 }
 private fun startCamera(){val f=ProcessCameraProvider.getInstance(this);f.addListener({val p=f.get();val pv=Preview.Builder().build().also{it.setSurfaceProvider(preview.surfaceProvider)};engine.bind(p,this,pv,ImageAnalysis.Analyzer{image->try{val m=ImageProxyTools.lumaMat(image);val a=detector.detect(m);m.release();engine.submitAssessment(a)}finally{image.close()}})},ContextCompat.getMainExecutor(this))}
 override fun onState(state:String){runOnUiThread{status.text=when(state){"DOCUMENT FOUND","CAPTURING"->"HOLD STEADY";else->state}}}
 override fun onCaptured(path:String){exec.execute{val b=BitmapFactory.decodeFile(path)?:return@execute;val n=ImageProcessor.normalize(b);val out=File(filesDir,"scripts/$scriptId/page-${System.currentTimeMillis()}.jpg");ImageProcessor.saveJpeg(n,out);b.recycle();n.recycle();runOnUiThread{lifecycleScope.launch{page++;dao.saveScriptPage(ScriptPageEntity(UUID.randomUUID().toString(),scriptId,page,path,out.absolutePath,System.currentTimeMillis()));dao.saveScript(ScriptEntity(scriptId,student.text.toString().ifBlank{null},subject.text.toString().ifBlank{null},note.text.toString().ifBlank{null},createdAt,null,"IN_PROGRESS",page));pagePaths.add(out.absolutePath);renderPages();status.text="PAGE $page SAVED ✓ — TURN PAGE"}}}}
 override fun onError(message:String){runOnUiThread{status.text=message}}
 private fun renderPages(){thumbs.removeAllViews();pagePaths.forEachIndexed{i,p->val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};box.addView(ImageView(this).apply{setImageBitmap(BitmapFactory.decodeFile(p));scaleType=ImageView.ScaleType.CENTER_CROP},LinearLayout.LayoutParams(110,105));box.addView(TextView(this).apply{text="${i+1}";gravity=Gravity.CENTER});box.addView(Button(this).apply{text="DELETE";setOnClickListener{lifecycleScope.launch{val ps=dao.scriptPages(scriptId);ps.getOrNull(i)?.let{dao.deleteScriptPage(it.pageId)};resequence();loadPages()}}});thumbs.addView(box)} }
 private suspend fun resequence(){dao.scriptPages(scriptId).forEachIndexed{i,p->dao.setPageNumber(p.pageId,i+1)}}
 private suspend fun loadPages(){val ps=dao.scriptPages(scriptId);page=ps.size;pagePaths.clear();pagePaths.addAll(ps.map{it.normalizedPath?:it.imagePath});renderPages()}
 private fun finishScript(){lifecycleScope.launch{resequence();val ps=dao.scriptPages(scriptId);page=ps.size;dao.saveScript(ScriptEntity(scriptId,student.text.toString().ifBlank{null},subject.text.toString().ifBlank{null},note.text.toString().ifBlank{null},createdAt,System.currentTimeMillis(),"COMPLETE",page));val paths=ps.map{it.normalizedPath?:it.imagePath};exec.execute{val dir=File(filesDir,"exports");val pdf=File(dir,"script-$scriptId.pdf");val zip=File(dir,"script-$scriptId.zip");PdfImageExporter.export(pdf,paths);ImageZipExporter.export(zip,paths,"{\"script_id\":\"$scriptId\",\"student_ref\":\"${student.text}\",\"subject\":\"${subject.text}\",\"page_count\":${paths.size}}");runOnUiThread{Toast.makeText(this@ScriptScannerActivity,"Script saved: ${paths.size} pages",Toast.LENGTH_LONG).show();finish()}}}}
 override fun onDestroy(){super.onDestroy();engine.shutdown();exec.shutdown()}
}
