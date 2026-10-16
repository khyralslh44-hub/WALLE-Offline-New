package com.eve.robot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.min

private data class EyeContour(
    val upperPeak: Float,
    val upperShoulder: Float,
    val lowerPeak: Float,
    val lowerShoulder: Float,
    val cornerLift: Float
)

/** Draws two expressive cyan robot eyes only: no pupils, mouth, text, or visible control buttons. */
class RobotFaceView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val shape = Path()
    private var mood = EveMood.AWAKE
    private var silhouette = EyeSilhouette.ALMOND
    private var previousSilhouette = silhouette
    private var silhouetteTransitionStartedAt = 0L
    private val silhouetteTransitionDurationMs = 260L
    private var contour = contourFor(EyeSilhouette.ALMOND)
    private var targetContour = contour
    private var fromContour = contour
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
    init {
        isClickable = false
        contentDescription = "EVE_CYAN_EXPRESSION_FACE"
        setBackgroundColor(Color.rgb(2, 7, 10))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setMood(next: EveMood, seed: Long = SystemClock.uptimeMillis()) {
        applyProfile(EyeExpressionGenerator.generate(EveDecision(next, "mood expression"), seed))
    }

    /** Eye geometry is generated from the same affect snapshot used by the offline brain and voice. */
    fun setExpression(decision: EveDecision, seed: Long = SystemClock.uptimeMillis()) {
        applyProfile(EyeExpressionGenerator.generate(decision, seed))
    }

    private fun applyProfile(profile: EyeExpressionProfile) {
        val now = SystemClock.uptimeMillis()
        // If another event arrives mid-transition, first finish parameter interpolation at the current time.
        updateTransition(now)
        previousSilhouette = silhouette
        silhouette = profile.silhouette
        silhouetteTransitionStartedAt = now
        mood = profile.mood
        targetContour = contourFor(profile.silhouette)
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
        fromContour = contour
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
        contour = EyeContour(
            upperPeak = mix(fromContour.upperPeak, targetContour.upperPeak),
            upperShoulder = mix(fromContour.upperShoulder, targetContour.upperShoulder),
            lowerPeak = mix(fromContour.lowerPeak, targetContour.lowerPeak),
            lowerShoulder = mix(fromContour.lowerShoulder, targetContour.lowerShoulder),
            cornerLift = mix(fromContour.cornerLift, targetContour.cornerLift)
        )
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
        // Do not invent a new emotion/gaze every few seconds. Cozmo-style micro-animation
        // is driven by explicit interaction decisions; idle mode only blinks. This prevents
        // the face from changing when the camera has seen nothing new.
        val idleGaze = 0f
        val idleGazeY = 0f
        val breath = 1f
        // The reference sheet uses compact horizontal LED blocks (about 2:1), not square tiles.
        // Keep a dark bridge between the eyes and scale with the actual view size on phone screens.
        // Compact cyan LED blocks: the reference has two distinct rounded pixels with a
        // dark bridge between them. Keep the base glyph compact and let each expression alter
        // its lid/height rather than turning the whole eye into a skewed polygon.
        val w = min(width * 0.235f, height * 0.165f) * eyeWidth
        val h = min(height * 0.135f, w * 0.72f) * eyeHeight
        val gap = min(width * 0.205f, w * 0.62f) * spacing
        val cy = height * 0.50f
        val silhouetteRaw = if (silhouetteTransitionStartedAt == 0L) 1f else
            ((now - silhouetteTransitionStartedAt).toFloat() / silhouetteTransitionDurationMs).coerceIn(0f, 1f)
        val silhouetteBlend = silhouetteRaw * silhouetteRaw * (3f - 2f * silhouetteRaw)
        if (silhouetteRaw >= 1f) silhouetteTransitionStartedAt = 0L

        fun drawExpressionLayer(style: EyeSilhouette, alpha: Float) {
            if (alpha <= 0.005f) return
            drawEye(canvas, width * 0.5f - gap + (gazeX + idleGaze) * width,
                cy + (gazeY + idleGazeY + 0f) * height, w * (1f + asymmetry * 0.24f), h,
                opening * blink * breath * (1f + asymmetry * 0.30f), tilt * 0.18f, glow, squint, contour, -1, style, alpha)
            drawEye(canvas, width * 0.5f + gap + (gazeX + idleGaze + asymmetry * 0.025f) * width,
                cy + (gazeY + idleGazeY + 0f - asymmetry * 0.035f) * height, w * (1f - asymmetry * 0.24f), h,
                opening * blink * breath * (1f - asymmetry * 0.30f), -tilt * 0.18f, glow, squint, contour, 1, style, alpha)
        }

        if (silhouetteBlend < 1f) drawExpressionLayer(previousSilhouette, 1f - silhouetteBlend)
        drawExpressionLayer(silhouette, silhouetteBlend)
        if (animate) postInvalidateDelayed(32L)
    }

    private fun drawEye(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, openness: Float, angle: Float, intensity: Float, squinting: Float, contour: EyeContour, side: Int, eyeSilhouette: EyeSilhouette, opacity: Float) {
        val baseHalfWidth = w * 0.5f
        val baseHalfHeight = h * 0.5f * openness.coerceIn(0.06f, 1.18f)
        // The neutral Cozmo reference uses compact, almost-square LED pixels. Slits stay wide,
        // while surprise/scared poses grow taller. Do not use one rectangle size for every mood.
        val widthScale = when (eyeSilhouette) {
            EyeSilhouette.NEUTRAL, EyeSilhouette.ALMOND, EyeSilhouette.SOFT -> 0.98f
            EyeSilhouette.ROUND_SURPRISE, EyeSilhouette.SCARED -> 0.78f
            EyeSilhouette.AWE -> 0.84f
            EyeSilhouette.SKEPTIC, EyeSilhouette.SUSPICIOUS -> 0.92f
            else -> 1f
        }
        val heightScale = when (eyeSilhouette) {
            // In the supplied Cozmo sheet, the normal eyes are close to square rounded pixels,
            // not the flatter bars used for happy/blink expressions.
            EyeSilhouette.NEUTRAL, EyeSilhouette.ALMOND, EyeSilhouette.SOFT -> 1.00f
            EyeSilhouette.ROUND_SURPRISE -> 1.24f
            EyeSilhouette.SCARED -> 1.34f
            EyeSilhouette.AWE -> 1.18f
            else -> 1f
        }
        val hw = baseHalfWidth * widthScale
        val hh = baseHalfHeight * heightScale
        val curve = squinting.coerceIn(0.45f, 1.22f)
        // Contour vertices are interpolated with the other facial parameters to prevent snapping.
        val upperPeak = contour.upperPeak * curve
        val upperShoulder = contour.upperShoulder * curve
        val lowerPeak = contour.lowerPeak
        val lowerShoulder = contour.lowerShoulder
        val cornerLift = contour.cornerLift * side * hh
        shape.reset()
        // Shape-specific vertical proportions are essential: surprise/scared eyes become taller,
        // neutral remains a rounded horizontal pixel, while squints and lids get thinner.
        val eyeScale = when (eyeSilhouette) {
            EyeSilhouette.ROUND_SURPRISE -> 1.72f
            EyeSilhouette.SCARED -> 1.88f
            EyeSilhouette.AWE -> 1.55f
            EyeSilhouette.SAD_DOWN, EyeSilhouette.SAD_UP, EyeSilhouette.DROOPY -> 1.24f
            EyeSilhouette.WORRIED -> 1.22f
            EyeSilhouette.FOCUSED -> 0.94f
            EyeSilhouette.ANNOYED, EyeSilhouette.SHARP, EyeSilhouette.FURIOUS -> 0.98f
            EyeSilhouette.PLAYFUL, EyeSilhouette.SKEPTIC, EyeSilhouette.SUSPICIOUS -> 0.96f
            EyeSilhouette.SQUINT, EyeSilhouette.BORED, EyeSilhouette.UNIMPRESSED -> 0.72f
            else -> 1f
        }
        val glyphHh = hh * when (eyeSilhouette) {
            EyeSilhouette.BLINK_HIGH -> 0.15f
            EyeSilhouette.BLINK_LOW -> 0.13f
            EyeSilhouette.BORED -> 0.32f
            EyeSilhouette.UNIMPRESSED -> 0.28f
            EyeSilhouette.SMILE -> 0.72f
            EyeSilhouette.GLEE -> 0.42f
            else -> eyeScale
        }
        val centerY = cy + when (eyeSilhouette) {
            EyeSilhouette.BLINK_HIGH -> -hh * 0.50f
            EyeSilhouette.BLINK_LOW, EyeSilhouette.SAD_DOWN -> hh * 0.30f
            EyeSilhouette.BORED, EyeSilhouette.UNIMPRESSED -> hh * 0.12f
            else -> 0f
        }
        val eyeRect = RectF(cx - hw, centerY - glyphHh, cx + hw, centerY + glyphHh)
        val roundRadius = min(hw, glyphHh).coerceAtLeast(1f)
        fun rounded(radius: Float) {
            // Thin blink/happy glyphs can be under 2px tall on scaled emulator canvases.
            // Keep the clamp range valid even when the eye half-height is below 1px.
            val maxRadius = min(hw, glyphHh).coerceAtLeast(0.5f)
            val r = radius.coerceIn(0.25f, maxRadius)
            shape.addRoundRect(
                eyeRect,
                floatArrayOf(r, r, r, r, r, r, r, r),
                Path.Direction.CW
            )
        }
        // Reference-faithful LED glyph set: every pose is built from compact cyan pixels.
        // Expression is created by changing lids, height, width, gaze and corner shape—not by
        // drawing a different cartoon face.
        when (eyeSilhouette) {
            EyeSilhouette.NEUTRAL, EyeSilhouette.ALMOND, EyeSilhouette.SOFT ->
                rounded(min(hw * 0.34f, glyphHh * 0.38f))

            EyeSilhouette.ROUND_SURPRISE, EyeSilhouette.SCARED, EyeSilhouette.AWE ->
                rounded(min(hw * 0.40f, glyphHh * 0.34f))

            EyeSilhouette.BLINK_HIGH, EyeSilhouette.BLINK_LOW,
            EyeSilhouette.SMILE, EyeSilhouette.GLEE,
            EyeSilhouette.BORED, EyeSilhouette.UNIMPRESSED,
            EyeSilhouette.SQUINT -> {
                val slit = when (eyeSilhouette) {
                    EyeSilhouette.BLINK_HIGH -> 0.22f
                    EyeSilhouette.BLINK_LOW -> 0.20f
                    EyeSilhouette.SMILE -> 0.30f
                    EyeSilhouette.GLEE -> 0.25f
                    EyeSilhouette.BORED -> 0.38f
                    EyeSilhouette.UNIMPRESSED -> 0.30f
                    else -> 0.34f
                }
                val r = RectF(cx - hw, centerY - glyphHh * slit, cx + hw, centerY + glyphHh * slit)
                shape.addRoundRect(r, glyphHh * slit, glyphHh * slit, Path.Direction.CW)
            }

            EyeSilhouette.SAD_DOWN -> {
                // Down-looking sadness: upper lid is lowered toward the inner/upper edge.
                shape.moveTo(cx - hw, centerY - hh * 0.10f)
                shape.quadTo(cx, centerY + hh * 0.20f, cx + hw, centerY + hh * 0.02f)
                shape.lineTo(cx + hw, centerY + hh * 0.54f)
                shape.quadTo(cx, centerY + hh * 0.72f, cx - hw, centerY + hh * 0.50f)
                shape.close()
            }

            EyeSilhouette.SAD_UP -> {
                // Up-looking sadness: the top edge rises toward the outside, like the reference sheet.
                shape.moveTo(cx - hw, centerY - hh * 0.52f)
                shape.quadTo(cx, centerY - hh * 0.72f, cx + hw, centerY - hh * 0.20f)
                shape.lineTo(cx + hw, centerY + hh * 0.44f)
                shape.quadTo(cx, centerY + hh * 0.64f, cx - hw, centerY + hh * 0.42f)
                shape.close()
            }

            EyeSilhouette.WORRIED -> {
                // Worried: tall rounded block with a raised inner lid.
                val r = RectF(cx - hw, centerY - glyphHh * 0.78f, cx + hw, centerY + glyphHh * 0.78f)
                shape.addRoundRect(r, glyphHh * 0.25f, glyphHh * 0.25f, Path.Direction.CW)
                shape.moveTo(cx - hw, centerY - glyphHh * 0.48f)
                shape.quadTo(cx, centerY - glyphHh * 0.82f, cx + hw, centerY - glyphHh * 0.58f)
                shape.lineTo(cx + hw, centerY - glyphHh * 0.36f)
                shape.quadTo(cx, centerY - glyphHh * 0.55f, cx - hw, centerY - glyphHh * 0.25f)
                shape.close()
            }

            EyeSilhouette.FOCUSED -> {
                // Determined/focused: one clean compact pixel with a slightly heavier lower lid.
                val r = RectF(cx - hw, centerY - glyphHh * 0.68f, cx + hw, centerY + glyphHh * 0.68f)
                shape.addRoundRect(r, glyphHh * 0.20f, glyphHh * 0.20f, Path.Direction.CW)
            }

            EyeSilhouette.ANNOYED -> {
                // Annoyed: narrowed horizontal block; no cartoon mouth or extra marks.
                val r = RectF(cx - hw, centerY - glyphHh * 0.30f, cx + hw, centerY + glyphHh * 0.30f)
                shape.addRoundRect(r, glyphHh * 0.18f, glyphHh * 0.18f, Path.Direction.CW)
            }

            EyeSilhouette.SKEPTIC, EyeSilhouette.SUSPICIOUS -> {
                // One lid is lower than the other. The two eyes remain individually readable.
                val extra = if ((side < 0) == (eyeSilhouette == EyeSilhouette.SKEPTIC)) 0.22f else 0f
                val top = -glyphHh * (0.56f - extra)
                val bottom = glyphHh * 0.48f
                val r = RectF(cx - hw, centerY + top, cx + hw, centerY + bottom)
                shape.addRoundRect(r, glyphHh * 0.18f, glyphHh * 0.18f, Path.Direction.CW)
            }

            EyeSilhouette.DROOPY -> {
                val r = RectF(cx - hw, centerY - glyphHh * 0.32f, cx + hw, centerY + glyphHh * 0.48f)
                shape.addRoundRect(r, glyphHh * 0.22f, glyphHh * 0.22f, Path.Direction.CW)
            }

            EyeSilhouette.SHARP -> {
                // Angry: the inner upper corner is lowered while the LED body stays compact.
                val inner = cx - side * hw
                val outer = cx + side * hw
                shape.moveTo(outer, centerY - glyphHh * 0.62f)
                shape.lineTo(inner, centerY - glyphHh * 0.18f)
                shape.lineTo(inner, centerY + glyphHh * 0.44f)
                shape.quadTo(cx, centerY + glyphHh * 0.62f, outer, centerY + glyphHh * 0.40f)
                shape.close()
            }

            EyeSilhouette.FURIOUS -> {
                // Furious: broader, low-set block with a strong inward brow.
                val inner = cx - side * hw
                val outer = cx + side * hw
                shape.moveTo(outer, centerY - glyphHh * 0.54f)
                shape.lineTo(inner, centerY + glyphHh * 0.02f)
                shape.lineTo(inner, centerY + glyphHh * 0.50f)
                shape.lineTo(outer, centerY + glyphHh * 0.38f)
                shape.close()
            }

            EyeSilhouette.PLAYFUL -> {
                // Playful/curious: keep the rounded LED pixel and let gaze/asymmetry carry the emotion.
                rounded(min(hw * 0.30f, glyphHh * 0.34f))
            }
        }
        if (opacity <= 0.005f) return
        val layerBounds = RectF(cx - hw * 1.8f, cy - hh * 1.8f, cx + hw * 1.8f, cy + hh * 1.8f)
        val layerSave = c.saveLayerAlpha(layerBounds, (opacity.coerceIn(0f, 1f) * 255f).toInt())
        c.save()
        c.rotate(angle, cx, cy)

        // Cozmo/Vector-style face reference: flat cyan LED glyphs, nearly black screen,
        // no white glints, no shiny gradient, and only a faint halo around the pixels.
        edge.shader = null
        edge.strokeWidth = max(1f, w * 0.025f)
        edge.color = Color.argb((18f * intensity).toInt().coerceIn(0, 24), 0, 225, 236)
        c.drawPath(shape, edge)

        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.alpha = 255
        fill.color = when (mood) {
            EveMood.SLEEPY, EveMood.CONCERNED, EveMood.IRRITATED, EveMood.ANGRY ->
                Color.rgb(13, 201, 215)
            EveMood.HAPPY, EveMood.SURPRISED, EveMood.PLAYFUL ->
                Color.rgb(27, 231, 238)
            else -> Color.rgb(20, 218, 228)
        }
        c.drawPath(shape, fill)

        c.restore()
        c.restoreToCount(layerSave)
    }

    private fun contourFor(silhouette: EyeSilhouette): EyeContour = when (silhouette) {
        EyeSilhouette.SMILE, EyeSilhouette.GLEE -> EyeContour(0.66f, 0.43f, 0.42f, 0.30f, 0f)
        EyeSilhouette.ROUND_SURPRISE, EyeSilhouette.SCARED, EyeSilhouette.AWE -> EyeContour(1.12f, 0.52f, 1.06f, 0.61f, 0f)
        EyeSilhouette.DROOPY, EyeSilhouette.SAD_DOWN, EyeSilhouette.SAD_UP, EyeSilhouette.BLINK_LOW -> EyeContour(0.60f, 0.38f, 0.76f, 0.56f, 0f)
        EyeSilhouette.FOCUSED, EyeSilhouette.SQUINT -> EyeContour(0.72f, 0.34f, 0.56f, 0.37f, 0f)
        EyeSilhouette.WORRIED -> EyeContour(0.83f, 0.47f, 0.76f, 0.61f, 0f)
        EyeSilhouette.SHARP, EyeSilhouette.FURIOUS, EyeSilhouette.ANNOYED -> EyeContour(0.70f, 0.29f, 0.48f, 0.35f, -0.11f)
        EyeSilhouette.PLAYFUL, EyeSilhouette.SKEPTIC, EyeSilhouette.SUSPICIOUS -> EyeContour(0.94f, 0.49f, 0.72f, 0.53f, 0f)
        EyeSilhouette.SOFT -> EyeContour(0.96f, 0.47f, 0.84f, 0.56f, 0f)
        EyeSilhouette.ALMOND -> EyeContour(0.90f, 0.46f, 0.80f, 0.54f, 0f)
        EyeSilhouette.NEUTRAL, EyeSilhouette.BLINK_HIGH, EyeSilhouette.BORED,
        EyeSilhouette.UNIMPRESSED -> EyeContour(0.90f, 0.46f, 0.80f, 0.54f, 0f)
    }
}
