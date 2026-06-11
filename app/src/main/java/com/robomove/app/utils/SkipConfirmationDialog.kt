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

class SkipConfirmationDialog(
    context: Context,
    private val onYes: () -> Unit,
    private val onNo: () -> Unit
) : Dialog(context) {

    // Nullable — we check before using to avoid crashes if init fails
    private var voiceManager: VoiceManager? = null

    // Guard against dismiss() being called multiple times
    private var isDismissed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_skip_confirmation)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        setupButtons()
        setupVoice()
    }

    private fun setupButtons() {
        findViewById<TextView>(R.id.btn_skip_yes).setOnClickListener {
            safeConfirm(yes = true)
        }

        findViewById<TextView>(R.id.btn_skip_no).setOnClickListener {
            safeConfirm(yes = false)
        }
    }

    private fun setupVoice() {
        voiceManager = VoiceManager(context) { command ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                when (command) {
                    VoiceCommand.YES -> safeConfirm(yes = true)
                    VoiceCommand.NO  -> safeConfirm(yes = false)
                    else             -> {}
                }
            }
        }
        voiceManager?.startListening()
    }

    /**
     * Single entry point for both button tap and voice confirmation.
     * The isDismissed guard prevents onYes/onNo firing twice if
     * voice and button are triggered at the same moment.
     */
    private fun safeConfirm(yes: Boolean) {
        if (isDismissed) return
        isDismissed = true

        voiceManager?.stopListening()
        voiceManager = null

        // Dismiss first, then call the callback
        // This order matters — callback may start new activities
        super.dismiss()

        if (yes) onYes() else onNo()
    }

    override fun dismiss() {
        // Called by Android system (e.g. back button press)
        if (isDismissed) return
        isDismissed = true
        voiceManager?.stopListening()
        voiceManager = null
        super.dismiss()
        // Treat system dismiss as "No" — resume the game
        onNo()
    }
}