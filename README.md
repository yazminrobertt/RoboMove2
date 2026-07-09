# RoboMove

An AI-based interactive fitness coaching application for children, built for the Daman/Reeman humanoid robot.

RoboMove turns a humanoid service robot into a fitness coach for children aged 6-12. Using the robot's built-in camera, microphone, and speaker, it guides users through a structured set of exercises, tracks their movement in real time using on-device pose estimation, and delivers spoken instructions, encouragement, and corrective feedback. The system runs fully offline, with no external sensors, cloud connectivity, or additional hardware required.

This project was developed as a Final Year Project (FYP) at Universiti Teknikal Malaysia Melaka (UTeM), under the supervision of Dr. Mohd Norhakim Bin Hassan.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Tech Stack](#tech-stack)
- [System Architecture](#system-architecture)
- [Exercises and Levels](#exercises-and-levels)
- [Getting Started](#getting-started)
- [Project Structure](#project-structure)
- [Acknowledgments](#acknowledgments)

---

## Overview

Childhood obesity and inactivity are on the rise, driven in part by increasing screen time and reduced outdoor play. Existing fitness apps and workout videos typically offer only one-way instruction with no real-time feedback, which limits how effectively they engage young users.

RoboMove addresses this by pairing a humanoid robot with:
- Real-time pose estimation to monitor a child's exercise form and count repetitions
- Offline speech recognition for hands-free voice control
- Spoken feedback (instructional, encouraging, and corrective) delivered through the robot's Text-to-Speech engine
- Robot head movement to keep the child properly framed in the camera

All processing runs locally on the robot's built-in Android tablet, requiring no internet connection, external sensors, or additional hardware.

## Features

- 14 exercises across 3 difficulty levels: Warm Up, Active, and Challenge
- Real-time pose detection using the robot's built-in RGB camera
- Automatic repetition counting with per-repetition form evaluation (Correct / Slightly Wrong / Wrong)
- Hands-free voice control with an 8-command offline speech grammar (start, stop, pause, play, skip, continue, yes, no)
- Spoken feedback: instructions on load, encouragement every second correct repetition, and corrective cues on incorrect form
- Score tracking with level-complete and results screens
- Camera alignment functions via robot head control, to keep the child in frame
- Fully offline operation, with no Google Play Services or internet connection required

## Tech Stack

| Component | Technology |
|---|---|
| Platform | Android 6.0 (API level 23), built-in robot tablet |
| Language | Kotlin |
| Pose Estimation | Google ML Kit Pose Detection (BlazePose) |
| Camera | CameraX |
| Speech Recognition | Vosk (offline, grammar-restricted) |
| Text-to-Speech | Android TTS engine |
| Robot Control | Reeman AIDL service interface (ServiceManager reflection) |
| Data Storage | In-memory Kotlin data classes, no external database |

## System Architecture

RoboMove is organized into four core modules, coordinated by a central `GameActivity`:

- **Exercise Guidance** - manages the 14 exercises across 3 levels (Warm Up: 4, Active: 7, Challenge: 3), backed by `LevelRepository`
- **Pose Detection** - `PoseDetector` (Google ML Kit landmark extraction from CameraX frames) and `RepCounter` (state machine for repetition counting and form evaluation)
- **Feedback** - `FeedbackManager`, delivering instructional, encouragement, and corrective speech via Android TTS
- **Interaction** - `VoiceManager`, processing offline Vosk speech recognition against a fixed 8-command grammar

Supporting components include `ScoreManager` (points tracking), `PoseOverlayView` (live skeleton rendering), `DamanHeadControl` (camera alignment via the Reeman AIDL service), and `CameraAlignmentActivity`.

## Exercises and Levels

| Level | Exercise Count | Focus |
|---|---|---|
| Warm Up | 4 | Light mobility and warm-up movements |
| Active | 7 | Core cardio and strength exercises |
| Challenge | 3 | Higher-intensity movements |

Each exercise loads with a demonstration video and spoken instructions before pose evaluation begins.

## Getting Started

RoboMove is built for deployment on a Daman/Reeman humanoid robot's built-in Android 6.0 tablet and depends on the robot's Reeman system service (`IReemanService`). It is not intended to run as a standalone consumer app.

### Prerequisites
- Android Studio (with Kotlin support)
- A Daman/Reeman humanoid robot, or a compatible device exposing the Reeman AIDL service
- Minimum SDK: API 23 (Android 6.0)

### Setup
```bash
git clone https://github.com/yazminrobertt/RoboMove2.git
cd RoboMove2
```
Open the project in Android Studio, let Gradle sync, then build and deploy to the robot's tablet:
```bash
./gradlew assembleDebug
```
Install the resulting APK on the robot's tablet (for example, via `adb install`) and grant camera and microphone permissions on first launch.

## Project Structure

```
robomove/
├── app/
│   ├── src/main/java/.../
│   │   ├── vision/         # PoseDetector, RepCounter, PoseOverlayView
│   │   ├── voice/          # VoiceManager (Vosk integration)
│   │   ├── feedback/       # FeedbackManager (TTS)
│   │   ├── robot/          # DamanHeadControl, CameraAlignmentActivity
│   │   ├── data/           # LevelRepository, Exercise, GameLevel
│   │   └── GameActivity.kt # Central orchestrator
│   └── src/main/assets/    # Exercise demo videos, Vosk model
└── README.md
```

## Acknowledgments

Developed by Nurul Yazmin Binti Ridzwan Robert as a Final Year Project at the Faculty of Artificial Intelligence and Cyber Security, Universiti Teknikal Malaysia Melaka (UTeM), under the supervision of Dr. Mohd Norhakim Bin Hassan.
