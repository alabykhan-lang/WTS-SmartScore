package com.wts.smartscore.ui
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wts.smartscore.data.SmartScoreDatabase
import kotlinx.coroutines.launch

class AiMarkerActivity:AppCompatActivity(){private val dao by lazy{SmartScoreDatabase.get(this).dao()};private lateinit var scripts:Spinner;private lateinit var q:TextView;private lateinit var scheme:TextView;private var qUri:Uri?=null;private var sUri:Uri?=null;private val pickQ=registerForActivityResult(ActivityResultContracts.OpenDocument()){u->if(u!=null){qUri=u;q.text="Question paper selected"}};private val pickS=registerForActivityResult(ActivityResultContracts.OpenDocument()){u->if(u!=null){sUri=u;scheme.text="Marking scheme selected"}}
 override fun onCreate(b:Bundle?){super.onCreate(b);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(30,40,30,30)};root.addView(TextView(this).apply{text="AI MARKER";textSize=26f});root.addView(TextView(this).apply{text="AI marking is proposal-only. Script scanning remains fully available without an AI provider.";textSize=15f;setPadding(0,12,0,18)});scripts=Spinner(this);root.addView(scripts);q=TextView(this).apply{text="No question paper selected"};scheme=TextView(this).apply{text="No marking scheme selected"};root.addView(Button(this).apply{text="SELECT QUESTION PAPER";setOnClickListener{pickQ.launch(arrayOf("application/pdf","image/*"))}});root.addView(q);root.addView(Button(this).apply{text="SELECT MARKING SCHEME";setOnClickListener{pickS.launch(arrayOf("application/pdf","image/*"))}});root.addView(scheme);root.addView(Button(this).apply{text="AI PROVIDER NOT CONFIGURED";setOnClickListener{Toast.makeText(this@AiMarkerActivity,"AI provider not configured. No credentials are embedded in SmartScore.",Toast.LENGTH_LONG).show()}});setContentView(root);lifecycleScope.launch{val all=dao.scriptsNow();scripts.adapter=ArrayAdapter(this@AiMarkerActivity,android.R.layout.simple_spinner_dropdown_item,if(all.isEmpty())listOf("No saved scripts") else all.map{"${it.studentRef?:it.scriptId} — ${it.subject?:""}"})}}
}
