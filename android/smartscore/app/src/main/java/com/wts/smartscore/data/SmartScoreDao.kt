package com.wts.smartscore.data
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface SmartScoreDao {
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveBroadsheet(v:BroadsheetEntity)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveSide(v:SheetSideEntity)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveScan(v:ScanEntity)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveReadings(v:List<ScoreReadingEntity>)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveCorrection(v:CorrectionEntity)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveScript(v:ScriptEntity)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveScriptPage(v:ScriptPageEntity)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveAiMark(v:AiMarkEntity)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveExport(v:ExportEntity)
 @Query("SELECT * FROM broadsheets ORDER BY createdAt DESC") fun broadsheets():Flow<List<BroadsheetEntity>>
 @Query("SELECT * FROM score_readings WHERE sheetId=:sheetId ORDER BY studentName,assessmentId") suspend fun readings(sheetId:String):List<ScoreReadingEntity>
 @Query("SELECT * FROM scripts ORDER BY createdAt DESC") fun scripts():Flow<List<ScriptEntity>>
 @Query("SELECT * FROM script_pages WHERE scriptId=:scriptId ORDER BY pageNumber") suspend fun scriptPages(scriptId:String):List<ScriptPageEntity>
 @Query("UPDATE score_readings SET reviewedValue=:value,state='MANUALLY_CORRECTED',reviewedAt=:now WHERE id=:id") suspend fun correctReading(id:String,value:Double?,now:Long)
}
