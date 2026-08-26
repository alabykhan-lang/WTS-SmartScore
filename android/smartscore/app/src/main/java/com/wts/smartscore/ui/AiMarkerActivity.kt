package com.wts.smartscore.ui
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class AiMarkerActivity:AppCompatActivity(){override fun onCreate(b:Bundle?){super.onCreate(b);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(30,40,30,30)};root.addView(TextView(this).apply{text="AI MARKER";textSize=26f});root.addView(TextView(this).apply{text="AI marking is proposal-only. Select a saved script, question paper and marking scheme. A configured provider is required for a marking request; scripts remain usable without AI.";textSize=16f;setPadding(0,20,0,20)});root.addView(Button(this).apply{text="NO PROVIDER CONFIGURED";isEnabled=false});root.addView(TextView(this).apply{text="Future providers: OpenAI, Gemini, other multimodal provider. API keys are not stored in this APK.";setPadding(0,20,0,0)});setContentView(root)}}
