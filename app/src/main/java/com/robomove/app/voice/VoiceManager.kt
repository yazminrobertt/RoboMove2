package com.robomove.app.voice

import android.content.Context
import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
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
        private const val GRAMMAR =
            "[\"start\", \"stop\", \"pause\", \"play\", \"skip\", \"continue\", \"yes\", \"no\", \"[unk]\"]"
        private const val COMMAND_COOLDOWN_MS = 800L

        // ── Singleton model — loaded once, reused across all VoiceManager instances ──
        @Volatile private var sharedModel: Model? = null
        private var isModelLoading = false
        private val modelReadyCallbacks = mutableListOf<() -> Unit>()

        private fun ensureModelLoaded(context: Context, onReady: () -> Unit) {
            synchronized(this) {
                if (sharedModel != null) {
                    // Already loaded — fire immediately on calling thread
                    android.os.Handler(android.os.Looper.getMainLooper()).post { onReady() }
                    return
                }
                modelReadyCallbacks.add(onReady)
                if (isModelLoading) return   // already in progress — callback queued above
                isModelLoading = true
            }

            Thread {
                try {
                    Log.d(TAG, "Loading Vosk model (first time only)...")
                    val outputDir = java.io.File(context.applicationContext.filesDir, "vosk-model")
                    if (!outputDir.exists() || outputDir.listFiles().isNullOrEmpty()) {
                        outputDir.mkdirs()
                        copyAssetFolder(context.assets, MODEL_PATH, outputDir.absolutePath)
                    }
                    sharedModel = Model(outputDir.absolutePath)
                    Log.d(TAG, "Vosk model loaded successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load Vosk model: ${e.message}")
                } finally {
                    synchronized(this) { isModelLoading = false }
                }

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    synchronized(this) {
                        val callbacks = modelReadyCallbacks.toList()
                        modelReadyCallbacks.clear()
                        callbacks.forEach { it() }
                    }
                }
            }.start()
        }

        // These stay here because they don't reference instance state
        private fun copyAssetFolder(assets: android.content.res.AssetManager, assetPath: String, destPath: String) {
            val files = assets.list(assetPath) ?: return
            val destDir = java.io.File(destPath)
            if (!destDir.exists()) destDir.mkdirs()
            for (file in files) {
                val srcPath = "$assetPath/$file"
                val dstPath = "$destPath/$file"
                if (!assets.list(srcPath).isNullOrEmpty()) {
                    copyAssetFolder(assets, srcPath, dstPath)
                } else {
                    copyAssetFile(assets, srcPath, dstPath)
                }
            }
        }

        private fun copyAssetFile(assets: android.content.res.AssetManager, srcPath: String, dstPath: String) {
            try {
                assets.open(srcPath).use { input ->
                    java.io.FileOutputStream(dstPath).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy $srcPath: ${e.message}")
            }
        }
    }

    // ── Instance state (per-screen, not shared) ───────────────────────────
    private var speechService: SpeechService? = null
    private var isListening = false
    private var shouldKeepListening = false
    private var lastCommandTime = 0L

    init {
        // Pass applicationContext so we never leak an Activity reference
        ensureModelLoaded(context.applicationContext) {
            // Model is ready — start listening if caller already asked for it
            if (shouldKeepListening && !isListening) {
                createAndStartRecognizer()
            }
        }
    }

    // ── PUBLIC ────────────────────────────────────────────────────────────

    fun startListening() {
        shouldKeepListening = true
        if (sharedModel == null) {
            Log.w(TAG, "Model not ready yet — will start when loaded")
            return
        }
        if (isListening) return
        createAndStartRecognizer()
    }

    fun stopListening() {
        shouldKeepListening = false
        isListening = false
        destroyRecognizer()
    }

    // ── RECOGNIZER LIFECYCLE ──────────────────────────────────────────────

    private fun createAndStartRecognizer() {
        destroyRecognizer()
        val model = sharedModel ?: run {
            Log.w(TAG, "createAndStartRecognizer called but model is null")
            return
        }
        try {
            val recognizer = Recognizer(model, SAMPLE_RATE, GRAMMAR)
            speechService = SpeechService(recognizer, SAMPLE_RATE)
            speechService?.startListening(recognitionListener)
            isListening = true
            Log.d(TAG, "Listening started")
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

    private fun restartIfNeeded() {
        if (shouldKeepListening) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (shouldKeepListening && !isListening) createAndStartRecognizer()
            }, 500)
        }
    }

    // ── RECOGNITION LISTENER (unchanged) ─────────────────────────────────

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val text = hypothesis?.let { extractText(it) } ?: return
            if (text.isNotEmpty()) Log.d(TAG, "Partial (ignored): \"$text\"")
        }

        override fun onResult(hypothesis: String?) {
            isListening = false
            hypothesis ?: run { restartIfNeeded(); return }
            val text = extractText(hypothesis)
            Log.d(TAG, "Final result: \"$text\"")
            if (text.isNotEmpty()) {
                val command = parseCommand(text)
                if (command != VoiceCommand.UNKNOWN) {
                    Log.d(TAG, "Command: $command")
                    fireCommand(command)
                }
            }
            restartIfNeeded()
        }

        override fun onFinalResult(hypothesis: String?) { /* handled by onResult */ }

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

    // ── COMMAND PARSING (unchanged) ───────────────────────────────────────

    private fun parseCommand(spokenText: String): VoiceCommand {
        val text = spokenText.lowercase().trim()
        return when {
            text.hasWord("start")    -> VoiceCommand.START
            text.hasWord("pause")    -> VoiceCommand.PAUSE
            text.hasWord("play")     -> VoiceCommand.PLAY
            text.hasWord("stop")     -> VoiceCommand.STOP
            text.hasWord("skip")     -> VoiceCommand.SKIP
            text.hasWord("continue") -> VoiceCommand.CONTINUE
            text.hasWord("yes")      -> VoiceCommand.YES
            text.hasWord("no")       -> VoiceCommand.NO
            else                     -> VoiceCommand.UNKNOWN
        }
    }

    private fun String.hasWord(word: String): Boolean =
        this.split(" ", "\t", ",", ".", "!", "?").any { it.trim() == word }

    // ── COMMAND DISPATCH (unchanged) ──────────────────────────────────────

    private fun fireCommand(command: VoiceCommand) {
        val now = System.currentTimeMillis()
        if (now - lastCommandTime < COMMAND_COOLDOWN_MS) {
            Log.d(TAG, "Cooldown — ignoring duplicate: $command")
            return
        }
        lastCommandTime = now
        android.os.Handler(android.os.Looper.getMainLooper()).post { onCommandDetected(command) }
    }

    // ── TEXT EXTRACTION (unchanged) ───────────────────────────────────────

    private fun extractText(hypothesis: String): String {
        return try {
            when {
                hypothesis.contains("\"partial\"") ->
                    hypothesis.substringAfter("\"partial\"")
                        .substringAfter("\"").substringBefore("\"").trim()
                hypothesis.contains("\"text\"") ->
                    hypothesis.substringAfter("\"text\"")
                        .substringAfter("\"").substringBefore("\"").trim()
                else -> hypothesis.trim()
            }
        } catch (e: Exception) {
            hypothesis.trim()
        }
    }
}