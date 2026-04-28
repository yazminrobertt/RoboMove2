package com.robomove.app.ui.pause

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.robomove.app.R
import com.robomove.app.ui.home.HomeActivity
import com.robomove.app.voice.VoiceCommand
import com.robomove.app.voice.VoiceManager
import com.robomove.app.utils.StopConfirmationDialog

class PauseActivity : AppCompatActivity() {

    private lateinit var voiceManager: VoiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pause)

        setupButtons()
        setupVoice()
    }

    private fun setupButtons() {
        // "Go to Home" button — tap to go home without voice
        findViewById<TextView>(R.id.btn_go_home).setOnClickListener {
            goToHome()
        }
    }

    private fun setupVoice() {
        voiceManager = VoiceManager(this) { command ->
            // Voice callbacks come on background thread — move to UI thread
            runOnUiThread {
                handleVoiceCommand(command)
            }
        }
        voiceManager.startListening()
    }

    private fun handleVoiceCommand(command: VoiceCommand) {
        when (command) {
            VoiceCommand.PLAY -> {
                // Resume — go back to Game
                resumeGame()
            }
            VoiceCommand.STOP -> {
                // Show stop confirmation (same dialog from game)
                showStopConfirmation()
            }
            else -> { /* ignore other commands while paused */ }
        }
    }

    private fun resumeGame() {
        // Send result back to GameActivity telling it to resume
        val intent = Intent().apply {
            putExtra("action", "resume")
        }
        setResult(RESULT_OK, intent)
        finish()
    }

    private fun showStopConfirmation() {
        // Stop listening while dialog is open
        voiceManager.stopListening()

        StopConfirmationDialog(
            context = this,
            onYes = { goToHome() },
            onNo  = {
                // User said no — stay paused, restart listening
                voiceManager.startListening()
            }
        ).show()
    }

    private fun goToHome() {
        val intent = Intent(this, HomeActivity::class.java)
        // Clear the back stack so pressing back doesn't return to game
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.stopListening()
    }
}