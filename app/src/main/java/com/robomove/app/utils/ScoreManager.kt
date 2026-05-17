package com.robomove.app.utils

import android.util.Log
import com.robomove.app.model.RepQuality

/**
 * Scoring rules (simplified):
 *   CORRECT or SLIGHTLY_WRONG → +10 points
 *   WRONG                     → +0  points
 *
 * No negative scoring — this is for children.
 */
class ScoreManager {

    companion object {
        private const val TAG = "ScoreManager"
        const val POINTS_PER_REP = 10
    }

    var totalScore: Int = 0
        private set

    /** Add points for one completed rep. Returns points added. */
    fun addRep(quality: RepQuality): Int {
        val points = when (quality) {
            RepQuality.CORRECT,
            RepQuality.SLIGHTLY_WRONG -> POINTS_PER_REP   // both count as 10
            RepQuality.WRONG          -> 0
        }
        totalScore += points
        Log.d(TAG, "Rep quality=$quality → +$points pts, total=$totalScore")
        return points
    }

    /** Directly restore a score value (used when resuming from level complete) */
    fun restoreScore(score: Int) {
        totalScore = score
        Log.d(TAG, "Score restored to $totalScore")
    }

    fun reset() {
        totalScore = 0
        Log.d(TAG, "Score reset to 0")
    }
}