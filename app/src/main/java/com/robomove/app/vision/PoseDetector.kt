package com.robomove.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * Wraps MediaPipe PoseLandmarker.
 * Processes camera frames and emits landmark results.
 *
 * Usage:
 *   val detector = PoseDetector(context) { result, width, height ->
 *       // use result.landmarks()
 *   }
 *   detector.detectLiveStream(imageProxy)
 */
class PoseDetector(
    private val context: Context,
    private val onResult: (PoseLandmarkerResult, Int, Int) -> Unit
) {

    companion object {
        private const val TAG = "PoseDetector"
        private const val MODEL_FILE = "pose_landmarker.task"

        // MediaPipe landmark indices
        const val LEFT_SHOULDER  = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_ELBOW     = 13
        const val RIGHT_ELBOW    = 14
        const val LEFT_WRIST     = 15
        const val RIGHT_WRIST    = 16
        const val LEFT_HIP       = 23
        const val RIGHT_HIP      = 24
        const val LEFT_KNEE      = 25
        const val RIGHT_KNEE     = 26
        const val LEFT_ANKLE     = 27
        const val RIGHT_ANKLE    = 28
        const val LEFT_INDEX     = 19  // fingertip
        const val RIGHT_INDEX    = 20
    }

    private var poseLandmarker: PoseLandmarker? = null

    init {
        setupPoseLandmarker()
    }

    private fun setupPoseLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_FILE)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result, image ->
                    onResult(result, image.width, image.height)
                }
                .setErrorListener { error ->
                    Log.e(TAG, "PoseLandmarker error: ${error.message}")
                }
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            Log.d(TAG, "PoseDetector initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PoseDetector: ${e.message}")
        }
    }

    /**
     * Call this for every camera frame.
     * ImageProxy comes from CameraX ImageAnalysis use case.
     */
    fun detectLiveStream(imageProxy: ImageProxy) {
        val bitmapBuffer = Bitmap.createBitmap(
            imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
        )
        imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }

        // Rotate to match display orientation
        val matrix = Matrix().apply {
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
        }
        val rotatedBitmap = Bitmap.createBitmap(
            bitmapBuffer, 0, 0,
            bitmapBuffer.width, bitmapBuffer.height, matrix, true
        )

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        val frameTime = System.currentTimeMillis()

        poseLandmarker?.detectAsync(mpImage, frameTime)
    }

    fun close() {
        poseLandmarker?.close()
        Log.d(TAG, "PoseDetector closed")
    }
}