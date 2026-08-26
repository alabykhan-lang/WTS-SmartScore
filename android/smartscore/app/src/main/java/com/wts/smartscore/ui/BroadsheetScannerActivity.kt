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
import com.wts.smartscore.scanner.*

class BroadsheetScannerActivity:AppCompatActivity(),SmartScanEngine.Listener{
 private lateinit var preview:PreviewView;private lateinit var status:TextView;private lateinit var engine:SmartScanEngine
 override fun onCreate(b:Bundle?){super.onCreate(b);if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.CAMERA),51);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};status=TextView(this).apply{text="PRESENT SMART BROADSHEET";gravity=Gravity.CENTER;textSize=18f;setPadding(10,20,10,20)};root.addView(status);preview=PreviewView(this);root.addView(preview,LinearLayout.LayoutParams(-1,0,1f));root.addView(TextView(this).apply{text="QR is optional. If identity cannot be resolved, the saved scan remains available for manual sheet/template selection.";setPadding(18,14,18,14)});setContentView(root);engine=SmartScanEngine(this).also{it.listener=this};startCamera()}
 private fun startCamera(){val f=ProcessCameraProvider.getInstance(this);f.addListener({val p=f.get();val pv=Preview.Builder().build().also{it.setSurfaceProvider(preview.surfaceProvider)};engine.bind(p,this,pv,ImageAnalysis.Analyzer{image->try{val m=ImageProxyTools.lumaMat(image);val a=OpenCvDocumentDetector().detect(m);m.release();engine.submitAssessment(a)}finally{image.close()}})},ContextCompat.getMainExecutor(this))}
 override fun onState(state:String){runOnUiThread{status.text=state}}
 override fun onCaptured(path:String){runOnUiThread{status.text="SIDE SAVED - REMOVE / FLIP SHEET";Toast.makeText(this,"Saved locally; recognition/review pipeline will use cached template",Toast.LENGTH_LONG).show()}}
 override fun onError(message:String){runOnUiThread{status.text=message}}
}
