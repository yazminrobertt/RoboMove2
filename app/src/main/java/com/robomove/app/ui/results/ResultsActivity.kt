package com.robomove.app.ui.results

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.robomove.app.R

class ResultsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOTAL_SCORE = "total_score"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_results)

        val score = intent.getIntExtra(EXTRA_TOTAL_SCORE, 0)
    }
}