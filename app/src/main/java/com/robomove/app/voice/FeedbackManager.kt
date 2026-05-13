package com.robomove.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.robomove.app.model.ExerciseType
import com.robomove.app.model.RepQuality
import java.util.Locale

/**
 * FeedbackManager uses Android's built-in TextToSpeech engine.
 *
 * WHY TextToSpeech over recorded audio files?
 *  - No extra files to manage
 *  - Works offline
 *  - Easy to change messages
 *  - Fast (no file I/O)
 *  - Perfectly suitable for a student project
 *
 * Anti-spam: waits MIN_FEEDBACK_INTERVAL_MS between each spoken line.
 */
class FeedbackManager(context: Context) {

    companion object {
        private const val TAG = "FeedbackManager"
        // Minimum gap between feedback messages (milliseconds)
        private const val MIN_FEEDBACK_INTERVAL_MS = 2500L
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var lastFeedbackTime = 0L

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                isReady = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setSpeechRate(0.9f)  // Slightly slower for children
                tts?.setPitch(1.1f)        // Slightly higher = friendlier tone
                Log.d(TAG, "TTS ready: $isReady")
            } else {
                Log.e(TAG, "TTS init failed with status: $status")
            }
        }
    }

    // ─────────────────────────────────────────
    // PUBLIC — CALL THESE FROM GAMEACTIVITY
    // ─────────────────────────────────────────

    /** Speak a rep quality result */
    fun speakRepFeedback(quality: RepQuality, exerciseType: ExerciseType) {
        val message = when (quality) {
            RepQuality.CORRECT -> getEncouragement()
            RepQuality.SLIGHTLY_WRONG -> getSlightCorrection(exerciseType)
            RepQuality.WRONG -> getCorrection(exerciseType)
        }
        speak(message)
    }

    /** Speak a correction mid-movement (called while pose is being held wrong) */
    fun speakCorrection(exerciseType: ExerciseType) {
        speak(getCorrection(exerciseType))
    }

    /** General encouragement — e.g. at level start */
    fun speakCustom(message: String) {
        speak(message, forceSpeak = true)
    }

    /** Tell the child which exercise is next */
    fun speakExerciseName(name: String, instruction: String) {
        speak("Next up: $name. $instruction", forceSpeak = true)
    }

    fun speakLevelStart(levelNumber: Int, message: String) {
        speak("Level $levelNumber! $message", forceSpeak = true)
    }

    fun speakLevelComplete(levelNumber: Int, score: Int) {
        speak("Level $levelNumber complete! Your score is $score. Amazing work!", forceSpeak = true)
    }

    fun speakGameComplete(totalScore: Int) {
        speak("You finished all levels! Your total score is $totalScore. You are a champion!", forceSpeak = true)
    }

    fun speakRepCount(current: Int, total: Int) {
        speak("$current of $total")
    }

    /** Clean up — call in onDestroy */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        Log.d(TAG, "TTS shut down")
    }

    // ─────────────────────────────────────────
    // PRIVATE — MESSAGE BANKS
    // ─────────────────────────────────────────

    private fun getEncouragement(): String {
        val messages = listOf(
            "Great job!", "Awesome!", "Keep going!", "You're doing amazing!",
            "Fantastic!", "Perfect!", "Well done!", "Superstar!", "Yes! That's it!"
        )
        return messages.random()
    }

    private fun getSlightCorrection(type: ExerciseType): String {
        return when (type) {
            ExerciseType.RAISE_LEFT_HAND    -> "Good try! Raise your left arm a little higher!"
            ExerciseType.RAISE_RIGHT_HAND   -> "Good try! Raise your right arm a little higher!"
            ExerciseType.BOTH_HANDS_UP      -> "Almost! Try to raise both arms higher!"
            ExerciseType.TOUCH_SHOULDERS    -> "Close! Bring your hands all the way to your shoulders!"
            ExerciseType.ARM_CIRCLES        -> "Nice! Make your circles a little bigger!"
            ExerciseType.SIDE_STRETCH_LEFT  -> "Good! Lean a little more to the left!"
            ExerciseType.SIDE_STRETCH_RIGHT -> "Good! Lean a little more to the right!"
            ExerciseType.KNEE_LIFT_LEFT     -> "Nice try! Lift your left knee higher!"
            ExerciseType.KNEE_LIFT_RIGHT    -> "Nice try! Lift your right knee higher!"
            ExerciseType.CROSS_BODY_LEFT    -> "Almost! Stretch your right hand to your left knee!"
            ExerciseType.CROSS_BODY_RIGHT   -> "Almost! Stretch your left hand to your right knee!"
            ExerciseType.JUMPING_JACK       -> "Good! Try to spread your arms and legs wider!"
            ExerciseType.SQUAT              -> "Good! Try to bend your knees a little more!"
            ExerciseType.CLAP_ABOVE_HEAD    -> "Almost! Raise your arms higher and clap!"
        }
    }

    private fun getCorrection(type: ExerciseType): String {
        return when (type) {
            ExerciseType.RAISE_LEFT_HAND    -> "Lift your left arm straight up!"
            ExerciseType.RAISE_RIGHT_HAND   -> "Lift your right arm straight up!"
            ExerciseType.BOTH_HANDS_UP      -> "Raise both arms above your head!"
            ExerciseType.TOUCH_SHOULDERS    -> "Start with arms out, then touch your shoulders!"
            ExerciseType.ARM_CIRCLES        -> "Stretch your arms out and make big circles!"
            ExerciseType.SIDE_STRETCH_LEFT  -> "Stand straight and lean your whole body to the left!"
            ExerciseType.SIDE_STRETCH_RIGHT -> "Stand straight and lean your whole body to the right!"
            ExerciseType.KNEE_LIFT_LEFT     -> "Put your hands on your hips and lift your left knee!"
            ExerciseType.KNEE_LIFT_RIGHT    -> "Put your hands on your hips and lift your right knee!"
            ExerciseType.CROSS_BODY_LEFT    -> "Lift your right hand and tap your left knee!"
            ExerciseType.CROSS_BODY_RIGHT   -> "Lift your left hand and tap your right knee!"
            ExerciseType.JUMPING_JACK       -> "Jump and spread your arms and legs wide!"
            ExerciseType.SQUAT              -> "Feet apart, bend your knees and go down!"
            ExerciseType.CLAP_ABOVE_HEAD    -> "Raise both hands and clap above your head!"
        }
    }

    // ─────────────────────────────────────────
    // PRIVATE — CORE SPEAK (anti-spam)
    // ─────────────────────────────────────────

    private fun speak(text: String, forceSpeak: Boolean = false) {
        if (!isReady) {
            Log.w(TAG, "TTS not ready, skipping: $text")
            return
        }

        val now = System.currentTimeMillis()
        if (!forceSpeak && (now - lastFeedbackTime) < MIN_FEEDBACK_INTERVAL_MS) {
            Log.d(TAG, "Anti-spam: skipping \"$text\"")
            return
        }

        lastFeedbackTime = now
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "robomove_tts")
        Log.d(TAG, "TTS speaking: \"$text\"")
    }
}