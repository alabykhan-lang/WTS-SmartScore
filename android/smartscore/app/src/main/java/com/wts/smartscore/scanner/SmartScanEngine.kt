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
import java.util.concurrent.atomic.AtomicBoolean

class SmartScanEngine(private val context:Context){
 private val analysisExecutor=Executors.newSingleThreadExecutor(); private val captureExecutor=Executors.newSingleThreadExecutor(); private val auto=AutoCaptureController(); private var imageCapture:ImageCapture?=null
 private val capturing=AtomicBoolean(false)
 var listener:Listener?=null
 interface Listener{fun onState(state:String);fun onCaptured(path:String);fun onError(message:String)}
 fun bind(provider:ProcessCameraProvider,lifecycle:LifecycleOwner,preview:Preview,analyzer:ImageAnalysis.Analyzer){
  imageCapture=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setJpegQuality(96).build()
  val analysis=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888).build().also{it.setAnalyzer(analysisExecutor,analyzer)}
  provider.unbindAll();provider.bindToLifecycle(lifecycle,CameraSelector.DEFAULT_BACK_CAMERA,preview,imageCapture,analysis)
 }
 fun submitAssessment(a:FrameAssessment){listener?.onState(auto.state.name.replace('_',' '));if(auto.onFrame(a))captureNow()}
 private fun captureNow(){if(!capturing.compareAndSet(false,true))return;val c=imageCapture?:run{capturing.set(false);return};listener?.onState("CAPTURING");val dir=File(context.filesDir,"captures").apply{mkdirs()};val file=File(dir,"${UUID.randomUUID()}.jpg");c.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),captureExecutor,object:ImageCapture.OnImageSavedCallback{
   override fun onImageSaved(r:ImageCapture.OutputFileResults){beep();auto.captured();capturing.set(false);listener?.onState("SCANNED");listener?.onCaptured(file.absolutePath)}
   override fun onError(e:ImageCaptureException){capturing.set(false);listener?.onError(e.message?:"Capture failed")}
  })}
 private fun beep(){ToneGenerator(AudioManager.STREAM_NOTIFICATION,90).startTone(ToneGenerator.TONE_PROP_BEEP,140);val v=context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator;v?.vibrate(VibrationEffect.createOneShot(100,VibrationEffect.DEFAULT_AMPLITUDE))}
 fun shutdown(){analysisExecutor.shutdown();captureExecutor.shutdown()}
}
