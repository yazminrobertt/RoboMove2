package com.robomove.app.vision

import android.util.Log
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import com.robomove.app.model.ExerciseType
import com.robomove.app.model.RepQuality

/**
 * RepCounter — ML Kit
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
    private val UP_HOLD_MS           = 200L
    private var lastRepCompletedTime = 0L
    private val REP_COOLDOWN_MS      = 600L
    private var armCirclePhase = 0
    private var imageWidth  = 1
    private var imageHeight = 1
    // Side stretch hysteresis — prevents double count from threshold fluctuation
    private var sideStretchIsLeaning = false

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
        armCirclePhase       = 0
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
        val wristIdx = if (isLeft) resolveLeft(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        else        resolveRight(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        val side = if (isLeft) "left" else "right"

        val noseIdx = PoseLandmark.NOSE

        val wristY = y(pose, wristIdx)
        val noseY  = y(pose, noseIdx)

        // Wrist must be clearly ABOVE the nose to count as "up"
        // In normalised coords, smaller Y = higher on screen
        // So wristY < noseY means wrist is above nose
        val isUp = wristY < noseY - 0.05f  // 5% above nose — strict threshold

        val qualityHint = when {
            wristY < noseY - 0.15f -> "CORRECT"  // well above nose
            wristY < noseY - 0.05f -> "SLIGHT"   // just above nose
            else                   -> "LOW"
        }

        Log.d(TAG, "RaiseHand($side) | wristY=${"%.3f".format(wristY)} noseY=${"%.3f".format(noseY)} isUp=$isUp")

        if (!isUp) maybeFeedback("Raise your $side arm higher — above your head!")
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

        // ── Scale reference: shoulder width ──────────────────────────────────
        // Instead of fixed pixel distance, measure relative to how wide the
        // shoulders appear. This is scale-invariant — works close or far.
        val shoulderWidth = Math.abs(rightShoulderX - leftShoulderX)
            .coerceAtLeast(0.05f)  // prevent divide by zero if detection glitches

        // Raw wrist-to-shoulder distances
        val leftDistRaw = Math.sqrt(
            ((leftWristX - leftShoulderX).toDouble().let { it * it } +
                    (leftWristY - leftShoulderY).toDouble().let { it * it })
        ).toFloat()

        val rightDistRaw = Math.sqrt(
            ((rightWristX - rightShoulderX).toDouble().let { it * it } +
                    (rightWristY - rightShoulderY).toDouble().let { it * it })
        ).toFloat()

        // Normalise by shoulder width — now 1.0 = "one shoulder width away"
        // Touching shoulder = ratio close to 0 (wrist at shoulder position)
        // T-pose / arms out = ratio around 1.0 or more
        val leftRatio  = leftDistRaw  / shoulderWidth
        val rightRatio = rightDistRaw / shoulderWidth

        // Rest = wrists below shoulders (hanging at sides)
        val isAtRest = leftWristY > leftShoulderY && rightWristY > rightShoulderY

        // Touching = wrist within ~60% of one shoulder-width from shoulder
        // This threshold works at any distance because it's relative to body size
        val isTouching = leftRatio < 0.60f && rightRatio < 0.60f

        if (isAtRest) hasShownRestPosition = true

        Log.d(TAG, "TouchShoulders | " +
                "shoulderW=${"%.3f".format(shoulderWidth)} " +
                "L_ratio=${"%.2f".format(leftRatio)} " +
                "R_ratio=${"%.2f".format(rightRatio)} " +
                "touching=$isTouching atRest=$isAtRest hasRest=$hasShownRestPosition")

        if (!hasShownRestPosition) return "Waiting for rest position"
        if (!isTouching) maybeFeedback("Bring your hands up to your shoulders!")

        return countRepDirect(
            isUp        = isTouching,
            qualityHint = when {
                leftRatio < 0.35f && rightRatio < 0.35f -> "CORRECT"
                isTouching                               -> "SLIGHT"
                else                                     -> "LOW"
            }
        )
    }

    private fun checkArmCircles(pose: Pose): String {
        val leftWristIdx     = resolveLeft(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        val rightWristIdx    = resolveRight(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        val leftShoulderIdx  = resolveLeft(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)
        val rightShoulderIdx = resolveRight(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)

        val leftWristY   = y(pose, leftWristIdx)
        val rightWristY  = y(pose, rightWristIdx)
        val leftShoulY   = y(pose, leftShoulderIdx)
        val rightShoulY  = y(pose, rightShoulderIdx)
        val leftWristX   = x(pose, leftWristIdx)
        val rightWristX  = x(pose, rightWristIdx)
        val leftShoulX   = x(pose, leftShoulderIdx)
        val rightShoulX  = x(pose, rightShoulderIdx)

        // Arms at sides = wrists roughly at shoulder height and spread wide
        val leftAtSide   = leftWristY > leftShoulY - 0.05f && leftWristY < leftShoulY + 0.15f &&
                leftWristX < leftShoulX - 0.10f
        val rightAtSide  = rightWristY > rightShoulY - 0.05f && rightWristY < rightShoulY + 0.15f &&
                rightWristX > rightShoulX + 0.10f
        val atSides      = leftAtSide && rightAtSide

        // Arms at top = wrists above shoulders
        val atTop = leftWristY < leftShoulY - 0.15f && rightWristY < rightShoulY - 0.15f

        // Arms below = wrists below shoulders (start/end of circle)
        val atBottom = leftWristY > leftShoulY + 0.10f && rightWristY > rightShoulY + 0.10f

        // Forward circle phase progression: bottom → sides → top → bottom
        when (armCirclePhase) {
            0 -> if (atSides || atTop) {
                // Started moving — entered sides or went straight up
                armCirclePhase = 1
                Log.d(TAG, "ArmCircle: phase 0→1 (sides/up detected)")
            }
            1 -> if (atTop) {
                armCirclePhase = 2
                Log.d(TAG, "ArmCircle: phase 1→2 (top reached)")
            }
            2 -> if (atBottom) {
                // Completed full forward circle
                armCirclePhase = 0
                Log.d(TAG, "ArmCircle: phase 2→0 (bottom — rep complete)")
            }
        }

        // isUp = reached top phase — this drives countRepDirect
        val isUp = armCirclePhase == 2

        Log.d(TAG, "ArmCircle | phase=$armCirclePhase atSides=$atSides atTop=$atTop atBottom=$atBottom")

        if (!atTop && !atSides) maybeFeedback("Keep your arms extended and make big forward circles!")
        return countRepDirect(isUp = isUp, qualityHint = if (isUp) "CORRECT" else "SLIGHT")
    }

    private fun checkSideStretch(pose: Pose, isLeft: Boolean): String {
        val leftShoulderIdx  = resolveLeft(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)
        val leftHipIdx       = resolveLeft(PoseDetector.LEFT_HIP, PoseDetector.RIGHT_HIP)
        val rightShoulderIdx = resolveRight(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)
        val rightHipIdx      = resolveRight(PoseDetector.LEFT_HIP, PoseDetector.RIGHT_HIP)
        val leftWristIdx     = resolveLeft(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        val rightWristIdx    = resolveRight(PoseDetector.LEFT_WRIST, PoseDetector.RIGHT_WRIST)
        val leftShoulderIdx2 = resolveLeft(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)
        val rightShoulderIdx2= resolveRight(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER)

        val leftShoulderX  = x(pose, leftShoulderIdx)
        val leftHipX       = x(pose, leftHipIdx)
        val rightShoulderX = x(pose, rightShoulderIdx)
        val rightHipX      = x(pose, rightHipIdx)
        val leftWristY     = y(pose, leftWristIdx)
        val rightWristY    = y(pose, rightWristIdx)
        val leftShoulderY  = y(pose, leftShoulderIdx2)
        val rightShoulderY = y(pose, rightShoulderIdx2)

        // Scale by body width so threshold works at any distance
        val leftHipX2  = x(pose, resolveLeft(PoseDetector.LEFT_HIP, PoseDetector.RIGHT_HIP))
        val rightHipX2 = x(pose, resolveRight(PoseDetector.LEFT_HIP, PoseDetector.RIGHT_HIP))
        val bodyWidth  = Math.abs(rightHipX2 - leftHipX2).coerceAtLeast(0.05f)

        val leanDelta: Float
        val armRaised: Boolean
        val direction: String

        if (isLeft) {
            armRaised  = leftWristY < leftShoulderY - 0.10f
            leanDelta  = (leftShoulderX - leftHipX) / bodyWidth
            direction  = "right"
            Log.d(TAG, "SideStretchLeft | armRaised=$armRaised leanRatio=${"%.3f".format(leanDelta)} leaning=$sideStretchIsLeaning")
        } else {
            armRaised  = rightWristY < rightShoulderY - 0.10f
            leanDelta  = (rightHipX - rightShoulderX) / bodyWidth
            direction  = "left"
            Log.d(TAG, "SideStretchRight | armRaised=$armRaised leanRatio=${"%.3f".format(leanDelta)} leaning=$sideStretchIsLeaning")
        }

        // ── Hysteresis entirely inside checkSideStretch ───────────────────────
        // ENTER lean state: arm raised + lean ratio > 0.35
        // EXIT lean state:  lean ratio drops below 0.12 (wide gap = no flickering)
        // isUp fed to countRepDirect is stable — only flips at clear transitions
        if (!sideStretchIsLeaning) {
            if (armRaised && leanDelta > 0.35f) {
                sideStretchIsLeaning = true
            }
        } else {
            if (leanDelta < 0.12f) {
                sideStretchIsLeaning = false
            }
        }

        val isUp = sideStretchIsLeaning

        if (!isUp) maybeFeedback("Raise your arm and lean to the $direction!")
        return countRepDirect(
            isUp        = isUp,
            qualityHint = when {
                leanDelta > 0.50f -> "CORRECT"
                leanDelta > 0.35f -> "SLIGHT"
                else              -> "LOW"
            }
        )
    }

    private fun checkKneeLift(pose: Pose, isLeft: Boolean): String {
        val kneeIdx = if (isLeft) resolveLeft(PoseDetector.LEFT_KNEE, PoseDetector.RIGHT_KNEE)
        else        resolveRight(PoseDetector.LEFT_KNEE, PoseDetector.RIGHT_KNEE)
        val hipIdx  = if (isLeft) resolveLeft(PoseDetector.LEFT_HIP, PoseDetector.RIGHT_HIP)
        else        resolveRight(PoseDetector.LEFT_HIP, PoseDetector.RIGHT_HIP)
        val side    = if (isLeft) "left" else "right"

        val kneeAboveHip = y(pose, hipIdx) - y(pose, kneeIdx)
        val isUp = kneeAboveHip > 0.03f

        Log.d("KneeLift", "$side | diff=${"%.3f".format(kneeAboveHip)} isUp=$isUp")

        if (!isUp) maybeFeedback("Lift your $side knee higher!")

        return countRepDirect(
            isUp        = isUp,
            qualityHint = if (kneeAboveHip > 0.10f) "CORRECT"
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