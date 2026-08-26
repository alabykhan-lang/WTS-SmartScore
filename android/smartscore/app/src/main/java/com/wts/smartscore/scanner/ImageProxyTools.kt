package com.wts.smartscore.scanner
import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat

object ImageProxyTools {
 fun lumaMat(image:ImageProxy):Mat{
  val plane=image.planes[0]; val w=image.width; val h=image.height; val row=plane.rowStride; val buf=plane.buffer
  val data=ByteArray(w*h)
  if(row==w){buf.rewind();buf.get(data,0,minOf(data.size,buf.remaining()))}
  else {for(y in 0 until h){val src=y*row;if(src>=buf.limit())break;buf.position(src);buf.get(data,y*w,minOf(w,buf.remaining()))}}
  return Mat(h,w,CvType.CV_8UC1).also{it.put(0,0,data)}
 }
}
