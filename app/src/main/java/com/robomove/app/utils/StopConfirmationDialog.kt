package com.robomove.app.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.widget.TextView
import com.robomove.app.R
import com.robomove.app.voice.VoiceCommand
import com.robomove.app.voice.VoiceManager

/**
 * A dialog that appears when "Robomove Stop" is heard.
 * Asks the user to say "Yes" to confirm or "No" to cancel.
 * Also has tap buttons for accessibility.
 */
class StopConfirmationDialog(
    context: Context,
    private val onYes: () -> Unit,
    private val onNo: () -> Unit
) : Dialog(context) {

    private lateinit var voiceManager: VoiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_stop_confirmation)

        // Make background transparent so our card shape shows properly
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        setupButtons()
        setupVoice()
    }

    private fun setupButtons() {
        // Tap YES
        findViewById<TextView>(R.id.btn_yes).setOnClickListener {
            voiceManager.stopListening()
            dismiss()
            onYes()
        }

        // Tap NO
        findViewById<TextView>(R.id.btn_no).setOnClickListener {
            voiceManager.stopListening()
            dismiss()
            onNo()
        }
    }

    private fun setupVoice() {

        voiceManager = VoiceManager(context) { command ->

            // Always run on main/UI thread
            android.os.Handler(android.os.Looper.getMainLooper()).post {

                when (command) {

                    VoiceCommand.YES -> {
                        android.util.Log.d("STOP_DIALOG", "YES DETECTED")

                        voiceManager.stopListening()

                        dismiss()

                        onYes()
                    }

                    VoiceCommand.NO -> {
                        android.util.Log.d("STOP_DIALOG", "NO DETECTED")

                        voiceManager.stopListening()

                        dismiss()

                        onNo()
                    }

                    else -> {
                        // Ignore other words
                    }
                }
            }
        }

        voiceManager.startListening()
    }

    override fun dismiss() {
        voiceManager.stopListening()
        super.dismiss()
    }
}