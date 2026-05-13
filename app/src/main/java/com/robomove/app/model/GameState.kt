package com.robomove.app.model

/**
 * The current state of the game at any moment.
 * GameActivity uses this to know what to show/do.
 */
enum class GameState {
    IDLE,       // Not started yet
    PLAYING,    // Active gameplay
    PAUSED,     // User paused
    LEVEL_COMPLETE,  // Just finished a level
    GAME_COMPLETE    // All levels done
}