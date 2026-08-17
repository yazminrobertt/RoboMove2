package com.robomove.app.voice

/**
 * All voice commands the app recognises.
 * Used by VoiceManager and handled in each Activity.
 */
enum class VoiceCommand {
    START,
    PAUSE,
    PLAY,
    STOP,
    SKIP,
    YES,
    NO,
    CONTINUE,
    UNKNOWN
}