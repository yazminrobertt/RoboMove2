package com.robomove.app.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.robomove.app.R
import com.robomove.app.ui.instructions.InstructionsActivity
import com.robomove.app.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val btnStart = findViewById<android.view.View>(R.id.btn_start)

        btnStart.setOnClickListener {
            startActivity(Intent(this, InstructionsActivity::class.java))
        }
    }
}