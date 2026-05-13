package com.robomove.app.utils

import android.util.Log
import com.robomove.app.model.RepQuality

/**
 * Manages the score for one game session.
 * Simple and stateless — just math.
 */
class ScoreManager {

    companion object {
        private const val TAG = "ScoreManager"
        const val POINTS_CORRECT = 10
        const val POINTS_SLIGHT  = 5
        const val POINTS_WRONG   = 0
    }

    var totalScore: Int = 0
        private set

    /** Add points for one rep. Returns points added. */
    fun addRep(quality: RepQuality): Int {
        val points = when (quality) {
            RepQuality.CORRECT        -> POINTS_CORRECT
            RepQuality.SLIGHTLY_WRONG -> POINTS_SLIGHT
            RepQuality.WRONG          -> POINTS_WRONG
        }
        totalScore += points
        Log.d(TAG, "Rep quality=$quality, +$points pts, total=$totalScore")
        return points
    }

    /** Call this when restarting the whole game */
    fun reset() {
        totalScore = 0
        Log.d(TAG, "Score reset")
    }
}