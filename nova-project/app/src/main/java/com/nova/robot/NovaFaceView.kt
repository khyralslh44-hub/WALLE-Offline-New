package com.nova.robot

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Lightweight, offline vector face. Expressions are intentionally distinct at a glance.
 * Blinking is occasional; the face does not react repeatedly to touch or run a busy animation loop.
 */
class NovaFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mood = NovaMood.CALM
    private var blinking = false
    private var gazeX = 0f
    private var gazeY = 0f
    private var gazeAnimatorX: ValueAnimator? = null
    private var gazeAnimatorY: ValueAnimator? = null

    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            blinking = true
            invalidate()
            postDelayed({
                if (!isAttachedToWindow) return@postDelayed
                blinking = false
                invalidate()
                postDelayed(this, 4_200L)
            }, 135L)
        }
    }

    fun setMood(next: NovaMood) {
        if (mood == next) return
        mood = next
        invalidate()
    }

    /** Smoothly glance toward the detected face position in the current camera frame. */
    fun setGaze(normalizedX: Float, normalizedY: Float) {
        val targetX = ((normalizedX.coerceIn(0f, 1f) - 0.5f) * 0.60f).coerceIn(-0.30f, 0.30f)
        val targetY = ((normalizedY.coerceIn(0f, 1f) - 0.5f) * 0.48f).coerceIn(-0.24f, 0.24f)
        if (kotlin.math.abs(targetX - gazeX) < 0.025f && kotlin.math.abs(targetY - gazeY) < 0.025f) return
        gazeAnimatorX?.cancel()
        gazeAnimatorY?.cancel()
        gazeAnimatorX = ValueAnimator.ofFloat(gazeX, targetX).apply {
            duration = 230L
            addUpdateListener {
                gazeX = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        gazeAnimatorY = ValueAnimator.ofFloat(gazeY, targetY).apply {
            duration = 230L
            addUpdateListener {
                gazeY = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(blinkRunnable)
        postDelayed(blinkRunnable, 2_400L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blinkRunnable)
        gazeAnimatorX?.cancel()
        gazeAnimatorY?.cancel()
        gazeAnimatorX = null
        gazeAnimatorY = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(0xFF040815.toInt())
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            w * 0.5f, h * 0.43f, min(w, h) * 0.72f,
            intArrayOf(0xFF122B54.toInt(), 0xFF08142B.toInt(), 0xFF040815.toInt()),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.color = 0x334D9CFF
        canvas.drawCircle(w * 0.5f, h * 0.43f, min(w, h) * 0.34f, paint)
        paint.color = 0x225BE9FF
        canvas.drawCircle(w * 0.5f, h * 0.43f, min(w, h) * 0.27f, paint)

        val eyeY = h * 0.43f
        val eyeW = min(w * 0.205f, h * 0.22f)
        val eyeH = eyeW * 0.98f
        val leftX = w * 0.34f
        val rightX = w * 0.66f
        val lookX = when (mood) {
            NovaMood.THINKING -> eyeW * 0.20f
            NovaMood.CURIOUS, NovaMood.PLAYFUL, NovaMood.WARM -> eyeW * 0.10f
            NovaMood.ALERT -> eyeW * 0.04f
            NovaMood.CAUTIOUS -> -eyeW * 0.08f
            else -> 0f
        }
        val lookY = if (mood == NovaMood.THINKING) -eyeH * 0.12f else 0f

        drawEye(canvas, leftX, eyeY, eyeW, eyeH, lookX + gazeX * eyeW, lookY + gazeY * eyeH, false, true)
        drawEye(canvas, rightX, eyeY, eyeW, eyeH, lookX + gazeX * eyeW, lookY + gazeY * eyeH, mood == NovaMood.PLAYFUL, false)

        // Small decorative lights stay dim so the eyes remain the clear focal point.
        drawOrbit(canvas, w * 0.17f, h * 0.28f, min(w, h) * 0.008f)
        drawOrbit(canvas, w * 0.81f, h * 0.25f, min(w, h) * 0.006f)
        drawOrbit(canvas, w * 0.77f, h * 0.62f, min(w, h) * 0.005f)
    }

    private fun drawEye(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        eyeW: Float,
        eyeH: Float,
        lookX: Float,
        lookY: Float,
        wink: Boolean,
        isLeft: Boolean
    ) {
        val profile = NovaEyeExpressionPolicy.profile(mood, isLeft)
        val halfW = eyeW * profile.widthFactor
        val halfH = eyeH * profile.heightFactor
        val closed = blinking || wink
        canvas.save()
        canvas.rotate(profile.tiltDegrees, centerX, centerY)

        if (closed) {
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (eyeW * 0.11f).coerceAtLeast(4f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = if (mood == NovaMood.FRUSTRATED) 0xFFFFB5C5.toInt() else 0xFF79EFFF.toInt()
            val path = Path().apply {
                moveTo(centerX - halfW, centerY)
                quadTo(centerX, centerY + halfH * 0.62f, centerX + halfW, centerY)
            }
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            canvas.restore()
            return
        }

        val bounds = RectF(
            centerX - halfW, centerY - halfH,
            centerX + halfW, centerY + halfH
        )
        val eyeColors = paletteColors(profile.palette)
        if (profile.heartShaped) {
            val heart = Path().apply {
                moveTo(centerX, centerY + halfH)
                cubicTo(centerX - halfW * 1.42f, centerY + halfH * 0.18f,
                    centerX - halfW * 1.12f, centerY - halfH * 0.80f,
                    centerX - halfW * 0.43f, centerY - halfH * 0.55f)
                cubicTo(centerX - halfW * 0.12f, centerY - halfH * 0.43f,
                    centerX, centerY - halfH * 0.08f, centerX, centerY)
                cubicTo(centerX, centerY - halfH * 0.08f,
                    centerX + halfW * 0.12f, centerY - halfH * 0.43f,
                    centerX + halfW * 0.43f, centerY - halfH * 0.55f)
                cubicTo(centerX + halfW * 1.12f, centerY - halfH * 0.80f,
                    centerX + halfW * 1.42f, centerY + halfH * 0.18f,
                    centerX, centerY + halfH)
                close()
            }
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                eyeColors, null, Shader.TileMode.CLAMP)
            canvas.drawPath(heart, paint)
            paint.shader = null
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(centerX - halfW * 0.38f, centerY - halfH * 0.32f,
                (eyeW * 0.045f).coerceAtLeast(2f), paint)
            canvas.restore()
            return
        }
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            bounds.left, bounds.top, bounds.right, bounds.bottom,
            eyeColors, null, Shader.TileMode.CLAMP
        )
        canvas.drawOval(bounds, paint)
        paint.shader = null

        // An upper shine glides with the gaze; luminous eyes stay free from dark pupils.
        paint.color = 0x66E9FFFF
        canvas.drawOval(
            centerX - halfW * 0.66f + lookX * 0.18f,
            centerY - halfH * 0.72f + lookY * 0.18f,
            centerX + halfW * 0.48f + lookX * 0.18f,
            centerY + halfH * 0.06f + lookY * 0.18f, paint
        )
        paint.color = 0xFFFFFFFF.toInt()
        val glintR = (eyeW * 0.045f).coerceAtLeast(2f)
        canvas.drawCircle(
            centerX + lookX - halfW * 0.22f,
            centerY + lookY - halfH * 0.22f,
            glintR, paint
        )

        if (mood == NovaMood.SLEEPY || mood == NovaMood.TIRED) {
            paint.color = 0xFF122445.toInt()
            canvas.drawRect(bounds.left - 1f, bounds.top - 1f, bounds.right + 1f,
                centerY - halfH * 0.02f, paint)
            paint.color = 0xFF72E9FF.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (eyeW * 0.06f).coerceAtLeast(2f)
            canvas.drawLine(bounds.left, centerY - halfH * 0.02f,
                bounds.right, centerY - halfH * 0.02f, paint)
            paint.style = Paint.Style.FILL
        } else if (profile.brow) {
            // Clear inward-leaning brows make frustration readable, but not threatening.
            paint.color = if (mood == NovaMood.CONFUSED) 0xFF9BE9FF.toInt() else 0xFFFFB5C5.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = (eyeW * 0.075f).coerceAtLeast(3f)
            val browY = bounds.top - halfH * 0.13f
            if (isLeft) {
                canvas.drawLine(bounds.left, browY - halfH * 0.12f, centerX + halfW * 0.25f, browY + halfH * 0.10f, paint)
            } else {
                val endY = if (mood == NovaMood.CONFUSED) browY - halfH * 0.26f else browY - halfH * 0.12f
                canvas.drawLine(centerX - halfW * 0.25f, browY + halfH * 0.10f, bounds.right, endY, paint)
            }
            paint.style = Paint.Style.FILL
        } else if (profile.smiling) {
            // Gentle lower crescent: happiness stays open-eyed and friendly, not permanently shut.
            paint.color = 0xFFB9FBFF.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = (eyeW * 0.055f).coerceAtLeast(2f)
            val smile = Path().apply {
                moveTo(bounds.left + halfW * 0.20f, bounds.bottom - halfH * 0.16f)
                quadTo(centerX, bounds.bottom + halfH * 0.06f, bounds.right - halfW * 0.20f, bounds.bottom - halfH * 0.16f)
            }
            canvas.drawPath(smile, paint)
            paint.style = Paint.Style.FILL
        }

        if (profile.tear) {
            val tear = Path().apply {
                moveTo(centerX + halfW * 0.78f, centerY + halfH * 0.25f)
                cubicTo(centerX + halfW * 0.54f, centerY + halfH * 0.66f,
                    centerX + halfW * 0.66f, centerY + halfH * 0.96f,
                    centerX + halfW * 0.88f, centerY + halfH * 0.88f)
                cubicTo(centerX + halfW * 1.12f, centerY + halfH * 0.72f,
                    centerX + halfW * 1.02f, centerY + halfH * 0.48f,
                    centerX + halfW * 0.78f, centerY + halfH * 0.25f)
                close()
            }
            paint.color = 0xFF72DFFF.toInt()
            canvas.drawPath(tear, paint)
        }
        canvas.restore()
    }

    private fun paletteColors(palette: NovaEyePalette): IntArray = when (palette) {
        NovaEyePalette.AQUA ->
            intArrayOf(0xFFA7FFE8.toInt(), 0xFF36DCCB.toInt(), 0xFF3189F0.toInt())
        NovaEyePalette.MINT ->
            intArrayOf(0xFFB7FFF0.toInt(), 0xFF39E8C5.toInt(), 0xFF247FE0.toInt())
        NovaEyePalette.ROSE ->
            intArrayOf(0xFFFFD0DF.toInt(), 0xFFFF789A.toInt(), 0xFFAD70FF.toInt())
        NovaEyePalette.ICE ->
            intArrayOf(0xFFD7FFFF.toInt(), 0xFF56DFFF.toInt(), 0xFF377CFF.toInt())
        NovaEyePalette.REST ->
            intArrayOf(0xFF89D8D0.toInt(), 0xFF309FBB.toInt(), 0xFF2852A8.toInt())
        NovaEyePalette.VIOLET ->
            intArrayOf(0xFFD7D9FF.toInt(), 0xFF7D9EFF.toInt(), 0xFF6C66D7.toInt())
        NovaEyePalette.LIME ->
            intArrayOf(0xFFE2FFB4.toInt(), 0xFF8CEB9E.toInt(), 0xFF43B6A3.toInt())
    }

    private fun drawOrbit(canvas: Canvas, x: Float, y: Float, radius: Float) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = 0xFF6BCFFF.toInt()
        canvas.drawCircle(x, y, radius * 1.7f, paint)
        paint.color = 0x449B8AFF
        canvas.drawCircle(x, y, radius * 3.4f, paint)
    }
}
