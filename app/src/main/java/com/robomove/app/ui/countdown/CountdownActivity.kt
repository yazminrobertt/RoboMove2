package com.robomove.app.ui.countdown

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.animation.AlphaAnimation
import android.view.animation.ScaleAnimation
import android.view.animation.AnimationSet
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.robomove.app.R
import com.robomove.app.ui.game.GameActivity
import com.robomove.app.ui.levelstart.LevelStartActivity

class CountdownActivity : AppCompatActivity() {

    private lateinit var tvNumber: TextView
    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_countdown)

        tvNumber = findViewById(R.id.tv_countdown_number)

        startCountdown()
    }

    private fun startCountdown() {
        // Count from 3 to 1, then go to game
        // Total = 4000ms, interval = 1000ms (ticks at 3, 2, 1, then done)
        countDownTimer = object : CountDownTimer(4000, 1000) {

            override fun onTick(millisUntilFinished: Long) {
                // millisUntilFinished goes 3999 → 2999 → 1999 → 999
                val secondsLeft = (millisUntilFinished / 1000) + 1
                tvNumber.text = secondsLeft.toString()
                animateNumber()
            }

            override fun onFinish() {
                // Countdown done — go to GameActivity
                goToGame()
            }

        }.start()
    }

    private fun animateNumber() {
        // Each number pops in with a scale + fade effect
        val scaleDown = ScaleAnimation(
            1.4f, 1f, 1.4f, 1f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply { duration = 400 }

        val fadeIn = AlphaAnimation(0.3f, 1f).apply { duration = 400 }

        AnimationSet(true).apply {
            addAnimation(scaleDown)
            addAnimation(fadeIn)
            fillAfter = true
            tvNumber.startAnimation(this)
        }
    }

    private fun goToGame() {
        // Go to LevelStart first (not directly to game)
        val intent = Intent(this, LevelStartActivity::class.java).apply {
            putExtra(LevelStartActivity.EXTRA_LEVEL_INDEX, 0)
            putExtra(LevelStartActivity.EXTRA_TOTAL_SCORE, 0)
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Always cancel timer to avoid memory leaks
        countDownTimer?.cancel()
    }
}