package com.wts.smartscore.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wts.smartscore.data.*
import com.wts.smartscore.scanner.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class BroadsheetScannerActivity:AppCompatActivity(),SmartScanEngine.Listener{
 private lateinit var preview:PreviewView;private lateinit var status:TextView;private lateinit var engine:SmartScanEngine
 private val detector=OpenCvDocumentDetector();private val exec=Executors.newSingleThreadExecutor();private val dao by lazy{SmartScoreDatabase.get(this).dao()};private val repo by lazy{V2TemplateRepository(this)}
 override fun onCreate(b:Bundle?){super.onCreate(b);if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.CAMERA),51);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};status=TextView(this).apply{text="SEARCHING FOR SMART BROADSHEET";gravity=Gravity.CENTER;textSize=18f;setPadding(10,18,10,18)};root.addView(status);preview=PreviewView(this);root.addView(preview,LinearLayout.LayoutParams(-1,0,1f));root.addView(TextView(this).apply{text="Automatic capture. QR identifies the side when possible; if QR fails, select Side 1 or Side 2 without retaking the secured page.";setPadding(18,12,18,12)});setContentView(root);engine=SmartScanEngine(this).also{it.listener=this};startCamera()}
 private fun startCamera(){val f=ProcessCameraProvider.getInstance(this);f.addListener({val p=f.get();val pv=Preview.Builder().build().also{it.setSurfaceProvider(preview.surfaceProvider)};engine.bind(p,this,pv,ImageAnalysis.Analyzer{image->try{val m=ImageProxyTools.lumaMat(image);val a=detector.detect(m);m.release();engine.submitAssessment(a)}finally{image.close()}})},ContextCompat.getMainExecutor(this))}
 override fun onState(state:String){runOnUiThread{status.text=when(state){"DOCUMENT FOUND","CAPTURING"->"HOLD STEADY...";"SEARCHING"->"SEARCHING FOR SHEET";else->state}}}
 override fun onCaptured(path:String){status.post{status.text="SCANNED ✓ — IDENTIFYING SIDE"};exec.execute{val original=BitmapFactory.decodeFile(path)?:return@execute;val normalized=ImageProcessor.normalize(original);val out=File(filesDir,"broadsheets/${System.currentTimeMillis()}.jpg");ImageProcessor.saveJpeg(normalized,out);val resolved=try{SheetIdentityResolver.resolveSideId(original,normalized)}catch(_:Exception){null};original.recycle();if(resolved!=null){val side=repo.sideById(resolved);if(side!=null)processSide(side,path,out.absolutePath,normalized)else askSide(path,out.absolutePath,normalized)}else askSide(path,out.absolutePath,normalized)}}
 private fun askSide(original:String,normalizedPath:String,bitmap:android.graphics.Bitmap){runOnUiThread{AlertDialog.Builder(this).setTitle("Select broadsheet side").setMessage("QR identity was not resolved. The captured page is secure; choose its side without retaking.").setItems(arrayOf("Side 1","Side 2")){_,which->exec.execute{repo.sideByNumber(which+1)?.let{processSide(it,original,normalizedPath,bitmap)}}}.setNegativeButton("Cancel"){_,_->bitmap.recycle();status.text="SCAN SAVED — SELECT SIDE WHEN READY"}.show()}}
 private fun processSide(side:SideTemplateDef,original:String,normalizedPath:String,bitmap:android.graphics.Bitmap){val scanId=UUID.randomUUID().toString();val start=System.currentTimeMillis();val readings=try{BroadsheetProcessor(this).process(bitmap,side,scanId)}catch(e:Exception){runOnUiThread{status.text="OCR ERROR: ${e.message}"};bitmap.recycle();return};bitmap.recycle();lifecycleScope.launch{dao.saveBroadsheet(BroadsheetEntity(side.sheetId,repo.classLabel,repo.subject,repo.templateVersion,side.totalSides,"REVIEW_REQUIRED",System.currentTimeMillis(),"LOCAL_ONLY"));dao.deleteReadingsForSide(side.sideId);dao.saveSide(SheetSideEntity(side.sideId,side.sheetId,side.sideNumber,side.totalSides,side.rowStart,side.rowEnd,System.currentTimeMillis(),original,normalizedPath,"QR_OR_MANUAL"));dao.saveScan(ScanEntity(scanId,side.sideId,"SMART_BROADSHEET",side.sideNumber,System.currentTimeMillis(),original,normalizedPath,"{\"processing_ms\":${System.currentTimeMillis()-start}}"));dao.saveReadings(readings);val count=dao.sideCount(side.sheetId);if(count>=side.totalSides){status.text="BROADSHEET COMPLETE ✓ — REVIEW";startActivity(Intent(this@BroadsheetScannerActivity,BroadsheetReviewActivity::class.java).putExtra("sheetId",side.sheetId))}else status.text="SIDE ${side.sideNumber} SAVED ✓ — FLIP / PRESENT OTHER SIDE"}}
 }
 override fun onError(message:String){runOnUiThread{status.text=message}}
 override fun onDestroy(){super.onDestroy();engine.shutdown();exec.shutdown()}
}
