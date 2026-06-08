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
        const val EXTRA_ENDED_EARLY   = "ended_early"
        private const val DISPLAY_DURATION_MS = 4000L
    }

    private lateinit var feedbackManager: FeedbackManager
    private var endedEarly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_level_complete)

        val levelIndex  = intent.getIntExtra(EXTRA_LEVEL_INDEX, 0)
        val totalScore  = intent.getIntExtra(EXTRA_TOTAL_SCORE, 0)
        val isGameOver  = intent.getBooleanExtra(EXTRA_IS_GAME_OVER, false)
        endedEarly  = intent.getBooleanExtra(EXTRA_ENDED_EARLY, false)   // ← ADD
        val levelNumber = levelIndex + 1

// Title — only say "All Levels Complete" when naturally finishing all levels
        findViewById<TextView>(R.id.tv_complete_title).text = when {
            isGameOver  -> "All Levels Complete! 🎉"
            endedEarly  -> "Level $levelNumber Complete!"
            else        -> "Level $levelNumber Complete!"
        }
        findViewById<TextView>(R.id.tv_score).text = totalScore.toString()

// Speak
        feedbackManager = FeedbackManager(this)
        when {
            isGameOver -> feedbackManager.speakGameComplete(totalScore, endedEarly = false)
            endedEarly -> feedbackManager.speakGameComplete(totalScore, endedEarly = true)
            else       -> feedbackManager.speakLevelComplete(levelNumber, totalScore)
        }

// Auto-advance
        Handler(Looper.getMainLooper()).postDelayed({
            when {
                isGameOver || endedEarly -> goToResults(totalScore)   // ← both go to results
                else                     -> goToNextLevel(levelIndex + 1, totalScore)
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
            putExtra(ResultsActivity.EXTRA_ENDED_EARLY, endedEarly)   // ← pass it through
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        feedbackManager.shutdown()
    }
}