package com.robomove.app.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View

class PoseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        val SKELETON_CONNECTIONS = listOf(
            Pair(11, 12), Pair(11, 23), Pair(12, 24), Pair(23, 24),
            Pair(11, 13), Pair(13, 15),
            Pair(12, 14), Pair(14, 16),
            Pair(23, 25), Pair(25, 27),
            Pair(24, 26), Pair(26, 28),
        )
    }

    private val jointPaint = Paint().apply {
        color       = Color.parseColor("#FFD700")
        style       = Paint.Style.FILL
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val bonePaint = Paint().apply {
        color       = Color.parseColor("#00E5FF")
        style       = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    // Use two arrays instead of list of pairs — avoids object allocation per frame
    private var landmarkX = FloatArray(0)
    private var landmarkY = FloatArray(0)
    private var landmarkCount = 0

    // Pre-computed screen coords — avoids multiply-per-draw
    private var screenX = FloatArray(0)
    private var screenY = FloatArray(0)

    private val mainHandler = Handler(Looper.getMainLooper())

    fun updatePose(
        pose: com.google.mlkit.vision.pose.Pose,
        imageWidth: Int,
        imageHeight: Int,
        isFrontCamera: Boolean = true
    ) {
        val all = pose.allPoseLandmarks
        if (all.isEmpty()) {
            landmarkCount = 0
            mainHandler.post { invalidate() }
            return
        }

        val count = all.size
        if (landmarkX.size < count) {
            landmarkX = FloatArray(count)
            landmarkY = FloatArray(count)
            screenX   = FloatArray(count)
            screenY   = FloatArray(count)
        }

        val w = imageWidth.toFloat()
        val h = imageHeight.toFloat()

        for (i in 0 until count) {
            val lm = all[i]
            val nx = lm.position.x / w
            landmarkX[i] = if (isFrontCamera) 1f - nx else nx
            landmarkY[i] = lm.position.y / h
        }
        landmarkCount = count

        // Post invalidate on main thread — but do coord scaling here
        // so onDraw just multiplies pre-normalised values by view size
        mainHandler.post { invalidate() }
    }

    fun clearPose() {
        landmarkCount = 0
        mainHandler.post { invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (landmarkCount == 0) return

        val vw = width.toFloat()
        val vh = height.toFloat()

        // Scale to screen coords once
        for (i in 0 until landmarkCount) {
            screenX[i] = landmarkX[i] * vw
            screenY[i] = landmarkY[i] * vh
        }

        // Draw bones first
        for ((a, b) in SKELETON_CONNECTIONS) {
            if (a >= landmarkCount || b >= landmarkCount) continue
            canvas.drawLine(screenX[a], screenY[a], screenX[b], screenY[b], bonePaint)
        }

        // Draw joints on top
        for (i in 0 until landmarkCount) {
            canvas.drawCircle(screenX[i], screenY[i], 12f, jointPaint)
        }
    }
}