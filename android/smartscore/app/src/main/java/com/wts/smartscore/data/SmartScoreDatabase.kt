package com.wts.smartscore.data
import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities=[BroadsheetEntity::class,SheetSideEntity::class,ScanEntity::class,ScoreReadingEntity::class,CorrectionEntity::class,ScriptEntity::class,ScriptPageEntity::class,AiMarkEntity::class,ExportEntity::class],version=2,exportSchema=true)
abstract class SmartScoreDatabase:RoomDatabase(){ abstract fun dao():SmartScoreDao
 companion object { @Volatile private var INSTANCE:SmartScoreDatabase?=null
  private val MIGRATION_1_2=object:Migration(1,2){override fun migrate(db:SupportSQLiteDatabase){
   db.execSQL("ALTER TABLE broadsheets ADD COLUMN layoutFamily TEXT NOT NULL DEFAULT 'SECONDARY_SINGLE_SUBJECT'")
   db.execSQL("ALTER TABLE broadsheets ADD COLUMN manifestPath TEXT")
   db.execSQL("ALTER TABLE sheet_sides ADD COLUMN layoutId TEXT NOT NULL DEFAULT 'LEGACY'")
   db.execSQL("ALTER TABLE sheet_sides ADD COLUMN subjectGroup TEXT")
   db.execSQL("ALTER TABLE sheet_sides ADD COLUMN templateVersion TEXT")
  }}
  fun get(context:Context)=INSTANCE?: synchronized(this){ INSTANCE?:Room.databaseBuilder(context.applicationContext,SmartScoreDatabase::class.java,"smartscore.db").addMigrations(MIGRATION_1_2).build().also{INSTANCE=it} }
 }
}
