package com.robomove.app.ui.pause

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.robomove.app.R
import com.robomove.app.ui.levelcomplete.LevelCompleteActivity
import com.robomove.app.voice.VoiceCommand
import com.robomove.app.voice.VoiceManager

class PauseActivity : AppCompatActivity() {

    companion object {
        // Actions sent back to GameActivity
        const val ACTION_RESUME        = "resume"
        const val ACTION_RESTART_LEVEL = "restart_level"

        // Extras passed in from GameActivity
        const val EXTRA_LEVEL_INDEX = "level_index"
        const val EXTRA_TOTAL_SCORE = "total_score"
    }

    private lateinit var voiceManager: VoiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pause)

        setupButtons()
        setupVoice()
    }

    private fun setupButtons() {

        // RESUME — go back to game
        findViewById<TextView>(R.id.btn_resume).setOnClickListener {
            sendResult(ACTION_RESUME)
        }

        // RESTART LEVEL — tell GameActivity to restart from exercise 0
        findViewById<TextView>(R.id.btn_restart_level).setOnClickListener {
            sendResult(ACTION_RESTART_LEVEL)
        }

        // END GAME — navigate to LevelCompleteActivity as final screen
        findViewById<TextView>(R.id.btn_end_game).setOnClickListener {
            endGame()
        }
    }

    private fun setupVoice() {
        voiceManager = VoiceManager(this) { command ->
            runOnUiThread {
                when (command) {
                    VoiceCommand.PLAY -> sendResult(ACTION_RESUME)
                    VoiceCommand.STOP -> endGame()
                    else -> {}
                }
            }
        }
        voiceManager.startListening()
    }

    private fun sendResult(action: String) {
        val intent = Intent().apply {
            putExtra("action", action)
        }
        setResult(RESULT_OK, intent)
        finish()
    }

    private fun endGame() {
        voiceManager.stopListening()

        val levelIndex = intent.getIntExtra(EXTRA_LEVEL_INDEX, 0)
        val totalScore = intent.getIntExtra(EXTRA_TOTAL_SCORE, 0)

        // Go to LevelComplete screen marked as game over
        val intent = Intent(this, LevelCompleteActivity::class.java).apply {
            putExtra(LevelCompleteActivity.EXTRA_LEVEL_INDEX,  levelIndex)
            putExtra(LevelCompleteActivity.EXTRA_TOTAL_SCORE,  totalScore)
            putExtra(LevelCompleteActivity.EXTRA_IS_GAME_OVER, true)
        }
        startActivity(intent)
        // Clear back stack so user cannot return to game
        finishAffinity()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.stopListening()
    }
}