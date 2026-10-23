package com.nova.robot

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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(blinkRunnable)
        postDelayed(blinkRunnable, 2_400L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blinkRunnable)
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

        drawEye(canvas, leftX, eyeY, eyeW, eyeH, lookX, lookY, false, true)
        drawEye(canvas, rightX, eyeY, eyeW, eyeH, lookX, lookY, mood == NovaMood.PLAYFUL, false)

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
        var widthFactor = 0.50f
        var heightFactor = 0.50f
        var tilt = 0f
        when (mood) {
            NovaMood.SURPRISED -> { widthFactor = 0.55f; heightFactor = 0.63f }
            NovaMood.SLEEPY -> { widthFactor = 0.52f; heightFactor = 0.31f }
            NovaMood.HAPPY, NovaMood.WARM -> { widthFactor = 0.49f; heightFactor = 0.43f }
            NovaMood.ALERT -> { widthFactor = 0.54f; heightFactor = 0.58f }
            NovaMood.CAUTIOUS -> { widthFactor = 0.47f; heightFactor = 0.40f; tilt = if (isLeft) -5f else 5f }
            NovaMood.TIRED -> { widthFactor = 0.51f; heightFactor = 0.28f }
            NovaMood.CURIOUS -> {
                heightFactor = if (isLeft) 0.58f else 0.48f
                widthFactor = if (isLeft) 0.48f else 0.51f
            }
            NovaMood.FRUSTRATED -> {
                heightFactor = 0.43f
                tilt = if (isLeft) -12f else 12f
            }
            else -> Unit
        }

        val halfW = eyeW * widthFactor
        val halfH = eyeH * heightFactor
        val closed = blinking || wink
        canvas.save()
        canvas.rotate(tilt, centerX, centerY)

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
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            bounds.left, bounds.top, bounds.right, bounds.bottom,
            intArrayOf(0xFF4ADFFF.toInt(), 0xFF329EFF.toInt(), 0xFF6F8FFF.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawOval(bounds, paint)
        paint.shader = null

        // Soft inner highlight keeps the eyes luminous without obscuring the pupils.
        paint.color = 0xFFB9FBFF.toInt()
        canvas.drawOval(
            centerX - halfW * 0.72f, centerY - halfH * 0.68f,
            centerX + halfW * 0.72f, centerY + halfH * 0.60f, paint
        )
        paint.color = 0xFF08142B.toInt()
        val pupilR = eyeW * if (mood == NovaMood.SURPRISED) 0.12f else 0.135f
        canvas.drawCircle(centerX + lookX, centerY + lookY, pupilR, paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(
            centerX + lookX - pupilR * 0.28f,
            centerY + lookY - pupilR * 0.32f,
            pupilR * 0.34f, paint
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
        } else if (mood == NovaMood.FRUSTRATED) {
            // Clear inward-leaning brows make frustration readable, but not threatening.
            paint.color = 0xFFFFB5C5.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = (eyeW * 0.075f).coerceAtLeast(3f)
            val browY = bounds.top - halfH * 0.13f
            if (isLeft) {
                canvas.drawLine(bounds.left, browY - halfH * 0.12f, centerX + halfW * 0.25f, browY + halfH * 0.10f, paint)
            } else {
                canvas.drawLine(centerX - halfW * 0.25f, browY + halfH * 0.10f, bounds.right, browY - halfH * 0.12f, paint)
            }
            paint.style = Paint.Style.FILL
        } else if (mood == NovaMood.HAPPY || mood == NovaMood.WARM) {
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
        canvas.restore()
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
