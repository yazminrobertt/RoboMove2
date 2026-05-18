package com.robomove.app.ui.game

import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
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
import com.robomove.app.vision.PoseOverlayView
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
        const val EXTRA_LEVEL_INDEX = "level_index"
        const val EXTRA_TOTAL_SCORE = "total_score"
        const val REQUEST_PAUSE     = 1001
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
    private lateinit var tvScore           : TextView
    private lateinit var tvReps            : TextView
    private lateinit var tvExerciseName    : TextView
    private lateinit var tvProgressLabel   : TextView
    private lateinit var progressBar       : ProgressBar
    private lateinit var cameraPreview     : PreviewView
    private lateinit var poseOverlayView   : PoseOverlayView
    private lateinit var videoDemo         : TextureView
    private lateinit var tvDemoPlaceholder : TextView
    private lateinit var tvExerciseDescription : TextView   // ← ADD


    // ── Video ──
    private var mediaPlayer: MediaPlayer? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) setupCamera()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        levelIndex    = intent.getIntExtra(EXTRA_LEVEL_INDEX, 0)
        scoreManager = ScoreManager()
        scoreManager.restoreScore(intent.getIntExtra(EXTRA_TOTAL_SCORE, 0))

        bindViews()
        setupManagers()
        checkCameraPermission()
        tvScore.text = scoreManager.totalScore.toString()
        loadCurrentExercise()

        Log.d(TAG, "GameActivity started — Level ${levelIndex + 1}, Exercise 1")
    }

    // ─────────────────────────────────────────
    // SETUP
    // ─────────────────────────────────────────

    private fun bindViews() {
        tvScore           = findViewById(R.id.tv_score)
        tvReps            = findViewById(R.id.tv_reps)
        tvExerciseName    = findViewById(R.id.tv_exercise_name)
        tvProgressLabel   = findViewById(R.id.tv_progress_label)
        progressBar       = findViewById(R.id.progress_bar)
        cameraPreview     = findViewById(R.id.camera_preview)
        poseOverlayView   = findViewById(R.id.pose_overlay)
        videoDemo         = findViewById(R.id.video_demo)
        tvDemoPlaceholder = findViewById(R.id.tv_demo_placeholder)
        tvExerciseDescription = findViewById(R.id.tv_exercise_description)


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

        tvExerciseName.text = exercise.displayName
        tvReps.text         = "0/${targetReps}"
        tvExerciseDescription.text = exercise.instruction
        currentReps         = 0

        val totalExercises   = currentLevel.exercises.size
        val progressPercent  = (exerciseIndex.toFloat() / totalExercises * 100).toInt()
        progressBar.progress = progressPercent
        tvProgressLabel.text = "Exercise ${exerciseIndex + 1} of $totalExercises"

        repCounter = RepCounter(
            exerciseType   = exercise.type,
            onRepCompleted = { quality -> onRepCompleted(quality) },
            onPoseFeedback = { msg ->
                // Feedback is already rate-limited inside FeedbackManager
                // Only pass through — do not call speakCorrection separately here
                Log.v(TAG, "Pose hint: $msg")
            }
        )

        feedbackManager.speakExerciseName(exercise.displayName, exercise.instruction)

        // Load demo video or show placeholder
        val videoName = exercise.videoFileName
        if (videoName.isNotEmpty()) {
            playDemoVideo(videoName)
        } else {
            stopDemoVideo()
        }

        Log.d(TAG, "Loaded exercise: ${exercise.displayName}")
    }

    // ─────────────────────────────────────────
    // DEMO VIDEO
    // ─────────────────────────────────────────

    private fun playDemoVideo(videoName: String) {
        stopDemoVideo()

        tvDemoPlaceholder.visibility = View.GONE
        videoDemo.visibility         = View.VISIBLE

        if (videoDemo.isAvailable) {
            startMediaPlayer(videoDemo.surfaceTexture!!, videoName)
        } else {
            videoDemo.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                    startMediaPlayer(surface, videoName)
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    mediaPlayer?.release()
                    mediaPlayer = null
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }

    private fun startMediaPlayer(surfaceTexture: SurfaceTexture, videoName: String) {
        try {
            val resId = resources.getIdentifier(videoName, "raw", packageName)
            if (resId == 0) {
                Log.w(TAG, "Video file not found in res/raw: $videoName")
                runOnUiThread { stopDemoVideo() }
                return
            }
            val afd = resources.openRawResourceFd(resId)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                setSurface(Surface(surfaceTexture))
                isLooping = true
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, _, _ ->
                    runOnUiThread { stopDemoVideo() }
                    true
                }
                prepareAsync()
            }
            afd.close()
        } catch (e: Exception) {
            Log.e(TAG, "Video load failed for $videoName: ${e.message}")
            runOnUiThread { stopDemoVideo() }
        }
    }

    private fun stopDemoVideo() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        videoDemo.visibility         = View.GONE
        tvDemoPlaceholder.visibility = View.VISIBLE
    }

    // ─────────────────────────────────────────
    // CAMERA + POSE
    // ─────────────────────────────────────────

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> setupCamera()
            else -> requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupCamera() {
        poseDetector = PoseDetector(this) { result, _, _ ->
            if (isPlaying) {
                poseOverlayView.updatePose(result)
                val status = repCounter.processLandmarks(result)
                Log.v(TAG, "Pose: $status")
            } else {
                poseOverlayView.clearPose()
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

            tvScore.text = scoreManager.totalScore.toString()
            tvReps.text  = "$currentReps/$targetReps"

            feedbackManager.speakRepFeedback(quality, currentExercise.type)

            Log.d(TAG, "Rep completed — quality=$quality, +$points, reps=$currentReps/$targetReps")

            if (currentReps >= targetReps) {
                onExerciseComplete()
            }
        }
    }

    private fun onExerciseComplete() {
        Log.d(TAG, "Exercise complete: ${currentExercise.displayName}")
        exerciseIndex++

        if (exerciseIndex >= currentLevel.exercises.size) {
            onLevelComplete()
        } else {
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

        val intent = Intent(this, PauseActivity::class.java).apply {
            putExtra(PauseActivity.EXTRA_LEVEL_INDEX, levelIndex)
            putExtra(PauseActivity.EXTRA_TOTAL_SCORE, scoreManager.totalScore)
        }
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
                mediaPlayer?.start()
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
            when (data?.getStringExtra("action")) {
                PauseActivity.ACTION_RESUME -> {
                    isPlaying = true
                    voiceManager.startListening()
                    Log.d(TAG, "Game resumed")
                }
                PauseActivity.ACTION_RESTART_LEVEL -> {
                    // Reset exercise index back to 0, keep total score
                    exerciseIndex = 0
                    currentReps   = 0
                    isPlaying     = true
                    voiceManager.startListening()
                    loadCurrentExercise()
                    Log.d(TAG, "Level restarted from exercise 0")
                }
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
        mediaPlayer?.pause()
    }

    override fun onResume() {
        super.onResume()
        isPlaying = true
        voiceManager.startListening()
        mediaPlayer?.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDemoVideo()
        voiceManager.stopListening()
        feedbackManager.shutdown()
        poseDetector.close()
        cameraExecutor.shutdown()
        Log.d(TAG, "GameActivity destroyed — resources cleaned up")
    }
}