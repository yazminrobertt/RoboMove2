package com.robomove.app.model

/**
 * One level = a list of exercises + display info.
 */
data class GameLevel(
    val levelNumber: Int,
    val title: String,
    val motivationalMessage: String,
    val exercises: List<Exercise>
)