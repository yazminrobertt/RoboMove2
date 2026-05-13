package com.robomove.app.ui.game

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.robomove.app.model.*
import com.robomove.app.ui.levelcomplete.LevelCompleteActivity
import com.robomove.app.ui.pause.PauseActivity
import com.robomove.app.utils.ScoreManager
import com.robomove.app.utils.StopConfirmationDialog
import com.robomove.app.vision.PoseDetector
import com.robomove.app.vision.RepCounter
import com.robomove.app.voice.FeedbackManager
import com.robomove.app.voice.VoiceCommand
import com.robomove.app.voice.VoiceManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.robomove.app.R
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts

class GameActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GameActivity"
        const val EXTRA_LEVEL_INDEX  = "level_index"
        const val EXTRA_TOTAL_SCORE  = "total_score"
        const val REQUEST_PAUSE      = 1001
    }

    // ── Data ──
    private val allLevels       by lazy { LevelRepository.getAllLevels() }
    private var levelIndex      = 0
    private var exerciseIndex   = 0
    private var currentReps     = 0
    private var scoreManager    = ScoreManager()
    private var isPlaying       = true

    private val currentLevel    get() = allLevels[levelIndex]
    private val currentExercise get() = currentLevel.exercises[exerciseIndex]
    private val targetReps      get() = currentExercise.targetReps

    // ── Systems ──
    private lateinit var feedbackManager : FeedbackManager
    private lateinit var voiceManager    : VoiceManager
    private lateinit var poseDetector    : PoseDetector
    private lateinit var repCounter      : RepCounter
    private lateinit var cameraExecutor  : ExecutorService

    // ── Views ──
    private lateinit var tvScore         : TextView
    private lateinit var tvReps          : TextView
    private lateinit var tvExerciseName  : TextView
    private lateinit var tvProgressLabel : TextView
    private lateinit var progressBar     : ProgressBar
    private lateinit var cameraPreview   : PreviewView

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                setupCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        // Read which level + score we're starting with
        levelIndex = intent.getIntExtra(EXTRA_LEVEL_INDEX, 0)
        scoreManager = ScoreManager()
        val incomingScore = intent.getIntExtra(EXTRA_TOTAL_SCORE, 0)
        repeat(incomingScore / 10) { scoreManager.addRep(RepQuality.CORRECT) } // restore score

        bindViews()
        setupManagers()
        checkCameraPermission()
        loadCurrentExercise()

        Log.d(TAG, "GameActivity started — Level ${levelIndex + 1}, Exercise 1")
    }

    // ─────────────────────────────────────────
    // SETUP
    // ─────────────────────────────────────────

    private fun bindViews() {
        tvScore         = findViewById(R.id.tv_score)
        tvReps          = findViewById(R.id.tv_reps)
        tvExerciseName  = findViewById(R.id.tv_exercise_name)
        tvProgressLabel = findViewById(R.id.tv_progress_label)
        progressBar     = findViewById(R.id.progress_bar)
        cameraPreview   = findViewById(R.id.camera_preview)

        // Pause button
        findViewById<TextView>(R.id.btn_pause).setOnClickListener {
            pauseGame()
        }
    }

    private fun setupManagers() {
        feedbackManager = FeedbackManager(this)
        cameraExecutor  = Executors.newSingleThreadExecutor()

        voiceManager = VoiceManager(this) { command ->
            runOnUiThread { handleVoiceCommand(command) }
        }
        voiceManager.startListening()
    }

    private fun loadCurrentExercise() {
        val exercise = currentExercise

        // Update text
        tvExerciseName.text  = exercise.displayName
        tvReps.text          = "0/${targetReps}"
        currentReps          = 0

        // Update progress bar
        val totalExercises   = currentLevel.exercises.size
        val progressPercent  = (exerciseIndex.toFloat() / totalExercises * 100).toInt()
        progressBar.progress = progressPercent
        tvProgressLabel.text = "Exercise ${exerciseIndex + 1} of $totalExercises"

        // Build rep counter for this exercise
        repCounter = RepCounter(
            exerciseType    = exercise.type,
            onRepCompleted  = { quality -> onRepCompleted(quality) },
            onPoseFeedback  = { msg ->
                runOnUiThread { feedbackManager.speakCorrection(exercise.type) }
            }
        )

        // Speak instruction
        feedbackManager.speakExerciseName(exercise.displayName, exercise.instruction)

        Log.d(TAG, "Loaded exercise: ${exercise.displayName}")
    }

    // ─────────────────────────────────────────
    // CAMERA + POSE
    // ─────────────────────────────────────────

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {

                setupCamera()
            }

            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
    private fun setupCamera() {
        poseDetector = PoseDetector(this) { result, _, _ ->
            if (isPlaying) {
                val status = repCounter.processLandmarks(result)
                Log.v(TAG, "Pose: $status")
            }
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(cameraPreview.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        poseDetector.detectLiveStream(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalyzer
                )
                Log.d(TAG, "Camera bound successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed: ${e.message}")
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // ─────────────────────────────────────────
    // GAME LOGIC
    // ─────────────────────────────────────────

    private fun onRepCompleted(quality: RepQuality) {
        runOnUiThread {
            val points = scoreManager.addRep(quality)
            currentReps++

            // Update UI
            tvScore.text = scoreManager.totalScore.toString()
            tvReps.text  = "$currentReps/$targetReps"

            // Speak feedback
            feedbackManager.speakRepFeedback(quality, currentExercise.type)

            Log.d(TAG, "Rep completed — quality=$quality, +$points, reps=$currentReps/$targetReps")

            // Check if exercise is done
            if (currentReps >= targetReps) {
                onExerciseComplete()
            }
        }
    }

    private fun onExerciseComplete() {
        Log.d(TAG, "Exercise complete: ${currentExercise.displayName}")
        exerciseIndex++

        if (exerciseIndex >= currentLevel.exercises.size) {
            // All exercises in this level done
            onLevelComplete()
        } else {
            // Load next exercise after short delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                loadCurrentExercise()
            }, 1500)
        }
    }

    private fun onLevelComplete() {
        Log.d(TAG, "Level ${levelIndex + 1} complete! Score=${scoreManager.totalScore}")
        voiceManager.stopListening()

        val isLastLevel = (levelIndex + 1) >= allLevels.size

        val intent = Intent(this, LevelCompleteActivity::class.java).apply {
            putExtra(LevelCompleteActivity.EXTRA_LEVEL_INDEX,  levelIndex)
            putExtra(LevelCompleteActivity.EXTRA_TOTAL_SCORE,  scoreManager.totalScore)
            putExtra(LevelCompleteActivity.EXTRA_IS_GAME_OVER, isLastLevel)
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    // ─────────────────────────────────────────
    // PAUSE / STOP
    // ─────────────────────────────────────────

    private fun handleVoiceCommand(command: VoiceCommand) {
        when (command) {
            VoiceCommand.PAUSE -> pauseGame()
            VoiceCommand.STOP  -> showStopConfirmation()
            else -> {}
        }
    }

    private fun pauseGame() {
        isPlaying = false
        voiceManager.stopListening()
        Log.d(TAG, "Game paused")

        val intent = Intent(this, PauseActivity::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_PAUSE)
    }

    private fun showStopConfirmation() {
        isPlaying = false
        voiceManager.stopListening()

        StopConfirmationDialog(
            context = this,
            onYes   = { goToHome() },
            onNo    = {
                isPlaying = true
                voiceManager.startListening()
            }
        ).show()
    }

    private fun goToHome() {
        val intent = Intent(this, com.robomove.app.ui.home.HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PAUSE && resultCode == RESULT_OK) {
            if (data?.getStringExtra("action") == "resume") {
                isPlaying = true
                voiceManager.startListening()
                Log.d(TAG, "Game resumed")
            }
        }
    }

    // ─────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        isPlaying = false
        voiceManager.stopListening()
    }

    override fun onResume() {
        super.onResume()
        isPlaying = true
        voiceManager.startListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.stopListening()
        feedbackManager.shutdown()
        poseDetector.close()
        cameraExecutor.shutdown()
        Log.d(TAG, "GameActivity destroyed — resources cleaned up")
    }
}