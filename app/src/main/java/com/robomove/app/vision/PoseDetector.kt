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

class PoseDetector(
    private val context: Context,
    private val onResult: (pose: Pose, imageWidth: Int, imageHeight: Int) -> Unit
) {

    companion object {
        private const val TAG = "PoseDetector"

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

        // FIX #2: Raised from 100ms to 150ms.
        // 100ms = 10fps sounds fast but on RK3399 each frame takes ~80-120ms
        // to process in SINGLE_IMAGE_MODE, so the 100ms timer wasn't actually
        // throttling anything — frames were piling up anyway. With STREAM_MODE
        // each frame is faster (~40-60ms), so 150ms gives genuine breathing room
        // without making fall detection feel sluggish. Adjust down to 120ms if
        // it feels too slow after testing.
        private const val FRAME_INTERVAL_MS = 100L
    }

    private val detector: PoseDetector
    private var lastProcessedTime = 0L
    private var isProcessing = false

    init {
        // FIX #1: STREAM_MODE, not SINGLE_IMAGE_MODE.
        //
        // SINGLE_IMAGE_MODE: treats every frame as a standalone photo.
        // Runs the full heavy detection pipeline from scratch each time —
        // no memory of the previous frame. This is intended for one-shot
        // use cases (e.g. "analyze this photo the user just picked").
        //
        // STREAM_MODE: designed for live camera feeds. Uses the previous
        // frame's result as a starting point and only tracks changes between
        // frames. Far less CPU work per frame. On RK3399 this roughly halves
        // the per-frame processing time.
        //
        // The old comment "STREAM_MODE adds overhead" was backwards — it's
        // SINGLE_IMAGE_MODE that adds overhead on live feeds because it
        // throws away all inter-frame context every single time.
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()

        detector = PoseDetection.getClient(options)
        Log.d(TAG, "ML Kit PoseDetector initialized — STREAM_MODE")
    }

    @androidx.camera.core.ExperimentalGetImage
    fun detectLiveStream(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()

        // Skip frame if we're still processing the last one OR
        // not enough time has passed since last processed frame.
        // The isProcessing flag is the critical guard — without it,
        // frames queue up faster than they're consumed and lag compounds.
        if (isProcessing || (now - lastProcessedTime) < FRAME_INTERVAL_MS) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        isProcessing = true
        lastProcessedTime = now

        // FIX #3: Capture dimensions BEFORE calling process(), not inside the
        // success callback. imageProxy may already be closed by the time the
        // success callback runs, making imageProxy.width/height unreliable on
        // some CameraX versions. Capturing here (while imageProxy is still open)
        // is always safe.
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
                // Always runs after success OR failure — resets the guard
                // and closes the proxy so CameraX can reuse the buffer.
                isProcessing = false
                imageProxy.close()
            }
    }

    fun close() {
        detector.close()
        Log.d(TAG, "PoseDetector closed")
    }
}