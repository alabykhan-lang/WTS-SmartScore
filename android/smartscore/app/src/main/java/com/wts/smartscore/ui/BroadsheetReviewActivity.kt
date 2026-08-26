package com.wts.smartscore.ui
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wts.smartscore.data.*
import com.wts.smartscore.export.CsvScoreExporter
import com.wts.smartscore.export.JsonScoreExporter
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class BroadsheetReviewActivity:AppCompatActivity(){private val dao by lazy{SmartScoreDatabase.get(this).dao()};private val exec=Executors.newSingleThreadExecutor();private lateinit var root:LinearLayout;private var sheetId=""
 override fun onCreate(b:Bundle?){super.onCreate(b);sheetId=intent.getStringExtra("sheetId")?:return finish();root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,16,16,16)};setContentView(ScrollView(this).apply{addView(root)})}
 override fun onResume(){super.onResume();load()}
 private fun load(){lifecycleScope.launch{root.removeAllViews();val sheet=dao.broadsheet(sheetId)?:return@launch;root.addView(TextView(this@BroadsheetReviewActivity).apply{text="${sheet.classLabel} — ${sheet.subject}";textSize=23f});val rows=dao.readings(sheetId);rows.groupBy{it.studentId}.forEach{(_,rs)->val line=LinearLayout(this@BroadsheetReviewActivity).apply{orientation=LinearLayout.HORIZONTAL};line.addView(TextView(this@BroadsheetReviewActivity).apply{text=rs.first().studentName;setPadding(4,8,12,8)},LinearLayout.LayoutParams(0,-2,1.6f));rs.forEach{r->line.addView(Button(this@BroadsheetReviewActivity).apply{text="${r.assessmentId.uppercase()}\n${r.reviewedValue?.toInt()?:if(r.state=="BLANK")"—" else "?"}";isAllCaps=false;setOnClickListener{edit(r)}},LinearLayout.LayoutParams(0,-2,1f))};root.addView(line)};root.addView(Button(this@BroadsheetReviewActivity).apply{text="EXPORT JSON + CSV";setOnClickListener{export(rows)}})}}
 private fun edit(r:ScoreReadingEntity){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,10,20,10)};if(r.cropPath!=null)box.addView(ImageView(this).apply{setImageBitmap(BitmapFactory.decodeFile(r.cropPath));adjustViewBounds=true;maxHeight=220});box.addView(TextView(this).apply{text="${r.studentName} • ${r.assessmentId.uppercase()} /${r.maximum.toInt()}\nDetected: ${r.rawValue?:"?"} • confidence ${"%.2f".format(r.confidence)} • ${r.state}"});val input=EditText(this).apply{inputType=2;setText(r.reviewedValue?.toInt()?.toString()?:"");hint="Correct score"};box.addView(input);AlertDialog.Builder(this).setTitle("Review score").setView(box).setPositiveButton("SAVE"){_,_->val v=input.text.toString().toDoubleOrNull();lifecycleScope.launch{dao.correctReading(r.id,v,System.currentTimeMillis());dao.saveCorrection(CorrectionEntity(UUID.randomUUID().toString(),r.id,r.reviewedValue,v,"Manual review",System.currentTimeMillis()));load()}}.setNegativeButton("Cancel",null).show()}
 private fun export(rows:List<ScoreReadingEntity>){exec.execute{val d=File(filesDir,"exports");val json=File(d,"$sheetId.json");val csv=File(d,"$sheetId.csv");JsonScoreExporter.export(json,sheetId,rows);CsvScoreExporter.export(csv,rows);runOnUiThread{Toast.makeText(this,"Saved ${json.name} and ${csv.name}",Toast.LENGTH_LONG).show()}}}
 override fun onDestroy(){super.onDestroy();exec.shutdown()}
}
