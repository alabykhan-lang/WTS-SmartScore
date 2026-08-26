package com.wts.smartscore.scanner
import com.wts.smartscore.model.ScanState

class AutoCaptureController(private val requiredStableFrames:Int=8){
 private var stableFrames=0; private var armed=true; private var waitingForExit=false
 var state:ScanState=ScanState.SEARCHING; private set
 fun onFrame(a:FrameAssessment):Boolean{
  if(waitingForExit){ if(a.quad==null || a.coverage<0.18f){ waitingForExit=false;armed=true;state=ScanState.SEARCHING }; return false }
  if(!armed)return false
  state=when{
   a.quad==null -> ScanState.SEARCHING
   a.coverage<0.42f -> ScanState.MOVE_CLOSER
   a.glare>0.18 -> ScanState.ALIGN
   a.blurScore<80 -> ScanState.HOLD_STEADY
   !a.stable -> ScanState.HOLD_STEADY
   else -> ScanState.DOCUMENT_FOUND
  }
  if(state==ScanState.DOCUMENT_FOUND)stableFrames++ else stableFrames=0
  if(stableFrames>=requiredStableFrames){ armed=false;stableFrames=0;state=ScanState.CAPTURING;return true }
  return false
 }
 fun captured(){state=ScanState.SCANNED;waitingForExit=true}
}
