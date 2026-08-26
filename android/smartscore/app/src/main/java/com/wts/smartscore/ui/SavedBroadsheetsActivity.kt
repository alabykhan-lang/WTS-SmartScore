package com.wts.smartscore.ui
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wts.smartscore.data.SmartScoreDatabase
import kotlinx.coroutines.launch

class SavedBroadsheetsActivity:AppCompatActivity(){private val dao by lazy{SmartScoreDatabase.get(this).dao()};private lateinit var root:LinearLayout
 override fun onCreate(b:Bundle?){super.onCreate(b);root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,25,20,20)};root.addView(TextView(this).apply{text="SAVED BROADSHEETS";textSize=25f});setContentView(ScrollView(this).apply{addView(root)})}
 override fun onResume(){super.onResume();lifecycleScope.launch{while(root.childCount>1)root.removeViewAt(1);val all=dao.broadsheetsNow();if(all.isEmpty())root.addView(TextView(this@SavedBroadsheetsActivity).apply{text="No saved broadsheets yet."});all.forEach{b->val sides=dao.sideCount(b.sheetId);root.addView(Button(this@SavedBroadsheetsActivity).apply{text="${b.classLabel} • ${b.subject} • $sides/${b.totalSides} side(s)";setOnClickListener{startActivity(Intent(this@SavedBroadsheetsActivity,BroadsheetReviewActivity::class.java).putExtra("sheetId",b.sheetId))}})}}}
}
