package com.robomove.app.model

/**
 * Holds ALL the data for one play session.
 * Passed between screens via companion object / ViewModel.
 */
data class GameSession(
    var totalScore: Int = 0,
    var currentLevelIndex: Int = 0,
    var currentExerciseIndex: Int = 0,
    var currentReps: Int = 0,
    var state: GameState = GameState.IDLE
) {
    /** Add points from a rep */
    fun addScore(quality: RepQuality) {
        totalScore += when (quality) {
            RepQuality.CORRECT        -> 10
            RepQuality.SLIGHTLY_WRONG -> 5
            RepQuality.WRONG          -> 0
        }
    }

    /** Move to next exercise. Returns true if level is complete. */
    fun nextExercise(totalExercisesInLevel: Int): Boolean {
        currentReps = 0
        currentExerciseIndex++
        return currentExerciseIndex >= totalExercisesInLevel
    }

    /** Move to next level. Returns true if all levels done. */
    fun nextLevel(totalLevels: Int): Boolean {
        currentExerciseIndex = 0
        currentReps = 0
        currentLevelIndex++
        return currentLevelIndex >= totalLevels
    }
}