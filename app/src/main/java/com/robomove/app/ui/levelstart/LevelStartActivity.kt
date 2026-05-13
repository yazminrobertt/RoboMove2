package com.robomove.app.ui.levelstart

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.robomove.app.R
import com.robomove.app.model.LevelRepository
import com.robomove.app.ui.game.GameActivity
import com.robomove.app.voice.FeedbackManager

class LevelStartActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LEVEL_INDEX  = "level_index"
        const val EXTRA_TOTAL_SCORE  = "total_score"
        private const val DISPLAY_DURATION_MS = 3000L
    }

    private lateinit var feedbackManager: FeedbackManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_level_start)

        val levelIndex = intent.getIntExtra(EXTRA_LEVEL_INDEX, 0)
        val totalScore = intent.getIntExtra(EXTRA_TOTAL_SCORE, 0)
        val level = LevelRepository.getAllLevels()[levelIndex]

        // Update UI
        findViewById<TextView>(R.id.tv_level_title).text   = level.title

        // Speak level announcement
        feedbackManager = FeedbackManager(this)
        feedbackManager.speakLevelStart(level.levelNumber, level.motivationalMessage)

        // Auto-advance to game after 3 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this, GameActivity::class.java).apply {
                putExtra(GameActivity.EXTRA_LEVEL_INDEX, levelIndex)
                putExtra(GameActivity.EXTRA_TOTAL_SCORE, totalScore)
            }
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, DISPLAY_DURATION_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        feedbackManager.shutdown()
    }
}