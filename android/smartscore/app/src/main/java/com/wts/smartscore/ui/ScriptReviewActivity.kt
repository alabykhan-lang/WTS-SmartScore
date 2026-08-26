package com.wts.smartscore.ui
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wts.smartscore.data.SmartScoreDatabase
import com.wts.smartscore.export.ImageZipExporter
import com.wts.smartscore.export.PdfImageExporter
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

class ScriptReviewActivity:AppCompatActivity(){private val dao by lazy{SmartScoreDatabase.get(this).dao()};private val exec=Executors.newSingleThreadExecutor();private lateinit var root:LinearLayout;private var id=""
 override fun onCreate(b:Bundle?){super.onCreate(b);id=intent.getStringExtra("scriptId")?:return finish();root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(18,18,18,18)};setContentView(ScrollView(this).apply{addView(root)})}
 override fun onResume(){super.onResume();load()}
 private fun load(){lifecycleScope.launch{root.removeAllViews();val s=dao.script(id)?:return@launch;root.addView(TextView(this@ScriptReviewActivity).apply{text="${s.studentRef?:"Script"} — ${s.subject?:""}";textSize=23f});val ps=dao.scriptPages(id);ps.forEachIndexed{i,p->val row=LinearLayout(this@ScriptReviewActivity).apply{orientation=LinearLayout.HORIZONTAL};row.addView(ImageView(this@ScriptReviewActivity).apply{setImageBitmap(BitmapFactory.decodeFile(p.normalizedPath?:p.imagePath));scaleType=ImageView.ScaleType.CENTER_CROP},LinearLayout.LayoutParams(160,190));val controls=LinearLayout(this@ScriptReviewActivity).apply{orientation=LinearLayout.VERTICAL};controls.addView(TextView(this@ScriptReviewActivity).apply{text="Page ${i+1}"});controls.addView(Button(this@ScriptReviewActivity).apply{text="DELETE";setOnClickListener{lifecycleScope.launch{dao.deleteScriptPage(p.pageId);dao.scriptPages(this@ScriptReviewActivity.id).forEachIndexed{j,x->dao.setPageNumber(x.pageId,j+1)};load()}}});row.addView(controls);root.addView(row)};root.addView(Button(this@ScriptReviewActivity).apply{text="RESCAN / ADD PAGE";setOnClickListener{startActivity(Intent(this@ScriptReviewActivity,ScriptScannerActivity::class.java).putExtra("scriptId",id))}});root.addView(Button(this@ScriptReviewActivity).apply{text="EXPORT PDF + IMAGE ZIP";setOnClickListener{val paths=ps.map{it.normalizedPath?:it.imagePath};exec.execute{val dir=File(filesDir,"exports");PdfImageExporter.export(File(dir,"script-$id.pdf"),paths);ImageZipExporter.export(File(dir,"script-$id.zip"),paths,"{\"script_id\":\"$id\",\"page_count\":${paths.size}}");runOnUiThread{Toast.makeText(this@ScriptReviewActivity,"Exports refreshed",Toast.LENGTH_LONG).show()}}}})}}
 override fun onDestroy(){super.onDestroy();exec.shutdown()}
}
