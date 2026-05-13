package com.robomove.app.model

/**
 * Every exercise the game knows about.
 * Adding a new exercise = add one entry here.
 */
enum class ExerciseType {
    // Level 1
    RAISE_LEFT_HAND,
    RAISE_RIGHT_HAND,
    BOTH_HANDS_UP,
    TOUCH_SHOULDERS,

    // Level 2
    ARM_CIRCLES,
    SIDE_STRETCH_LEFT,
    SIDE_STRETCH_RIGHT,
    KNEE_LIFT_LEFT,
    KNEE_LIFT_RIGHT,
    CROSS_BODY_LEFT,
    CROSS_BODY_RIGHT,

    // Level 3
    JUMPING_JACK,
    SQUAT,
    CLAP_ABOVE_HEAD
}