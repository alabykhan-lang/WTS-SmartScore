package com.wts.smartscore.scanner

import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat

object ImageProxyTools {
    /** Copies the luma plane without assuming a tightly packed or pixelStride=1 buffer. */
    fun lumaMat(image: ImageProxy): Mat {
        val plane = image.planes.first()
        val width = image.width.coerceAtLeast(1)
        val height = image.height.coerceAtLeast(1)
        val crop = image.cropRect
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val buffer = plane.buffer.duplicate()
        val data = ByteArray(width * height)

        for (y in 0 until height) {
            val sourceRow = (crop.top + y) * rowStride + crop.left * pixelStride
            for (x in 0 until width) {
                val sourceIndex = sourceRow + x * pixelStride
                if (sourceIndex in 0 until buffer.limit()) data[y * width + x] = buffer.get(sourceIndex)
            }
        }
        return Mat(height, width, CvType.CV_8UC1).also { it.put(0, 0, data) }
    }
}
