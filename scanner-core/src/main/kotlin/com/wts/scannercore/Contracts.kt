package com.wts.scannercore

enum class ScannerStatus { SEARCHING, DOCUMENT_FOUND, ALIGN, MOVE_CLOSER, HOLD_STEADY, CAPTURING, SCANNED, WAITING_FOR_EXIT }
data class NormalizedPage(val scanId:String,val originalPath:String,val normalizedPath:String,val width:Int,val height:Int,val capturedAt:Long)
interface DocumentScanner { suspend fun normalize(sourcePath:String):NormalizedPage }
interface PageIdentityResolver { suspend fun resolve(page:NormalizedPage):PageIdentity? }
data class PageIdentity(val sheetId:String?,val sideId:String?,val method:String,val confidence:Double)
