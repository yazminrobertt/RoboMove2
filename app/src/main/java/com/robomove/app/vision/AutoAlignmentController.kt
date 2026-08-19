package com.robomove.app.vision

import android.util.Log
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark

/**
 * Continuously tracks the user's raised-hands position in frame and computes
 * a smoothed target head-tilt angle, so the robot's head keeps up with
 * changes in distance (closer → tilt up, farther → tilt down) instead of
 * settling once and getting stuck.
 *
 * Framing priority: keep the raised hands inside frame with a small margin
 * above them. Feet are secondary — it's fine for them to sit near the
 * bottom edge. Horizontal centering (pan) logic below.
 */
class AutoAlignmentController {

    companion object {
        private const val TAG = "AutoAlignmentController"

        private const val VISIBILITY_THRESHOLD = 0.6f

        private const val CENTER_MIN = 0.35f
        private const val CENTER_MAX = 0.65f

        // Desired normalised Y (0 = top of frame) for the top of the raised
        // hands — a small gap above the hands, not flush against the edge.
        private const val TARGET_TOP_MARGIN = 0.08f

        // How strongly a framing error nudges the target angle. Doesn't need
        // to be precise — the activity step-limits actual movement anyway.
        // This mainly sets direction + how eagerly it wants to move.
        private const val TILT_GAIN_DEGREES_PER_UNIT = 60f

        // EMA smoothing factor for the raw hand-height signal — higher =
        // more responsive but jitterier, lower = smoother but laggier.
        private const val SMOOTHING_ALPHA = 0.25f

        // If the head pans the wrong way (moves away from you instead of
        // toward you), flip this. This corrects for this robot's specific
        // camera mirroring/mounting, which doesn't match the usual
        // front/back camera assumption.
        private const val INVERT_PAN = true
    }

    enum class PanAdjustment { NONE, LEFT, RIGHT, CENTERED }

    data class AlignmentState(
        val personVisible: Boolean,
        val handsUp: Boolean,
        val targetVertiAngle: Float?,   // null = hold current angle, no reliable subject
        val panAdjustment: PanAdjustment,
        val fingersVisible: Boolean,
        val feetVisible: Boolean
    )

    private var imageWidth = 1
    private var imageHeight = 1
    private var isFrontCamera = true

    // Smoothed (EMA) normalised Y of the top of the raised hands. Reset to
    // null whenever the subject is lost so a returning person doesn't
    // inherit a stale value from before they left.
    private var smoothedTopY: Float? = null

    fun setImageDimensions(width: Int, height: Int, isFrontCamera: Boolean = true) {
        imageWidth = width
        imageHeight = height
        this.isFrontCamera = isFrontCamera
    }

    /**
     * Call every pose frame while auto-align is active. currentVertiAngle is
     * the head's current tilt angle — the returned target is relative to it,
     * so this needs the live value each call, not a cached one.
     */
    fun evaluate(pose: Pose, currentVertiAngle: Int, vertiMin: Int, vertiMax: Int): AlignmentState {
        val nose = pose.getPoseLandmark(PoseLandmark.NOSE)
        if (nose == null || nose.inFrameLikelihood < VISIBILITY_THRESHOLD) {
            smoothedTopY = null
            return AlignmentState(
                personVisible = false, handsUp = false, targetVertiAngle = null,
                panAdjustment = PanAdjustment.NONE, fingersVisible = false, feetVisible = false
            )
        }

        val leftWrist  = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
        val rightWrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
        if (leftWrist == null || rightWrist == null) {
            smoothedTopY = null
            return AlignmentState(
                personVisible = true, handsUp = false, targetVertiAngle = null,
                panAdjustment = PanAdjustment.NONE, fingersVisible = false, feetVisible = false
            )
        }

        val handsUp = leftWrist.position.y < nose.position.y && rightWrist.position.y < nose.position.y
        if (!handsUp) {
            smoothedTopY = null
            return AlignmentState(
                personVisible = true, handsUp = false, targetVertiAngle = null,
                panAdjustment = PanAdjustment.NONE, fingersVisible = false, feetVisible = false
            )
        }

        // ── Continuous tilt target ────────────────────────────────────────
        // Top of the raised hands — smaller Y = higher on screen, so take the min.
        val rawTopY = minOf(leftWrist.position.y, rightWrist.position.y) / imageHeight

        // EMA smoothing — blends the new reading with the running average
        // instead of reacting to a single noisy frame.
        smoothedTopY = smoothedTopY?.let { it + SMOOTHING_ALPHA * (rawTopY - it) } ?: rawTopY

        val error = smoothedTopY!! - TARGET_TOP_MARGIN
        // error > 0 → hands sit lower than target (too much space above) →
        //   user is farther away → tilt down (angle increases toward VERTI_MAX)
        // error < 0 → hands sit above target (too close to top edge) →
        //   user is closer → tilt up (angle decreases toward VERTI_MIN)
        val rawTarget = currentVertiAngle + (error * TILT_GAIN_DEGREES_PER_UNIT)
        val targetVertiAngle = rawTarget.coerceIn(vertiMin.toFloat(), vertiMax.toFloat())

        // ── Framing checks — status/messaging only, don't drive tilt ──────
        val leftIndex  = pose.getPoseLandmark(PoseLandmark.LEFT_INDEX)
        val rightIndex = pose.getPoseLandmark(PoseLandmark.RIGHT_INDEX)
        val leftAnkle  = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
        val rightAnkle = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)
        val fingersVisible = isClearlyVisible(leftIndex) && isClearlyVisible(rightIndex)
        val feetVisible     = isClearlyVisible(leftAnkle) && isClearlyVisible(rightAnkle)

        // ── Pan ─────────────────────────────────────────────────────────
        val normalizedNoseX = nose.position.x / imageWidth
        var effectiveX = if (isFrontCamera) 1f - normalizedNoseX else normalizedNoseX
        if (INVERT_PAN) effectiveX = 1f - effectiveX

        val panAdjustment = when {
            effectiveX < CENTER_MIN -> PanAdjustment.LEFT
            effectiveX > CENTER_MAX -> PanAdjustment.RIGHT
            else -> PanAdjustment.CENTERED
        }

        Log.d(TAG, "topY=${"%.3f".format(smoothedTopY)} error=${"%.3f".format(error)} " +
                "target=${"%.1f".format(targetVertiAngle)} fingers=$fingersVisible feet=$feetVisible " +
                "effectiveX=${"%.3f".format(effectiveX)} pan=$panAdjustment")

        return AlignmentState(
            personVisible = true, handsUp = true, targetVertiAngle = targetVertiAngle,
            panAdjustment = panAdjustment, fingersVisible = fingersVisible, feetVisible = feetVisible
        )
    }

    private fun isClearlyVisible(landmark: PoseLandmark?): Boolean {
        if (landmark == null) return false
        return landmark.inFrameLikelihood >= VISIBILITY_THRESHOLD
    }
}