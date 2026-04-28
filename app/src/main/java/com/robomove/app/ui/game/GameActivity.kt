package com.robomove.app.ui.game

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.robomove.app.R
import com.robomove.app.ui.pause.PauseActivity
import com.robomove.app.utils.StopConfirmationDialog
import com.robomove.app.voice.VoiceCommand
import com.robomove.app.voice.VoiceManager

class GameActivity : AppCompatActivity() {

    private lateinit var voiceManager: VoiceManager

    // Request code so we know when PauseActivity returns
    companion object {
        const val REQUEST_PAUSE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        setupVoice()
    }

    private fun setupVoice() {
        voiceManager = VoiceManager(this) { command ->
            runOnUiThread {
                handleVoiceCommand(command)
            }
        }
        voiceManager.startListening()
    }

    private fun handleVoiceCommand(command: VoiceCommand) {
        when (command) {
            VoiceCommand.PAUSE -> pauseGame()
            VoiceCommand.STOP  -> showStopConfirmation()
            else -> { /* START and PLAY ignored during gameplay */ }
        }
    }

    private fun pauseGame() {
        voiceManager.stopListening()
        val intent = Intent(this, PauseActivity::class.java)
        // startActivityForResult so PauseActivity can tell us to resume
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_PAUSE)
    }

    private fun showStopConfirmation() {
        voiceManager.stopListening()

        StopConfirmationDialog(
            context = this,
            onYes = { goToHome() },
            onNo  = {
                // Back to playing — restart voice
                voiceManager.startListening()
            }
        ).show()
    }

    private fun goToHome() {
        val intent = Intent(this,
            com.robomove.app.ui.home.HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    // Called when PauseActivity finishes
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PAUSE && resultCode == RESULT_OK) {
            val action = data?.getStringExtra("action")
            if (action == "resume") {
                // Resume voice listening
                voiceManager.startListening()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        voiceManager.stopListening()
    }

    override fun onResume() {
        super.onResume()
        voiceManager.startListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.stopListening()
    }
}