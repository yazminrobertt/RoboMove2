package com.robomove.app.vision

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions

/**
 * Wraps ML Kit PoseDetector.
 * Works on API 23+ — compatible with Daman robot tablet.
 *
 * Replaces MediaPipe PoseLandmarker.
 * Same 33 landmarks, same body points, different coordinate format.
 * Coordinates are normalised to 0..1 inside RepCounter using image dimensions.
 */
class PoseDetector(
    private val context: Context,
    private val onResult: (pose: Pose, imageWidth: Int, imageHeight: Int) -> Unit
) {

    companion object {
        private const val TAG = "PoseDetector"

        // ML Kit landmark type constants
        // Same body points as MediaPipe — just accessed differently
        const val LEFT_SHOULDER  = PoseLandmark.LEFT_SHOULDER
        const val RIGHT_SHOULDER = PoseLandmark.RIGHT_SHOULDER
        const val LEFT_ELBOW     = PoseLandmark.LEFT_ELBOW
        const val RIGHT_ELBOW    = PoseLandmark.RIGHT_ELBOW
        const val LEFT_WRIST     = PoseLandmark.LEFT_WRIST
        const val RIGHT_WRIST    = PoseLandmark.RIGHT_WRIST
        const val LEFT_HIP       = PoseLandmark.LEFT_HIP
        const val RIGHT_HIP      = PoseLandmark.RIGHT_HIP
        const val LEFT_KNEE      = PoseLandmark.LEFT_KNEE
        const val RIGHT_KNEE     = PoseLandmark.RIGHT_KNEE
        const val LEFT_ANKLE     = PoseLandmark.LEFT_ANKLE
        const val RIGHT_ANKLE    = PoseLandmark.RIGHT_ANKLE
        const val LEFT_INDEX     = PoseLandmark.LEFT_INDEX
        const val RIGHT_INDEX    = PoseLandmark.RIGHT_INDEX
    }

    private val detector: PoseDetector

    init {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()

        detector = PoseDetection.getClient(options)
        Log.d(TAG, "ML Kit PoseDetector initialized — API 23 compatible")
    }

    /**
     * Call this for every camera frame from CameraX ImageAnalysis.
     * ImageProxy is closed inside this function — do not close it outside.
     */
    @androidx.camera.core.ExperimentalGetImage
    fun detectLiveStream(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val imageWidth  = imageProxy.width
        val imageHeight = imageProxy.height

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        detector.process(image)
            .addOnSuccessListener { pose ->
                onResult(pose, imageWidth, imageHeight)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Pose detection failed: ${e.message}")
            }
            .addOnCompleteListener {
                // Always close — even on failure
                imageProxy.close()
            }
    }

    fun close() {
        detector.close()
        Log.d(TAG, "PoseDetector closed")
    }
}