package com.robomove.app.ui.alignment

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.robomove.app.R
import com.robomove.app.robot.DamanHeadControl
import com.robomove.app.ui.countdown.CountdownActivity
import com.robomove.app.vision.AutoAlignmentController
import com.robomove.app.vision.PoseDetector
import com.robomove.app.voice.FeedbackManager
import com.robomove.app.voice.VoiceManager
import com.robomove.app.voice.VoiceCommand
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.round

@androidx.camera.core.ExperimentalGetImage
class CameraAlignmentActivity : AppCompatActivity() {

    companion object {
        // ── Pan (left/right) — unchanged step-and-settle behaviour ──────────
        private const val PAN_STEP_SETTLE_MS = 1500L
        private const val PAN_STEP_DEGREES = 10

        // ── Tilt (up/down) — continuous, smoothed, step-limited ────────────
        private const val TILT_MAX_STEP_PER_TICK = 2      // degrees per command — keeps it gliding, not jumping
        private const val TILT_SPEED = 45                  // servo speed for tilt commands
        private const val TILT_SETTLE_MS = 900L
        private const val TILT_DEADZONE_DEGREES = 3f

        // ── TTS ──
        // A repeated status message only gets spoken again after this many
        // ms of being stuck on the same message — stops it nagging every
        // single frame while still reminding the person if they're not
        // responding to the first prompt.
        private const val STATUS_REPEAT_COOLDOWN_MS = 6000L
    }

    private lateinit var headControl: DamanHeadControl
    private lateinit var voiceManager: VoiceManager
    private lateinit var feedbackManager: FeedbackManager

    // ── Auto align ──
    private lateinit var poseDetector: PoseDetector
    private lateinit var cameraExecutor: ExecutorService
    private val autoAlignController = AutoAlignmentController()
    private val autoHandler = Handler(Looper.getMainLooper())
    private var isFrontCamera = true
    private var isAutoAligning = false
    private var panBusy = false
    private var tiltBusy = false
    private var currentHoriAngle = 0
    private var currentVertiAngle = 0

    // ── TTS dedupe state ──
    private var lastSpokenMessage: String? = null
    private var lastSpokenTime = 0L

    // Horizontal views
    private lateinit var seekHoriAngle: SeekBar
    private lateinit var seekHoriSpeed: SeekBar
    private lateinit var tvHoriAngleVal: TextView
    private lateinit var tvHoriSpeedVal: TextView
    private lateinit var btnHoriReset: Button
    private lateinit var btnHoriApply: Button

    // Vertical views
    private lateinit var seekVertiAngle: SeekBar
    private lateinit var seekVertiSpeed: SeekBar
    private lateinit var tvVertiAngleVal: TextView
    private lateinit var tvVertiSpeedVal: TextView
    private lateinit var btnVertiReset: Button
    private lateinit var btnVertiApply: Button

    private lateinit var btnContinue: Button
    private lateinit var cameraPreview: PreviewView

    // Auto align views
    private lateinit var tvAutoAlignStatus: TextView
    private lateinit var btnAutoAlign: Button
    private lateinit var btnStopAutoAlign: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_alignment)

        headControl = DamanHeadControl(this)
        feedbackManager = FeedbackManager(this)

        // FIX: react when the service actually finishes binding instead of
        // assuming it's ready the instant connect() returns (bindService is async).
        headControl.onConnectionChanged = { connected ->
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (connected) "Head service connected" else "Head service disconnected",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        headControl.connect()

        // ───────────────────────────────────────────────────────────────
        // IMPORTANT: once you've verified in Logcat (tag "DamanHeadControl")
        // that the payload/frame being built looks correct, flip this to
        // false so commands actually reach the hardware. Leave it `true`
        // while you're still just checking the logs.
        DamanHeadControl.DRY_RUN = false
        // ───────────────────────────────────────────────────────────────

        bindViews()
        setupHorizontalControls()
        setupVerticalControls()
        setupButtons()
        setupAutoAlignButtons()
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupPoseDetector()
        startCamera()

        voiceManager = VoiceManager(this) { command ->
            runOnUiThread { handleVoiceCommand(command) }
        }
        voiceManager.startListening()
    }

    private fun bindViews() {
        seekHoriAngle   = findViewById(R.id.seekHoriAngle)
        seekHoriSpeed   = findViewById(R.id.seekHoriSpeed)
        tvHoriAngleVal  = findViewById(R.id.tvHoriAngleVal)
        tvHoriSpeedVal  = findViewById(R.id.tvHoriSpeedVal)
        btnHoriReset    = findViewById(R.id.btnHoriReset)
        btnHoriApply    = findViewById(R.id.btnHoriApply)

        seekVertiAngle  = findViewById(R.id.seekVertiAngle)
        seekVertiSpeed  = findViewById(R.id.seekVertiSpeed)
        tvVertiAngleVal = findViewById(R.id.tvVertiAngleVal)
        tvVertiSpeedVal = findViewById(R.id.tvVertiSpeedVal)
        btnVertiReset   = findViewById(R.id.btnVertiReset)
        btnVertiApply   = findViewById(R.id.btnVertiApply)

        btnContinue     = findViewById(R.id.btnContinue)
        cameraPreview   = findViewById(R.id.cameraPreview)

        tvAutoAlignStatus = findViewById(R.id.tvAutoAlignStatus)
        btnAutoAlign      = findViewById(R.id.btnAutoAlign)
        btnStopAutoAlign  = findViewById(R.id.btnStopAutoAlign)
    }

    private fun handleVoiceCommand(command: VoiceCommand) {
        when (command) {
            VoiceCommand.CONTINUE -> btnContinue.performClick()
            else -> {}
        }
    }

    // ── Horizontal ────────────────────────────────────────────────────────

    private fun setupHorizontalControls() {
        // SeekBar max=160, progress=80 means angle 0 at center
        // angle = progress - 80  →  range -80..+80
        seekHoriAngle.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val angle = progress - 80
                tvHoriAngleVal.text = angle.toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // SeekBar max=99, progress=49 means speed 50 at start
        // speed = progress + 1  →  range 1..100
        seekHoriSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvHoriSpeedVal.text = (progress + 1).toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // ── Vertical ──────────────────────────────────────────────────────────

    private fun setupVerticalControls() {
        // SeekBar max=23, progress=3 means angle 0 at start
        // angle = progress - 3  →  range -3..+20
        seekVertiAngle.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val angle = progress - 3
                tvVertiAngleVal.text = angle.toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        seekVertiSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvVertiSpeedVal.text = (progress + 1).toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // ── Buttons ───────────────────────────────────────────────────────────

    private fun setupButtons() {
        btnHoriReset.setOnClickListener {
            seekHoriAngle.progress = 80   // back to center (angle = 0)
            tvHoriAngleVal.text = "0"
            currentHoriAngle = 0
            reportResult(headControl.resetHorizontal())
        }

        btnHoriApply.setOnClickListener {
            val angle = seekHoriAngle.progress - 80
            val speed = seekHoriSpeed.progress + 1
            currentHoriAngle = angle
            reportResult(headControl.moveHorizontal(angle, speed))
        }

        btnVertiReset.setOnClickListener {
            seekVertiAngle.progress = 3   // back to angle = 0
            tvVertiAngleVal.text = "0"
            currentVertiAngle = 0
            reportResult(headControl.resetVertical())
        }

        btnVertiApply.setOnClickListener {
            val angle = seekVertiAngle.progress - 3
            val speed = seekVertiSpeed.progress + 1
            currentVertiAngle = angle
            reportResult(headControl.moveVertical(angle, speed))
        }

        btnContinue.setOnClickListener {
            // NOTE: don't call feedbackManager.stopSpeaking() here — onDestroy()'s
            // shutdown() already stops the engine as its first step. Calling stop()
            // here AND shutdown()-which-also-stops() moments later in onDestroy()
            // fires two stop signals at the Pico TTS engine almost simultaneously,
            // which is what was crashing it ("Pico TTS Engine has stopped").
            stopAutoAlign()

            val intent = Intent(this, CountdownActivity::class.java)
            // Pass through any extras from the previous screen
            intent.putExtras(getIntent().extras ?: Bundle())
            startActivity(intent)
            finish()
        }
    }

    // FIX: surface what actually happened instead of assuming it worked.
    // -999 = dry run (check Logcat), -1 = service not connected / call failed,
    // anything else = whatever the hardware service returned.
    private fun reportResult(result: Int) {
        val message = when {
            result == -999 -> "DRY RUN — not sent (check Logcat: DamanHeadControl)"
            result == -1   -> "Failed to send — is the service connected? (headControl.isReady()=${headControl.isReady()})"
            else           -> "Sent OK (result=$result)"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ─────────────────────────────────────────
    // AUTO ALIGN — continuous tracking
    // ─────────────────────────────────────────

    private fun setupAutoAlignButtons() {
        btnAutoAlign.setOnClickListener { startAutoAlign() }
        btnStopAutoAlign.setOnClickListener { stopAutoAlign() }
    }

    private fun startAutoAlign() {
        isAutoAligning = true
        panBusy = false
        tiltBusy = false
        lastSpokenMessage = null
        lastSpokenTime = 0L
        currentHoriAngle = seekHoriAngle.progress - 80
        currentVertiAngle = seekVertiAngle.progress - 3
        btnAutoAlign.isEnabled = false
        btnStopAutoAlign.isEnabled = true
        setStatus("Starting auto align...", speakable = false)
    }

    // This is a continuous tracking mode — it never stops itself on reaching
    // good framing, since the whole point is to keep adjusting as the person
    // moves. Stop is manual, e.g. right before Continue.
    private fun stopAutoAlign() {
        isAutoAligning = false
        panBusy = false
        tiltBusy = false
        autoHandler.removeCallbacksAndMessages(null)
        btnAutoAlign.isEnabled = true
        btnStopAutoAlign.isEnabled = false
        setStatus("Auto align stopped", speakable = false)
    }

    /**
     * Updates the on-screen status text, and optionally speaks it — with
     * dedupe so the same message isn't spoken every single pose frame.
     * Only re-speaks an unchanged message after STATUS_REPEAT_COOLDOWN_MS,
     * acting as a gentle reminder rather than constant chatter.
     */
    private fun setStatus(message: String, speakable: Boolean) {
        tvAutoAlignStatus.text = message
        if (!speakable) return

        val now = System.currentTimeMillis()
        if (message != lastSpokenMessage || now - lastSpokenTime >= STATUS_REPEAT_COOLDOWN_MS) {
            feedbackManager.speakCustom(message)
            lastSpokenMessage = message
            lastSpokenTime = now
        }
    }

    private fun applyAlignmentState(state: AutoAlignmentController.AlignmentState) {
        if (!isAutoAligning) return

        if (!state.personVisible) {
            setStatus("Looking for you...", speakable = false)
            return
        }
        if (!state.handsUp) {
            setStatus("Raise both hands above your head!", speakable = true)
            return
        }

        // ── Continuous tilt — recomputed fresh every frame, but only acted
        //    on when not still settling from the last move, and only if
        //    the difference is big enough to matter. ─────────────────────
        state.targetVertiAngle?.let { target ->
            if (!tiltBusy) {
                val diff = target - currentVertiAngle
                if (abs(diff) >= TILT_DEADZONE_DEGREES) {
                    val step = diff.coerceIn(
                        -TILT_MAX_STEP_PER_TICK.toFloat(), TILT_MAX_STEP_PER_TICK.toFloat()
                    )
                    val newAngle = round(currentVertiAngle + step).toInt()
                        .coerceIn(headControl.VERTI_MIN, headControl.VERTI_MAX)
                    if (newAngle != currentVertiAngle) {
                        currentVertiAngle = newAngle
                        tiltBusy = true
                        headControl.moveVertical(currentVertiAngle, TILT_SPEED)
                        autoHandler.postDelayed({ tiltBusy = false }, TILT_SETTLE_MS)
                    }
                }
            }
        }

        // ── Pan — same discrete step-and-settle behaviour as before ───────
        if (!panBusy) {
            when (state.panAdjustment) {
                AutoAlignmentController.PanAdjustment.LEFT -> {
                    currentHoriAngle = (currentHoriAngle - PAN_STEP_DEGREES)
                        .coerceIn(headControl.HORI_MIN, headControl.HORI_MAX)
                    panBusy = true
                    headControl.moveHorizontal(currentHoriAngle, 50)
                    autoHandler.postDelayed({ panBusy = false }, PAN_STEP_SETTLE_MS)
                }
                AutoAlignmentController.PanAdjustment.RIGHT -> {
                    currentHoriAngle = (currentHoriAngle + PAN_STEP_DEGREES)
                        .coerceIn(headControl.HORI_MIN, headControl.HORI_MAX)
                    panBusy = true
                    headControl.moveHorizontal(currentHoriAngle, 50)
                    autoHandler.postDelayed({ panBusy = false }, PAN_STEP_SETTLE_MS)
                }
                else -> {}
            }
        }

        // ── Status message — the meaningful outcomes get spoken, the
        //    generic "still adjusting" filler stays silent. ───────────────
        when {
            !state.feetVisible && currentVertiAngle >= headControl.VERTI_MAX - 1 ->
                setStatus("Take a step back so I can see your feet!", speakable = true)
            state.fingersVisible && state.feetVisible &&
                    state.panAdjustment == AutoAlignmentController.PanAdjustment.CENTERED ->
                setStatus("Perfect! I can see you fully.", speakable = true)
            else ->
                setStatus("Adjusting...", speakable = false)
        }
    }

    // ── Pose detection wiring ────────────────────────────────────────────

    private fun setupPoseDetector() {
        poseDetector = PoseDetector(this) { pose, imageWidth, imageHeight ->
            if (isAutoAligning) {
                autoAlignController.setImageDimensions(imageWidth, imageHeight, isFrontCamera)
                val state = autoAlignController.evaluate(
                    pose, currentVertiAngle, headControl.VERTI_MIN, headControl.VERTI_MAX
                )
                runOnUiThread { applyAlignmentState(state) }
            }
        }
    }

    // ── Camera ────────────────────────────────────────────────────────────

    private fun startCamera() {
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

                val cameraSelector = when {
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> {
                        isFrontCamera = true
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    }
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> {
                        isFrontCamera = false
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    else -> {
                        // No camera available — preview and auto-align stay disabled
                        return@addListener
                    }
                }

                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                // Fall back to back camera if the selected one fails to bind
                try {
                    cameraProvider.unbindAll()
                    isFrontCamera = false
                    cameraProvider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer
                    )
                } catch (e2: Exception) {
                    // No camera available — preview stays blank
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        stopAutoAlign()
        headControl.disconnect()
        voiceManager.stopListening()
        feedbackManager.shutdown()
        poseDetector.close()
        cameraExecutor.shutdown()
    }
}