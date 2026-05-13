package com.robomove.app.model

/**
 * The single source of truth for all game content.
 * To add/change exercises — edit here ONLY.
 */
object LevelRepository {

    fun getAllLevels(): List<GameLevel> = listOf(
        buildLevel1(),
        buildLevel2(),
        buildLevel3()
    )

    // ─────────────────────────────────────────
    // LEVEL 1 — Beginner
    // ─────────────────────────────────────────
    private fun buildLevel1() = GameLevel(
        levelNumber = 1,
        title = "Level 1",
        motivationalMessage = "Let's warm up! Simple moves to get started.",
        exercises = listOf(
            Exercise(
                type = ExerciseType.RAISE_LEFT_HAND,
                displayName = "Raise Left Hand",
                instruction = "Lift your LEFT arm straight up above your head!",
                videoFileName = "raise_left_hand"
            ),
            Exercise(
                type = ExerciseType.RAISE_RIGHT_HAND,
                displayName = "Raise Right Hand",
                instruction = "Now lift your RIGHT arm straight up above your head!",
                videoFileName = "raise_right_hand"
            ),
            Exercise(
                type = ExerciseType.BOTH_HANDS_UP,
                displayName = "Both Hands Up",
                instruction = "Raise BOTH arms high above your head!",
                videoFileName = "both_hands_up"
            ),
            Exercise(
                type = ExerciseType.TOUCH_SHOULDERS,
                displayName = "Touch Shoulders",
                instruction = "Start in T-pose, then bring both hands to your shoulders!",
                videoFileName = "touch_shoulders"
            )
        )
    )

    // ─────────────────────────────────────────
    // LEVEL 2 — Movement Basics
    // ─────────────────────────────────────────
    private fun buildLevel2() = GameLevel(
        levelNumber = 2,
        title = "Level 2",
        motivationalMessage = "Great work! Let's move a little more!",
        exercises = listOf(
            Exercise(
                type = ExerciseType.ARM_CIRCLES,
                displayName = "Arm Circles",
                instruction = "T-pose, then rotate your arms forward in big circles!",
                videoFileName = "arm_circles"
            ),
            Exercise(
                type = ExerciseType.SIDE_STRETCH_LEFT,
                displayName = "Side Stretch Left",
                instruction = "Raise your right arm and lean your body to the LEFT!",
                videoFileName = "side_stretch_left"
            ),
            Exercise(
                type = ExerciseType.SIDE_STRETCH_RIGHT,
                displayName = "Side Stretch Right",
                instruction = "Raise your left arm and lean your body to the RIGHT!",
                videoFileName = "side_stretch_right"
            ),
            Exercise(
                type = ExerciseType.KNEE_LIFT_LEFT,
                displayName = "Knee Lift Left",
                instruction = "Hands on hips, lift your LEFT knee up high!",
                videoFileName = "knee_lift_left"
            ),
            Exercise(
                type = ExerciseType.KNEE_LIFT_RIGHT,
                displayName = "Knee Lift Right",
                instruction = "Hands on hips, lift your RIGHT knee up high!",
                videoFileName = "knee_lift_right"
            ),
            Exercise(
                type = ExerciseType.CROSS_BODY_LEFT,
                displayName = "Cross-Body Touch Left",
                instruction = "Lift your RIGHT hand and touch your LEFT knee!",
                videoFileName = "cross_body_left"
            ),
            Exercise(
                type = ExerciseType.CROSS_BODY_RIGHT,
                displayName = "Cross-Body Touch Right",
                instruction = "Lift your LEFT hand and touch your RIGHT knee!",
                videoFileName = "cross_body_right"
            )
        )
    )

    // ─────────────────────────────────────────
    // LEVEL 3 — Full Body
    // ─────────────────────────────────────────
    private fun buildLevel3() = GameLevel(
        levelNumber = 3,
        title = "Level 3",
        motivationalMessage = "Final level! You are a superstar!",
        exercises = listOf(
            Exercise(
                type = ExerciseType.JUMPING_JACK,
                displayName = "Jumping Jack",
                instruction = "Jump! Spread your legs and raise both arms at the same time!",
                videoFileName = "jumping_jack"
            ),
            Exercise(
                type = ExerciseType.SQUAT,
                displayName = "Squat",
                instruction = "Feet apart, bend your knees and go down slowly!",
                videoFileName = "squat"
            ),
            Exercise(
                type = ExerciseType.CLAP_ABOVE_HEAD,
                displayName = "Clap Above Head",
                instruction = "Raise both arms and clap your hands above your head!",
                videoFileName = "clap_above_head"
            )
        )
    )
}