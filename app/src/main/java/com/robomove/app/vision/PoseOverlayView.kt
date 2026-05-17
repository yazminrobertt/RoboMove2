package com.robomove.app.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * A transparent View placed ON TOP of the camera preview.
 * Every time new landmarks arrive, we call updatePose() and it redraws.
 *
 * It draws:
 *   - Dots on each body joint
 *   - Lines connecting joints (the skeleton)
 */
class PoseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "PoseOverlayView"

        // Skeleton connections — pairs of landmark indices to draw lines between
        // Based on MediaPipe Pose landmark map
        val SKELETON_CONNECTIONS = listOf(
            // Torso
            Pair(11, 12), // left shoulder  → right shoulder
            Pair(11, 23), // left shoulder  → left hip
            Pair(12, 24), // right shoulder → right hip
            Pair(23, 24), // left hip       → right hip

            // Left arm
            Pair(11, 13), // left shoulder → left elbow
            Pair(13, 15), // left elbow    → left wrist

            // Right arm
            Pair(12, 14), // right shoulder → right elbow
            Pair(14, 16), // right elbow    → right wrist

            // Left leg
            Pair(23, 25), // left hip   → left knee
            Pair(25, 27), // left knee  → left ankle

            // Right leg
            Pair(24, 26), // right hip  → right knee
            Pair(26, 28), // right knee → right ankle
        )
    }

    // ── Paint brushes ──
    private val jointPaint = Paint().apply {
        color       = Color.parseColor("#FFD700") // Gold dots
        style       = Paint.Style.FILL
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val bonePaint = Paint().apply {
        color       = Color.parseColor("#00E5FF") // Cyan lines
        style       = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val missedPaint = Paint().apply {
        color       = Color.parseColor("#FF5252") // Red when pose wrong
        style       = Paint.Style.FILL
        strokeWidth = 8f
        isAntiAlias = true
    }

    // ── State ──
    private var landmarks: List<Pair<Float, Float>> = emptyList()
    // landmark coords are normalised 0..1, we scale to view size when drawing

    // ─────────────────────────────────────────
    // PUBLIC — call from GameActivity on each frame
    // ─────────────────────────────────────────

    /**
     * Call this every time PoseDetector returns a result.
     * Pass the raw landmark list (normalised x, y values).
     */
    fun updatePose(result: PoseLandmarkerResult) {
        if (result.landmarks().isEmpty()) {
            landmarks = emptyList()
            postInvalidate()
            return
        }

        // Extract x, y as pairs — these are 0..1 normalised values
        landmarks = result.landmarks()[0].map { lm ->
            Pair(lm.x(), lm.y())
        }

        // Trigger a redraw on the UI thread
        postInvalidate()
    }

    /** Call this to clear the skeleton (e.g. when paused) */
    fun clearPose() {
        landmarks = emptyList()
        postInvalidate()
    }

    // ─────────────────────────────────────────
    // DRAWING
    // ─────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (landmarks.isEmpty()) return

        val viewWidth  = width.toFloat()
        val viewHeight = height.toFloat()

        // Draw skeleton lines first (so dots appear on top)
        for ((startIdx, endIdx) in SKELETON_CONNECTIONS) {
            if (startIdx >= landmarks.size || endIdx >= landmarks.size) continue

            val (x1, y1) = landmarks[startIdx]
            val (x2, y2) = landmarks[endIdx]

            // Mirror X because front camera flips horizontally
            canvas.drawLine(
                (1f - x1) * viewWidth,
                y1 * viewHeight,
                (1f - x2) * viewWidth,
                y2 * viewHeight,
                bonePaint
            )
        }

        // Draw joint dots on top
        for ((x, y) in landmarks) {
            canvas.drawCircle(
                (1f - x) * viewWidth,   // mirror X
                y * viewHeight,
                12f,
                jointPaint
            )
        }
    }
}