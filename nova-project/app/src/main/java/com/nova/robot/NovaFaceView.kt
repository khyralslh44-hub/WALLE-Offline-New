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
import kotlin.math.max
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
    private var dizzyAngle = 0f
    private var dizzyAnimator: ValueAnimator? = null
    // Bounded idle glances add life without random mood changes or a constant animation loop.
    private var idleGlanceIndex = 0
    private var idleGlanceAnimator: ValueAnimator? = null
    private val idleGlanceRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            val idleMood = mood == NovaMood.CALM || mood == NovaMood.HAPPY ||
                mood == NovaMood.WARM || mood == NovaMood.CURIOUS
            if (idleMood && idleGlanceAnimator?.isRunning != true) {
                val targets = arrayOf(
                    Pair(-0.10f, 0.00f), Pair(0.08f, -0.04f),
                    Pair(0.00f, 0.03f), Pair(0.11f, 0.00f), Pair(0.00f, 0.00f)
                )
                val target = targets[idleGlanceIndex % targets.size]
                idleGlanceIndex++
                animateIdleGaze(target.first, target.second)
            }
            postDelayed(this, 5_600L)
        }
    }

    private fun animateIdleGaze(targetX: Float, targetY: Float) {
        idleGlanceAnimator?.cancel()
        val fromX = gazeX
        val fromY = gazeY
        idleGlanceAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420L
            addUpdateListener {
                val t = it.animatedValue as Float
                gazeX = fromX + (targetX - fromX) * t
                gazeY = fromY + (targetY - fromY) * t
                invalidate()
            }
            start()
        }
        postDelayed({
            if (!isAttachedToWindow || mood !in listOf(
                    NovaMood.CALM, NovaMood.HAPPY, NovaMood.WARM, NovaMood.CURIOUS
                )) return@postDelayed
            idleGlanceAnimator?.cancel()
            idleGlanceAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 520L
                addUpdateListener {
                    val t = it.animatedValue as Float
                    gazeX = targetX * (1f - t)
                    gazeY = targetY * (1f - t)
                    invalidate()
                }
                start()
            }
        }, 720L)
    }

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
        if (next == NovaMood.DIZZY) startDizzyAnimation() else stopDizzyAnimation()
        invalidate()
    }

    /** A restrained wobble makes dizziness readable without shaking the whole screen. */
    private fun startDizzyAnimation() {
        if (dizzyAnimator?.isRunning == true) return
        dizzyAnimator = ValueAnimator.ofFloat(-6f, 6f).apply {
            duration = 720L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                dizzyAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopDizzyAnimation() {
        dizzyAnimator?.cancel()
        dizzyAnimator = null
        dizzyAngle = 0f
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
        removeCallbacks(idleGlanceRunnable)
        postDelayed(blinkRunnable, 2_400L)
        postDelayed(idleGlanceRunnable, 3_100L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blinkRunnable)
        removeCallbacks(idleGlanceRunnable)
        idleGlanceAnimator?.cancel()
        idleGlanceAnimator = null
        gazeAnimatorX?.cancel()
        gazeAnimatorY?.cancel()
        gazeAnimatorX = null
        gazeAnimatorY = null
        stopDizzyAnimation()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Vector-inspired full-screen face: pure black background, only two luminous eyes.
        canvas.drawColor(android.graphics.Color.BLACK)
        val eyeY = h * 0.47f
        val eyeW = min(w * 0.285f, h * 0.245f)
        val eyeH = eyeW * 0.92f
        val leftX = w * 0.325f
        val rightX = w * 0.675f
        val lookX = when (mood) {
            NovaMood.THINKING -> eyeW * 0.18f
            NovaMood.EXCITED, NovaMood.CURIOUS, NovaMood.PLAYFUL, NovaMood.WARM -> eyeW * 0.08f
            NovaMood.ANGRY, NovaMood.DETERMINED -> -eyeW * 0.08f
            NovaMood.PROUD -> eyeW * 0.025f
            NovaMood.ALERT -> eyeW * 0.035f
            NovaMood.JEALOUS -> -eyeW * 0.13f
            NovaMood.DIZZY -> eyeW * 0.03f
            NovaMood.CAUTIOUS -> -eyeW * 0.06f
            else -> 0f
        }
        val lookY = if (mood == NovaMood.THINKING) -eyeH * 0.10f else 0f

        canvas.save()
        if (mood == NovaMood.DIZZY) canvas.rotate(dizzyAngle, w * 0.5f, eyeY)
        drawEye(canvas, leftX, eyeY, eyeW, eyeH, lookX + gazeX * eyeW, lookY + gazeY * eyeH, false, true)
        drawEye(canvas, rightX, eyeY, eyeW, eyeH, lookX + gazeX * eyeW, lookY + gazeY * eyeH, mood == NovaMood.PLAYFUL, false)
        canvas.restore()
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
            paint.color = paletteColors(profile.palette)[1]
            val path = Path().apply {
                moveTo(centerX - halfW, centerY)
                quadTo(centerX, centerY + halfH * 0.62f, centerX + halfW, centerY)
            }
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            canvas.restore()
            return
        }

        val bounds = RectF(centerX - halfW, centerY - halfH, centerX + halfW, centerY + halfH)
        val eyeColors = paletteColors(profile.palette)
        val glowColor = (eyeColors[1] and 0x00FFFFFF) or 0x55000000
        paint.shader = RadialGradient(
            centerX, centerY, max(halfW, halfH) * 1.95f,
            intArrayOf(glowColor, 0x003DFFD0),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawOval(
            centerX - halfW * 1.75f, centerY - halfH * 1.75f,
            centerX + halfW * 1.75f, centerY + halfH * 1.75f, paint
        )
        paint.shader = null

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
        } else {
            // Outer bright ring and rich green/cyan iris create the glossy friendly lens.
            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(
                centerX - halfW * 0.22f + lookX * 0.10f,
                centerY - halfH * 0.27f + lookY * 0.10f,
                max(halfW, halfH) * 1.42f,
                intArrayOf(0xFFF1FFFF.toInt(), eyeColors[0], eyeColors[1], eyeColors[2]),
                floatArrayOf(0f, 0.27f, 0.67f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawOval(bounds, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (eyeW * 0.044f).coerceAtLeast(2.5f)
            paint.color = eyeColors[2]
            canvas.drawOval(bounds, paint)

            // A subtle dark pupil is surrounded by visible luminous green or blue iris.
            paint.style = Paint.Style.FILL
            val pupilW = halfW * if (mood == NovaMood.SURPRISED || mood == NovaMood.CURIOUS) 0.39f else 0.45f
            val pupilH = halfH * 0.49f
            val pupilX = centerX + lookX * 0.38f
            val pupilY = centerY + lookY * 0.38f
            val pupilBounds = RectF(pupilX - pupilW, pupilY - pupilH, pupilX + pupilW, pupilY + pupilH)
            paint.shader = RadialGradient(
                pupilX - pupilW * 0.24f, pupilY - pupilH * 0.30f, max(pupilW, pupilH) * 1.45f,
                intArrayOf(0xFF123344.toInt(), 0xFF020714.toInt()),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawOval(pupilBounds, paint)
            paint.shader = null

            // Tiny radial iris glints make the eyes read as glass, not flat LEDs.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (eyeW * 0.022f).coerceAtLeast(1.5f)
            paint.color = 0xBDEBFFFF.toInt()
            canvas.drawOval(
                RectF(centerX - halfW * 0.78f, centerY - halfH * 0.78f,
                    centerX + halfW * 0.78f, centerY + halfH * 0.78f), paint
            )
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt()
            val glintR = (eyeW * 0.055f).coerceAtLeast(2.5f)
            canvas.drawCircle(pupilX - pupilW * 0.40f, pupilY - pupilH * 0.43f, glintR, paint)
            paint.color = 0xBBDAFFFF.toInt()
            canvas.drawCircle(pupilX + pupilW * 0.34f, pupilY + pupilH * 0.35f, glintR * 0.38f, paint)
        }

        // Upper lids distinguish sleepy and tired moods while retaining the recognizable eye design.
        if (mood == NovaMood.SLEEPY || mood == NovaMood.TIRED) {
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = 0xE9020A17.toInt()
            canvas.drawRoundRect(bounds.left - 1f, bounds.top - 1f, bounds.right + 1f,
                centerY + halfH * 0.10f, halfH * 0.25f, halfH * 0.25f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (eyeW * 0.045f).coerceAtLeast(2f)
            paint.color = 0xFF72E9FF.toInt()
            canvas.drawLine(bounds.left, centerY + halfH * 0.10f, bounds.right,
                centerY + halfH * 0.10f, paint)
            paint.style = Paint.Style.FILL
        }

        if (profile.brow) {
            paint.shader = null
            paint.color = when (mood) {
                NovaMood.CONFUSED -> 0xFF9BE9FF.toInt()
                NovaMood.JEALOUS, NovaMood.DETERMINED -> 0xFFB7F46A.toInt()
                NovaMood.ANGRY -> 0xFFFF677C.toInt()
                else -> 0xFFFFB5C5.toInt()
            }
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = (eyeW * 0.075f).coerceAtLeast(3f)
            val browY = bounds.top - halfH * 0.20f
            if (isLeft) {
                canvas.drawLine(bounds.left - halfW * 0.03f, browY - halfH * 0.10f,
                    centerX + halfW * 0.22f, browY + halfH * 0.13f, paint)
            } else {
                val endY = if (mood == NovaMood.CONFUSED) browY - halfH * 0.27f else browY - halfH * 0.10f
                canvas.drawLine(centerX - halfW * 0.22f, browY + halfH * 0.13f,
                    bounds.right + halfW * 0.03f, endY, paint)
            }
            paint.style = Paint.Style.FILL
        }

        if (profile.tear) {
            val tear = Path().apply {
                moveTo(centerX + halfW * 0.76f, centerY + halfH * 0.28f)
                cubicTo(centerX + halfW * 0.52f, centerY + halfH * 0.67f,
                    centerX + halfW * 0.66f, centerY + halfH * 0.96f,
                    centerX + halfW * 0.88f, centerY + halfH * 0.88f)
                cubicTo(centerX + halfW * 1.10f, centerY + halfH * 0.72f,
                    centerX + halfW * 1.00f, centerY + halfH * 0.48f,
                    centerX + halfW * 0.76f, centerY + halfH * 0.28f)
                close()
            }
            paint.shader = null
            paint.color = 0xFF72DFFF.toInt()
            canvas.drawPath(tear, paint)
        }
        canvas.restore()
    }

    /** The mouth is separate from the eyes so each expression reads clearly at phone size. */
    private fun drawMouth(canvas: Canvas, w: Float, h: Float, eyeW: Float) {
        val centerX = w * 0.5f
        val centerY = h * 0.514f
        val mouthW = eyeW * when (mood) {
            NovaMood.EXCITED, NovaMood.SURPRISED -> 0.24f
            NovaMood.ANGRY, NovaMood.DETERMINED, NovaMood.JEALOUS -> 0.19f
            NovaMood.SLEEPY, NovaMood.TIRED -> 0.17f
            else -> 0.20f
        }
        val mouthH = eyeW * 0.09f
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = (eyeW * 0.045f).coerceAtLeast(2.2f)
        paint.color = when (mood) {
            NovaMood.ANGRY -> 0xFFFF7A8E.toInt()
            NovaMood.JEALOUS, NovaMood.DETERMINED, NovaMood.PROUD -> 0xFFC9FF91.toInt()
            NovaMood.LOVE, NovaMood.EMBARRASSED -> 0xFFFF9DDA.toInt()
            else -> 0xFF7DF5FF.toInt()
        }

        when (mood) {
            NovaMood.SURPRISED, NovaMood.ALERT -> {
                paint.style = Paint.Style.FILL
                paint.shader = RadialGradient(centerX, centerY, mouthW * 1.7f,
                    intArrayOf(0xFF7DF5FF.toInt(), 0xFF258BCC.toInt()),
                    null, Shader.TileMode.CLAMP)
                canvas.drawOval(RectF(centerX - mouthW * 0.46f, centerY - mouthH * 1.35f,
                    centerX + mouthW * 0.46f, centerY + mouthH * 1.35f), paint)
                paint.shader = null
            }
            NovaMood.SAD, NovaMood.AFRAID, NovaMood.ANGRY, NovaMood.JEALOUS,
            NovaMood.FRUSTRATED, NovaMood.DETERMINED -> {
                val path = Path().apply {
                    moveTo(centerX - mouthW, centerY + mouthH * 0.55f)
                    quadTo(centerX, centerY - mouthH * 0.80f,
                        centerX + mouthW, centerY + mouthH * 0.55f)
                }
                canvas.drawPath(path, paint)
            }
            NovaMood.DIZZY, NovaMood.CONFUSED, NovaMood.DISGUSTED -> {
                val path = Path().apply {
                    moveTo(centerX - mouthW, centerY)
                    quadTo(centerX - mouthW * 0.55f, centerY - mouthH,
                        centerX, centerY)
                    quadTo(centerX + mouthW * 0.55f, centerY + mouthH,
                        centerX + mouthW, centerY)
                }
                canvas.drawPath(path, paint)
            }
            NovaMood.SLEEPY, NovaMood.TIRED, NovaMood.THINKING, NovaMood.CAUTIOUS -> {
                canvas.drawLine(centerX - mouthW * 0.65f, centerY, centerX + mouthW * 0.65f, centerY, paint)
            }
            else -> {
                val curve = when (mood) {
                    NovaMood.EXCITED, NovaMood.HAPPY -> mouthH * 2.4f
                    NovaMood.LOVE, NovaMood.ADMIRING, NovaMood.WARM, NovaMood.EMBARRASSED -> mouthH * 1.9f
                    NovaMood.PROUD -> mouthH * 1.25f
                    else -> mouthH * 1.6f
                }
                val path = Path().apply {
                    moveTo(centerX - mouthW, centerY - mouthH * 0.25f)
                    quadTo(centerX, centerY + curve, centerX + mouthW, centerY - mouthH * 0.25f)
                }
                canvas.drawPath(path, paint)
            }
        }
        paint.style = Paint.Style.FILL

        if (mood == NovaMood.EMBARRASSED) {
            paint.color = 0xFFFF86C7.toInt()
            canvas.drawCircle(w * 0.25f, centerY - eyeW * 0.18f, eyeW * 0.035f, paint)
            canvas.drawCircle(w * 0.75f, centerY - eyeW * 0.18f, eyeW * 0.035f, paint)
        }
    }

    private fun drawDizzyStars(canvas: Canvas, w: Float, eyeY: Float, eyeW: Float) {
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (eyeW * 0.035f).coerceAtLeast(2f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFFC9B8FF.toInt()
        val radius = eyeW * 0.09f
        val orbit = eyeW * 0.84f
        val leftX = w * 0.34f - orbit
        val rightX = w * 0.66f + orbit
        val y = eyeY - eyeW * 0.55f
        canvas.drawLine(leftX - radius, y, leftX + radius, y, paint)
        canvas.drawLine(leftX, y - radius, leftX, y + radius, paint)
        canvas.drawLine(rightX - radius, y, rightX + radius, y, paint)
        canvas.drawLine(rightX, y - radius, rightX, y + radius, paint)
        paint.style = Paint.Style.FILL
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
