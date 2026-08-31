package com.wts.smartscore.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

object HighResImageLoader {
    fun rotationDegrees(path: String): Int = when (runCatching {
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    fun load(path: String): Bitmap {
        val source = requireNotNull(BitmapFactory.decodeFile(path)) { "Unable to decode high-resolution capture" }
        val degrees = rotationDegrees(path).toFloat()
        if (degrees == 0f) return source
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(degrees) }, true)
        source.recycle()
        return rotated
    }
}
