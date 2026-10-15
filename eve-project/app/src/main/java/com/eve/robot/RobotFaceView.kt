package com.eve.robot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Draws two expressive green eyes only: no pupil, mouth, text, or control buttons. */
class RobotFaceView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(210, 255, 225) }
    private val shape = Path()
    private var mood = EveMood.AWAKE
    private var silhouette = EyeSilhouette.ALMOND
    private var opening = 0.98f
    private var tilt = 0f
    private var gazeX = 0f
    private var gazeY = 0f
    private var glow = 0.92f
    private var squint = 1f
    private var asymmetry = 0f
    private var eyeWidth = 1f
    private var eyeHeight = 1f
    private var spacing = 1f

    // Targets create smooth transitions rather than snapping between a handful of fixed poses.
    private var targetOpening = opening
    private var targetTilt = tilt
    private var targetGazeX = gazeX
    private var targetGazeY = gazeY
    private var targetGlow = glow
    private var targetSquint = squint
    private var targetAsymmetry = asymmetry
    private var targetEyeWidth = eyeWidth
    private var targetEyeHeight = eyeHeight
    private var targetSpacing = spacing

    private var fromOpening = opening
    private var fromTilt = tilt
    private var fromGazeX = gazeX
    private var fromGazeY = gazeY
    private var fromGlow = glow
    private var fromSquint = squint
    private var fromAsymmetry = asymmetry
    private var fromEyeWidth = eyeWidth
    private var fromEyeHeight = eyeHeight
    private var fromSpacing = spacing
    private var transitionStartedAt = 0L
    private val transitionDurationMs = 360L
    private var blinkAt = SystemClock.uptimeMillis() + 2200L
    private var blinkStarted = 0L
    private var blinkDurationMs = 180L
    private var animate = false
    private var glanceX = 0f
    private var glanceY = 0f
    private var targetGlanceX = 0f
    private var targetGlanceY = 0f
    private var nextGlanceAt = SystemClock.uptimeMillis() + 3000L
    private var scanStep = 0

    init {
        isClickable = true
        contentDescription = "EVE_GREEN_EYES_FACE"
        setBackgroundColor(Color.rgb(2, 7, 10))
    }

    fun setMood(next: EveMood, seed: Long = SystemClock.uptimeMillis()) {
        applyProfile(EyeExpressionGenerator.generate(EveDecision(next, "mood expression"), seed))
    }

    /** Eye geometry is generated from the same affect snapshot used by the offline brain and voice. */
    fun setExpression(decision: EveDecision, seed: Long = SystemClock.uptimeMillis()) {
        applyProfile(EyeExpressionGenerator.generate(decision, seed))
    }

    private fun applyProfile(profile: EyeExpressionProfile) {
        mood = profile.mood
        silhouette = profile.silhouette
        // If another event arrives mid-transition, begin the new motion from the interpolated pose.
        updateTransition(SystemClock.uptimeMillis())
        targetOpening = profile.opening
        targetTilt = profile.tilt
        targetGazeX = profile.gazeX
        targetGazeY = profile.gazeY
        targetGlow = profile.glow
        targetSquint = profile.squint
        targetAsymmetry = profile.asymmetry
        targetEyeWidth = profile.eyeWidth
        targetEyeHeight = profile.eyeHeight
        targetSpacing = profile.spacing
        beginTransition()
        // Keep an independent blink clock: frequent camera reactions must not postpone blinking.
        invalidate()
    }

    private fun beginTransition() {
        fromOpening = opening
        fromTilt = tilt
        fromGazeX = gazeX
        fromGazeY = gazeY
        fromGlow = glow
        fromSquint = squint
        fromAsymmetry = asymmetry
        fromEyeWidth = eyeWidth
        fromEyeHeight = eyeHeight
        fromSpacing = spacing
        transitionStartedAt = SystemClock.uptimeMillis()
    }

    private fun updateTransition(now: Long) {
        if (transitionStartedAt == 0L) return
        val raw = ((now - transitionStartedAt).toFloat() / transitionDurationMs).coerceIn(0f, 1f)
        // Smoothstep avoids robotic snapping at the beginning/end of an expression.
        val p = raw * raw * (3f - 2f * raw)
        fun mix(from: Float, to: Float) = from + (to - from) * p
        opening = mix(fromOpening, targetOpening)
        tilt = mix(fromTilt, targetTilt)
        gazeX = mix(fromGazeX, targetGazeX)
        gazeY = mix(fromGazeY, targetGazeY)
        glow = mix(fromGlow, targetGlow)
        squint = mix(fromSquint, targetSquint)
        asymmetry = mix(fromAsymmetry, targetAsymmetry)
        eyeWidth = mix(fromEyeWidth, targetEyeWidth)
        eyeHeight = mix(fromEyeHeight, targetEyeHeight)
        spacing = mix(fromSpacing, targetSpacing)
        if (raw >= 1f) transitionStartedAt = 0L
    }

    fun startAnimation() { animate = true; invalidate() }
    fun stopAnimation() { animate = false }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(2, 7, 10))
        val now = SystemClock.uptimeMillis()
        updateTransition(now)
        if (animate && now >= blinkAt) {
            blinkStarted = now
            blinkDurationMs = when (mood) {
                // Drowsy eyes close more slowly; bright moods use a crisp, lively blink.
                EveMood.SLEEPY -> 285L
                EveMood.HAPPY, EveMood.PLAYFUL, EveMood.FAMILIAR -> 155L
                EveMood.SURPRISED -> 195L
                else -> 180L
            }
            blinkAt = now + when (mood) {
                EveMood.SLEEPY -> 2600L
                EveMood.HAPPY, EveMood.PLAYFUL -> 3000L
                EveMood.FAMILIAR -> 3900L
                EveMood.SURPRISED -> 5200L
                EveMood.IRRITATED, EveMood.ANGRY -> 5000L
                else -> 4100L
            }
        }
        val blinkAge = now - blinkStarted
        val blink = if (blinkStarted > 0L && blinkAge < blinkDurationMs) {
            val p = blinkAge.toFloat() / blinkDurationMs
            // Smooth close-and-open motion prevents a mechanical snap.
            if (p < 0.5f) 1f - p * 1.8f else 0.1f + (p - 0.5f) * 1.8f
        } else 1f
        if (blinkStarted > 0L && blinkAge >= blinkDurationMs) blinkStarted = 0L
        if (animate && now >= nextGlanceAt) {
            // EVE's gaze is purposeful: playful moods scan a little faster; irritated moods look away.
            when {
                mood == EveMood.IRRITATED || mood == EveMood.ANGRY -> when (scanStep % 4) {
                    0 -> { targetGlanceX = 0.052f; targetGlanceY = 0.008f }
                    1 -> { targetGlanceX = 0.028f; targetGlanceY = 0.018f }
                    2 -> { targetGlanceX = -0.020f; targetGlanceY = 0.006f }
                    else -> { targetGlanceX = 0.018f; targetGlanceY = 0.012f }
                }
                else -> when (scanStep % 4) {
                    0 -> { targetGlanceX = -0.034f; targetGlanceY = 0.004f }
                    1 -> { targetGlanceX = 0f; targetGlanceY = -0.014f }
                    2 -> { targetGlanceX = 0.034f; targetGlanceY = 0.004f }
                    else -> { targetGlanceX = 0f; targetGlanceY = 0f }
                }
            }
            scanStep++
            nextGlanceAt = now + when (mood) {
                EveMood.PLAYFUL -> 2700L
                EveMood.IRRITATED -> 4600L
                EveMood.ANGRY -> 5600L
                else -> 3600L
            }
        }
        val glanceEase = if (animate) 0.075f else 1f
        glanceX += (targetGlanceX - glanceX) * glanceEase
        glanceY += (targetGlanceY - glanceY) * glanceEase
        val idleGaze = if (animate) sin(now / 1610.0).toFloat() * 0.012f else 0f
        val idleGazeY = if (animate) sin(now / 2340.0).toFloat() * 0.007f else 0f
        val breath = if (animate) 0.965f + 0.035f * sin(now / 980.0).toFloat() else 1f
        // EVE's eyes are wide almond shapes with a visible bridge of dark space between them.
        // Keep the geometry relative to the view's actual dp size so glow strokes do not merge
        // the two eyes on tall phone screens or when the device uses display scaling.
        val w = min(width * 0.36f, height * 0.24f) * eyeWidth
        val h = min(height * 0.25f, w * 0.78f) * eyeHeight
        val gap = min(width * 0.24f, w * 0.88f) * spacing
        val cy = height * 0.50f
        drawEye(canvas, width * 0.5f - gap + (gazeX + idleGaze + glanceX) * width,
            cy + (gazeY + idleGazeY + glanceY * 0.42f) * height, w * (1f + asymmetry * 0.24f), h,
            opening * blink * breath * (1f + asymmetry * 0.30f), tilt, glow, squint, silhouette, -1)
        drawEye(canvas, width * 0.5f + gap + (gazeX + idleGaze + glanceX + asymmetry * 0.025f) * width,
            cy + (gazeY + idleGazeY + glanceY * 0.42f - asymmetry * 0.035f) * height, w * (1f - asymmetry * 0.24f), h,
            opening * blink * breath * (1f - asymmetry * 0.30f), -tilt, glow, squint, silhouette, 1)
        if (animate) postInvalidateDelayed(32L)
    }

    private fun drawEye(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, openness: Float, angle: Float, intensity: Float, squinting: Float, silhouette: EyeSilhouette, side: Int) {
        val hw = w * 0.5f
        val hh = h * 0.5f * openness.coerceIn(0.06f, 1.18f)
        val curve = squinting.coerceIn(0.45f, 1.22f)
        // Procedural contour library: each affect uses its own eyelid silhouette, not just
        // a resized copy. This ports the lightweight polygon/curve idea to Canvas for Android.
        val upperPeak = when (silhouette) {
            EyeSilhouette.SMILE -> 0.66f
            EyeSilhouette.ROUND_SURPRISE -> 1.12f
            EyeSilhouette.DROOPY -> 0.60f
            EyeSilhouette.FOCUSED -> 0.72f
            EyeSilhouette.WORRIED -> 0.83f
            EyeSilhouette.SHARP -> 0.70f
            EyeSilhouette.PLAYFUL -> 0.94f
            EyeSilhouette.SOFT -> 0.96f
            EyeSilhouette.ALMOND -> 0.90f
        } * curve
        val upperShoulder = when (silhouette) {
            EyeSilhouette.SMILE -> 0.43f
            EyeSilhouette.ROUND_SURPRISE -> 0.52f
            EyeSilhouette.DROOPY -> 0.38f
            EyeSilhouette.FOCUSED -> 0.34f
            EyeSilhouette.WORRIED -> 0.47f
            EyeSilhouette.SHARP -> 0.29f
            EyeSilhouette.PLAYFUL -> 0.49f
            EyeSilhouette.SOFT -> 0.47f
            EyeSilhouette.ALMOND -> 0.46f
        } * curve
        val lowerPeak = when (silhouette) {
            EyeSilhouette.SMILE -> 0.42f
            EyeSilhouette.ROUND_SURPRISE -> 1.06f
            EyeSilhouette.DROOPY -> 0.76f
            EyeSilhouette.FOCUSED -> 0.56f
            EyeSilhouette.WORRIED -> 0.76f
            EyeSilhouette.SHARP -> 0.48f
            EyeSilhouette.PLAYFUL -> 0.72f
            EyeSilhouette.SOFT -> 0.84f
            EyeSilhouette.ALMOND -> 0.80f
        }
        val lowerShoulder = when (silhouette) {
            EyeSilhouette.SMILE -> 0.30f
            EyeSilhouette.ROUND_SURPRISE -> 0.61f
            EyeSilhouette.DROOPY -> 0.56f
            EyeSilhouette.FOCUSED -> 0.37f
            EyeSilhouette.WORRIED -> 0.61f
            EyeSilhouette.SHARP -> 0.35f
            EyeSilhouette.PLAYFUL -> 0.53f
            EyeSilhouette.SOFT -> 0.56f
            EyeSilhouette.ALMOND -> 0.54f
        }
        val cornerLift = if (silhouette == EyeSilhouette.SHARP) -hh * 0.11f * side else 0f
        shape.reset()
        shape.moveTo(cx - hw, cy + cornerLift)
        shape.cubicTo(
            cx - hw * 0.96f, cy - hh * upperShoulder,
            cx - hw * 0.52f, cy - hh * upperPeak,
            cx, cy - hh * upperPeak
        )
        shape.cubicTo(
            cx + hw * 0.58f, cy - hh * upperPeak,
            cx + hw * 0.97f, cy - hh * upperShoulder,
            cx + hw, cy - cornerLift
        )
        shape.cubicTo(
            cx + hw * 0.97f, cy + hh * lowerShoulder,
            cx + hw * 0.52f, cy + hh * lowerPeak,
            cx, cy + hh * lowerPeak
        )
        shape.cubicTo(
            cx - hw * 0.56f, cy + hh * lowerPeak,
            cx - hw * 0.97f, cy + hh * lowerShoulder,
            cx - hw, cy + cornerLift
        )
        shape.close()
        c.save()
        c.rotate(angle, cx, cy)
        edge.shader = null
        // Layered restrained glow gives the eyes depth without a black pupil or harsh neon rim.
        edge.strokeWidth = max(2f, w * 0.20f)
        edge.color = Color.argb((25f * intensity).toInt().coerceIn(0, 36), 25, 255, 105)
        c.drawPath(shape, edge)
        edge.strokeWidth = max(2f, w * 0.095f)
        edge.color = Color.argb((42f * intensity).toInt().coerceIn(0, 55), 35, 255, 115)
        c.drawPath(shape, edge)
        edge.strokeWidth = max(2f, w * 0.065f)
        edge.color = Color.argb((48f * intensity).toInt().coerceIn(0, 62), 25, 255, 105)
        c.drawPath(shape, edge)
        edge.strokeWidth = max(1.5f, w * 0.025f)
        edge.color = Color.argb((88f * intensity).toInt().coerceIn(0, 110), 60, 255, 130)
        c.drawPath(shape, edge)
        fill.style = Paint.Style.FILL
        val eyeColors = when (mood) {
            EveMood.SLEEPY, EveMood.CONCERNED, EveMood.IRRITATED, EveMood.ANGRY ->
                intArrayOf(Color.rgb(150, 235, 178), Color.rgb(30, 218, 103), Color.rgb(0, 104, 48))
            EveMood.HAPPY, EveMood.SURPRISED, EveMood.PLAYFUL ->
                intArrayOf(Color.rgb(222, 255, 232), Color.rgb(70, 255, 144), Color.rgb(0, 162, 70))
            else -> intArrayOf(Color.rgb(190, 255, 215), Color.rgb(44, 245, 119), Color.rgb(0, 137, 61))
        }
        fill.shader = LinearGradient(cx - hw * 0.2f, cy - hh, cx + hw * 0.12f, cy + hh,
            eyeColors, null, Shader.TileMode.CLAMP)
        c.drawPath(shape, fill)
        fill.shader = null
        edge.strokeWidth = max(1.5f, w * 0.009f)
        edge.color = Color.argb((130f * intensity).toInt().coerceIn(0, 150), 190, 255, 210)
        c.drawPath(shape, edge)
        // Reflections only; this intentionally never draws a black pupil.
        glint.alpha = 220
        c.drawCircle(cx - hw * 0.27f, cy - hh * 0.30f, max(2.5f, w * 0.022f), glint)
        glint.alpha = 110
        c.drawCircle(cx + hw * 0.20f, cy - hh * 0.44f, max(1.5f, w * 0.009f), glint)
        c.restore()
    }
}
