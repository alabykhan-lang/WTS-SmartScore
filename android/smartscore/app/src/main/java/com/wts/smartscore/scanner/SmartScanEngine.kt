package com.wts.smartscore.scanner

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class SmartScanEngine(private val context:Context){
 private val analysisExecutor=Executors.newSingleThreadExecutor(); private val captureExecutor=Executors.newSingleThreadExecutor(); private val auto=AutoCaptureController(); private var imageCapture:ImageCapture?=null
 var listener:Listener?=null
 interface Listener{fun onState(state:String);fun onCaptured(path:String);fun onError(message:String)}
 fun bind(provider:ProcessCameraProvider,lifecycle:LifecycleOwner,preview:Preview,analyzer:ImageAnalysis.Analyzer){
  imageCapture=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build(); val analysis=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also{it.setAnalyzer(analysisExecutor,analyzer)}
  provider.unbindAll();provider.bindToLifecycle(lifecycle,CameraSelector.DEFAULT_BACK_CAMERA,preview,imageCapture,analysis)
 }
 fun submitAssessment(a:FrameAssessment){ listener?.onState(auto.state.name.replace('_',' ')); if(auto.onFrame(a)) captureNow() }
 private fun captureNow(){ val c=imageCapture?:return; val dir=File(context.filesDir,"captures").apply{mkdirs()}; val file=File(dir,"${UUID.randomUUID()}.jpg"); c.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),captureExecutor,object:ImageCapture.OnImageSavedCallback{
   override fun onImageSaved(r:ImageCapture.OutputFileResults){beep();auto.captured();listener?.onState("SCANNED");listener?.onCaptured(file.absolutePath)}
   override fun onError(e:ImageCaptureException){listener?.onError(e.message?:"Capture failed")}
  }) }
 private fun beep(){ ToneGenerator(AudioManager.STREAM_NOTIFICATION,85).startTone(ToneGenerator.TONE_PROP_BEEP,130); (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(VibrationEffect.createOneShot(90,VibrationEffect.DEFAULT_AMPLITUDE)) }
}
