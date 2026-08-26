package com.wts.smartscore.scanner
import com.wts.smartscore.model.*

class BroadsheetRecognition {
 data class DigitGuess(val value:Int?,val confidence:Double,val blank:Boolean=false)
 interface NumericRecognizer { fun recognize(cropPath:String):DigitGuess }
 fun classify(guess:DigitGuess,maximum:Double):Pair<Double?,ReadingState>{
  if(guess.blank)return null to ReadingState.BLANK
  val v=guess.value?.toDouble()?:return null to ReadingState.UNREADABLE
  if(v<0 || v>maximum)return v to ReadingState.INVALID
  return if(guess.confidence>=0.90) v to ReadingState.CONFIRMED else v to ReadingState.REVIEW_REQUIRED
 }
}
