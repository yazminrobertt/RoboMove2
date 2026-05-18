package com.robomove.app.ui.countdown

import android.content.Intent
import android.os.Bundle
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.robomove.app.R
import com.robomove.app.ui.levelstart.LevelStartActivity

class CountdownActivity : AppCompatActivity() {

    private lateinit var tvNumber: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_countdown)

        tvNumber = findViewById(R.id.tv_countdown_number)

        startCountdown()
    }

    private fun startCountdown() {

        val numbers = listOf("3", "2", "1")

        numbers.forEachIndexed { index, number ->

            tvNumber.postDelayed({

                tvNumber.text = number
                animateNumber()

                // After showing "1", start the game
                if (index == numbers.lastIndex) {

                    tvNumber.postDelayed({
                        goToGame()
                    }, 1000)
                }

            }, (index * 1000).toLong())
        }
    }

    private fun animateNumber() {

        val scaleDown = ScaleAnimation(
            1.4f, 1f,
            1.4f, 1f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 400
        }

        val fadeIn = AlphaAnimation(0.3f, 1f).apply {
            duration = 400
        }

        AnimationSet(true).apply {
            addAnimation(scaleDown)
            addAnimation(fadeIn)
            fillAfter = true

            tvNumber.startAnimation(this)
        }
    }

    private fun goToGame() {

        val intent = Intent(this, LevelStartActivity::class.java).apply {
            putExtra(LevelStartActivity.EXTRA_LEVEL_INDEX, 0)
            putExtra(LevelStartActivity.EXTRA_TOTAL_SCORE, 0)
        }

        startActivity(intent)

        overridePendingTransition(
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )

        finish()
    }
}