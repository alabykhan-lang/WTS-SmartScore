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
 @Query("SELECT * FROM broadsheets ORDER BY createdAt DESC") suspend fun broadsheetsNow():List<BroadsheetEntity>
 @Query("SELECT * FROM broadsheets WHERE sheetId=:sheetId LIMIT 1") suspend fun broadsheet(sheetId:String):BroadsheetEntity?
 @Query("SELECT * FROM sheet_sides WHERE sheetId=:sheetId ORDER BY sideNumber") suspend fun sides(sheetId:String):List<SheetSideEntity>
 @Query("SELECT COUNT(*) FROM sheet_sides WHERE sheetId=:sheetId") suspend fun sideCount(sheetId:String):Int
 @Query("SELECT * FROM score_readings WHERE sheetId=:sheetId ORDER BY studentName,assessmentId") suspend fun readings(sheetId:String):List<ScoreReadingEntity>
 @Query("DELETE FROM score_readings WHERE sideId=:sideId") suspend fun deleteReadingsForSide(sideId:String)
 @Query("SELECT * FROM scripts ORDER BY createdAt DESC") fun scripts():Flow<List<ScriptEntity>>
 @Query("SELECT * FROM scripts ORDER BY createdAt DESC") suspend fun scriptsNow():List<ScriptEntity>
 @Query("SELECT * FROM scripts WHERE scriptId=:scriptId LIMIT 1") suspend fun script(scriptId:String):ScriptEntity?
 @Query("SELECT * FROM script_pages WHERE scriptId=:scriptId ORDER BY pageNumber") suspend fun scriptPages(scriptId:String):List<ScriptPageEntity>
 @Query("DELETE FROM script_pages WHERE pageId=:pageId") suspend fun deleteScriptPage(pageId:String)
 @Query("UPDATE script_pages SET pageNumber=:pageNumber WHERE pageId=:pageId") suspend fun setPageNumber(pageId:String,pageNumber:Int)
 @Query("UPDATE score_readings SET reviewedValue=:value,state='MANUALLY_CORRECTED',reviewedAt=:now WHERE id=:id") suspend fun correctReading(id:String,value:Double?,now:Long)
}
