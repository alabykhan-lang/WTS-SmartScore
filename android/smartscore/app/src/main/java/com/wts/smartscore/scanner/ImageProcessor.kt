package com.wts.smartscore.scanner
import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot

object ImageProcessor {
 fun normalize(source:Bitmap):Bitmap{
  val rgba=Mat();Utils.bitmapToMat(source,rgba);val gray=Mat();Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
  val a=OpenCvDocumentDetector().detect(gray);gray.release()
  val q=a.quad ?: return source.copy(Bitmap.Config.ARGB_8888,false).also{rgba.release()}
  fun dist(a:android.graphics.PointF,b:android.graphics.PointF)=hypot((a.x-b.x).toDouble(),(a.y-b.y).toDouble())
  val w=maxOf(dist(q.tl,q.tr),dist(q.bl,q.br)).toInt().coerceAtLeast(300);val h=maxOf(dist(q.tl,q.bl),dist(q.tr,q.br)).toInt().coerceAtLeast(400)
  val srcPts=MatOfPoint2f(Point(q.tl.x.toDouble(),q.tl.y.toDouble()),Point(q.tr.x.toDouble(),q.tr.y.toDouble()),Point(q.br.x.toDouble(),q.br.y.toDouble()),Point(q.bl.x.toDouble(),q.bl.y.toDouble()))
  val dstPts=MatOfPoint2f(Point(0.0,0.0),Point((w-1).toDouble(),0.0),Point((w-1).toDouble(),(h-1).toDouble()),Point(0.0,(h-1).toDouble()))
  val out=Mat();Imgproc.warpPerspective(rgba,out,Imgproc.getPerspectiveTransform(srcPts,dstPts),Size(w.toDouble(),h.toDouble()))
  val lab=Mat();Imgproc.cvtColor(out,lab,Imgproc.COLOR_RGBA2RGB);val channels=mutableListOf<Mat>();Core.split(lab,channels);val clahe=Imgproc.createCLAHE(2.0,Size(8.0,8.0));clahe.apply(channels[0],channels[0]);Core.merge(channels,lab)
  val bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Imgproc.cvtColor(lab,out,Imgproc.COLOR_RGB2RGBA);Utils.matToBitmap(out,bmp);listOf(rgba,out,lab,srcPts,dstPts).forEach{it.release()};channels.forEach{it.release()};return bmp
 }
 fun saveJpeg(bitmap:Bitmap,file:File){file.parentFile?.mkdirs();FileOutputStream(file).use{bitmap.compress(Bitmap.CompressFormat.JPEG,94,it)}}
}
