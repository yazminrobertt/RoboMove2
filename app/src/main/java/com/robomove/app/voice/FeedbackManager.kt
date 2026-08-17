package com.robomove.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.robomove.app.model.ExerciseType
import com.robomove.app.model.RepQuality
import java.util.Locale

class FeedbackManager(context: Context) {

    companion object {
        private const val TAG = "FeedbackManager"
        private const val INSTRUCTION_UTTERANCE_ID = "robomove_instruction"
        private const val CHEER_EVERY_N_REPS = 2
        private const val CORRECTION_COOLDOWN_MS = 4000L       // was 5000L
        private const val ENCOURAGEMENT_COOLDOWN_MS = 2000L    // was 3000L
        private const val POST_INSTRUCTION_GRACE_MS = 2000L    //
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var onReadyCallback: (() -> Unit)? = null
    private var lastCorrectionTime    = 0L
    private var lastEncouragementTime = 0L
    private var instructionDoneAt     = 0L
    private var correctRepCount       = 0

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                isReady = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setSpeechRate(0.95f)
                tts?.setPitch(1.05f)

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == INSTRUCTION_UTTERANCE_ID) {
                            instructionDoneAt = System.currentTimeMillis()
                            Log.d(TAG, "Instruction done — grace period started")
                        }
                    }

                    @Deprecated("Required override for API < 21")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == INSTRUCTION_UTTERANCE_ID) {
                            instructionDoneAt = System.currentTimeMillis()
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == INSTRUCTION_UTTERANCE_ID) {
                            instructionDoneAt = System.currentTimeMillis()
                        }
                    }
                })

                Log.d(TAG, "TTS ready: $isReady")
                onReadyCallback?.invoke()
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    // ─────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────

    fun setOnReadyCallback(callback: () -> Unit) {
        if (isReady) callback() else onReadyCallback = callback
    }

    fun speakExerciseName(name: String, instruction: String) {
        correctRepCount = 0
        instructionDoneAt = 0L  // lock until utterance completes

        tts?.speak(
            "$name. $instruction",
            TextToSpeech.QUEUE_FLUSH,
            null,
            INSTRUCTION_UTTERANCE_ID
        )
        Log.d(TAG, "Instruction queued for: $name")
    }

    fun speakRepFeedback(quality: RepQuality, exerciseType: ExerciseType) {
        if (isInstructionBlocking()) {
            Log.d(TAG, "Rep feedback suppressed — grace period active")
            return
        }
        when (quality) {
            RepQuality.CORRECT,
            RepQuality.SLIGHTLY_WRONG -> maybeCheer()
            RepQuality.WRONG          -> maybeCorrect(exerciseType)
        }
    }

    fun speakCorrection(exerciseType: ExerciseType) {
        if (isInstructionBlocking()) return
        maybeCorrect(exerciseType)
    }

    fun speakLevelStart(levelNumber: Int, message: String) {
        speakImmediate("Level $levelNumber! $message")
    }

    fun speakLevelComplete(levelNumber: Int, score: Int) {
        speakImmediate("Level $levelNumber complete! Your score is $score. Amazing work!")
    }

    fun speakGameComplete(totalScore: Int, endedEarly: Boolean = false) {
        val message = when {
            endedEarly && totalScore == 0 ->
                "Game over! Your score is zero. Keep practising and try again!"
            endedEarly ->
                "Game ended early. Your score is $totalScore. Come back and finish next time!"
            totalScore == 0 ->
                "You finished all levels! Keep practising to score some points next time!"
            totalScore < 200 ->
                "You finished all levels! Your score is $totalScore. Keep it up, you are improving!"
            totalScore < 400 ->
                "Great effort! Your score is $totalScore. You are getting stronger!"
            totalScore < 600 ->
                "Amazing work! Your score is $totalScore. You are nearly a champion!"
            else ->
                "You finished all levels! Your score is $totalScore. You are a champion!"
        }
        speakImmediate(message)
    }

    fun speakCustom(message: String) {
        speakImmediate(message)
    }

    fun stopSpeaking() {
        tts?.stop()
        // Unlock immediately — don't leave feedback blocked while paused
        instructionDoneAt = System.currentTimeMillis() - POST_INSTRUCTION_GRACE_MS
        Log.d(TAG, "TTS stopped")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    // ─────────────────────────────────────────
    // PRIVATE — LOGIC
    // ─────────────────────────────────────────

    private fun isInstructionBlocking(): Boolean {
        if (instructionDoneAt == 0L) return true  // instruction not finished yet
        return System.currentTimeMillis() - instructionDoneAt < POST_INSTRUCTION_GRACE_MS
    }

    private fun maybeCheer() {
        correctRepCount++
        if (correctRepCount % CHEER_EVERY_N_REPS != 0) return
        val now = System.currentTimeMillis()
        if (now - lastEncouragementTime < ENCOURAGEMENT_COOLDOWN_MS) return
        lastEncouragementTime = now
        speakImmediate(getEncouragement())
    }

    private fun maybeCorrect(exerciseType: ExerciseType) {
        val now = System.currentTimeMillis()
        if (now - lastCorrectionTime < CORRECTION_COOLDOWN_MS) return
        lastCorrectionTime = now
        speakImmediate(getCorrection(exerciseType))
    }

    private fun speakImmediate(text: String) {
        if (!isReady) { Log.w(TAG, "TTS not ready: $text"); return }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "robomove_tts")
        Log.d(TAG, "TTS: \"$text\"")
    }

    // ─────────────────────────────────────────
    // MESSAGE BANKS
    // ─────────────────────────────────────────

    private fun getEncouragement() = listOf(
        "Good job!", "Keep it up!", "Awesome!", "Well done!",
        "You're doing great!", "Fantastic!", "Keep going!"
    ).random()

    private val correctionBanks: Map<ExerciseType, List<String>> = mapOf(
        ExerciseType.RAISE_LEFT_HAND    to listOf(
            "Raise your left arm up!",
            "Left arm out and up!",
            "Lift your left arm higher!"
        ),
        ExerciseType.RAISE_RIGHT_HAND   to listOf(
            "Raise your right arm up!",
            "Right arm out and up!",
            "Lift your right arm higher!"
        ),
        ExerciseType.BOTH_HANDS_UP      to listOf(
            "Raise both arms above your head!",
            "Both arms up high!",
            "Stretch both arms up!"
        ),
        ExerciseType.TOUCH_SHOULDERS    to listOf(
            "Bring your hands to your shoulders.",
            "Touch your shoulders with both hands.",
            "Hands to shoulders!"
        ),
        ExerciseType.ARM_CIRCLES        to listOf(
            "Stretch your arms out and make big circles.",
            "Arms wide and keep circling!",
            "Big arm circles, keep going!"
        ),
        ExerciseType.SIDE_STRETCH_LEFT  to listOf(
            "Lean your body gently to the right.",
            "Stretch over to the right side.",
            "Reach up and over to the right!"
        ),
        ExerciseType.SIDE_STRETCH_RIGHT to listOf(
            "Lean your body gently to the left.",
            "Stretch over to the left side.",
            "Reach up and over to the left!"
        ),
        ExerciseType.KNEE_LIFT_LEFT     to listOf(
            "Lift your left knee up!",
            "Left knee up high!",
            "Bring that left knee up!"
        ),
        ExerciseType.KNEE_LIFT_RIGHT    to listOf(
            "Lift your right knee up!",
            "Right knee up high!",
            "Bring that right knee up!"
        ),
        ExerciseType.CROSS_BODY_LEFT    to listOf(
            "Reach your right hand to your left knee.",
            "Right hand across to your left knee!",
            "Cross your right arm over to the left!"
        ),
        ExerciseType.CROSS_BODY_RIGHT   to listOf(
            "Reach your left hand to your right knee.",
            "Left hand across to your right knee!",
            "Cross your left arm over to the right!"
        ),
        ExerciseType.JUMPING_JACK       to listOf(
            "Jump and spread your arms and legs wide!",
            "Arms and legs out, jump!",
            "Big jump — spread out wide!"
        ),
        ExerciseType.SQUAT              to listOf(
            "Bend your knees and lower yourself down.",
            "Squat down nice and low!",
            "Knees bent, go lower!"
        ),
        ExerciseType.CLAP_ABOVE_HEAD    to listOf(
            "Raise both hands and clap above your head!",
            "Hands up and clap!",
            "Clap your hands up high!"
        )
    )

    private fun getCorrection(type: ExerciseType): String {
        return correctionBanks[type]?.random() ?: "Keep going!"
    }
}