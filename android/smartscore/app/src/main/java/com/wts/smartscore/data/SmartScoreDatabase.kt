package com.wts.smartscore.data
import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities=[BroadsheetEntity::class,SheetSideEntity::class,ScanEntity::class,ScoreReadingEntity::class,CorrectionEntity::class,ScriptEntity::class,ScriptPageEntity::class,AiMarkEntity::class,ExportEntity::class,ProcessingTaskEntity::class],version=3,exportSchema=true)
abstract class SmartScoreDatabase:RoomDatabase(){ abstract fun dao():SmartScoreDao
 companion object { @Volatile private var INSTANCE:SmartScoreDatabase?=null
  private val MIGRATION_1_2=object:Migration(1,2){override fun migrate(db:SupportSQLiteDatabase){
   db.execSQL("ALTER TABLE broadsheets ADD COLUMN layoutFamily TEXT NOT NULL DEFAULT 'SECONDARY_SINGLE_SUBJECT'")
   db.execSQL("ALTER TABLE broadsheets ADD COLUMN manifestPath TEXT")
   db.execSQL("ALTER TABLE sheet_sides ADD COLUMN layoutId TEXT NOT NULL DEFAULT 'LEGACY'")
   db.execSQL("ALTER TABLE sheet_sides ADD COLUMN subjectGroup TEXT")
   db.execSQL("ALTER TABLE sheet_sides ADD COLUMN templateVersion TEXT")
  }}
  private val MIGRATION_2_3=object:Migration(2,3){override fun migrate(db:SupportSQLiteDatabase){
   db.execSQL("ALTER TABLE broadsheets ADD COLUMN term TEXT NOT NULL DEFAULT 'FIRST'")
   db.execSQL("ALTER TABLE broadsheets ADD COLUMN documentType TEXT NOT NULL DEFAULT 'SMART_TEMPLATE'")
   db.execSQL("ALTER TABLE broadsheets ADD COLUMN identityConfidence REAL NOT NULL DEFAULT 0.0")
   db.execSQL("ALTER TABLE broadsheets ADD COLUMN pageCount INTEGER NOT NULL DEFAULT 0")
   db.execSQL("ALTER TABLE broadsheets ADD COLUMN recognizedCount INTEGER NOT NULL DEFAULT 0")
   db.execSQL("ALTER TABLE broadsheets ADD COLUMN reviewCount INTEGER NOT NULL DEFAULT 0")
   db.execSQL("ALTER TABLE broadsheets ADD COLUMN lastUpdatedAt INTEGER NOT NULL DEFAULT 0")
   db.execSQL("ALTER TABLE sheet_sides ADD COLUMN pageState TEXT NOT NULL DEFAULT 'SCANNED'")
   db.execSQL("ALTER TABLE sheet_sides ADD COLUMN identityConfidence REAL NOT NULL DEFAULT 0.0")
   db.execSQL("ALTER TABLE sheet_sides ADD COLUMN identityJson TEXT")
   db.execSQL("ALTER TABLE sheet_sides ADD COLUMN sessionId TEXT DEFAULT ''")
   db.execSQL("ALTER TABLE score_readings ADD COLUMN recognizedText TEXT")
   db.execSQL("ALTER TABLE score_readings ADD COLUMN digitDetailsJson TEXT")
   db.execSQL("ALTER TABLE scripts ADD COLUMN sessionId TEXT DEFAULT 'LOCAL'")
   db.execSQL("ALTER TABLE scripts ADD COLUMN identityStatus TEXT NOT NULL DEFAULT 'UNIDENTIFIED'")
   db.execSQL("ALTER TABLE scripts ADD COLUMN identityConfidence REAL NOT NULL DEFAULT 0.0")
   db.execSQL("ALTER TABLE script_pages ADD COLUMN analysisState TEXT NOT NULL DEFAULT 'PENDING'")
   db.execSQL("ALTER TABLE script_pages ADD COLUMN pageClass TEXT NOT NULL DEFAULT 'UNKNOWN'")
   db.execSQL("ALTER TABLE script_pages ADD COLUMN boundaryScore REAL NOT NULL DEFAULT 0.0")
   db.execSQL("ALTER TABLE script_pages ADD COLUMN identityJson TEXT")
   db.execSQL("ALTER TABLE script_pages ADD COLUMN ocrText TEXT NOT NULL DEFAULT ''")
   db.execSQL("ALTER TABLE script_pages ADD COLUMN sessionId TEXT")
   db.execSQL("CREATE TABLE IF NOT EXISTS processing_tasks (taskId TEXT NOT NULL, taskType TEXT NOT NULL, parentId TEXT NOT NULL, payloadJson TEXT, status TEXT NOT NULL, attempts INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, lastError TEXT, PRIMARY KEY(taskId))")
   db.execSQL("CREATE INDEX IF NOT EXISTS index_processing_tasks_status_createdAt ON processing_tasks(status, createdAt)")
   db.execSQL("CREATE INDEX IF NOT EXISTS index_script_pages_sessionId ON script_pages(sessionId)")
  }}
  fun get(context:Context)=INSTANCE?: synchronized(this){ INSTANCE?:Room.databaseBuilder(context.applicationContext,SmartScoreDatabase::class.java,"smartscore.db").addMigrations(MIGRATION_1_2,MIGRATION_2_3).build().also{INSTANCE=it} }
 }
}
