package com.robomove.app.ui.game

import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.robomove.app.robot.DamanArmControl
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
import com.robomove.app.utils.SkipConfirmationDialog

@androidx.camera.core.ExperimentalGetImage
class GameActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GameActivity"
        const val EXTRA_LEVEL_INDEX = "level_index"
        const val EXTRA_TOTAL_SCORE = "total_score"
        const val REQUEST_PAUSE     = 1001

        // How long (ms) after raising the arms before sending them back down.
        // Tune this against the real Daman — see calibration notes.
        private const val ARM_LOWER_DELAY_MS = 1200L
    }

    // ── Data ──
    private val allLevels       by lazy { LevelRepository.getAllLevels() }
    private var levelIndex      = 0
    private var exerciseIndex   = 0
    private var currentReps     = 0
    private var scoreManager    = ScoreManager()
    private var isPlaying       = true
    private var scoreAtLevelStart = 0
    private var isFrontCamera = false
    private var lastImageWidth  = 1
    private var lastImageHeight = 1

    private var isReturningFromPause = false


    private val currentLevel    get() = allLevels[levelIndex]
    private val currentExercise get() = currentLevel.exercises[exerciseIndex]
    private val targetReps      get() = currentExercise.targetReps

    // ── Systems ──
    private lateinit var feedbackManager : FeedbackManager
    private lateinit var voiceManager    : VoiceManager
    private lateinit var poseDetector    : PoseDetector
    private lateinit var repCounter      : RepCounter
    private lateinit var cameraExecutor  : ExecutorService

    // ── Robot ──
    private lateinit var armControl : DamanArmControl
    private val armHandler = Handler(Looper.getMainLooper())
    private var armBusy = false               // idea 1: prevents overlapping gestures
    private var cheerGestureIndex = 0          // idea 2: cycles through the 3 gestures below

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
        val incomingScore = intent.getIntExtra(EXTRA_TOTAL_SCORE, 0)
        scoreManager.restoreScore(incomingScore)
        scoreAtLevelStart=incomingScore

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

        findViewById<TextView>(R.id.btn_skip).setOnClickListener {
            showSkipConfirmation()
        }
    }

    private fun setupManagers() {
        feedbackManager = FeedbackManager(this)
        cameraExecutor  = Executors.newSingleThreadExecutor()

        // ── Robot arm control ──
        // Same connect pattern as DamanHeadControl in CameraAlignmentActivity.
        armControl = DamanArmControl(this)
        armControl.onConnectionChanged = { connected ->
            Log.d(TAG, "Arm service connected: $connected")
        }
        armControl.connect()

        // ───────────────────────────────────────────────────────────────
        // IMPORTANT: keep DRY_RUN = true until you've checked Logcat
        // (tag "DamanArmControl") and confirmed the joint values in
        // raiseBothArms()/openBothArmsWide()/raiseRightArm()/raiseLeftArm()
        // are safe on the real Daman. Flip to false only after that.
        DamanArmControl.DRY_RUN = false
        // ───────────────────────────────────────────────────────────────

        // Fire the arm gesture in sync with FeedbackManager's own
        // every-2-correct-reps cheer — no separate counter needed here.
        feedbackManager.onCheerTriggered = { performCheerGesture() }

        voiceManager = VoiceManager(this) { command ->
            runOnUiThread { handleVoiceCommand(command) }
        }
        voiceManager.startListening()

        // Wait for TTS to be ready before speaking the first instruction
        // This fixes the bug where Level 1 Exercise 1 instruction is skipped
        feedbackManager.setOnReadyCallback {
            runOnUiThread {
                Log.d(TAG, "TTS ready — speaking first exercise instruction now")
                val exercise = currentExercise
                feedbackManager.speakExerciseName(exercise.displayName, exercise.instruction)
            }
        }
    }

    // ─────────────────────────────────────────
    // ROBOT ARM GESTURES
    // ─────────────────────────────────────────

    /**
     * Cycles through 3 gestures every time it's called, so the robot doesn't
     * do the exact same motion every single cheer:
     *   0 → both arms straight up
     *   1 → both arms open wide (more sideways, less forward)
     *   2 → single-arm wave, alternating right then left each time this comes up
     *
     * armBusy guards against a second gesture firing before the first one has
     * finished lowering (e.g. two cheers close together).
     */
    private fun performCheerGesture() {
        if (armBusy || !armControl.isReady()) {
            Log.d(TAG, "Skipping arm gesture — busy=$armBusy ready=${armControl.isReady()}")
            return
        }
        armBusy = true

        when (cheerGestureIndex % 3) {
            0 -> {
                armControl.raiseBothArms(speed = 90)
                armHandler.postDelayed({
                    armControl.lowerBothArms(speed = 90)
                    armBusy = false
                }, ARM_LOWER_DELAY_MS)
            }
            1 -> {
                armControl.openBothArmsWide(speed = 90)
                armHandler.postDelayed({
                    armControl.lowerBothArms(speed = 90)
                    armBusy = false
                }, ARM_LOWER_DELAY_MS)
            }
            2 -> {
                val useRightArm = (cheerGestureIndex / 3) % 2 == 0
                if (useRightArm) {
                    armControl.raiseRightArm(speed = 90)
                } else {
                    armControl.raiseLeftArm(speed = 90)
                }
                armHandler.postDelayed({
                    if (useRightArm) {
                        armControl.lowerRightArm(speed = 90)
                    } else {
                        armControl.lowerLeftArm(speed = 90)
                    }
                    armBusy = false
                }, ARM_LOWER_DELAY_MS)
            }
        }

        cheerGestureIndex++
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
            onPoseFeedback = { _ ->
                runOnUiThread {
                    feedbackManager.speakCorrection(exercise.type)
                }
            }
        )

        // Apply saved dimensions immediately so first frames are normalised correctly
        repCounter.setImageDimensions(lastImageWidth, lastImageHeight, isFrontCamera)

        // Speak instruction
        // Note: for the very first exercise, this is called via the TTS ready callback
        // in setupManagers() instead — so we only call directly for exercise 2 onwards
        if (exerciseIndex > 0 || levelIndex > 0) {
            feedbackManager.speakExerciseName(exercise.displayName, exercise.instruction)
        } else {
            // Level 1 Exercise 1 — TTS ready callback in setupManagers() handles this
            Log.d(TAG, "Skipping direct speak — TTS ready callback will handle Level 1 Ex 1")
        }

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
        // Fully release old player first
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        tvDemoPlaceholder.visibility = View.GONE
        videoDemo.visibility         = View.VISIBLE

        // Force surface reset by detaching and reattaching the listener
        videoDemo.surfaceTextureListener = null

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
        poseDetector = PoseDetector(this) { pose, imageWidth, imageHeight ->
            // Rep counting runs on camera thread — fast, no UI work
            if (isPlaying) {
                lastImageWidth  = imageWidth
                lastImageHeight = imageHeight
                repCounter.setImageDimensions(imageWidth, imageHeight, isFrontCamera)
                val status = repCounter.processLandmarks(pose)
                Log.v(TAG, "Pose: $status")
            }

            // Overlay update posted to main thread separately
            // so it doesn't block the next frame from being processed
            runOnUiThread {
                if (isPlaying) {
                    poseOverlayView.updatePose(pose, imageWidth, imageHeight, isFrontCamera)
                } else {
                    poseOverlayView.clearPose()
                }
            }
        }


        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(cameraPreview.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        poseDetector.detectLiveStream(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()

                // Try front camera first, fall back to back camera
                val cameraSelector = when {
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> {
                        Log.d(TAG, "Using front camera")
                        isFrontCamera = true
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    }
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> {
                        Log.d(TAG, "Front camera not found — using back camera")
                        isFrontCamera = false
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    else -> {
                        Log.e(TAG, "No camera available on this device")
                        showNoCameraMessage()
                        return@addListener
                    }
                }

                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
                Log.d(TAG, "Camera bound successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed: ${e.message}")
                showNoCameraMessage()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun showNoCameraMessage() {
        runOnUiThread {
            // Hide the camera preview area and show a message
            cameraPreview.visibility = View.GONE
            poseOverlayView.visibility = View.GONE

            // Show placeholder text in camera card area
            val placeholder = findViewById<TextView>(R.id.tv_demo_placeholder)
            // Reuse existing placeholder or find camera card
            Log.w(TAG, "No camera found — running without pose detection")

            // Game still works — just no pose tracking
            // Rep counting will be manual or disabled
        }
    }

    // ─────────────────────────────────────────
    // GAME LOGIC
    // ─────────────────────────────────────────

    private fun onRepCompleted(quality: RepQuality) {
        runOnUiThread {
            if (currentReps >= targetReps) return@runOnUiThread
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
            VoiceCommand.SKIP  -> showSkipConfirmation()
            else -> {}
        }
    }

    private fun pauseGame() {
        isPlaying = false
        isReturningFromPause = true   // ← add this line
        voiceManager.stopListening()
        feedbackManager.stopSpeaking()
        mediaPlayer?.pause()
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

    private fun showSkipConfirmation() {
        // Don't show if already on last exercise of level — nothing to skip to
        if (exerciseIndex >= currentLevel.exercises.size - 1) {
            feedbackManager.speakCustom("This is the last exercise in the level!")
            Log.d(TAG, "Skip ignored — already on last exercise")
            return
        }

        isPlaying = false
        voiceManager.stopListening()
        feedbackManager.stopSpeaking()

        SkipConfirmationDialog(
            context = this,
            onYes   = { skipExercise() },
            onNo    = {
                // Resume everything
                isPlaying = true
                voiceManager.startListening()
                mediaPlayer?.start()
            }
        ).show()
    }

    private fun skipExercise() {
        Log.d(TAG, "Exercise skipped: ${currentExercise.displayName}")

        // Stop current video before moving on
        stopDemoVideo()

        // Move to next exercise — no score awarded for skipped exercise
        exerciseIndex++
        currentReps = 0
        isPlaying   = true
        voiceManager.startListening()

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            loadCurrentExercise()
        }, 300)
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
                    val videoName = currentExercise.videoFileName
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (videoName.isNotEmpty()) {
                            playDemoVideo(videoName)
                        } else {
                            stopDemoVideo()
                        }
                    }, 300)
                    Log.d(TAG, "Game resumed")
                }

                PauseActivity.ACTION_RESTART_LEVEL -> {
                    exerciseIndex = 0
                    currentReps   = 0
                    isPlaying     = true
                    // Roll score back to what it was at the start of this level
                    scoreManager.restoreScore(scoreAtLevelStart)
                    tvScore.text = scoreManager.totalScore.toString()
                    voiceManager.startListening()
                    stopDemoVideo()
                    Log.d(TAG, "Level restarted — score rolled back to $scoreAtLevelStart")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        loadCurrentExercise()
                    }, 300)
                }
            }
        }
    }


    // ─────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        // Don't set isPlaying = false here — onActivityResult handles state
        // when returning from PauseActivity. Only pause the video.
        voiceManager.stopListening()
        mediaPlayer?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (isReturningFromPause) {
            // onActivityResult will handle everything — don't touch mediaPlayer here
            isReturningFromPause = false
            return
        }
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
        armHandler.removeCallbacksAndMessages(null)
        armControl.disconnect()
        Log.d(TAG, "GameActivity destroyed — resources cleaned up")
    }
}