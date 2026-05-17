package com.robomove.app.model

/**
 * One exercise in the game.
 *
 * @param type          Which exercise this is
 * @param displayName   Shown on screen e.g. "Raise Left Hand"
 * @param instruction   Shown as subtitle e.g. "Lift your left arm above your head"
 * @param targetReps    How many reps needed (always 5 for now)
 * @param videoFileName Name of video file in res/raw e.g. "raise_left_hand"
 *                      (we'll add videos later — leave as empty string for now)
 */
data class Exercise(
    val type: ExerciseType,
    val displayName: String,
    val instruction: String,           // spoken by TTS
    val description: String = "",      // ← ADD THIS — shown on screen
    val targetReps: Int = 5,
    val videoFileName: String = ""
)