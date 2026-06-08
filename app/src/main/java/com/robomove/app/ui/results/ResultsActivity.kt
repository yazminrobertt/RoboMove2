package com.robomove.app.ui.results

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.robomove.app.R
import com.robomove.app.ui.countdown.CountdownActivity
import com.robomove.app.ui.home.HomeActivity
import com.robomove.app.voice.FeedbackManager
import com.robomove.app.voice.VoiceCommand
import com.robomove.app.voice.VoiceManager

class ResultsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ResultsActivity"
        const val EXTRA_TOTAL_SCORE = "total_score"
        const val EXTRA_ENDED_EARLY  = "ended_early"

        // Maximum possible score — used to calculate star rating
        // 14 exercises total × 5 reps × 10 points = 700
        private const val MAX_SCORE = 700
    }

    private lateinit var feedbackManager : FeedbackManager
    private lateinit var voiceManager    : VoiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_results)

        val totalScore  = intent.getIntExtra(EXTRA_TOTAL_SCORE, 0)
        val endedEarly  = intent.getBooleanExtra(EXTRA_ENDED_EARLY, false)

        setupUI(totalScore, endedEarly)
        setupVoice()

        // Speak the result after a short delay so TTS has time to init
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            feedbackManager.speakGameComplete(totalScore,endedEarly)
        }, 800)
    }

    // ─────────────────────────────────────────
    // UI SETUP
    // ─────────────────────────────────────────

    private fun setupUI(totalScore: Int, endedEarly: Boolean = false) {
        findViewById<TextView>(R.id.tv_total_score).text = totalScore.toString()

        val message = getMotivationalMessage(totalScore, endedEarly)
        findViewById<TextView>(R.id.tv_motivational_message).text = message

        val stars = calculateStars(totalScore)
        findViewById<TextView>(R.id.tv_stars).text = "⭐".repeat(stars) +
                "🌑".repeat(3 - stars)

        val percentage = ((totalScore.toFloat() / MAX_SCORE) * 100).toInt()
        findViewById<TextView>(R.id.tv_score_breakdown).text =
            "You scored $percentage% of the maximum!\nMax possible: $MAX_SCORE points"

        findViewById<TextView>(R.id.btn_play_again).setOnClickListener { playAgain() }
        findViewById<TextView>(R.id.btn_home).setOnClickListener { goToHome() }
    }

    // ─────────────────────────────────────────
    // VOICE
    // ─────────────────────────────────────────

    private fun setupVoice() {
        feedbackManager = FeedbackManager(this)
        voiceManager = VoiceManager(this) { command ->
            runOnUiThread {
                when (command) {
                    VoiceCommand.START -> playAgain()
                    else -> {}
                }
            }
        }
        voiceManager.startListening()
    }

    // ─────────────────────────────────────────
    // NAVIGATION
    // ─────────────────────────────────────────

    private fun playAgain() {
        voiceManager.stopListening()
        // Go back to countdown — starts from Level 1, score 0
        val intent = Intent(this, CountdownActivity::class.java)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun goToHome() {
        voiceManager.stopListening()
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    // ─────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────

    /**
     * Returns a message based on score percentage.
     * Keeps it positive and encouraging — no negative messages.
     */
    private fun getMotivationalMessage(score: Int, endedEarly: Boolean): String {
        if (endedEarly) {
            return when {
                score == 0  -> "Don't give up! Try again and do your best! 💪"
                score < 200 -> "Good start! Finish all levels next time! 🌟"
                else        -> "Nice score! Can you finish all the levels next time? 🏆"
            }
        }

        val percentage = (score.toFloat() / MAX_SCORE) * 100
        return when {
            percentage >= 90 -> "Perfect! You're a superstar! 🌟"
            percentage >= 70 -> "Amazing work! You're a champion! 🏆"
            percentage >= 50 -> "Great job! Keep practising! 💪"
            percentage >= 30 -> "Good effort! You're getting better! 😊"
            score == 0       -> "Keep practising! Every move counts! 🎉"
            else             -> "Well done for trying! Keep it up! 🎉"
        }
    }

    /**
     * 3 stars = 70%+
     * 2 stars = 40%+
     * 1 star  = any score > 0
     */
    private fun calculateStars(score: Int): Int {
        val percentage = (score.toFloat() / MAX_SCORE) * 100
        return when {
            percentage >= 70 -> 3
            percentage >= 40 -> 2
            score > 0        -> 1
            else             -> 0
        }
    }

    // ─────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────

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
        feedbackManager.shutdown()
    }
}