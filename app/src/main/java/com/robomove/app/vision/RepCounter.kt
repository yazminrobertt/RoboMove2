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
    private var hasShownRestPosition = false
    private var repQuality = RepQuality.WRONG
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
        hasShownRestPosition = false
        repQuality = RepQuality.WRONG   // ← ADD THIS
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

    /**
     * RAISE LEFT / RIGHT HAND — Side-arc motion
     *
     * The child starts with arms at sides, raises them outward and upward.
     *
     * We use a two-phase check:
     *   Phase 1 (arm out): wrist X is significantly outside hip X (arm extended to side)
     *   Phase 2 (arm up):  wrist Y is above shoulder Y
     *
     * A rep = arm goes up (wrist above shoulder level) then comes back down.
     * We accept "arm out at shoulder height" as SLIGHTLY_WRONG
     * and "wrist clearly above shoulder" as CORRECT.
     */
    private fun checkRaiseHand(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        isLeft: Boolean
    ): String {
        val wristIdx    = if (isLeft) PoseDetector.LEFT_WRIST    else PoseDetector.RIGHT_WRIST
        val shoulderIdx = if (isLeft) PoseDetector.LEFT_SHOULDER else PoseDetector.RIGHT_SHOULDER
        val hipIdx      = if (isLeft) PoseDetector.LEFT_HIP      else PoseDetector.RIGHT_HIP
        val side        = if (isLeft) "left" else "right"

        val wristY    = y(landmarks, wristIdx)
        val shoulderY = y(landmarks, shoulderIdx)
        val wristX    = x(landmarks, wristIdx)
        val hipX      = x(landmarks, hipIdx)
        val shoulderX = x(landmarks, shoulderIdx)

        // How far wrist is raised above shoulder (positive = above)
        val heightAboveShoulder = shoulderY - wristY

        // How far wrist is extended outward from the body
        // For left side: wrist should be to the left of shoulder (smaller X)
        // For right side: wrist should be to the right of shoulder (larger X)
        val armExtension = if (isLeft) shoulderX - wristX else wristX - shoulderX

        // "Up" = wrist is at or above shoulder level AND arm is extended outward
        val isAtShoulderLevel = heightAboveShoulder > -0.05f && armExtension > 0.10f
        val isAboveShoulder   = heightAboveShoulder > 0.20f

        val isUp = isAtShoulderLevel || isAboveShoulder

        val qualityHint = when {
            isAboveShoulder   -> "CORRECT"
            isAtShoulderLevel -> "SLIGHT"
            else              -> "LOW"
        }

        if (!isUp) maybeFeedback("Raise your $side arm out to the side and up!")

        return countRepDirect(isUp = isUp, qualityHint = qualityHint)
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

    /**
     * TOUCH SHOULDERS
     *
     * Correct sequence:
     *   1. Arms out (T-pose) — this is the REST position
     *   2. Bring hands to shoulders — this is the UP (active) position
     *   3. Return to T-pose — rep complete
     *
     * We require the child to show T-pose FIRST before any rep is counted.
     * This prevents a false rep at exercise start.
     */
    private fun checkTouchShoulders(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ): String {

        val leftWristX     = x(landmarks, PoseDetector.LEFT_WRIST)
        val leftWristY     = y(landmarks, PoseDetector.LEFT_WRIST)

        val rightWristX    = x(landmarks, PoseDetector.RIGHT_WRIST)
        val rightWristY    = y(landmarks, PoseDetector.RIGHT_WRIST)

        val leftShoulderX  = x(landmarks, PoseDetector.LEFT_SHOULDER)
        val leftShoulderY  = y(landmarks, PoseDetector.LEFT_SHOULDER)

        val rightShoulderX = x(landmarks, PoseDetector.RIGHT_SHOULDER)
        val rightShoulderY = y(landmarks, PoseDetector.RIGHT_SHOULDER)

        // ─────────────────────────────
        // REST POSITION = T-pose
        // ─────────────────────────────

        val leftExtended =
            leftShoulderX - leftWristX > 0.16f

        val rightExtended =
            rightWristX - rightShoulderX > 0.16f

        val isInTPose = leftExtended && rightExtended

        // ─────────────────────────────
        // DISTANCE wrist ↔ shoulder
        // ─────────────────────────────

        val leftDistance = Math.sqrt(
            (
                    (leftWristX - leftShoulderX) * (leftWristX - leftShoulderX) +
                            (leftWristY - leftShoulderY) * (leftWristY - leftShoulderY)
                    ).toDouble()
        ).toFloat()

        val rightDistance = Math.sqrt(
            (
                (rightWristX - rightShoulderX) * (rightWristX - rightShoulderX) +
                        (rightWristY - rightShoulderY) * (rightWristY - rightShoulderY)
                ).toDouble()
        ).toFloat()

        // Much more forgiving threshold
        val leftTouching  = leftDistance < 0.22f
        val rightTouching = rightDistance < 0.22f

        val isTouching = leftTouching && rightTouching

        // ─────────────────────────────
        // Must show T-pose first
        // ─────────────────────────────

        if (!isTouching) {
            maybeFeedback("Bring your hands to your shoulders!")
        }

        Log.d(
            TAG,
            "TouchShoulders | leftDist=$leftDistance rightDist=$rightDistance touching=$isTouching"
        )

        return countRepDirect(
            isUp = isTouching,
            qualityHint = if (isTouching) "CORRECT" else "LOW"
        )
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
    /**
     * SIDE STRETCH LEFT = lean body to the RIGHT, right hand on hip
     * SIDE STRETCH RIGHT = lean body to the LEFT, left hand on hip
     *
     * Detection: when leaning right, the LEFT shoulder moves RIGHT (higher X)
     * relative to the left hip. We measure horizontal offset.
     */
    /**
     * SIDE STRETCH LEFT  = child leans to THEIR left
     * SIDE STRETCH RIGHT = child leans to THEIR right
     *
     * Front camera is MIRRORED — when child leans left,
     * their body appears to lean right in raw image coords.
     * So we flip the delta direction to compensate.
     */
    private fun checkSideStretch(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        isLeft: Boolean
    ): String {
        val leftShoulderX  = x(landmarks, PoseDetector.LEFT_SHOULDER)
        val leftHipX       = x(landmarks, PoseDetector.LEFT_HIP)
        val rightShoulderX = x(landmarks, PoseDetector.RIGHT_SHOULDER)
        val rightHipX      = x(landmarks, PoseDetector.RIGHT_HIP)

        // Flipped vs before — compensates for mirrored front camera
        val leanDelta = if (isLeft) {
            // Side Stretch LEFT = child leans left = shoulder drifts LEFT in image
            leftHipX - leftShoulderX      // positive when shoulder goes left of hip
        } else {
            // Side Stretch RIGHT = child leans right = shoulder drifts RIGHT in image
            rightShoulderX - rightHipX    // positive when shoulder goes right of hip
        }

        val isUp      = leanDelta > 0.08f
        val direction = if (isLeft) "left" else "right"

        if (!isUp) maybeFeedback("Lean your whole body to the $direction!")

        return countRepDirect(
            isUp        = isUp,
            qualityHint = when {
                leanDelta > 0.16f -> "CORRECT"
                leanDelta > 0.08f -> "SLIGHT"
                else              -> "LOW"
            }
        )
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
    /**
     * SQUAT detection using knee bend angle.
     *
     * We calculate the angle at the knee: hip → knee → ankle.
     * Standing straight ≈ 170°+
     * Partial squat     ≈ 120–150°
     * Good squat        ≈ 90–120°
     *
     * Rep = goes into squat position (angle drops) then stands back up.
     */
    private fun checkSquat(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ): String {

        // Use left side for detection (more reliable when facing camera)
        val hipX   = x(landmarks, PoseDetector.LEFT_HIP)
        val hipY   = y(landmarks, PoseDetector.LEFT_HIP)
        val kneeX  = x(landmarks, PoseDetector.LEFT_KNEE)
        val kneeY  = y(landmarks, PoseDetector.LEFT_KNEE)
        val ankleX = x(landmarks, PoseDetector.LEFT_ANKLE)
        val ankleY = y(landmarks, PoseDetector.LEFT_ANKLE)

        val angleDegrees = calculateAngle(
            ax = hipX,   ay = hipY,
            bx = kneeX,  by = kneeY,   // vertex = knee
            cx = ankleX, cy = ankleY
        )

        // Lower angle = deeper squat
        val isInSquat = angleDegrees < 140.0   // knee bent enough
        val isGoodSquat = angleDegrees < 110.0  // solid squat depth

        val qualityHint = when {
            isGoodSquat -> "CORRECT"
            isInSquat   -> "SLIGHT"
            else        -> "LOW"
        }

        if (!isInSquat) maybeFeedback("Bend your knees and go lower!")

        Log.v("RepCounter", "Squat angle=${angleDegrees.toInt()}° isInSquat=$isInSquat")

        return countRepDirect(isUp = isInSquat, qualityHint = qualityHint)
    }

    /**
     * Calculate the angle at point B in a triangle A-B-C.
     * Returns degrees 0..180.
     */
    private fun calculateAngle(
        ax: Float, ay: Float,
        bx: Float, by: Float,
        cx: Float, cy: Float
    ): Double {
        // Vectors BA and BC
        val baX = ax - bx
        val baY = ay - by
        val bcX = cx - bx
        val bcY = cy - by

        val dotProduct = (baX * bcX + baY * bcY).toDouble()
        val magBA = Math.sqrt((baX * baX + baY * baY).toDouble())
        val magBC = Math.sqrt((bcX * bcX + bcY * bcY).toDouble())

        if (magBA == 0.0 || magBC == 0.0) return 180.0

        val cosAngle = (dotProduct / (magBA * magBC)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(Math.acos(cosAngle))
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
            // Transitioned to UP position — save quality NOW while pose is correct
            isInUpPosition = true
            repQuality = quality   // ← SAVE IT HERE
            Log.d(TAG, "$exerciseType → UP (quality=$quality)")
        } else if (!isUp && isInUpPosition) {
            // Transitioned back to DOWN — rep complete!
            isInUpPosition = false
            Log.d(TAG, "$exerciseType → DOWN = REP COMPLETED (quality=$repQuality)")
            onRepCompleted(repQuality)   // ← USE SAVED QUALITY, not current
        }

        return "isUp=$isUp, quality=$qualityHint, inUpPos=$isInUpPosition"
    }
}