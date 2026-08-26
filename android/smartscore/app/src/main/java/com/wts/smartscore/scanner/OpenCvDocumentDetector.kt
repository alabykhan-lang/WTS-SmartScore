package com.wts.smartscore.scanner

import android.graphics.PointF
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

class OpenCvDocumentDetector {
 fun detect(gray:Mat):FrameAssessment{
  val small=Mat(); Imgproc.resize(gray,small,Size(720.0,gray.rows()*720.0/gray.cols()))
  val blur=Mat(); Imgproc.GaussianBlur(small,blur,Size(5.0,5.0),0.0)
  val edges=Mat(); Imgproc.Canny(blur,edges,60.0,180.0)
  val contours=mutableListOf<MatOfPoint>(); Imgproc.findContours(edges,contours,Mat(),Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE)
  var best:MatOfPoint2f?=null; var bestArea=0.0
  contours.forEach{ c -> val c2=MatOfPoint2f(*c.toArray()); val peri=Imgproc.arcLength(c2,true); val approx=MatOfPoint2f(); Imgproc.approxPolyDP(c2,approx,0.02*peri,true); val area=abs(Imgproc.contourArea(approx)); if(approx.total()==4L && area>bestArea){best=approx;bestArea=area} }
  val q=best?.let{ orderedQuad(it.toArray(),gray.cols().toFloat()/small.cols(),gray.rows().toFloat()/small.rows()) }
  val coverage=(bestArea/(small.cols()*small.rows())).toFloat()
  val lap=Mat(); Imgproc.Laplacian(small,lap,CvType.CV_64F); val mean=MatOfDouble(); val std=MatOfDouble(); Core.meanStdDev(lap,mean,std); val blurScore=std.toArray().firstOrNull()?.pow(2)?:0.0
  val bright=Mat(); Imgproc.threshold(small,bright,245.0,255.0,Imgproc.THRESH_BINARY); val glare=Core.countNonZero(bright).toDouble()/(small.total().coerceAtLeast(1))
  return FrameAssessment(q,coverage,blurScore,stable=true,glare=glare,stateHint="")
 }
 private fun orderedQuad(p:Array<Point>,sx:Float,sy:Float):Quad{
  val pts=p.map{PointF((it.x*sx).toFloat(),(it.y*sy).toFloat())}; val tl=pts.minBy{it.x+it.y}; val br=pts.maxBy{it.x+it.y}; val tr=pts.maxBy{it.x-it.y}; val bl=pts.minBy{it.x-it.y}; return Quad(tl,tr,br,bl)
 }
}
