package com.nova.robot

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Expressive, low-cost vector eyes. It redraws on state changes and blinks only occasionally. */
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

        // Two orbit lights and a subtle centre glow; fixed geometry avoids per-frame simulation.
        paint.color = 0x444D9CFF
        canvas.drawCircle(w * 0.5f, h * 0.43f, min(w, h) * 0.34f, paint)
        paint.color = 0x335BE9FF
        canvas.drawCircle(w * 0.5f, h * 0.43f, min(w, h) * 0.27f, paint)

        val eyeY = h * 0.43f
        val eyeW = min(w * 0.205f, h * 0.22f)
        val eyeH = eyeW * 0.98f
        val leftX = w * 0.34f
        val rightX = w * 0.66f
        val lookX = when (mood) {
            NovaMood.THINKING -> eyeW * 0.18f
            NovaMood.CURIOUS, NovaMood.PLAYFUL -> eyeW * 0.09f
            NovaMood.SURPRISED -> 0f
            else -> 0f
        }
        val lookY = if (mood == NovaMood.THINKING) -eyeH * 0.10f else 0f
        drawEye(canvas, leftX, eyeY, eyeW, eyeH, lookX, lookY, false)
        drawEye(canvas, rightX, eyeY, eyeW, eyeH, lookX, lookY, mood == NovaMood.PLAYFUL)
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
        wink: Boolean
    ) {
        val halfW = eyeW * if (mood == NovaMood.SURPRISED) 0.56f else 0.5f
        val halfH = eyeH * when (mood) {
            NovaMood.SURPRISED -> 0.62f
            NovaMood.SLEEPY -> 0.39f
            NovaMood.HAPPY -> 0.30f
            else -> 0.5f
        }
        val closed = blinking || mood == NovaMood.HAPPY || wink
        if (closed) {
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (eyeW * 0.13f).coerceAtLeast(4f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = 0xFF79EFFF.toInt()
            val path = Path().apply {
                moveTo(centerX - halfW, centerY)
                quadTo(centerX, centerY + halfH * 0.75f, centerX + halfW, centerY)
            }
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            return
        }

        val bounds = android.graphics.RectF(
            centerX - halfW, centerY - halfH, centerX + halfW, centerY + halfH
        )
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            bounds.left, bounds.top, bounds.right, bounds.bottom,
            intArrayOf(0xFF59DFFF.toInt(), 0xFF42A6FF.toInt(), 0xFF927BFF.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawOval(bounds, paint)
        paint.shader = null
        paint.color = 0xFFB9FBFF.toInt()
        canvas.drawOval(
            centerX - halfW * 0.70f, centerY - halfH * 0.63f,
            centerX + halfW * 0.70f, centerY + halfH * 0.63f, paint
        )
        paint.color = 0xFF08142B.toInt()
        val pupilR = eyeW * if (mood == NovaMood.SURPRISED) 0.16f else 0.135f
        canvas.drawCircle(centerX + lookX, centerY + lookY, pupilR, paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(centerX + lookX - pupilR * 0.28f, centerY + lookY - pupilR * 0.32f, pupilR * 0.34f, paint)

        if (mood == NovaMood.SLEEPY) {
            paint.color = 0xFF122445.toInt()
            canvas.drawRect(bounds.left - 1f, bounds.top - 1f, bounds.right + 1f, centerY - halfH * 0.15f, paint)
            paint.color = 0xFF72E9FF.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (eyeW * 0.06f).coerceAtLeast(2f)
            canvas.drawLine(bounds.left, centerY - halfH * 0.13f, bounds.right, centerY - halfH * 0.13f, paint)
            paint.style = Paint.Style.FILL
        } else if (mood == NovaMood.FRUSTRATED) {
            paint.color = 0xFFCFB5FF.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (eyeW * 0.055f).coerceAtLeast(2f)
            canvas.drawLine(bounds.left, bounds.top, centerX, bounds.top + halfH * 0.16f, paint)
            canvas.drawLine(centerX, bounds.top + halfH * 0.16f, bounds.right, bounds.top, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun drawOrbit(canvas: Canvas, x: Float, y: Float, radius: Float) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = 0xFF6BCFFF.toInt()
        canvas.drawCircle(x, y, radius * 2.3f, paint)
        paint.color = 0x889B8AFF.toInt()
        canvas.drawCircle(x, y, radius * 4.8f, paint)
    }
}
