package com.robomove.app.ui.alignment

import android.content.Intent
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.robomove.app.R
import com.robomove.app.robot.DamanHeadControl
import com.robomove.app.ui.countdown.CountdownActivity
import com.robomove.app.voice.VoiceManager
import com.robomove.app.voice.VoiceCommand

class CameraAlignmentActivity : AppCompatActivity() {

    private lateinit var headControl: DamanHeadControl
    private lateinit var voiceManager: VoiceManager

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
            reportResult(headControl.resetHorizontal())
        }

        btnHoriApply.setOnClickListener {
            val angle = seekHoriAngle.progress - 80
            val speed = seekHoriSpeed.progress + 1
            reportResult(headControl.moveHorizontal(angle, speed))
        }

        btnVertiReset.setOnClickListener {
            seekVertiAngle.progress = 3   // back to angle = 0
            tvVertiAngleVal.text = "0"
            reportResult(headControl.resetVertical())
        }

        btnVertiApply.setOnClickListener {
            val angle = seekVertiAngle.progress - 3
            val speed = seekVertiSpeed.progress + 1
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

    // ── Camera ────────────────────────────────────────────────────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(cameraPreview.surfaceProvider)
            }

            // Robot tablet has no front camera — use back camera
            val cameraSelector = try {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } catch (e: Exception) {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
            } catch (e: Exception) {
                // Fall back to back camera if front not available
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, preview
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
        headControl.disconnect()
        voiceManager.stopListening()
    }
}