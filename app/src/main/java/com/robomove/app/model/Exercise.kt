package com.robomove.app.model

/**
 * One exercise in the game.
 *
 * @param type          Which exercise this is
 * @param displayName   contoh "Raise Left Hand"
 * @param instruction   coth "Lift your left arm above your head"
 * @param targetReps    5 all
 * @param videoFileName name of video for each exercise
 */
data class Exercise(
    val type: ExerciseType,
    val displayName: String,
    val instruction: String,           // spoken by TTS
    val description: String = "",      // ← ADD THIS — shown on screen
    val targetReps: Int = 5,
    val videoFileName: String = ""
)