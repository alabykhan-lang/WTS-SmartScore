package com.wts.smartscore.model

enum class ScanMode { SMART_BROADSHEET, SCRIPT, GENERAL_DOCUMENT }
enum class ScanState { SEARCHING, DOCUMENT_FOUND, ALIGN, MOVE_CLOSER, MOVE_BACK, HOLD_STEADY, CAPTURING, SCANNED, WAITING_FOR_PAGE_EXIT }
enum class ReadingState { CONFIRMED, REVIEW_REQUIRED, INVALID, BLANK, UNREADABLE, MANUALLY_CORRECTED }

data class Assessment(val id:String,val label:String,val maximum:Double,val order:Int)
data class Roi(val assessmentId:String,val x:Double,val y:Double,val width:Double,val height:Double)
data class StudentRow(val rowNo:Int,val studentId:String,val studentName:String,val rois:List<Roi>)
data class SheetPage(val sheetId:String,val pageId:String,val pageNumber:Int,val expectedPageCount:Int?,val rowStart:Int,val rowEnd:Int,val templateVersion:String,val layoutId:String,val layoutFamily:String,val subjectGroup:String,val rows:List<StudentRow>)
typealias SheetSide = SheetPage
data class ScoreReading(val studentId:String,val assessmentId:String,val rawValue:Double?,val reviewedValue:Double?,val confidence:Double,val state:ReadingState,val cropPath:String?)
data class AiQuestionMark(val question:String,val maximum:Double,val awarded:Double,val reason:String,val confidence:Double,val reviewRequired:Boolean)
data class AiMarkProposal(val scriptId:String,val questions:List<AiQuestionMark>,val proposedTotal:Double,val reviewRequired:Boolean,val provider:String)
