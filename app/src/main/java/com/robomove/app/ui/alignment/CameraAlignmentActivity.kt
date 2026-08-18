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
import com.robomove.app.voice.VoiceManager
import com.robomove.app.voice.VoiceCommand
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@androidx.camera.core.ExperimentalGetImage
class CameraAlignmentActivity : AppCompatActivity() {

    companion object {
        private const val AUTO_STEP_SETTLE_MS = 1500L   // wait after each move before re-checking
        private const val PAN_STEP_DEGREES = 10
        private const val TILT_STEP_DEGREES = 2           // small — verti range is only -3..20
    }

    private lateinit var headControl: DamanHeadControl
    private lateinit var voiceManager: VoiceManager

    // ── Auto align ──
    private lateinit var poseDetector: PoseDetector
    private lateinit var cameraExecutor: ExecutorService
    private val autoAlignController = AutoAlignmentController()
    private val autoHandler = Handler(Looper.getMainLooper())
    private var isFrontCamera = true
    private var isAutoAligning = false
    private var autoAlignBusy = false
    private var currentHoriAngle = 0
    private var currentVertiAngle = 0

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
    // AUTO ALIGN
    // ─────────────────────────────────────────

    private fun setupAutoAlignButtons() {
        btnAutoAlign.setOnClickListener { startAutoAlign() }
        btnStopAutoAlign.setOnClickListener { stopAutoAlign() }
    }

    private fun startAutoAlign() {
        isAutoAligning = true
        autoAlignBusy = false
        currentHoriAngle = seekHoriAngle.progress - 80
        currentVertiAngle = seekVertiAngle.progress - 3
        btnAutoAlign.isEnabled = false
        btnStopAutoAlign.isEnabled = true
        tvAutoAlignStatus.text = "Starting auto align..."
    }

    private fun stopAutoAlign(success: Boolean = false) {
        isAutoAligning = false
        autoAlignBusy = false
        autoHandler.removeCallbacksAndMessages(null)
        btnAutoAlign.isEnabled = true
        btnStopAutoAlign.isEnabled = false
        if (!success) tvAutoAlignStatus.text = "Auto align stopped"
    }

    private fun handleAutoAlignAdjustment(adjustment: AutoAlignmentController.Adjustment) {
        if (!isAutoAligning || autoAlignBusy) return

        when (adjustment) {
            AutoAlignmentController.Adjustment.WAITING_FOR_PERSON -> {
                tvAutoAlignStatus.text = "Looking for you..."
            }
            AutoAlignmentController.Adjustment.WAITING_FOR_HANDS_UP -> {
                tvAutoAlignStatus.text = "Raise both hands above your head!"
            }
            AutoAlignmentController.Adjustment.NEED_TILT_UP -> {
                // Verti: lower angle (toward -3) = looking up, per seekVertiAngle
                // "Up" label being on the low-progress side.
                currentVertiAngle = (currentVertiAngle - TILT_STEP_DEGREES)
                    .coerceIn(headControl.VERTI_MIN, headControl.VERTI_MAX)
                tvAutoAlignStatus.text = "Tilting up to see your hands..."
                autoAlignBusy = true
                headControl.moveVertical(currentVertiAngle, 40)
                autoHandler.postDelayed({ autoAlignBusy = false }, AUTO_STEP_SETTLE_MS)
            }
            AutoAlignmentController.Adjustment.NEED_TILT_DOWN -> {
                // Verti: higher angle (toward 20) = looking down.
                currentVertiAngle = (currentVertiAngle + TILT_STEP_DEGREES)
                    .coerceIn(headControl.VERTI_MIN, headControl.VERTI_MAX)
                tvAutoAlignStatus.text = "Tilting down to see your feet..."
                autoAlignBusy = true
                headControl.moveVertical(currentVertiAngle, 40)
                autoHandler.postDelayed({ autoAlignBusy = false }, AUTO_STEP_SETTLE_MS)
            }
            AutoAlignmentController.Adjustment.NEED_STEP_BACK -> {
                // No wheels — just ask the person to step back themselves.
                tvAutoAlignStatus.text = "Take a step back so I can see all of you!"
            }
            AutoAlignmentController.Adjustment.NEED_PAN_LEFT -> {
                currentHoriAngle = (currentHoriAngle - PAN_STEP_DEGREES)
                    .coerceIn(headControl.HORI_MIN, headControl.HORI_MAX)
                tvAutoAlignStatus.text = "Adjusting view..."
                autoAlignBusy = true
                headControl.moveHorizontal(currentHoriAngle, 50)
                autoHandler.postDelayed({ autoAlignBusy = false }, AUTO_STEP_SETTLE_MS)
            }
            AutoAlignmentController.Adjustment.NEED_PAN_RIGHT -> {
                currentHoriAngle = (currentHoriAngle + PAN_STEP_DEGREES)
                    .coerceIn(headControl.HORI_MIN, headControl.HORI_MAX)
                tvAutoAlignStatus.text = "Adjusting view..."
                autoAlignBusy = true
                headControl.moveHorizontal(currentHoriAngle, 50)
                autoHandler.postDelayed({ autoAlignBusy = false }, AUTO_STEP_SETTLE_MS)
            }
            AutoAlignmentController.Adjustment.ALIGNED -> {
                tvAutoAlignStatus.text = "Perfect! I can see you fully."
                stopAutoAlign(success = true)
            }
        }
    }

    // ── Pose detection wiring ────────────────────────────────────────────

    private fun setupPoseDetector() {
        poseDetector = PoseDetector(this) { pose, imageWidth, imageHeight ->
            if (isAutoAligning) {
                autoAlignController.setImageDimensions(imageWidth, imageHeight, isFrontCamera)
                val adjustment = autoAlignController.evaluate(pose)
                runOnUiThread { handleAutoAlignAdjustment(adjustment) }
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
        poseDetector.close()
        cameraExecutor.shutdown()
    }
}