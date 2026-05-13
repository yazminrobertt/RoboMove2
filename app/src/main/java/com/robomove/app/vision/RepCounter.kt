package com.robomove.app.vision

import android.util.Log
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.robomove.app.model.ExerciseType
import com.robomove.app.model.RepQuality

/**
 * RepCounter takes raw pose landmarks and converts them into:
 *   - Whether the "up" position is held
 *   - When a full rep cycle completes (up → down → up)
 *   - The quality of the rep
 *
 * HOW REPS ARE COUNTED:
 *   Each exercise has an "up" state and a "down" (rest) state.
 *   A rep = going from rest → up → rest again.
 *   We track this with a simple state machine: isInUpPosition flag.
 */
class RepCounter(
    private val exerciseType: ExerciseType,
    private val onRepCompleted: (quality: RepQuality) -> Unit,
    private val onPoseFeedback: (message: String) -> Unit
) {

    companion object {
        private const val TAG = "RepCounter"
    }

    // State machine
    private var isInUpPosition = false
    private var lastFeedbackTime = 0L
    private val FEEDBACK_COOLDOWN_MS = 3000L

    /**
     * Main entry point — call this every frame with new landmarks.
     * Returns a human-readable status string for debugging.
     */
    fun processLandmarks(result: PoseLandmarkerResult): String {
        if (result.landmarks().isEmpty()) {
            return "No pose detected"
        }

        val landmarks = result.landmarks()[0]

        return when (exerciseType) {
            ExerciseType.RAISE_LEFT_HAND    -> checkRaiseHand(landmarks, isLeft = true)
            ExerciseType.RAISE_RIGHT_HAND   -> checkRaiseHand(landmarks, isLeft = false)
            ExerciseType.BOTH_HANDS_UP      -> checkBothHandsUp(landmarks)
            ExerciseType.TOUCH_SHOULDERS    -> checkTouchShoulders(landmarks)
            ExerciseType.ARM_CIRCLES        -> checkArmCircles(landmarks)
            ExerciseType.SIDE_STRETCH_LEFT  -> checkSideStretch(landmarks, isLeft = true)
            ExerciseType.SIDE_STRETCH_RIGHT -> checkSideStretch(landmarks, isLeft = false)
            ExerciseType.KNEE_LIFT_LEFT     -> checkKneeLift(landmarks, isLeft = true)
            ExerciseType.KNEE_LIFT_RIGHT    -> checkKneeLift(landmarks, isLeft = false)
            ExerciseType.CROSS_BODY_LEFT    -> checkCrossBody(landmarks, isLeft = true)
            ExerciseType.CROSS_BODY_RIGHT   -> checkCrossBody(landmarks, isLeft = false)
            ExerciseType.JUMPING_JACK       -> checkJumpingJack(landmarks)
            ExerciseType.SQUAT              -> checkSquat(landmarks)
            ExerciseType.CLAP_ABOVE_HEAD    -> checkClapAboveHead(landmarks)
        }
    }

    fun reset() {
        isInUpPosition = false
        Log.d(TAG, "RepCounter reset for $exerciseType")
    }

    // ─────────────────────────────────────────
    // HELPER — Get normalised Y (0=top, 1=bottom)
    // ─────────────────────────────────────────

    private fun y(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
                  index: Int) = landmarks[index].y()

    private fun x(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
                  index: Int) = landmarks[index].x()

    private fun maybeFeedback(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastFeedbackTime > FEEDBACK_COOLDOWN_MS) {
            lastFeedbackTime = now
            onPoseFeedback(message)
        }
    }

    // ─────────────────────────────────────────
    // EXERCISE CHECKERS
    // ─────────────────────────────────────────

    /** RAISE LEFT / RIGHT HAND
     *  Up condition: wrist is above shoulder (lower Y value)
     *  Threshold: wrist.y < shoulder.y - 0.15
     */
    private fun checkRaiseHand(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        isLeft: Boolean
    ): String {
        val wristIdx    = if (isLeft) PoseDetector.LEFT_WRIST    else PoseDetector.RIGHT_WRIST
        val shoulderIdx = if (isLeft) PoseDetector.LEFT_SHOULDER else PoseDetector.RIGHT_SHOULDER
        val side        = if (isLeft) "left" else "right"

        val wristY    = y(landmarks, wristIdx)
        val shoulderY = y(landmarks, shoulderIdx)
        val diff      = shoulderY - wristY   // positive = wrist is above shoulder

        val isUp = diff > 0.25f  // wrist clearly above shoulder

        return countRep(
            isUp = isUp,
            correctThreshold = 0.35f,
            slightThreshold  = 0.20f,
            value            = diff,
            feedbackWhenLow  = "Raise your $side arm higher!"
        )
    }

    /** BOTH HANDS UP
     *  Up condition: BOTH wrists above shoulders
     */
    private fun checkBothHandsUp(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ): String {
        val leftDiff  = y(landmarks, PoseDetector.LEFT_SHOULDER)  - y(landmarks, PoseDetector.LEFT_WRIST)
        val rightDiff = y(landmarks, PoseDetector.RIGHT_SHOULDER) - y(landmarks, PoseDetector.RIGHT_WRIST)
        val bothUp    = leftDiff > 0.20f && rightDiff > 0.20f
        val quality   = if (leftDiff > 0.35f && rightDiff > 0.35f) "CORRECT"
        else if (bothUp) "SLIGHT" else "LOW"

        if (!bothUp) maybeFeedback("Raise BOTH arms above your head!")

        return countRepDirect(isUp = bothUp, qualityHint = quality)
    }

    /** TOUCH SHOULDERS — arms go from T-pose to touching shoulders */
    private fun checkTouchShoulders(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ): String {
        // Wrist X should be close to shoulder X when touching
        val leftDeltaX  = Math.abs(x(landmarks, PoseDetector.LEFT_WRIST)  - x(landmarks, PoseDetector.LEFT_SHOULDER))
        val rightDeltaX = Math.abs(x(landmarks, PoseDetector.RIGHT_WRIST) - x(landmarks, PoseDetector.RIGHT_SHOULDER))
        val touching = leftDeltaX < 0.12f && rightDeltaX < 0.12f

        if (!touching) maybeFeedback("Bring your hands to your shoulders!")

        return countRepDirect(isUp = touching, qualityHint = if (touching) "CORRECT" else "LOW")
    }

    /** ARM CIRCLES — detect wrist going above then below shoulder level */
    private fun checkArmCircles(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ): String {
        val leftAbove  = y(landmarks, PoseDetector.LEFT_WRIST)  < y(landmarks, PoseDetector.LEFT_SHOULDER)
        val rightAbove = y(landmarks, PoseDetector.RIGHT_WRIST) < y(landmarks, PoseDetector.RIGHT_SHOULDER)
        val isUp = leftAbove && rightAbove
        if (!isUp) maybeFeedback("Keep your arms extended and make big circles!")
        return countRepDirect(isUp = isUp, qualityHint = if (isUp) "CORRECT" else "SLIGHT")
    }

    /** SIDE STRETCH — lean detected by hip-shoulder angle */
    private fun checkSideStretch(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        isLeft: Boolean
    ): String {
        // When leaning left: left shoulder X moves toward left hip X
        val shoulderX = x(landmarks, PoseDetector.LEFT_SHOULDER)
        val hipX      = x(landmarks, PoseDetector.LEFT_HIP)
        val leanDelta = if (isLeft) hipX - shoulderX else shoulderX - hipX
        val isUp      = leanDelta > 0.08f
        val side      = if (isLeft) "left" else "right"
        if (!isUp) maybeFeedback("Lean your body further to the $side!")
        return countRepDirect(isUp = isUp, qualityHint = if (leanDelta > 0.14f) "CORRECT" else "SLIGHT")
    }

    /** KNEE LIFT — knee Y rises above hip Y */
    private fun checkKneeLift(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        isLeft: Boolean
    ): String {
        val kneeIdx = if (isLeft) PoseDetector.LEFT_KNEE  else PoseDetector.RIGHT_KNEE
        val hipIdx  = if (isLeft) PoseDetector.LEFT_HIP   else PoseDetector.RIGHT_HIP
        val side    = if (isLeft) "left" else "right"

        val kneeAboveHip = y(landmarks, hipIdx) - y(landmarks, kneeIdx)
        val isUp = kneeAboveHip > 0.05f

        if (!isUp) maybeFeedback("Lift your $side knee higher!")

        return countRepDirect(
            isUp = isUp,
            qualityHint = if (kneeAboveHip > 0.12f) "CORRECT" else if (isUp) "SLIGHT" else "LOW"
        )
    }

    /** CROSS BODY TOUCH — right hand near left knee (or vice versa) */
    private fun checkCrossBody(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        isLeft: Boolean
    ): String {
        // isLeft = touch left knee with right hand
        val handIdx = if (isLeft) PoseDetector.RIGHT_WRIST else PoseDetector.LEFT_WRIST
        val kneeIdx = if (isLeft) PoseDetector.LEFT_KNEE   else PoseDetector.RIGHT_KNEE
        val side    = if (isLeft) "left" else "right"

        val distX = Math.abs(x(landmarks, handIdx) - x(landmarks, kneeIdx))
        val distY = Math.abs(y(landmarks, handIdx) - y(landmarks, kneeIdx))
        val distance = Math.sqrt((distX * distX + distY * distY).toDouble()).toFloat()
        val isUp = distance < 0.18f

        if (!isUp) maybeFeedback("Reach your hand to your $side knee!")

        return countRepDirect(
            isUp = isUp,
            qualityHint = if (distance < 0.10f) "CORRECT" else if (isUp) "SLIGHT" else "LOW"
        )
    }

    /** JUMPING JACK — arms out + legs apart */
    private fun checkJumpingJack(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ): String {
        val armsUp   = y(landmarks, PoseDetector.LEFT_WRIST) < y(landmarks, PoseDetector.LEFT_SHOULDER) &&
                y(landmarks, PoseDetector.RIGHT_WRIST) < y(landmarks, PoseDetector.RIGHT_SHOULDER)
        val legsApart = Math.abs(x(landmarks, PoseDetector.LEFT_ANKLE) -
                x(landmarks, PoseDetector.RIGHT_ANKLE)) > 0.25f
        val isUp = armsUp && legsApart
        if (!armsUp)   maybeFeedback("Raise both arms higher!")
        if (!legsApart) maybeFeedback("Spread your legs wider!")
        return countRepDirect(isUp = isUp, qualityHint = if (isUp) "CORRECT" else "SLIGHT")
    }

    /** SQUAT — knee angle or hip drops below a threshold */
    private fun checkSquat(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ): String {
        // Hips drop when squatting — hip Y increases (lower on screen)
        val hipY  = y(landmarks, PoseDetector.LEFT_HIP)
        val kneeY = y(landmarks, PoseDetector.LEFT_KNEE)
        val dropDelta = hipY - kneeY   // smaller = hips lower relative to knee
        val isDown = dropDelta < 0.10f  // hips near knee level

        if (dropDelta > 0.20f) maybeFeedback("Bend your knees more and go lower!")

        return countRepDirect(
            isUp = isDown,  // "up" here means the squat position
            qualityHint = if (dropDelta < 0.05f) "CORRECT" else if (isDown) "SLIGHT" else "LOW"
        )
    }

    /** CLAP ABOVE HEAD — both wrists above head, close together */
    private fun checkClapAboveHead(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ): String {
        val leftWristAbove  = y(landmarks, PoseDetector.LEFT_WRIST)  < y(landmarks, PoseDetector.LEFT_SHOULDER)  - 0.15f
        val rightWristAbove = y(landmarks, PoseDetector.RIGHT_WRIST) < y(landmarks, PoseDetector.RIGHT_SHOULDER) - 0.15f
        val handsClose = Math.abs(x(landmarks, PoseDetector.LEFT_WRIST) -
                x(landmarks, PoseDetector.RIGHT_WRIST)) < 0.15f
        val isUp = leftWristAbove && rightWristAbove && handsClose
        if (!leftWristAbove || !rightWristAbove) maybeFeedback("Raise both hands above your head!")
        if (!handsClose) maybeFeedback("Bring your hands together to clap!")
        return countRepDirect(isUp = isUp, qualityHint = if (isUp) "CORRECT" else "SLIGHT")
    }

    // ─────────────────────────────────────────
    // REP STATE MACHINE
    // ─────────────────────────────────────────

    /**
     * Simple version for threshold-based exercises.
     * diff = measured value, thresholds decide quality.
     */
    private fun countRep(
        isUp: Boolean,
        correctThreshold: Float,
        slightThreshold: Float,
        value: Float,
        feedbackWhenLow: String
    ): String {
        if (!isUp) maybeFeedback(feedbackWhenLow)

        val quality = when {
            value >= correctThreshold -> RepQuality.CORRECT
            value >= slightThreshold  -> RepQuality.SLIGHTLY_WRONG
            else -> RepQuality.WRONG
        }

        return countRepDirect(isUp, quality.name)
    }

    /** Core state machine — tracks up/down transitions */
    private fun countRepDirect(isUp: Boolean, qualityHint: String): String {
        val quality = when (qualityHint) {
            "CORRECT" -> RepQuality.CORRECT
            "SLIGHT"  -> RepQuality.SLIGHTLY_WRONG
            else      -> RepQuality.WRONG
        }

        if (isUp && !isInUpPosition) {
            // Transitioned to UP position
            isInUpPosition = true
            Log.d(TAG, "$exerciseType → UP (quality=$quality)")
        } else if (!isUp && isInUpPosition) {
            // Transitioned back to DOWN — rep complete!
            isInUpPosition = false
            Log.d(TAG, "$exerciseType → DOWN = REP COMPLETED (quality=$quality)")
            onRepCompleted(quality)
        }

        return "isUp=$isUp, quality=$qualityHint, inUpPos=$isInUpPosition"
    }
}