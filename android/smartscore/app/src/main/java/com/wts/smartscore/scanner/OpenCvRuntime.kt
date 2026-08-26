package com.wts.smartscore.scanner

import android.content.Context
import android.os.Build
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import java.io.File

object OpenCvRuntime {
    private const val TAG = "SmartScoreOpenCV"

    enum class State { OPENCV_INITIALIZING, OPENCV_READY, OPENCV_FAILED }

    data class Result(
        val state: State,
        val details: String,
        val error: Throwable? = null
    )

    @Volatile private var cached: Result? = null

    @Synchronized
    fun initialize(context: Context, forceRetry: Boolean = false): Result {
        if (!forceRetry) cached?.let { if (it.state == State.OPENCV_READY) return it }

        val abi = Build.SUPPORTED_ABIS.joinToString(",")
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: "<none>"
        val nativeFile = File(nativeDir, "libopencv_java4.so")
        Log.i(TAG, "OPENCV_INITIALIZING abi=$abi nativeDir=$nativeDir extractedNativeExists=${nativeFile.exists()}")

        return try {
            val loaded = OpenCVLoader.initLocal()
            if (!loaded) throw IllegalStateException("OpenCVLoader.initLocal() returned false")

            val smoke = Mat(2, 2, CvType.CV_8UC1)
            try {
                smoke.setTo(Scalar(1.0))
                val nonZero = Core.countNonZero(smoke)
                if (nonZero != 4) throw IllegalStateException("OpenCV smoke test returned $nonZero instead of 4")
            } finally {
                smoke.release()
            }

            val result = Result(
                State.OPENCV_READY,
                "OpenCV ${Core.VERSION}; ABI=$abi; native=libopencv_java4.so; extracted=${nativeFile.exists()}"
            )
            cached = result
            Log.i(TAG, "OPENCV_READY ${result.details}")
            result
        } catch (t: Throwable) {
            val result = Result(
                State.OPENCV_FAILED,
                "ABI=$abi; native=libopencv_java4.so; extracted=${nativeFile.exists()}; ${t.javaClass.simpleName}: ${t.message}",
                t
            )
            cached = result
            Log.e(TAG, "OPENCV_FAILED ${result.details}", t)
            result
        }
    }

    fun isReady(): Boolean = cached?.state == State.OPENCV_READY

    fun diagnostics(): String = cached?.details ?: "OpenCV not initialized"
}
