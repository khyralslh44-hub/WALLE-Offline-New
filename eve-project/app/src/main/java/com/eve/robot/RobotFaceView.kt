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
        val idleGaze = if (animate) sin(now / 1900.0).toFloat() * 0.012f else 0f
        val breath = if (animate) 0.985f + 0.015f * sin(now / 850.0).toFloat() else 1f
        val w = min(width * 0.34f, height * 0.26f) * eyeWidth
        val h = min(height * 0.34f, w * 1.40f) * eyeHeight
        val gap = min(width * 0.19f, w * 0.60f) * spacing
        val cy = height * 0.50f
        drawEye(canvas, width * 0.5f - gap + (gazeX + idleGaze) * width, cy + gazeY * height, w * (1f + asymmetry * 0.14f), h, opening * blink * breath, tilt, glow, squint)
        drawEye(canvas, width * 0.5f + gap + (gazeX + idleGaze) * width, cy + gazeY * height, w * (1f - asymmetry * 0.14f), h, opening * blink * breath, -tilt, glow, squint)
        if (animate) postInvalidateDelayed(32L)
    }

    private fun drawEye(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, openness: Float, angle: Float, intensity: Float, squinting: Float) {
        val hw = w * 0.5f
        val hh = h * 0.5f * openness.coerceIn(0.06f, 1.18f)
        val curve = squinting.coerceIn(0.45f, 1.22f)
        shape.reset()
        shape.moveTo(cx - hw, cy)
        shape.cubicTo(cx - hw * 0.58f, cy - hh * curve, cx + hw * 0.50f, cy - hh * curve, cx + hw, cy)
        shape.cubicTo(cx + hw * 0.54f, cy + hh * 0.83f, cx - hw * 0.56f, cy + hh * 0.83f, cx - hw, cy)
        shape.close()
        c.save()
        c.rotate(angle, cx, cy)
        edge.shader = null
        edge.strokeWidth = max(2f, w * 0.065f)
        edge.color = Color.argb((35f * intensity).toInt().coerceIn(0, 48), 25, 255, 105)
        c.drawPath(shape, edge)
        edge.strokeWidth = max(1.5f, w * 0.025f)
        edge.color = Color.argb((80f * intensity).toInt().coerceIn(0, 100), 60, 255, 130)
        c.drawPath(shape, edge)
        fill.style = Paint.Style.FILL
        fill.shader = LinearGradient(cx, cy - hh, cx, cy + hh,
            intArrayOf(Color.rgb(178, 255, 205), Color.rgb(42, 255, 120), Color.rgb(0, 145, 67)),
            null, Shader.TileMode.CLAMP)
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
