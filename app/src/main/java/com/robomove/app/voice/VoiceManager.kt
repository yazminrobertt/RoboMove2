package com.robomove.app.voice

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

class VoiceManager(
    private val context: Context,
    private val onCommandDetected: (VoiceCommand) -> Unit
) {

    companion object {
        private const val TAG = "VoiceManager"
        private const val MODEL_PATH = "model"
        private const val SAMPLE_RATE = 16000.0f
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var isListening = false
    private var shouldKeepListening = false
    private var isModelLoaded = false

    // Prevents double-firing from partial + final result
    private var lastCommandTime = 0L
    private val COMMAND_COOLDOWN_MS = 1500L

    init {
        loadModelAsync()
    }

    // ─────────────────────────────────────────
    // PUBLIC — same interface as original
    // ─────────────────────────────────────────

    fun startListening() {
        shouldKeepListening = true
        if (!isModelLoaded) {
            Log.w(TAG, "Model not ready yet — will start when ready")
            return
        }
        if (isListening) {
            Log.d(TAG, "Already listening — ignoring startListening()")
            return
        }
        createAndStartRecognizer()
        Log.d(TAG, "START LISTENING CALLED")
    }

    fun stopListening() {
        shouldKeepListening = false
        isListening = false
        destroyRecognizer()
        Log.d(TAG, "Stopped listening")
    }

    // ─────────────────────────────────────────
    // PRIVATE — SETUP
    // ─────────────────────────────────────────

    private fun createAndStartRecognizer() {
        // Destroy old one first — same as original
        destroyRecognizer()

        try {
            val recognizer = Recognizer(model, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE)
            speechService?.startListening(recognitionListener)
            isListening = true
            Log.d(TAG, "Started listening...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recognizer: ${e.message}")
            isListening = false
        }
    }

    private fun destroyRecognizer() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying recognizer: ${e.message}")
        }
        speechService = null
        isListening = false
    }

    /** Same as original — restarts after result or error */
    private fun restartIfNeeded() {
        if (shouldKeepListening) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (shouldKeepListening && !isListening) {
                    createAndStartRecognizer()
                }
            }, 500)
        }
    }

    // ─────────────────────────────────────────
    // PRIVATE — MODEL LOADING
    // ─────────────────────────────────────────

    private fun loadModelAsync() {
        Thread {
            try {
                Log.d(TAG, "Loading Vosk model from assets/model...")

                val outputDir = java.io.File(context.filesDir, "vosk-model")

                if (!outputDir.exists() || outputDir.listFiles().isNullOrEmpty()) {
                    outputDir.mkdirs()
                    copyAssetFolder(context.assets, MODEL_PATH, outputDir.absolutePath)
                    Log.d(TAG, "Model copied to: ${outputDir.absolutePath}")
                } else {
                    Log.d(TAG, "Model already exists at: ${outputDir.absolutePath}")
                }

                model = Model(outputDir.absolutePath)
                isModelLoaded = true
                Log.d(TAG, "Vosk model loaded successfully")

                // If startListening was called before model was ready, start now
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (shouldKeepListening && !isListening) {
                        createAndStartRecognizer()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Vosk model: ${e.message}")
            }
        }.start()
    }

    private fun copyAssetFolder(
        assetManager: AssetManager,
        assetPath: String,
        destPath: String
    ) {
        val files = assetManager.list(assetPath) ?: return
        val destDir = java.io.File(destPath)
        if (!destDir.exists()) destDir.mkdirs()

        for (file in files) {
            val srcPath = "$assetPath/$file"
            val dstPath = "$destPath/$file"
            val subFiles = assetManager.list(srcPath)
            if (!subFiles.isNullOrEmpty()) {
                copyAssetFolder(assetManager, srcPath, dstPath)
            } else {
                copyAssetFile(assetManager, srcPath, dstPath)
            }
        }
    }

    private fun copyAssetFile(
        assetManager: AssetManager,
        srcPath: String,
        dstPath: String
    ) {
        try {
            assetManager.open(srcPath).use { input ->
                java.io.FileOutputStream(dstPath).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy $srcPath: ${e.message}")
        }
    }

    // ─────────────────────────────────────────
    // PRIVATE — PARSE TEXT INTO COMMANDS
    // Same logic as original VoiceManager
    // ─────────────────────────────────────────

    private fun parseCommand(spokenText: String): VoiceCommand {
        val text = spokenText.lowercase().trim()
        Log.d(TAG, "Heard: \"$text\"")

        return when {
            text.contains("robomove start") || text.contains("robot move start") || text.contains("start") -> VoiceCommand.START
            text.contains("robomove pause") || text.contains("robot move pause") || text.contains("pause") -> VoiceCommand.PAUSE
            text.contains("robomove play")  || text.contains("robot move play")  || text.contains("play")  -> VoiceCommand.PLAY
            text.contains("robomove stop")  || text.contains("robot move stop")  || text.contains("stop")  -> VoiceCommand.STOP
            text.contains("robomove skip")  || text.contains("robot move skip")  || text.contains("skip")  -> VoiceCommand.SKIP
            text.contains("yes")                                                 -> VoiceCommand.YES
            text.contains("no")                                                  -> VoiceCommand.NO
            else -> VoiceCommand.UNKNOWN
        }
    }

    // ─────────────────────────────────────────
    // RECOGNITION LISTENER
    // Matches original structure — onResults fires
    // command, onPartialResult for fast response,
    // errors restart just like original
    // ─────────────────────────────────────────

    private val recognitionListener = object : RecognitionListener {

        override fun onPartialResult(hypothesis: String?) {
            hypothesis ?: return
            val text = extractText(hypothesis)
            if (text.isEmpty()) return

            val command = parseCommand(text)
            if (command != VoiceCommand.UNKNOWN) {
                fireCommand(command)
            }
        }

        override fun onResult(hypothesis: String?) {
            isListening = false
            hypothesis ?: return
            val text = extractText(hypothesis)
            if (text.isEmpty()) {
                restartIfNeeded()
                return
            }

            val command = parseCommand(text)
            if (command != VoiceCommand.UNKNOWN) {
                Log.d(TAG, "Command detected: $command from \"$text\"")
                fireCommand(command)
            }

            // Keep listening after result — same as original
            restartIfNeeded()
        }

        override fun onFinalResult(hypothesis: String?) {
            // onResult already handles this — do nothing here
            // to avoid double firing
        }

        override fun onError(exception: Exception?) {
            isListening = false
            Log.d(TAG, "Recognition error: ${exception?.message} — restarting")
            restartIfNeeded()
        }

        override fun onTimeout() {
            isListening = false
            Log.d(TAG, "Timeout — restarting")
            restartIfNeeded()
        }
    }

    // ─────────────────────────────────────────
    // FIRE COMMAND — with cooldown to prevent
    // double-firing from partial + final
    // ─────────────────────────────────────────

    private fun fireCommand(command: VoiceCommand) {
        val now = System.currentTimeMillis()
        if (now - lastCommandTime < COMMAND_COOLDOWN_MS) {
            Log.d(TAG, "Command cooldown — ignoring duplicate: $command")
            return
        }
        lastCommandTime = now

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onCommandDetected(command)
        }
    }

    // ─────────────────────────────────────────
    // TEXT EXTRACTION FROM VOSK JSON
    // ─────────────────────────────────────────

    private fun extractText(hypothesis: String): String {
        return try {
            when {
                hypothesis.contains("\"partial\"") ->
                    hypothesis
                        .substringAfter("\"partial\"")
                        .substringAfter("\"")
                        .substringBefore("\"")
                        .trim()
                hypothesis.contains("\"text\"") ->
                    hypothesis
                        .substringAfter("\"text\"")
                        .substringAfter("\"")
                        .substringBefore("\"")
                        .trim()
                else -> hypothesis.trim()
            }
        } catch (e: Exception) {
            hypothesis.trim()
        }
    }
}