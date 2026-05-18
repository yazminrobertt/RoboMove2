package com.robomove.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.robomove.app.model.ExerciseType
import com.robomove.app.model.RepQuality
import java.util.Locale

/**
 * FeedbackManager — calmer version for children.
 *
 * Rules:
 *  1. Exercise instruction is always spoken fully first.
 *     No rep feedback fires until INSTRUCTION_LOCKOUT_MS has passed.
 *  2. Encouragement is only spoken occasionally (every N correct reps).
 *  3. Correction feedback has a longer cooldown — not every frame.
 *  4. No mid-movement spam — corrections only fire after a rep attempt.
 */
class FeedbackManager(context: Context) {

    companion object {
        private const val TAG = "FeedbackManager"

        // After speaking an instruction, wait this long before any rep feedback
        private const val INSTRUCTION_LOCKOUT_MS = 4000L

        // Minimum gap between correction messages
        private const val CORRECTION_COOLDOWN_MS = 5000L

        // Minimum gap between encouragement messages
        private const val ENCOURAGEMENT_COOLDOWN_MS = 3000L

        // Only cheer every N correct reps (not every single one)
        private const val CHEER_EVERY_N_REPS = 2
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var onReadyCallback: (() -> Unit)? = null
    private var lastCorrectionTime    = 0L
    private var lastEncouragementTime = 0L
    private var instructionSpokenAt   = 0L   // timestamp of last instruction
    private var correctRepCount       = 0    // counts up, resets each exercise

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                isReady = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setSpeechRate(0.85f)
                tts?.setPitch(1.1f)
                Log.d(TAG, "TTS ready: $isReady")
                // Notify caller that TTS is ready
                onReadyCallback?.invoke()
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    // ─────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────

    /**
     * Set a callback that fires once TTS is fully initialized.
     * Use this to delay the first exercise instruction until TTS is ready.
     * If TTS is already ready when this is called, fires immediately.
     */
    fun setOnReadyCallback(callback: () -> Unit) {
        if (isReady) {
            // Already ready — fire immediately
            callback()
        } else {
            onReadyCallback = callback
        }
    }

    /**
     * Speak the exercise name + instruction.
     * Locks out rep feedback for INSTRUCTION_LOCKOUT_MS afterwards.
     * Call this when a new exercise loads.
     */
    fun speakExerciseName(name: String, instruction: String) {
        correctRepCount = 0
        instructionSpokenAt = System.currentTimeMillis()
        speakImmediate("$name. $instruction")
        Log.d(TAG, "Instruction spoken for: $name")
    }

    /**
     * Called after a rep completes.
     * Only speaks if lockout has passed.
     */
    fun speakRepFeedback(quality: RepQuality, exerciseType: ExerciseType) {
        if (isInInstructionLockout()) {
            Log.d(TAG, "Rep feedback suppressed — instruction lockout active")
            return
        }

        when (quality) {
            RepQuality.CORRECT,
            RepQuality.SLIGHTLY_WRONG -> maybeCheer()

            RepQuality.WRONG -> maybeCorrect(exerciseType)
        }
    }

    /**
     * Called mid-movement when pose looks wrong.
     * Uses its own cooldown — less frequent than rep feedback.
     */
    fun speakCorrection(exerciseType: ExerciseType) {
        if (isInInstructionLockout()) return
        maybeCorrect(exerciseType)
    }

    fun speakLevelStart(levelNumber: Int, message: String) {
        speakImmediate("Level $levelNumber! $message")
    }

    fun speakLevelComplete(levelNumber: Int, score: Int) {
        speakImmediate("Level $levelNumber complete! Your score is $score. Amazing work!")
    }

    fun speakGameComplete(totalScore: Int) {
        speakImmediate("You finished all levels! Your total score is $totalScore. You are a champion!")
    }

    fun speakCustom(message: String) {
        speakImmediate(message)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        Log.d(TAG, "TTS shut down")
    }

    // ─────────────────────────────────────────
    // PRIVATE — LOGIC
    // ─────────────────────────────────────────

    private fun isInInstructionLockout(): Boolean {
        val elapsed = System.currentTimeMillis() - instructionSpokenAt
        return elapsed < INSTRUCTION_LOCKOUT_MS
    }

    private fun maybeCheer() {
        correctRepCount++

        // Only cheer every N reps, not every single rep
        if (correctRepCount % CHEER_EVERY_N_REPS != 0) {
            Log.d(TAG, "Cheer skipped — rep $correctRepCount (cheering every $CHEER_EVERY_N_REPS)")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastEncouragementTime < ENCOURAGEMENT_COOLDOWN_MS) {
            Log.d(TAG, "Cheer suppressed — cooldown")
            return
        }

        lastEncouragementTime = now
        speakImmediate(getEncouragement())
    }

    private fun maybeCorrect(exerciseType: ExerciseType) {
        val now = System.currentTimeMillis()
        if (now - lastCorrectionTime < CORRECTION_COOLDOWN_MS) {
            Log.d(TAG, "Correction suppressed — cooldown")
            return
        }
        lastCorrectionTime = now
        speakImmediate(getCorrection(exerciseType))
    }

    private fun speakImmediate(text: String) {
        if (!isReady) {
            Log.w(TAG, "TTS not ready, skipping: $text")
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "robomove_tts")
        Log.d(TAG, "TTS: \"$text\"")
    }

    // ─────────────────────────────────────────
    // MESSAGE BANKS
    // ─────────────────────────────────────────

    private fun getEncouragement(): String {
        return listOf(
            "Good job!", "Keep it up!", "Awesome!", "Well done!",
            "You're doing great!", "Fantastic!", "Keep going!"
        ).random()
    }

    private fun getCorrection(type: ExerciseType): String {
        return when (type) {
            ExerciseType.RAISE_LEFT_HAND    -> "Raise your left arm out to the side and up."
            ExerciseType.RAISE_RIGHT_HAND   -> "Raise your right arm out to the side and up."
            ExerciseType.BOTH_HANDS_UP      -> "Raise both arms above your head."
            ExerciseType.TOUCH_SHOULDERS    -> "Bring your hands to your shoulders."
            ExerciseType.ARM_CIRCLES        -> "Stretch your arms out and make big circles."
            ExerciseType.SIDE_STRETCH_LEFT  -> "Lean your body gently to the left."
            ExerciseType.SIDE_STRETCH_RIGHT -> "Lean your body gently to the right."
            ExerciseType.KNEE_LIFT_LEFT     -> "Lift your left knee up."
            ExerciseType.KNEE_LIFT_RIGHT    -> "Lift your right knee up."
            ExerciseType.CROSS_BODY_LEFT    -> "Reach your right hand to your left knee."
            ExerciseType.CROSS_BODY_RIGHT   -> "Reach your left hand to your right knee."
            ExerciseType.JUMPING_JACK       -> "Jump and spread your arms and legs wide."
            ExerciseType.SQUAT             -> "Bend your knees and lower yourself down."
            ExerciseType.CLAP_ABOVE_HEAD   -> "Raise both hands and clap above your head."
        }
    }
}