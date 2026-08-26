package com.wts.smartscore.data
import android.content.Context
import androidx.room.*

@Database(entities=[BroadsheetEntity::class,SheetSideEntity::class,ScanEntity::class,ScoreReadingEntity::class,CorrectionEntity::class,ScriptEntity::class,ScriptPageEntity::class,AiMarkEntity::class,ExportEntity::class],version=1,exportSchema=true)
abstract class SmartScoreDatabase:RoomDatabase(){ abstract fun dao():SmartScoreDao
 companion object { @Volatile private var INSTANCE:SmartScoreDatabase?=null
  fun get(context:Context)=INSTANCE?: synchronized(this){ INSTANCE?:Room.databaseBuilder(context.applicationContext,SmartScoreDatabase::class.java,"smartscore.db").build().also{INSTANCE=it} }
 }
}
