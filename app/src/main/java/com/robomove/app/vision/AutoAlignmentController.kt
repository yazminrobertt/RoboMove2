package com.robomove.app.vision

import android.util.Log
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark

/**
 * Checks whether the whole body (raised hands + feet) is visible and centered
 * in frame, and reports what adjustment is needed. No hardware calls in here —
 * CameraAlignmentActivity decides what to do with the result.
 *
 * Head-only version: tilt (up/down) and pan (left/right) are handled
 * automatically. If the person is too close for tilting alone to fit both
 * fingers and feet in frame, NEED_STEP_BACK is returned so the app can ask
 * them to step back manually — no wheels involved.
 */
class AutoAlignmentController {

    companion object {
        private const val TAG = "AutoAlignmentController"

        // ML Kit's inFrameLikelihood is 0..1 — confidence the landmark is
        // actually inside the visible frame (not cut off)
        private const val VISIBILITY_THRESHOLD = 0.6f

        // Nose must land within this horizontal band (0..1, normalised) to
        // count as centered
        private const val CENTER_MIN = 0.35f
        private const val CENTER_MAX = 0.65f
    }

    enum class Adjustment {
        WAITING_FOR_PERSON,
        WAITING_FOR_HANDS_UP,
        NEED_TILT_UP,     // fingers cut off — tilt head up to include them
        NEED_TILT_DOWN,   // feet cut off — tilt head down to include them
        NEED_STEP_BACK,   // both fingers AND feet missing — ask person to step back
        NEED_PAN_LEFT,
        NEED_PAN_RIGHT,
        ALIGNED
    }

    private var imageWidth = 1
    private var imageHeight = 1
    private var isFrontCamera = true

    fun setImageDimensions(width: Int, height: Int, isFrontCamera: Boolean = true) {
        imageWidth = width
        imageHeight = height
        this.isFrontCamera = isFrontCamera
    }

    fun evaluate(pose: Pose): Adjustment {
        val nose = pose.getPoseLandmark(PoseLandmark.NOSE)
        if (nose == null || nose.inFrameLikelihood < VISIBILITY_THRESHOLD) {
            return Adjustment.WAITING_FOR_PERSON
        }

        val leftWrist  = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
        val rightWrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
        if (leftWrist == null || rightWrist == null) {
            return Adjustment.WAITING_FOR_PERSON
        }

        val handsUp = leftWrist.position.y < nose.position.y &&
                rightWrist.position.y < nose.position.y
        if (!handsUp) {
            return Adjustment.WAITING_FOR_HANDS_UP
        }

        val leftIndex  = pose.getPoseLandmark(PoseLandmark.LEFT_INDEX)
        val rightIndex = pose.getPoseLandmark(PoseLandmark.RIGHT_INDEX)
        val leftAnkle  = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
        val rightAnkle = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)

        val fingersVisible = isClearlyVisible(leftIndex) && isClearlyVisible(rightIndex)
        val feetVisible     = isClearlyVisible(leftAnkle) && isClearlyVisible(rightAnkle)

        Log.d(TAG, "handsUp=$handsUp fingersVisible=$fingersVisible feetVisible=$feetVisible")

        // Both missing at once → tilting can't fix this, person is too close.
        // No wheels — just ask them to step back themselves.
        if (!fingersVisible && !feetVisible) {
            return Adjustment.NEED_STEP_BACK
        }

        // Only one end missing → tilt toward it.
        if (!fingersVisible) {
            return Adjustment.NEED_TILT_UP
        }
        if (!feetVisible) {
            return Adjustment.NEED_TILT_DOWN
        }

        val normalizedNoseX = nose.position.x / imageWidth
        val effectiveX = if (isFrontCamera) 1f - normalizedNoseX else normalizedNoseX

        return when {
            effectiveX < CENTER_MIN -> Adjustment.NEED_PAN_LEFT
            effectiveX > CENTER_MAX -> Adjustment.NEED_PAN_RIGHT
            else -> Adjustment.ALIGNED
        }
    }

    private fun isClearlyVisible(landmark: PoseLandmark?): Boolean {
        if (landmark == null) return false
        return landmark.inFrameLikelihood >= VISIBILITY_THRESHOLD
    }
}