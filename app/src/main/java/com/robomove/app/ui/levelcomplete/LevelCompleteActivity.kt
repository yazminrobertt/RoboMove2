package com.robomove.app.ui.levelcomplete

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.robomove.app.R
import com.robomove.app.ui.levelstart.LevelStartActivity
import com.robomove.app.ui.results.ResultsActivity
import com.robomove.app.voice.FeedbackManager

class LevelCompleteActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LEVEL_INDEX   = "level_index"
        const val EXTRA_TOTAL_SCORE   = "total_score"
        const val EXTRA_IS_GAME_OVER  = "is_game_over"
        private const val DISPLAY_DURATION_MS = 4000L
    }

    private lateinit var feedbackManager: FeedbackManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_level_complete)

        val levelIndex  = intent.getIntExtra(EXTRA_LEVEL_INDEX, 0)
        val totalScore  = intent.getIntExtra(EXTRA_TOTAL_SCORE, 0)
        val isGameOver  = intent.getBooleanExtra(EXTRA_IS_GAME_OVER, false)
        val levelNumber = levelIndex + 1

        // Update UI
        findViewById<TextView>(R.id.tv_complete_title).text =
            if (isGameOver) "All Levels Complete! 🎉" else "Level $levelNumber Complete!"
        findViewById<TextView>(R.id.tv_score).text = totalScore.toString()

        // Speak
        feedbackManager = FeedbackManager(this)
        if (isGameOver) {
            feedbackManager.speakGameComplete(totalScore)
        } else {
            feedbackManager.speakLevelComplete(levelNumber, totalScore)
        }

        // Auto-advance
        Handler(Looper.getMainLooper()).postDelayed({
            if (isGameOver) {
                goToResults(totalScore)
            } else {
                goToNextLevel(levelIndex + 1, totalScore)
            }
        }, DISPLAY_DURATION_MS)
    }

    private fun goToNextLevel(nextLevelIndex: Int, score: Int) {
        val intent = Intent(this, LevelStartActivity::class.java).apply {
            putExtra(LevelStartActivity.EXTRA_LEVEL_INDEX, nextLevelIndex)
            putExtra(LevelStartActivity.EXTRA_TOTAL_SCORE, score)
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun goToResults(score: Int) {
        val intent = Intent(this, ResultsActivity::class.java).apply {
            putExtra(ResultsActivity.EXTRA_TOTAL_SCORE, score)
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        feedbackManager.shutdown()
    }
}