package com.robomove.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * VoiceManager handles all speech recognition for the RoboMove app.
 * It listens continuously and fires a callback when a known command is heard.
 *
 * Supported commands:
 *   "robomove start"  → VoiceCommand.START
 *   "robomove pause"  → VoiceCommand.PAUSE
 *   "robomove play"   → VoiceCommand.PLAY
 *   "robomove stop"   → VoiceCommand.STOP
 *   "yes"             → VoiceCommand.YES  (for stop confirmation)
 *   "no"              → VoiceCommand.NO   (for stop confirmation)
 */

// All possible voice commands the app understands
enum class VoiceCommand {
    START, PAUSE, PLAY, STOP, YES, NO, UNKNOWN
}

class VoiceManager(
    private val context: Context,
    private val onCommandDetected: (VoiceCommand) -> Unit
) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var shouldKeepListening = false  // controls auto-restart loop

    companion object {
        private const val TAG = "VoiceManager"
    }

    // ─────────────────────────────────────────
    // PUBLIC FUNCTIONS
    // ─────────────────────────────────────────

    /** Call this to start listening. It will keep restarting automatically. */
    fun startListening() {
        shouldKeepListening = true
        createAndStartRecognizer()
        Log.d(TAG, "START LISTENING CALLED")
    }

    /** Call this to fully stop listening (e.g. when leaving a screen) */
    fun stopListening() {
        shouldKeepListening = false
        isListening = false
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    // ─────────────────────────────────────────
    // PRIVATE — SETUP
    // ─────────────────────────────────────────

    private fun createAndStartRecognizer() {
        // Destroy old one first to avoid leaks
        speechRecognizer?.destroy()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(recognitionListener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // Keep listening even during partial speech
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra("android.speech.extra.DICTATION_MODE", true)
        }

        speechRecognizer?.startListening(intent)
        isListening = true
        Log.d(TAG, "Started listening...")
    }

    /** Restart after a result or error — keeps the loop going */
    private fun restartIfNeeded() {
        if (shouldKeepListening) {
            // Small delay before restarting to avoid rapid loops
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (shouldKeepListening) {
                    createAndStartRecognizer()
                }
            }, 1300)
        }
    }

    // ─────────────────────────────────────────
    // PRIVATE — PARSE TEXT INTO COMMANDS
    // ─────────────────────────────────────────

    private fun parseCommand(spokenText: String): VoiceCommand {
        // Lowercase and trim so matching is not case-sensitive
        val text = spokenText.lowercase().trim()
        Log.d(TAG, "Heard: \"$text\"")

        return when {
            text.contains("start") -> VoiceCommand.START
            text.contains("pause") -> VoiceCommand.PAUSE
            text.contains("play") -> VoiceCommand.PLAY
            text.contains("stop") -> VoiceCommand.STOP
            text.contains("yes") -> VoiceCommand.YES
            text.contains("no") -> VoiceCommand.NO
            else -> VoiceCommand.UNKNOWN
        }
    }

    // ─────────────────────────────────────────
    // RECOGNITION LISTENER (Android callbacks)
    // ─────────────────────────────────────────

    private val recognitionListener = object : RecognitionListener {

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?: return

            // Try each result until we find a known command
            for (result in matches) {
                val command = parseCommand(result)
                if (command != VoiceCommand.UNKNOWN) {
                    Log.d(TAG, "Command detected: $command from \"$result\"")
                    onCommandDetected(command)
                    break
                }
            }

            // Keep listening after getting a result
            restartIfNeeded()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // Check partial results too for faster response
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: return

            val command = parseCommand(partial)
            if (command != VoiceCommand.UNKNOWN) {
                Log.d(TAG, "Partial command detected: $command")
                onCommandDetected(command)
            }
        }

        override fun onError(error: Int) {
            isListening = false
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH        -> "No match"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT  -> "Timeout"
                SpeechRecognizer.ERROR_AUDIO           -> "Audio error"
                SpeechRecognizer.ERROR_NETWORK         -> "Network error"
                else -> "Error code $error"
            }
            Log.d(TAG, "Recognition error: $errorMsg — restarting")
            Log.e(TAG, "ERROR: $error")
            // Restart on error (timeout is normal, happens when silent)
            restartIfNeeded()
        }

        // ── We don't need these but must implement them ──
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
            Log.d(TAG, "MIC READY")
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { isListening = false }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}