package com.robomove.app.vision

import android.util.Log
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import com.robomove.app.model.ExerciseType
import com.robomove.app.model.RepQuality

/**
 * RepCounter — ML Kit version.
 *
 * Logic is identical to the MediaPipe version.
 * Only difference: landmarks come from Pose.getPoseLandmark()
 * instead of a list, and coordinates are pixel-based so we
 * normalise using imageWidth / imageHeight to keep all
 * thresholds the same as before.
 */
class RepCounter(
    private val exerciseType: ExerciseType,
    private val onRepCompleted: (quality: RepQuality) -> Unit,
    private val onPoseFeedback: (message: String) -> Unit
) {

    companion object {
        private const val TAG = "RepCounter"
    }

    // ── State machine ──
    private var isFrontCamera = true
    private var isInUpPosition       = false
    private var hasShownRestPosition = false
    private var repQuality           = RepQuality.WRONG
    private var lastFeedbackTime     = 0L
    private val FEEDBACK_COOLDOWN_MS = 3000L

    // ── Debounce ──
    private var upPositionStartTime  = 0L
    private val UP_HOLD_MS           = 400L
    private var lastRepCompletedTime = 0L
    private val REP_COOLDOWN_MS      = 800L

    // ── Image dimensions for normalising ML Kit pixel coords ──
    private var imageWidth  = 1
    private var imageHeight = 1

    /** Call this every frame before processLandmarks */
    fun setImageDimensions(width: Int, height: Int, isFrontCamera: Boolean = true) {
        imageWidth         = width
        imageHeight        = height
        this.isFrontCamera = isFrontCamera
    }

    /**
     * When using back camera, LEFT and RIGHT are not swapped by ML Kit
     * but the child is facing the camera so their left appears on our right.
     * We swap the landmark index to compensate.
     */
    private fun resolveLeft(leftLandmark: Int, rightLandmark: Int): Int {
        return if (isFrontCamera) leftLandmark else rightLandmark
    }

    private fun resolveRight(leftLandmark: Int, rightLandmark: Int): Int {
        return if (isFrontCamera) rightLandmark else leftLandmark
    }

    /**
     * Main entry point — call every frame with new pose.
     * Returns status string for Logcat debugging.
     */
    fun processLandmarks(pose: Pose): String {
        // If nose not found — no person detected
        if (pose.getPoseLandmark(PoseLandmark.NOSE) == null) {
            return "No pose detected"
        }

        return when (exerciseType) {
            ExerciseType.RAISE_LEFT_HAND    -> checkRaiseHand(pose, isLeft = true)
            ExerciseType.RAISE_RIGHT_HAND   -> checkRaiseHand(pose, isLeft = false)
            ExerciseType.BOTH_HANDS_UP      -> checkBothHandsUp(pose)
            ExerciseType.TOUCH_SHOULDERS    -> checkTouchShoulders(pose)
            ExerciseType.ARM_CIRCLES        -> checkArmCircles(pose)
            ExerciseType.SIDE_STRETCH_LEFT  -> checkSideStretch(pose, isLeft = true)
            ExerciseType.SIDE_STRETCH_RIGHT -> checkSideStretch(pose, isLeft = false)
            ExerciseType.KNEE_LIFT_LEFT     -> checkKneeLift(pose, isLeft = true)
            ExerciseType.KNEE_LIFT_RIGHT    -> checkKneeLift(pose, isLeft = false)
            ExerciseType.CROSS_BODY_LEFT    -> checkCrossBody(pose, isLeft = true)
            ExerciseType.CROSS_BODY_RIGHT   -> checkCrossBody(pose, isLeft = false)
            ExerciseType.JUMPING_JACK       -> checkJumpingJack(pose)
            ExerciseType.SQUAT              -> checkSquat(pose)
            ExerciseType.CLAP_ABOVE_HEAD    -> checkClapAboveHead(pose)
        }
    }

    fun reset() {
        isInUpPosition       = false
        hasShownRestPosition = false
        repQuality           = RepQuality.WRONG
        upPositionStartTime  = 0L
        lastRepCompletedTime = 0L
        Log.d(TAG, "RepCounter reset for $exerciseType")
    }

    // ─────────────────────────────────────────
    // COORDINATE HELPERS
    // Normalises ML Kit pixel coords to 0..1
    // so all thresholds stay identical to before
    // ─────────────────────────────────────────

    private fun y(pose: Pose, landmarkType: Int): Float {
        return (pose.getPoseLandmark(landmarkType)?.position?.y ?: 0f) / imageHeight
    }

    private fun x(pose: Pose, landmarkType: Int): Float {
        return (pose.getPoseLandmark(landmarkType)?.position?.x ?: 0f) / imageWidth
    }

    private fun maybeFeedback(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastFeedbackTime > FEEDBACK_COOLDOWN_MS) {
            lastFeedbackTime = now
            onPoseFeedback(message)
        }
    }

    // ─────────────────────────────────────────
    // EXERCISE CHECKERS
    // All logic identical to MediaPipe version
    // ─────────────────────────────────────────

    private fun checkRaiseHand(pose: Pose, isLeft: Boolean): String {
        val wristIdx    = if (isLeft) resolveLeft(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        else        resolveRight(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        val shoulderIdx = if (isLeft) resolveLeft(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)
        else        resolveRight(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)
        val side        = if (isLeft) "left" else "right"

        val wristY              = y(pose, wristIdx)
        val shoulderY           = y(pose, shoulderIdx)
        val wristX              = x(pose, wristIdx)
        val shoulderX           = x(pose, shoulderIdx)

        val heightAboveShoulder = shoulderY - wristY
        val armExtension        = if (isLeft) shoulderX - wristX else wristX - shoulderX

        val isAtShoulderLevel   = heightAboveShoulder > -0.05f && armExtension > 0.10f
        val isAboveShoulder     = heightAboveShoulder > 0.20f
        val isUp                = isAtShoulderLevel || isAboveShoulder

        val qualityHint = when {
            isAboveShoulder   -> "CORRECT"
            isAtShoulderLevel -> "SLIGHT"
            else              -> "LOW"
        }

        if (!isUp) maybeFeedback("Raise your $side arm out to the side and up!")
        return countRepDirect(isUp = isUp, qualityHint = qualityHint)
    }

    private fun checkBothHandsUp(pose: Pose): String {
        val leftShoulderIdx  = resolveLeft(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)
        val leftWristIdx     = resolveLeft(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        val rightShoulderIdx = resolveRight(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)
        val rightWristIdx    = resolveRight(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)

        val leftDiff  = y(pose, leftShoulderIdx)  - y(pose, leftWristIdx)
        val rightDiff = y(pose, rightShoulderIdx) - y(pose, rightWristIdx)
        val bothUp    = leftDiff > 0.20f && rightDiff > 0.20f
        val quality   = if (leftDiff > 0.35f && rightDiff > 0.35f) "CORRECT"
        else if (bothUp) "SLIGHT" else "LOW"

        if (!bothUp) maybeFeedback("Raise BOTH arms above your head!")
        return countRepDirect(isUp = bothUp, qualityHint = quality)
    }

    private fun checkTouchShoulders(pose: Pose): String {
        val leftWristIdx     = resolveLeft(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        val rightWristIdx    = resolveRight(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        val leftShoulderIdx  = resolveLeft(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)
        val rightShoulderIdx = resolveRight(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)

        val leftWristX     = x(pose, leftWristIdx)
        val leftWristY     = y(pose, leftWristIdx)
        val rightWristX    = x(pose, rightWristIdx)
        val rightWristY    = y(pose, rightWristIdx)
        val leftShoulderX  = x(pose, leftShoulderIdx)
        val leftShoulderY  = y(pose, leftShoulderIdx)
        val rightShoulderX = x(pose, rightShoulderIdx)
        val rightShoulderY = y(pose, rightShoulderIdx)

        val leftExtended  = leftShoulderX - leftWristX > 0.16f
        val rightExtended = rightWristX - rightShoulderX > 0.16f
        val isInTPose     = leftExtended && rightExtended

        val leftDistance = Math.sqrt(
            ((leftWristX - leftShoulderX).toDouble().let { it * it } +
                    (leftWristY - leftShoulderY).toDouble().let { it * it })
        ).toFloat()

        val rightDistance = Math.sqrt(
            ((rightWristX - rightShoulderX).toDouble().let { it * it } +
                    (rightWristY - rightShoulderY).toDouble().let { it * it })
        ).toFloat()

        val isTouching = leftDistance < 0.22f && rightDistance < 0.22f

        if (isInTPose) hasShownRestPosition = true
        if (!hasShownRestPosition) return "Waiting for T-pose rest position"
        if (!isTouching) maybeFeedback("Bring your hands to your shoulders!")

        return countRepDirect(isUp = isTouching, qualityHint = if (isTouching) "CORRECT" else "LOW")
    }

    private fun checkArmCircles(pose: Pose): String {
        val leftWristIdx    = resolveLeft(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        val leftShoulderIdx = resolveLeft(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)
        val rightWristIdx   = resolveRight(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        val rightShoulderIdx= resolveRight(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)

        val leftAbove  = y(pose, leftWristIdx)  < y(pose, leftShoulderIdx)
        val rightAbove = y(pose, rightWristIdx) < y(pose, rightShoulderIdx)
        val isUp       = leftAbove && rightAbove
        if (!isUp) maybeFeedback("Keep your arms extended and make big circles!")
        return countRepDirect(isUp = isUp, qualityHint = if (isUp) "CORRECT" else "SLIGHT")
    }

    private fun checkSideStretch(pose: Pose, isLeft: Boolean): String {
        val leftShoulderIdx  = resolveLeft(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)
        val leftHipIdx       = resolveLeft(PoseDetector.LEFT_HIP, PoseDetector.RIGHT_HIP)
        val rightShoulderIdx = resolveRight(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)
        val rightHipIdx      = resolveRight(PoseDetector.LEFT_HIP, PoseDetector.RIGHT_HIP)

        val leftShoulderX  = x(pose, leftShoulderIdx)
        val leftHipX       = x(pose, leftHipIdx)
        val rightShoulderX = x(pose, rightShoulderIdx)
        val rightHipX      = x(pose, rightHipIdx)

        val leanDelta = if (isLeft) leftHipX - leftShoulderX
        else        rightShoulderX - rightHipX

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

    private fun checkKneeLift(pose: Pose, isLeft: Boolean): String {
        val kneeIdx      = if (isLeft) resolveLeft(PoseDetector.LEFT_KNEE, PoseDetector.RIGHT_KNEE)
        else        resolveRight(PoseDetector.LEFT_KNEE, PoseDetector.RIGHT_KNEE)
        val hipIdx       = if (isLeft) resolveLeft(PoseDetector.LEFT_HIP, PoseDetector.RIGHT_HIP)
        else        resolveRight(PoseDetector.LEFT_HIP, PoseDetector.RIGHT_HIP)
        val side         = if (isLeft) "left" else "right"
        val kneeAboveHip = y(pose, hipIdx) - y(pose, kneeIdx)
        val isUp         = kneeAboveHip > 0.05f

        if (!isUp) maybeFeedback("Lift your $side knee higher!")
        return countRepDirect(
            isUp        = isUp,
            qualityHint = if (kneeAboveHip > 0.12f) "CORRECT"
            else if (isUp) "SLIGHT" else "LOW"
        )
    }

    private fun checkCrossBody(pose: Pose, isLeft: Boolean): String {
        // isLeft = touch left knee with right hand
        val handIdx  = if (isLeft) resolveRight(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        else        resolveLeft(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        val kneeIdx  = if (isLeft) resolveLeft(PoseDetector.LEFT_KNEE, PoseDetector.RIGHT_KNEE)
        else        resolveRight(PoseDetector.LEFT_KNEE, PoseDetector.RIGHT_KNEE)
        val side     = if (isLeft) "left" else "right"
        val distX    = Math.abs(x(pose, handIdx) - x(pose, kneeIdx))
        val distY    = Math.abs(y(pose, handIdx) - y(pose, kneeIdx))
        val distance = Math.sqrt((distX * distX + distY * distY).toDouble()).toFloat()
        val isUp     = distance < 0.18f

        if (!isUp) maybeFeedback("Reach your hand to your $side knee!")
        return countRepDirect(
            isUp        = isUp,
            qualityHint = if (distance < 0.10f) "CORRECT" else if (isUp) "SLIGHT" else "LOW"
        )
    }

    private fun checkJumpingJack(pose: Pose): String {
        val leftWristIdx    = resolveLeft(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        val leftShoulderIdx = resolveLeft(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)
        val rightWristIdx   = resolveRight(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        val rightShoulderIdx= resolveRight(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)
        val leftAnkleIdx    = resolveLeft(PoseDetector.LEFT_ANKLE, PoseDetector.RIGHT_ANKLE)
        val rightAnkleIdx   = resolveRight(PoseDetector.LEFT_ANKLE, PoseDetector.RIGHT_ANKLE)

        val armsUp    = y(pose, leftWristIdx)  < y(pose, leftShoulderIdx) &&
                y(pose, rightWristIdx) < y(pose, rightShoulderIdx)
        val legsApart = Math.abs(x(pose, leftAnkleIdx) - x(pose, rightAnkleIdx)) > 0.25f
        val isUp = armsUp && legsApart

        if (!armsUp)    maybeFeedback("Raise both arms higher!")
        if (!legsApart) maybeFeedback("Spread your legs wider!")
        return countRepDirect(isUp = isUp, qualityHint = if (isUp) "CORRECT" else "SLIGHT")
    }

    private fun checkSquat(pose: Pose): String {
        val hipIdx   = resolveLeft(PoseDetector.LEFT_HIP, PoseDetector.RIGHT_HIP)
        val kneeIdx  = resolveLeft(PoseDetector.LEFT_KNEE, PoseDetector.RIGHT_KNEE)
        val ankleIdx = resolveLeft(PoseDetector.LEFT_ANKLE, PoseDetector.RIGHT_ANKLE)

        val hipX   = x(pose, hipIdx)
        val hipY   = y(pose, hipIdx)
        val kneeX  = x(pose, kneeIdx)
        val kneeY  = y(pose, kneeIdx)
        val ankleX = x(pose, ankleIdx)
        val ankleY = y(pose, ankleIdx)

        val angleDegrees = calculateAngle(hipX, hipY, kneeX, kneeY, ankleX, ankleY)
        val isInSquat    = angleDegrees < 140.0
        val isGoodSquat  = angleDegrees < 110.0

        if (!isInSquat) maybeFeedback("Bend your knees and go lower!")
        Log.v(TAG, "Squat angle=${angleDegrees.toInt()}° inSquat=$isInSquat")

        return countRepDirect(
            isUp        = isInSquat,
            qualityHint = when {
                isGoodSquat -> "CORRECT"
                isInSquat   -> "SLIGHT"
                else        -> "LOW"
            }
        )
    }

    private fun checkClapAboveHead(pose: Pose): String {
        val leftWristIdx    = resolveLeft(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        val leftShoulderIdx = resolveLeft(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)
        val rightWristIdx   = resolveRight(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        val rightShoulderIdx= resolveRight(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)

        val leftAbove  = y(pose, leftWristIdx)  < y(pose, leftShoulderIdx)  - 0.15f
        val rightAbove = y(pose, rightWristIdx) < y(pose, rightShoulderIdx) - 0.15f
        val handsClose = Math.abs(x(pose, leftWristIdx) - x(pose, rightWristIdx)) < 0.15f
        val isUp = leftAbove && rightAbove && handsClose

        if (!leftAbove || !rightAbove) maybeFeedback("Raise both hands above your head!")
        if (!handsClose)               maybeFeedback("Bring your hands together to clap!")
        return countRepDirect(isUp = isUp, qualityHint = if (isUp) "CORRECT" else "SLIGHT")
    }

    // ─────────────────────────────────────────
    // ANGLE HELPER
    // ─────────────────────────────────────────

    private fun calculateAngle(
        ax: Float, ay: Float,
        bx: Float, by: Float,
        cx: Float, cy: Float
    ): Double {
        val baX = ax - bx;  val baY = ay - by
        val bcX = cx - bx;  val bcY = cy - by
        val dot = (baX * bcX + baY * bcY).toDouble()
        val magBA = Math.sqrt((baX * baX + baY * baY).toDouble())
        val magBC = Math.sqrt((bcX * bcX + bcY * bcY).toDouble())
        if (magBA == 0.0 || magBC == 0.0) return 180.0
        return Math.toDegrees(Math.acos((dot / (magBA * magBC)).coerceIn(-1.0, 1.0)))
    }

    // ─────────────────────────────────────────
    // STATE MACHINE WITH DEBOUNCE
    // ─────────────────────────────────────────

    private fun countRepDirect(isUp: Boolean, qualityHint: String): String {
        val quality = when (qualityHint) {
            "CORRECT" -> RepQuality.CORRECT
            "SLIGHT"  -> RepQuality.SLIGHTLY_WRONG
            else      -> RepQuality.WRONG
        }

        val now = System.currentTimeMillis()

        if (isUp && !isInUpPosition) {
            isInUpPosition      = true
            upPositionStartTime = now
            repQuality          = quality
            Log.d(TAG, "$exerciseType → entered UP (quality=$quality)")

        } else if (isUp && isInUpPosition) {
            if (quality == RepQuality.CORRECT) repQuality = RepQuality.CORRECT

        } else if (!isUp && isInUpPosition) {
            val heldDuration = now - upPositionStartTime
            if (heldDuration >= UP_HOLD_MS) {
                val timeSinceLastRep = now - lastRepCompletedTime
                if (timeSinceLastRep >= REP_COOLDOWN_MS) {
                    isInUpPosition       = false
                    lastRepCompletedTime = now
                    Log.d(TAG, "$exerciseType → REP DONE held=${heldDuration}ms quality=$repQuality")
                    onRepCompleted(repQuality)
                } else {
                    isInUpPosition = false
                    Log.d(TAG, "$exerciseType → rep ignored cooldown timeSince=${timeSinceLastRep}ms")
                }
            } else {
                isInUpPosition = false
                Log.d(TAG, "$exerciseType → wobble ignored held=${heldDuration}ms")
            }
        }

        return "isUp=$isUp quality=$qualityHint held=${now - upPositionStartTime}ms inUp=$isInUpPosition"
    }
}