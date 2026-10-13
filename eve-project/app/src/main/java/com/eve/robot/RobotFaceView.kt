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
import kotlin.random.Random

/** Draws two expressive green eyes only: no pupil, mouth, text, or control buttons. */
class RobotFaceView(context: Context) : View(context) {
    private val rng = Random(System.currentTimeMillis())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(210, 255, 225) }
    private val shape = Path()
    private var mood = EveMood.AWAKE
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
    private var blinkAt = SystemClock.uptimeMillis() + 2000L
    private var blinkStarted = 0L
    private var animate = false
    private var glanceX = 0f
    private var glanceY = 0f
    private var targetGlanceX = 0f
    private var targetGlanceY = 0f
    private var nextGlanceAt = SystemClock.uptimeMillis() + 2400L

    init {
        isClickable = true
        contentDescription = "EVE_GREEN_EYES_FACE"
        setBackgroundColor(Color.rgb(2, 7, 10))
    }

    fun setMood(next: EveMood, seed: Long = SystemClock.uptimeMillis()) {
        mood = next
        val b = when (next) {
            EveMood.AWAKE -> floatArrayOf(1.04f, 0f, 0f, -0.02f, 1f, 1.05f)
            EveMood.NEUTRAL -> floatArrayOf(0.86f, 0f, 0f, 0f, 0.76f, 1f)
            EveMood.CURIOUS -> floatArrayOf(1.02f, -4f, 0.04f, -0.025f, 1f, 1.12f)
            EveMood.HAPPY -> floatArrayOf(0.72f, -6f, 0f, -0.02f, 0.98f, 0.76f)
            EveMood.SURPRISED -> floatArrayOf(1.13f, 0f, 0f, -0.025f, 1f, 1.18f)
            EveMood.CONCERNED -> floatArrayOf(0.66f, 3f, -0.02f, 0.02f, 0.65f, 0.78f)
            EveMood.FAMILIAR -> floatArrayOf(0.82f, -2f, 0.015f, 0f, 0.9f, 0.92f)
            EveMood.SLEEPY -> floatArrayOf(0.28f, 0f, 0f, 0.05f, 0.48f, 0.55f)
            EveMood.FOCUSED -> floatArrayOf(0.61f, 0f, 0f, 0f, 0.72f, 0.70f)
        }
        val r = Random(seed)
        opening = vary(b[0], 0.10f, 0.16f, 1.17f, r)
        tilt = vary(b[1], 3.5f, -12f, 12f, r)
        gazeX = vary(b[2], 0.04f, -0.09f, 0.09f, r)
        gazeY = vary(b[3], 0.03f, -0.07f, 0.07f, r)
        glow = vary(b[4], 0.13f, 0.25f, 1f, r)
        squint = vary(b[5], 0.11f, 0.45f, 1.22f, r)
        asymmetry = (r.nextFloat() * 0.24f - 0.12f)
        eyeWidth = 0.89f + r.nextFloat() * 0.22f
        eyeHeight = 0.88f + r.nextFloat() * 0.24f
        spacing = 0.92f + r.nextFloat() * 0.16f
        blinkAt = SystemClock.uptimeMillis() + 1500L + r.nextLong(3000L)
        invalidate()
    }

    /**
     * Compose each expression from continuous affect values as well as its mood.
     * This deliberately generates a large space of parameterized expressions rather than
     * cycling through a small set of fixed pictures.
     */
    fun setExpression(decision: EveDecision, seed: Long = SystemClock.uptimeMillis()) {
        setMood(decision.mood, seed)
        val r = Random(seed xor 0x4E56454L)
        val emotion = decision.valence.coerceIn(-1f, 1f)
        val alertness = decision.arousal.coerceIn(0f, 1f)
        val curiosity = decision.curiosity.coerceIn(0f, 1f)
        val familiarity = decision.familiarity.coerceIn(0f, 1f)
        val tired = decision.fatigue.coerceIn(0f, 1f)
        opening = (opening + (alertness - 0.42f) * 0.36f - tired * 0.18f
            + (r.nextFloat() - 0.5f) * 0.18f).coerceIn(0.16f, 1.20f)
        tilt = (tilt + emotion * 5.5f + (curiosity - 0.5f) * 9.0f + (familiarity - 0.3f) * 3.5f
            + (r.nextFloat() - 0.5f) * 5f).coerceIn(-17f, 17f)
        gazeX = (gazeX + (curiosity - 0.5f) * 0.15f
            + (r.nextFloat() - 0.5f) * 0.065f).coerceIn(-0.17f, 0.17f)
        gazeY = (gazeY + tired * 0.065f - alertness * 0.035f
            + (r.nextFloat() - 0.5f) * 0.05f).coerceIn(-0.12f, 0.12f)
        glow = (glow * (0.79f + emotion * 0.10f + alertness * 0.06f + familiarity * 0.08f))
            .coerceIn(0.28f, 1f)
        squint = (squint + emotion * 0.10f - tired * 0.30f
            + (r.nextFloat() - 0.5f) * 0.16f).coerceIn(0.42f, 1.24f)
        asymmetry = (asymmetry + (curiosity - 0.5f) * 0.44f
            + (r.nextFloat() - 0.5f) * 0.34f).coerceIn(-0.42f, 0.42f)
        eyeWidth = (eyeWidth + (alertness - 0.45f) * 0.11f
            + (r.nextFloat() - 0.5f) * 0.10f).coerceIn(0.76f, 1.22f)
        eyeHeight = (eyeHeight + emotion * 0.055f - tired * 0.12f
            + (r.nextFloat() - 0.5f) * 0.10f).coerceIn(0.64f, 1.24f)
        spacing = (spacing + (alertness - 0.4f) * 0.12f
            + (r.nextFloat() - 0.5f) * 0.10f).coerceIn(0.80f, 1.20f)
        blinkAt = SystemClock.uptimeMillis() + 1300L + r.nextLong(5200L)
        invalidate()
    }

    private fun vary(v: Float, range: Float, low: Float, high: Float, r: Random) =
        (v + (r.nextFloat() * 2f - 1f) * range).coerceIn(low, high)

    fun startAnimation() { animate = true; invalidate() }
    fun stopAnimation() { animate = false }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(2, 7, 10))
        val now = SystemClock.uptimeMillis()
        if (animate && now >= blinkAt) {
            blinkStarted = now
            blinkAt = now + 2600L + rng.nextLong(4200L)
        }
        val blink = if (blinkStarted > 0L && now - blinkStarted < 180L) {
            val p = (now - blinkStarted).toFloat() / 180f
            if (p < 0.5f) 1f - p * 1.8f else 0.1f + (p - 0.5f) * 1.8f
        } else 1f
        if (blinkStarted > 0L && now - blinkStarted >= 180L) blinkStarted = 0L
        if (animate && now >= nextGlanceAt) {
            // Unscripted short glances make the face feel observant rather than a static icon.
            targetGlanceX = rng.nextFloat() * 0.085f - 0.0425f
            targetGlanceY = rng.nextFloat() * 0.038f - 0.019f
            nextGlanceAt = now + 1900L + rng.nextLong(3900L)
        }
        val glanceEase = if (animate) 0.075f else 1f
        glanceX += (targetGlanceX - glanceX) * glanceEase
        glanceY += (targetGlanceY - glanceY) * glanceEase
        val idleGaze = if (animate) sin(now / 1610.0).toFloat() * 0.012f else 0f
        val idleGazeY = if (animate) sin(now / 2340.0).toFloat() * 0.007f else 0f
        val breath = if (animate) 0.965f + 0.035f * sin(now / 980.0).toFloat() else 1f
        val w = min(width * 0.35f, height * 0.27f) * eyeWidth
        val h = min(height * 0.34f, w * 1.43f) * eyeHeight
        val gap = min(width * 0.18f, w * 0.58f) * spacing
        val cy = height * 0.50f
        drawEye(canvas, width * 0.5f - gap + (gazeX + idleGaze + glanceX) * width,
            cy + (gazeY + idleGazeY + glanceY * 0.42f) * height, w * (1f + asymmetry * 0.24f), h,
            opening * blink * breath * (1f + asymmetry * 0.30f), tilt, glow, squint)
        drawEye(canvas, width * 0.5f + gap + (gazeX + idleGaze + glanceX + asymmetry * 0.025f) * width,
            cy + (gazeY + idleGazeY + glanceY * 0.42f - asymmetry * 0.035f) * height, w * (1f - asymmetry * 0.24f), h,
            opening * blink * breath * (1f - asymmetry * 0.30f), -tilt, glow, squint)
        if (animate) postInvalidateDelayed(32L)
    }

    private fun drawEye(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, openness: Float, angle: Float, intensity: Float, squinting: Float) {
        val hw = w * 0.5f
        val hh = h * 0.5f * openness.coerceIn(0.06f, 1.18f)
        val curve = squinting.coerceIn(0.45f, 1.22f)
        shape.reset()
        // Soft, large EVE-inspired oval almond. The silhouette and lid aperture are varied
        // continuously by the emotional state, while the requested green eye color stays fixed.
        shape.moveTo(cx - hw, cy)
        shape.cubicTo(cx - hw * 0.96f, cy - hh * 0.48f * curve, cx - hw * 0.54f, cy - hh * 0.98f * curve, cx, cy - hh * curve)
        shape.cubicTo(cx + hw * 0.58f, cy - hh * 0.98f * curve, cx + hw * 0.98f, cy - hh * 0.46f * curve, cx + hw, cy)
        shape.cubicTo(cx + hw * 0.98f, cy + hh * 0.58f, cx + hw * 0.54f, cy + hh * 0.98f, cx, cy + hh * 0.96f)
        shape.cubicTo(cx - hw * 0.58f, cy + hh * 0.98f, cx - hw * 0.98f, cy + hh * 0.54f, cx - hw, cy)
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
            EveMood.SLEEPY, EveMood.CONCERNED -> intArrayOf(Color.rgb(150, 235, 178), Color.rgb(30, 218, 103), Color.rgb(0, 104, 48))
            EveMood.HAPPY, EveMood.SURPRISED -> intArrayOf(Color.rgb(222, 255, 232), Color.rgb(70, 255, 144), Color.rgb(0, 162, 70))
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
