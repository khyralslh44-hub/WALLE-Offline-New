package com.eve.robot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Result from a local Paint Shot round. No image or shot data leaves the device. */
internal data class PaintShotResult(
    val hit: Boolean,
    val shotNumber: Int,
    val targetX: Float,
    val targetY: Float,
    val aimX: Float,
    val aimY: Float
)

/**
 * Touch-based, EMO-inspired Paint Shot arena.
 *
 * Tap a moving target to fire a virtual paint pulse. The target moves independently, shots
 * travel from EVE's launcher, and hits/misses are scored locally. This is a harmless screen
 * animation; it does not take or transmit photos and never controls a physical device.
 */
internal class PaintShotArenaView(
    context: Context,
    private val onShot: (PaintShotResult) -> Unit
) : View(context) {

    private data class Shot(
        val startX: Float,
        val startY: Float,
        val aimX: Float,
        val aimY: Float,
        val hit: Boolean,
        val startedAt: Long
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(145, 126, 210, 218)
        textSize = dp(10f)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }
    private val shots = mutableListOf<Shot>()
    private val epoch = SystemClock.uptimeMillis()
    private var targetX = 0f
    private var targetY = 0f
    private var targetInitialized = false
    private var dodgeOffsetX = 0f
    private var dodgeOffsetY = 0f
    private var dodgeStartedAt = 0L
    private var dodgeDurationMs = 420L
    private var active = true
    private var muzzleFlashAt = 0L
    private var lastHitAt = 0L
    private var lastHit = false
    private var shotsFired = 0
    private var shotsHit = 0

    internal val targetCenterForTest: Pair<Float, Float>
        get() {
            updateTarget(SystemClock.uptimeMillis())
            return targetX to targetY
        }
    internal val shotsFiredForTest: Int get() = shotsFired
    internal val shotsHitForTest: Int get() = shotsHit

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Paint Shot. المس الهدف المتحرك لإطلاق كرة طلاء افتراضية."
        setBackgroundColor(Color.rgb(3, 9, 14))
    }

    internal fun setGameActive(value: Boolean) {
        active = value
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        targetInitialized = false
        updateTarget(SystemClock.uptimeMillis())
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun updateTarget(now: Long) {
        if (width <= 0 || height <= 0) return
        val elapsed = (now - epoch).coerceAtLeast(0L).toDouble()
        val dodgeRaw = if (dodgeStartedAt == 0L) 1f else
            ((now - dodgeStartedAt).toFloat() / dodgeDurationMs).coerceIn(0f, 1f)
        val dodgeBlend = dodgeRaw * dodgeRaw * (3f - 2f * dodgeRaw)
        if (dodgeRaw >= 1f) {
            dodgeStartedAt = 0L
            dodgeOffsetX = 0f
            dodgeOffsetY = 0f
        }
        val idleX = width * (0.50f + 0.19f * sin(elapsed / 1650.0).toFloat())
        val idleY = height * (0.31f + 0.055f * sin(elapsed / 1010.0 + 0.8).toFloat())
        targetX = idleX + dodgeOffsetX * (1f - dodgeBlend)
        targetY = idleY + dodgeOffsetY * (1f - dodgeBlend)
        if (!targetInitialized) targetInitialized = true
    }

    private fun dodgeEyes(now: Long, shotX: Float) {
        val direction = if (shotX < targetX) 1f else -1f
        dodgeOffsetX = direction * width * 0.19f
        dodgeOffsetY = -height * 0.035f
        dodgeStartedAt = now
        dodgeDurationMs = 460L
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (active) fireAt(event.x, event.y)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun fireAt(x: Float, y: Float) {
        if (!active || width <= 0 || height <= 0) return
        val now = SystemClock.uptimeMillis()
        updateTarget(now)
        val eyeHalfGap = dp(22f)
        val eyeRadiusX = dp(27f)
        val eyeRadiusY = dp(23f)
        val leftEyeDistance = hypot(((x - (targetX - eyeHalfGap)) / eyeRadiusX).toDouble(),
            ((y - targetY) / eyeRadiusY).toDouble())
        val rightEyeDistance = hypot(((x - (targetX + eyeHalfGap)) / eyeRadiusX).toDouble(),
            ((y - targetY) / eyeRadiusY).toDouble())
        val hit = min(leftEyeDistance, rightEyeDistance) <= 1.35
        shotsFired++
        if (hit) shotsHit++
        shots += Shot(
            startX = width * 0.5f,
            startY = height * 0.91f,
            aimX = x.coerceIn(0f, width.toFloat()),
            aimY = y.coerceIn(dp(8f), height.toFloat()),
            hit = hit,
            startedAt = now
        )
        muzzleFlashAt = now
        lastHitAt = now
        lastHit = hit
        if (!hit) dodgeEyes(now, x)
        performHapticFeedback(if (hit) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        android.util.Log.i("EVE_PAINT_SHOT", "shots=$shotsFired hit=$hit evaded=${!hit} totalHits=$shotsHit")
        onShot(PaintShotResult(hit, shotsFired, targetX, targetY, x, y))
        invalidate()
    }

    /** Instrumentation helper uses the exact same scoring code as a real tap. */
    internal fun shootForTest(x: Float, y: Float) = fireAt(x, y)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        updateTarget(now)
        canvas.drawColor(Color.rgb(3, 9, 14))
        drawRange(canvas)
        drawTarget(canvas, now)
        shots.removeAll { now - it.startedAt > PROJECTILE_MS + SPLASH_MS }
        shots.forEach { drawShot(canvas, it, now) }
        drawLauncher(canvas, now)
        if (active || shots.isNotEmpty() || now - muzzleFlashAt in 0L..100L) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawRange(canvas: Canvas) {
        val horizon = height * 0.66f
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(28, 55, 210, 220)
        for (i in 1..5) {
            val y = horizon + (height - horizon) * i / 6f
            canvas.drawLine(width * 0.04f, y, width * 0.96f, y, stroke)
        }
        for (i in 0..8) {
            val x = width * i / 8f
            canvas.drawLine(width * 0.5f, horizon, x, height.toFloat(), stroke)
        }
        stroke.color = Color.argb(45, 20, 220, 230)
        stroke.strokeWidth = dp(1.5f)
        canvas.drawLine(width * 0.06f, horizon, width * 0.94f, horizon, stroke)
        textPaint.textSize = dp(10f)
        textPaint.color = Color.argb(115, 100, 220, 226)
        canvas.drawText("PAINT SHOT  •  TAP TO FIRE", width / 2f, dp(24f), textPaint)
    }

    private fun drawTarget(canvas: Canvas, now: Long) {
        val pulse = 1f + 0.055f * sin(now / 145.0).toFloat()
        val flashAge = now - lastHitAt
        val flash = flashAge in 0L..230L
        val radiusX = dp(21f) * pulse
        val radiusY = dp(20f) * pulse
        val eyeGap = dp(23f)
        val eyeColor = if (flash && lastHit) Color.rgb(115, 255, 203) else Color.rgb(0, 222, 231)
        fill.color = Color.argb(28, 0, 224, 236)
        canvas.drawRoundRect(
            RectF(targetX - eyeGap - radiusX * 1.35f, targetY - radiusY * 1.5f,
                targetX + eyeGap + radiusX * 1.35f, targetY + radiusY * 1.5f),
            dp(17f), dp(17f), fill
        )
        for (eyeCenterX in floatArrayOf(targetX - eyeGap, targetX + eyeGap)) {
            fill.color = Color.argb(42, 0, 240, 245)
            canvas.drawRoundRect(
                RectF(eyeCenterX - radiusX * 1.32f, targetY - radiusY * 1.32f,
                    eyeCenterX + radiusX * 1.32f, targetY + radiusY * 1.32f),
                dp(11f), dp(11f), fill
            )
            fill.color = eyeColor
            canvas.drawRoundRect(
                RectF(eyeCenterX - radiusX, targetY - radiusY,
                    eyeCenterX + radiusX, targetY + radiusY),
                dp(7f), dp(7f), fill
            )
        }
        if (flash && lastHit) {
            stroke.strokeWidth = dp(2f)
            stroke.color = Color.argb(220, 115, 255, 203)
            canvas.drawRoundRect(
                RectF(targetX - eyeGap - radiusX * 1.45f, targetY - radiusY * 1.55f,
                    targetX + eyeGap + radiusX * 1.45f, targetY + radiusY * 1.55f),
                dp(12f), dp(12f), stroke
            )
            textPaint.color = Color.rgb(115, 255, 203)
            textPaint.textSize = dp(11f)
            canvas.drawText("HIT!", targetX, targetY - radiusY * 2.0f, textPaint)
        } else if (flash && !lastHit) {
            textPaint.color = Color.rgb(255, 190, 106)
            textPaint.textSize = dp(11f)
            canvas.drawText("DODGED!", targetX, targetY - radiusY * 2.0f, textPaint)
        }
    }

    private fun drawShot(canvas: Canvas, shot: Shot, now: Long) {
        val age = now - shot.startedAt
        if (age < PROJECTILE_MS) {
            val raw = (age.toFloat() / PROJECTILE_MS).coerceIn(0f, 1f)
            val t = raw * raw * (3f - 2f * raw)
            val x = shot.startX + (shot.aimX - shot.startX) * t
            val y = shot.startY + (shot.aimY - shot.startY) * t
            val tailT = max(0f, t - 0.16f)
            val tailX = shot.startX + (shot.aimX - shot.startX) * tailT
            val tailY = shot.startY + (shot.aimY - shot.startY) * tailT
            stroke.strokeWidth = dp(13f)
            stroke.color = Color.argb(28, 255, 101, 36)
            canvas.drawLine(tailX, tailY, x, y, stroke)
            stroke.strokeWidth = dp(6f)
            stroke.color = Color.argb(150, 255, 116, 40)
            canvas.drawLine(tailX, tailY, x, y, stroke)
            stroke.strokeWidth = dp(2f)
            stroke.color = Color.rgb(255, 239, 174)
            canvas.drawLine(tailX, tailY, x, y, stroke)
            fill.color = Color.rgb(255, 125, 53)
            canvas.drawCircle(x, y, dp(7f), fill)
            fill.color = Color.rgb(255, 227, 145)
            canvas.drawCircle(x, y, dp(2.7f), fill)
        } else {
            val p = ((age - PROJECTILE_MS).toFloat() / SPLASH_MS).coerceIn(0f, 1f)
            val alpha = (210f * (1f - p)).toInt().coerceIn(0, 210)
            val radius = dp(8f) + dp(28f) * p
            stroke.strokeWidth = dp(2.5f) * (1f - p * 0.45f)
            stroke.color = Color.argb(alpha, 255, if (shot.hit) 210 else 111, if (shot.hit) 87 else 46)
            canvas.drawCircle(shot.aimX, shot.aimY, radius, stroke)
            for (i in 0 until 8) {
                val a = i * (Math.PI / 4.0)
                val travel = radius * 0.72f
                val sx = shot.aimX + cos(a).toFloat() * travel
                val sy = shot.aimY + sin(a).toFloat() * travel
                fill.color = Color.argb(alpha, 255, if (shot.hit) 186 else 118, 67)
                canvas.drawCircle(sx, sy, dp(2.2f) * (1f - p), fill)
            }
        }
    }

    private fun drawLauncher(canvas: Canvas, now: Long) {
        val cx = width * 0.5f
        val baseY = height * 0.91f
        val body = RectF(cx - dp(42f), baseY - dp(17f), cx + dp(42f), baseY + dp(13f))
        fill.color = Color.rgb(7, 18, 25)
        canvas.drawRoundRect(body, dp(12f), dp(12f), fill)
        stroke.strokeWidth = dp(1.6f)
        stroke.color = Color.argb(180, 15, 221, 233)
        canvas.drawRoundRect(body, dp(12f), dp(12f), stroke)
        fill.color = Color.rgb(0, 218, 230)
        val eyeW = dp(19f)
        val eyeH = dp(11f)
        canvas.drawRoundRect(RectF(cx - dp(25f), baseY - dp(8f), cx - dp(25f) + eyeW, baseY - dp(8f) + eyeH), dp(4f), dp(4f), fill)
        canvas.drawRoundRect(RectF(cx + dp(6f), baseY - dp(8f), cx + dp(6f) + eyeW, baseY - dp(8f) + eyeH), dp(4f), dp(4f), fill)
        if (now - muzzleFlashAt in 0L..100L) {
            val fade = 1f - (now - muzzleFlashAt).toFloat() / 100f
            fill.color = Color.argb((190f * fade).toInt(), 255, 167, 79)
            canvas.drawCircle(cx, baseY - dp(21f), dp(17f) * fade, fill)
            fill.color = Color.argb((240f * fade).toInt(), 255, 240, 164)
            canvas.drawCircle(cx, baseY - dp(21f), dp(6f) * fade, fill)
        }
    }

    companion object {
        private const val PROJECTILE_MS = 210L
        private const val SPLASH_MS = 360L
    }
}
