package com.wts.smartscore.ui
import android.Manifest
import android.content.pm.PackageManager
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
import com.wts.smartscore.scanner.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class ScriptScannerActivity:AppCompatActivity(),SmartScanEngine.Listener{
 private lateinit var preview:PreviewView;private lateinit var status:TextView;private lateinit var engine:SmartScanEngine;private val dao by lazy{SmartScoreDatabase.get(this).dao()};private val scriptId=UUID.randomUUID().toString();private var page=0
 override fun onCreate(b:Bundle?){super.onCreate(b);if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.CAMERA),41);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};status=TextView(this).apply{text="START SCRIPT - PRESENT PAGE 1";gravity=Gravity.CENTER;textSize=17f;setPadding(12,18,12,18)};root.addView(status);preview=PreviewView(this);root.addView(preview,LinearLayout.LayoutParams(-1,0,1f));root.addView(Button(this).apply{text="DONE";setOnClickListener{finishScript()}});setContentView(root);lifecycleScope.launch{dao.saveScript(ScriptEntity(scriptId,null,null,null,System.currentTimeMillis(),null,"IN_PROGRESS",0))};engine=SmartScanEngine(this).also{it.listener=this};startCamera()}
 private fun startCamera(){val f=ProcessCameraProvider.getInstance(this);f.addListener({val p=f.get();val pv=Preview.Builder().build().also{it.setSurfaceProvider(preview.surfaceProvider)};engine.bind(p,this,pv,ImageAnalysis.Analyzer{image->try{val m=ImageProxyTools.lumaMat(image);val a=OpenCvDocumentDetector().detect(m);m.release();engine.submitAssessment(a)}finally{image.close()}})},ContextCompat.getMainExecutor(this))}
 override fun onState(state:String){runOnUiThread{status.text=state}}
 override fun onCaptured(path:String){page++;val n=page;lifecycleScope.launch{dao.saveScriptPage(ScriptPageEntity(UUID.randomUUID().toString(),scriptId,n,path,null,System.currentTimeMillis()));dao.saveScript(ScriptEntity(scriptId,null,null,null,System.currentTimeMillis(),null,"IN_PROGRESS",n))};runOnUiThread{status.text="PAGE $n SAVED - TURN PAGE"}}
 override fun onError(message:String){runOnUiThread{status.text=message}}
 private fun finishScript(){lifecycleScope.launch{dao.saveScript(ScriptEntity(scriptId,null,null,null,System.currentTimeMillis(),System.currentTimeMillis(),"COMPLETE",page));Toast.makeText(this@ScriptScannerActivity,"Script saved with $page pages",Toast.LENGTH_LONG).show();finish()}}
}
