package com.robomove.app.ui.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.robomove.app.R
import com.robomove.app.ui.home.HomeActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    // How long the splash shows (3 seconds)
    private val SPLASH_DURATION = 3000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Animate the title
        animateTitle()

        // After SPLASH_DURATION milliseconds, go to HomeActivity
        Handler(Looper.getMainLooper()).postDelayed({
            goToHome()
        }, SPLASH_DURATION)
    }

    private fun animateTitle() {
        val logoView = findViewById<ImageView>(R.id.iv_logo)

        // Fade in + slight scale up animation
        val fadeIn = AlphaAnimation(0f, 1f).apply {
            duration = 1000
        }
        val scaleUp = ScaleAnimation(
            0.8f, 1f, 0.8f, 1f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 1000
        }

        val animSet = AnimationSet(true).apply {
            addAnimation(fadeIn)
            addAnimation(scaleUp)
            fillAfter = true
        }

        logoView.startAnimation(animSet)
    }

    private fun goToHome() {
        val intent = Intent(this, HomeActivity::class.java)
        startActivity(intent)
        // Smooth transition
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish() // Close splash so user can't go back to it
    }
}