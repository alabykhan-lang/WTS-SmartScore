package com.wts.smartscore.data

import androidx.room.*

@Entity(tableName="broadsheets") data class BroadsheetEntity(@PrimaryKey val sheetId:String,val classLabel:String,val subject:String,val templateVersion:String,val totalSides:Int,val reviewStatus:String,val createdAt:Long,val syncStatus:String="LOCAL_ONLY")
@Entity(tableName="sheet_sides",indices=[Index("sheetId")]) data class SheetSideEntity(@PrimaryKey val sideId:String,val sheetId:String,val sideNumber:Int,val totalSides:Int,val rowStart:Int,val rowEnd:Int,val scanTimestamp:Long,val imagePath:String,val normalizedPath:String?,val identityMethod:String)
@Entity(tableName="scans",indices=[Index("parentId")]) data class ScanEntity(@PrimaryKey val scanId:String,val parentId:String,val mode:String,val pageNumber:Int,val capturedAt:Long,val imagePath:String,val normalizedPath:String?,val qualityJson:String)
@Entity(tableName="score_readings",indices=[Index("sheetId"),Index("studentId")]) data class ScoreReadingEntity(@PrimaryKey val id:String,val sheetId:String,val sideId:String,val scanId:String,val studentId:String,val studentName:String,val assessmentId:String,val maximum:Double,val rawValue:Double?,val reviewedValue:Double?,val confidence:Double,val state:String,val cropPath:String?,val reviewedAt:Long?)
@Entity(tableName="corrections",indices=[Index("readingId")]) data class CorrectionEntity(@PrimaryKey val correctionId:String,val readingId:String,val oldValue:Double?,val newValue:Double?,val reason:String?,val createdAt:Long)
@Entity(tableName="scripts") data class ScriptEntity(@PrimaryKey val scriptId:String,val studentRef:String?,val subject:String?,val testRef:String?,val createdAt:Long,val completedAt:Long?,val completionState:String,val pageCount:Int)
@Entity(tableName="script_pages",indices=[Index("scriptId")]) data class ScriptPageEntity(@PrimaryKey val pageId:String,val scriptId:String,val pageNumber:Int,val imagePath:String,val normalizedPath:String?,val capturedAt:Long)
@Entity(tableName="ai_marks",indices=[Index("scriptId")]) data class AiMarkEntity(@PrimaryKey val markId:String,val scriptId:String,val provider:String,val requestJson:String,val responseJson:String,val proposedTotal:Double?,val reviewRequired:Boolean,val reviewed:Boolean,val createdAt:Long)
@Entity(tableName="exports") data class ExportEntity(@PrimaryKey val exportId:String,val parentId:String,val exportType:String,val filePath:String,val createdAt:Long,val status:String)
