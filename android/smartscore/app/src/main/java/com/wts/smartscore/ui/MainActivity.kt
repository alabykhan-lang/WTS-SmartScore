package com.wts.smartscore.ui
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView

class MainActivity:AppCompatActivity(){
 private val cameraPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);showHome();if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)cameraPermission.launch(Manifest.permission.CAMERA)}
 private fun showHome(){val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(28,42,28,28);setBackgroundColor(0xFFF4F7FA.toInt())};root.addView(TextView(this).apply{text="WTS SMARTSCORE";textSize=28f;setTextColor(0xFF17324D.toInt())});root.addView(TextView(this).apply{text="Private scanning & marking workspace";textSize=14f;setPadding(0,0,0,24)});root.addView(card("SMART BROADSHEET","Scan and digitize handwritten class scores"){startActivity(Intent(this,BroadsheetScannerActivity::class.java))});root.addView(card("SCRIPT SCANNER","Digitize a complete student answer script"){startActivity(Intent(this,ScriptScannerActivity::class.java))});root.addView(card("AI MARKER","Mark a digitized script with a configured AI provider"){startActivity(Intent(this,AiMarkerActivity::class.java))});root.addView(card("GENERAL DOCUMENT SCANNER","Scan ordinary multipage documents without QR"){startActivity(Intent(this,GeneralScannerActivity::class.java))});root.addView(card("SAVED BROADSHEETS","Local batches and review state"){Toast.makeText(this,"Saved broadsheet browser is backed by Room",Toast.LENGTH_SHORT).show()});root.addView(card("SAVED SCRIPTS","Local script packages"){Toast.makeText(this,"Saved script browser is backed by Room",Toast.LENGTH_SHORT).show()});setContentView(ScrollView(this).apply{addView(root)})}
 private fun card(title:String,sub:String,onClick:()->Unit):MaterialCardView{val c=MaterialCardView(this);c.radius=22f;c.cardElevation=3f;c.setContentPadding(22,20,22,20);val v=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(TextView(this@MainActivity).apply{text=title;textSize=18f;setTextColor(0xFF17324D.toInt())});addView(TextView(this@MainActivity).apply{text=sub;textSize=13f;setPadding(0,6,0,0)})};c.addView(v);c.setOnClickListener{onClick()};c.layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{bottomMargin=16};return c}
}
