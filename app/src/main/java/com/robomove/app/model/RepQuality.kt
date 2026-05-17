package com.robomove.app.model

/**
 * How well the child performed one rep.
 * Used to decide points and feedback message.
 */
enum class RepQuality {
    CORRECT,        // +10 points — perfect form
    SLIGHTLY_WRONG, // +10 jugak  points — close but not quite
    WRONG           // +0  points — not detected / wrong pose
}