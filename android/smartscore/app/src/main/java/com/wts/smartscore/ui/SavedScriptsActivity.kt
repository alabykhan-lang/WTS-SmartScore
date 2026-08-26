package com.wts.smartscore.ui
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wts.smartscore.data.SmartScoreDatabase
import kotlinx.coroutines.launch

class SavedScriptsActivity:AppCompatActivity(){private val dao by lazy{SmartScoreDatabase.get(this).dao()};private lateinit var root:LinearLayout
 override fun onCreate(b:Bundle?){super.onCreate(b);root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,25,20,20)};root.addView(TextView(this).apply{text="SAVED SCRIPTS";textSize=25f});setContentView(ScrollView(this).apply{addView(root)})}
 override fun onResume(){super.onResume();load()}
 private fun load(){lifecycleScope.launch{val all=dao.scriptsNow();while(root.childCount>1)root.removeViewAt(1);if(all.isEmpty())root.addView(TextView(this@SavedScriptsActivity).apply{text="No saved scripts yet."});all.forEach{s->root.addView(Button(this@SavedScriptsActivity).apply{text="${s.studentRef?:"Unnamed script"} • ${s.subject?:"No subject"} • ${s.pageCount} page(s) • ${s.completionState}";setOnClickListener{startActivity(Intent(this@SavedScriptsActivity,ScriptReviewActivity::class.java).putExtra("scriptId",s.scriptId))}})}}}
}
