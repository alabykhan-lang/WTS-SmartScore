package com.wts.smartscore.data

import androidx.room.*

@Entity(tableName="broadsheets") data class BroadsheetEntity(
    @PrimaryKey val sheetId:String,
    val classLabel:String,
    val subject:String,
    val templateVersion:String,
    @ColumnInfo(name="totalSides") val expectedPageCount:Int,
    val reviewStatus:String,
    val createdAt:Long,
    val syncStatus:String="LOCAL_ONLY",
    @ColumnInfo(defaultValue="'SECONDARY_SINGLE_SUBJECT'") val layoutFamily:String="SECONDARY_SINGLE_SUBJECT",
    val manifestPath:String?=null,
    @ColumnInfo(defaultValue="'FIRST'") val term:String="FIRST",
    @ColumnInfo(defaultValue="'SMART_TEMPLATE'") val documentType:String="SMART_TEMPLATE",
    @ColumnInfo(defaultValue="0.0") val identityConfidence:Double=0.0,
    @ColumnInfo(defaultValue="0") val pageCount:Int=0,
    @ColumnInfo(defaultValue="0") val recognizedCount:Int=0,
    @ColumnInfo(defaultValue="0") val reviewCount:Int=0,
    @ColumnInfo(defaultValue="0") val lastUpdatedAt:Long=createdAt
)

/**
 * The database table retains its v1 name for migration compatibility. In the
 * product model every row is now a physical Smart Broadsheet page/segment.
 */
@Entity(tableName="sheet_sides",indices=[Index("sheetId")]) data class SheetSideEntity(
    @PrimaryKey val sideId:String,
    val sheetId:String,
    val sideNumber:Int,
    val totalSides:Int,
    val rowStart:Int,
    val rowEnd:Int,
    val scanTimestamp:Long,
    val imagePath:String,
    val normalizedPath:String?,
    val identityMethod:String,
    @ColumnInfo(defaultValue="'LEGACY'") val layoutId:String="LEGACY",
    val subjectGroup:String?=null,
    val templateVersion:String?=null,
    @ColumnInfo(defaultValue="'SCANNED'") val pageState:String="SCANNED",
    @ColumnInfo(defaultValue="0.0") val identityConfidence:Double=0.0,
    val identityJson:String?=null,
    @ColumnInfo(defaultValue="''") val extractionJson:String?=null,
    @ColumnInfo(defaultValue="''") val sessionId:String?=null
)
@Entity(tableName="scans",indices=[Index("parentId")]) data class ScanEntity(@PrimaryKey val scanId:String,val parentId:String,val mode:String,val pageNumber:Int,val capturedAt:Long,val imagePath:String,val normalizedPath:String?,val qualityJson:String)
@Entity(tableName="score_readings",indices=[Index("sheetId"),Index("studentId")]) data class ScoreReadingEntity(@PrimaryKey val id:String,val sheetId:String,val sideId:String,val scanId:String,val studentId:String,val studentName:String,val assessmentId:String,val maximum:Double,val rawValue:Double?,val reviewedValue:Double?,val confidence:Double,val state:String,val cropPath:String?,val reviewedAt:Long?,val recognizedText:String?=null,val digitDetailsJson:String?=null)
@Entity(tableName="corrections",indices=[Index("readingId")]) data class CorrectionEntity(@PrimaryKey val correctionId:String,val readingId:String,val oldValue:Double?,val newValue:Double?,val reason:String?,val createdAt:Long)
@Entity(tableName="scripts") data class ScriptEntity(@PrimaryKey val scriptId:String,val studentRef:String?,val subject:String?,val testRef:String?,val createdAt:Long,val completedAt:Long?,val completionState:String,val pageCount:Int,@ColumnInfo(defaultValue="'LOCAL'") val sessionId:String?=null,@ColumnInfo(defaultValue="'UNIDENTIFIED'") val identityStatus:String="UNIDENTIFIED",@ColumnInfo(defaultValue="0.0") val identityConfidence:Double=0.0)
@Entity(tableName="script_pages",indices=[Index("scriptId"),Index("sessionId")]) data class ScriptPageEntity(@PrimaryKey val pageId:String,val scriptId:String,val pageNumber:Int,val imagePath:String,val normalizedPath:String?,val capturedAt:Long,@ColumnInfo(defaultValue="'PENDING'") val analysisState:String="PENDING",@ColumnInfo(defaultValue="'UNKNOWN'") val pageClass:String="UNKNOWN",@ColumnInfo(defaultValue="0.0") val boundaryScore:Double=0.0,val identityJson:String?=null,@ColumnInfo(defaultValue="''") val ocrText:String="",val sessionId:String?=null)
@Entity(tableName="ai_marks",indices=[Index("scriptId")]) data class AiMarkEntity(@PrimaryKey val markId:String,val scriptId:String,val provider:String,val requestJson:String,val responseJson:String,val proposedTotal:Double?,val reviewRequired:Boolean,val reviewed:Boolean,val createdAt:Long)
@Entity(tableName="exports") data class ExportEntity(@PrimaryKey val exportId:String,val parentId:String,val exportType:String,val filePath:String,val createdAt:Long,val status:String)
@Entity(tableName="processing_tasks",indices=[Index(value=["status","createdAt"])]) data class ProcessingTaskEntity(@PrimaryKey val taskId:String,val taskType:String,val parentId:String,val payloadJson:String?,val status:String,val attempts:Int,val createdAt:Long,val updatedAt:Long,val lastError:String?=null)
