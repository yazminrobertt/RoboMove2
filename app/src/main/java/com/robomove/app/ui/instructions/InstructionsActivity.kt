package com.robomove.app.ui.instructions

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.robomove.app.R
import com.robomove.app.ui.countdown.CountdownActivity
import com.robomove.app.voice.VoiceCommand
import com.robomove.app.voice.VoiceManager

class InstructionsActivity : AppCompatActivity() {

    private lateinit var voiceManager: VoiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_instructions)

        checkMicPermission()
        setupClickListeners()
        setupVoice()
    }

    private fun setupClickListeners() {
        // Back button
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            finish()
        }

        // Yellow button — tap also starts (for testing without voice)
        findViewById<TextView>(R.id.btn_say_start).setOnClickListener {
            goToCountdown()
        }
    }

    private fun checkMicPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                200
            )
        }
    }

    private fun setupVoice() {
        voiceManager = VoiceManager(this) { command ->
            runOnUiThread {
                if (command == VoiceCommand.START) {
                    goToCountdown()
                }
            }
        }
        window.decorView.postDelayed({
            voiceManager.startListening()
        }, 800)
    }

    private fun goToCountdown() {
        voiceManager.stopListening()
        val intent = Intent(this, CountdownActivity::class.java)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // Stop listening when leaving this screen
    override fun onPause() {
        super.onPause()
        voiceManager.stopListening()
    }

    // Restart listening when coming back to this screen
    override fun onResume() {
        super.onResume()
        voiceManager.startListening()
    }
}