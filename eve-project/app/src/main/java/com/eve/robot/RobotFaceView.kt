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
        // Match the reference sheet's compact rounded-square LED pixels instead of wide pills.
        // Scale both axes independently so the eyes keep their proportions on tall phone screens.
        // Keep the display pixels horizontally rectangular like the Cozmo sheet.
        // The old near-square eye height made the face read as two large tiles instead of LED eyes.
        val w = min(width * 0.23f, height * 0.15f) * eyeWidth
        // Neutral Cozmo-style LED blocks are close to square; each expression then changes its own aperture.
        val h = min(height * 0.11f, w * 0.82f) * eyeHeight
        val gap = min(width * 0.21f, w * 0.62f) * spacing
        val cy = height * 0.50f
        val silhouetteRaw = if (silhouetteTransitionStartedAt == 0L) 1f else
            ((now - silhouetteTransitionStartedAt).toFloat() / silhouetteTransitionDurationMs).coerceIn(0f, 1f)
        val silhouetteBlend = silhouetteRaw * silhouetteRaw * (3f - 2f * silhouetteRaw)
        if (silhouetteRaw >= 1f) silhouetteTransitionStartedAt = 0L

        fun drawExpressionLayer(style: EyeSilhouette, alpha: Float) {
            if (alpha <= 0.005f) return
            drawEye(canvas, width * 0.5f - gap + (gazeX + idleGaze) * width,
                cy + (gazeY + idleGazeY + 0f) * height, w * (1f + asymmetry * 0.24f), h,
                opening * blink * breath * (1f + asymmetry * 0.30f), tilt * 0.06f, glow, squint, contour, -1, style, alpha)
            drawEye(canvas, width * 0.5f + gap + (gazeX + idleGaze + asymmetry * 0.025f) * width,
                cy + (gazeY + idleGazeY + 0f - asymmetry * 0.035f) * height, w * (1f - asymmetry * 0.24f), h,
                opening * blink * breath * (1f - asymmetry * 0.30f), -tilt * 0.06f, glow, squint, contour, 1, style, alpha)
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
            EyeSilhouette.NEUTRAL -> 0.98f
            // Neutral mood stays in the compact pixel family rather than becoming a wide almond.
            EyeSilhouette.ALMOND -> 0.82f
            EyeSilhouette.SOFT -> 0.86f
            EyeSilhouette.ROUND_SURPRISE -> 0.96f
            EyeSilhouette.AWE -> 0.96f
            EyeSilhouette.SCARED -> 0.90f
            EyeSilhouette.SKEPTIC, EyeSilhouette.SUSPICIOUS -> 0.92f
            else -> 1f
        }
        val heightScale = when (eyeSilhouette) {
            EyeSilhouette.NEUTRAL -> 1.00f
            EyeSilhouette.ALMOND -> 1.08f
            EyeSilhouette.SOFT -> 1.34f
            EyeSilhouette.ROUND_SURPRISE -> 1.00f
            EyeSilhouette.AWE -> 1.18f
            EyeSilhouette.SCARED -> 1.12f
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
            EyeSilhouette.ROUND_SURPRISE -> 1.00f
            EyeSilhouette.SCARED -> 1.42f
            // Keep awe eyes enlarged but near-square; an oversized vertical glyph failed the pixel audit.
            EyeSilhouette.AWE -> 0.90f
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
            EyeSilhouette.BORED -> 0.58f
            EyeSilhouette.UNIMPRESSED -> 0.58f
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
            // Normal/awake: crisp rounded-square LED pixels.
            EyeSilhouette.NEUTRAL -> rounded(min(hw * 0.40f, glyphHh * 0.42f))

            // Neutral mood: a smaller square-ish pixel with rounder corners than Awake.
            EyeSilhouette.ALMOND -> rounded(min(hw * 0.43f, glyphHh * 0.45f))

            // Curious: both eyes remain open LED pixels; a slight lid offset conveys attention.
            EyeSilhouette.CURIOUS -> {
                val eyeTopScale = if (side < 0) 0.78f else 0.88f
                val curiousRect = RectF(
                    cx - hw, centerY - glyphHh * eyeTopScale,
                    cx + hw, centerY + glyphHh * eyeTopScale
                )
                val r = min(hw * 0.26f, glyphHh * 0.32f)
                shape.addRoundRect(curiousRect, r, r, Path.Direction.CW)
            }

            // Warm/familiar: capsule-like eyes, intentionally rounder than neutral/almond.
            EyeSilhouette.SOFT -> {
                val r = min(hw * 0.90f, glyphHh * 0.92f)
                val rect = RectF(cx - hw, centerY - glyphHh * 0.82f,
                    cx + hw, centerY + glyphHh * 0.82f)
                shape.addRoundRect(rect, r, r, Path.Direction.CW)
            }

            // Surprise: nearly square, bright blocks; awe is oval and scared is angular.
            EyeSilhouette.ROUND_SURPRISE -> rounded(min(hw * 0.42f, glyphHh * 0.44f))

            EyeSilhouette.AWE -> {
                val oval = RectF(cx - hw * 0.98f, centerY - glyphHh * 0.92f,
                    cx + hw * 0.98f, centerY + glyphHh * 0.92f)
                shape.addOval(oval, Path.Direction.CW)
            }

            EyeSilhouette.SCARED -> {
                // Tall eye with a pinched upper edge and expanded lower edge: visibly alarmed.
                shape.moveTo(cx - hw * 0.62f, centerY - glyphHh)
                shape.quadTo(cx, centerY - glyphHh * 1.16f, cx + hw * 0.62f, centerY - glyphHh)
                shape.lineTo(cx + hw, centerY + glyphHh * 0.62f)
                shape.quadTo(cx, centerY + glyphHh, cx - hw, centerY + glyphHh * 0.62f)
                shape.close()
            }

            // Happy is a clearly upward arch/crescent rather than a neutral block.
            EyeSilhouette.SMILE -> {
                shape.moveTo(cx - hw, centerY + glyphHh * 0.18f)
                shape.cubicTo(cx - hw * 0.60f, centerY - glyphHh * 1.05f,
                    cx + hw * 0.60f, centerY - glyphHh * 1.05f,
                    cx + hw, centerY + glyphHh * 0.18f)
                shape.cubicTo(cx + hw * 0.54f, centerY - glyphHh * 0.06f,
                    cx - hw * 0.54f, centerY - glyphHh * 0.06f,
                    cx - hw, centerY + glyphHh * 0.18f)
                shape.close()
            }

            // Glee: higher, flatter lid arcs than the broad happy crescent.
            EyeSilhouette.GLEE -> {
                shape.moveTo(cx - hw, centerY + glyphHh * 0.22f)
                shape.cubicTo(cx - hw * 0.54f, centerY - glyphHh * 0.68f,
                    cx + hw * 0.54f, centerY - glyphHh * 0.68f,
                    cx + hw, centerY + glyphHh * 0.22f)
                shape.cubicTo(cx + hw * 0.62f, centerY + glyphHh * 0.04f,
                    cx - hw * 0.62f, centerY + glyphHh * 0.04f,
                    cx - hw, centerY + glyphHh * 0.22f)
                shape.close()
            }

            // High/low blink are separate slits at different vertical positions.
            EyeSilhouette.BLINK_HIGH, EyeSilhouette.BLINK_LOW -> {
                val y = if (eyeSilhouette == EyeSilhouette.BLINK_HIGH) -glyphHh * 0.42f else glyphHh * 0.42f
                val slitH = max(1.1f, glyphHh * if (eyeSilhouette == EyeSilhouette.BLINK_HIGH) 0.20f else 0.24f)
                val r = RectF(cx - hw, centerY + y - slitH, cx + hw, centerY + y + slitH)
                shape.addRoundRect(r, slitH, slitH, Path.Direction.CW)
            }

            // Bored: heavy, sagging lid line. Unlike a blink it droops toward the centre.
            EyeSilhouette.BORED -> {
                shape.moveTo(cx - hw, centerY - glyphHh * 0.10f)
                shape.cubicTo(cx - hw * 0.58f, centerY + glyphHh * 0.26f,
                    cx + hw * 0.52f, centerY + glyphHh * 0.26f, cx + hw, centerY - glyphHh * 0.10f)
                shape.lineTo(cx + hw, centerY + glyphHh * 0.22f)
                shape.quadTo(cx, centerY + glyphHh * 0.45f, cx - hw, centerY + glyphHh * 0.22f)
                shape.close()
            }

            // Unimpressed: angled lid with the outer corner deliberately lower than the inner one.
            EyeSilhouette.UNIMPRESSED -> {
                val inner = cx - side * hw
                val outer = cx + side * hw
                shape.moveTo(outer, centerY - glyphHh * 0.26f)
                shape.lineTo(inner, centerY + glyphHh * 0.03f)
                shape.lineTo(inner, centerY + glyphHh * 0.34f)
                shape.quadTo(cx, centerY + glyphHh * 0.40f, outer, centerY + glyphHh * 0.16f)
                shape.close()
            }

            // Squint: pinched centre with tapered lateral edges, not a straight slit.
            EyeSilhouette.SQUINT -> {
                shape.moveTo(cx - hw, centerY - glyphHh * 0.18f)
                shape.quadTo(cx, centerY - glyphHh * 0.02f, cx + hw, centerY - glyphHh * 0.18f)
                shape.quadTo(cx + hw * 0.60f, centerY + glyphHh * 0.18f, cx, centerY + glyphHh * 0.13f)
                shape.quadTo(cx - hw * 0.60f, centerY + glyphHh * 0.18f, cx - hw, centerY - glyphHh * 0.18f)
                shape.close()
            }

            // Sad looking down/up intentionally have different lids and gaze directions.
            EyeSilhouette.SAD_DOWN -> {
                shape.moveTo(cx - hw, centerY - glyphHh * 0.08f)
                shape.quadTo(cx, centerY + glyphHh * 0.28f, cx + hw, centerY + glyphHh * 0.04f)
                shape.lineTo(cx + hw, centerY + glyphHh * 0.56f)
                shape.quadTo(cx, centerY + glyphHh * 0.78f, cx - hw, centerY + glyphHh * 0.50f)
                shape.close()
            }

            EyeSilhouette.SAD_UP -> {
                shape.moveTo(cx - hw, centerY - glyphHh * 0.58f)
                shape.quadTo(cx, centerY - glyphHh * 0.86f, cx + hw, centerY - glyphHh * 0.18f)
                shape.lineTo(cx + hw, centerY + glyphHh * 0.42f)
                shape.quadTo(cx, centerY + glyphHh * 0.64f, cx - hw, centerY + glyphHh * 0.42f)
                shape.close()
            }

            EyeSilhouette.WORRIED -> {
                // Uneven raised lid + round lower bowl.
                shape.moveTo(cx - hw, centerY - glyphHh * 0.38f)
                shape.cubicTo(cx - hw * 0.66f, centerY - glyphHh * 0.94f,
                    cx - hw * 0.18f, centerY - glyphHh * 0.54f,
                    cx + hw * 0.18f, centerY - glyphHh * 0.60f)
                shape.cubicTo(cx + hw * 0.56f, centerY - glyphHh * 0.64f,
                    cx + hw * 0.82f, centerY - glyphHh * 0.88f,
                    cx + hw, centerY - glyphHh * 0.52f)
                shape.lineTo(cx + hw, centerY + glyphHh * 0.40f)
                shape.cubicTo(cx + hw * 0.82f, centerY + glyphHh * 0.77f,
                    cx - hw * 0.72f, centerY + glyphHh * 0.78f,
                    cx - hw, centerY + glyphHh * 0.42f)
                shape.close()
            }

            EyeSilhouette.FOCUSED -> {
                // Determined: compact block with clipped upper corners and a flatter lid.
                shape.moveTo(cx - hw, centerY - glyphHh * 0.38f)
                shape.lineTo(cx - hw * 0.72f, centerY - glyphHh * 0.76f)
                shape.lineTo(cx + hw * 0.62f, centerY - glyphHh * 0.76f)
                shape.lineTo(cx + hw, centerY - glyphHh * 0.34f)
                shape.lineTo(cx + hw * 0.92f, centerY + glyphHh * 0.48f)
                shape.quadTo(cx, centerY + glyphHh * 0.64f, cx - hw * 0.92f, centerY + glyphHh * 0.48f)
                shape.close()
            }

            EyeSilhouette.ANNOYED -> {
                // Annoyed: narrow wedge, but less aggressive than the angry shape.
                shape.moveTo(cx - hw, centerY - glyphHh * 0.50f)
                shape.lineTo(cx + hw, centerY - glyphHh * 0.18f)
                shape.lineTo(cx + hw * 0.92f, centerY + glyphHh * 0.28f)
                shape.quadTo(cx, centerY + glyphHh * 0.48f, cx - hw * 0.92f, centerY + glyphHh * 0.28f)
                shape.close()
            }

            EyeSilhouette.SKEPTIC -> {
                // One raised and one lowered eyelid gives a recognisable asymmetrical expression.
                val top = if (side < 0) centerY - glyphHh * 0.76f else centerY - glyphHh * 0.26f
                val bottom = if (side < 0) centerY + glyphHh * 0.38f else centerY + glyphHh * 0.52f
                val r = RectF(cx - hw, top, cx + hw, bottom)
                shape.addRoundRect(r, glyphHh * 0.18f, glyphHh * 0.18f, Path.Direction.CW)
            }

            EyeSilhouette.SUSPICIOUS -> {
                // Both eyes tilt inward, a wary look distinct from the one-sided skeptic.
                val inner = cx - side * hw
                val outer = cx + side * hw
                shape.moveTo(outer, centerY - glyphHh * 0.54f)
                shape.lineTo(inner, centerY - glyphHh * 0.10f)
                shape.lineTo(inner, centerY + glyphHh * 0.28f)
                shape.quadTo(cx, centerY + glyphHh * 0.46f, outer, centerY + glyphHh * 0.34f)
                shape.close()
            }

            EyeSilhouette.DROOPY -> {
                // Sleepy lid is a curved falling arc, not a straight-line eye.
                shape.moveTo(cx - hw, centerY - glyphHh * 0.20f)
                shape.quadTo(cx, centerY + glyphHh * 0.34f, cx + hw, centerY - glyphHh * 0.06f)
                shape.lineTo(cx + hw, centerY + glyphHh * 0.30f)
                shape.quadTo(cx, centerY + glyphHh * 0.54f, cx - hw, centerY + glyphHh * 0.30f)
                shape.close()
            }

            EyeSilhouette.SHARP -> {
                // Angry: upper inner corner drops, matching the inward-sloping angry brow.
                val inner = cx - side * hw
                val outer = cx + side * hw
                shape.moveTo(outer, centerY - glyphHh * 0.74f)
                shape.lineTo(inner, centerY + glyphHh * 0.18f)
                shape.lineTo(inner, centerY + glyphHh * 0.54f)
                shape.quadTo(cx, centerY + glyphHh * 0.64f, outer, centerY + glyphHh * 0.36f)
                shape.close()
            }

            EyeSilhouette.FURIOUS -> {
                // Furious: more extreme inward slope and lower lid.
                val inner = cx - side * hw
                val outer = cx + side * hw
                shape.moveTo(outer, centerY - glyphHh * 0.92f)
                shape.lineTo(inner, centerY + glyphHh * 0.38f)
                shape.lineTo(inner, centerY + glyphHh * 0.62f)
                shape.lineTo(outer, centerY + glyphHh * 0.34f)
                shape.close()
            }

            EyeSilhouette.PLAYFUL -> {
                // A small crescent on the left and a rounded open pixel on the right form a playful wink.
                if (side < 0) {
                    shape.moveTo(cx - hw, centerY + glyphHh * 0.12f)
                    shape.cubicTo(cx - hw * 0.54f, centerY - glyphHh * 0.72f,
                        cx + hw * 0.52f, centerY - glyphHh * 0.72f, cx + hw, centerY + glyphHh * 0.12f)
                    shape.lineTo(cx + hw * 0.90f, centerY + glyphHh * 0.34f)
                    shape.quadTo(cx, centerY + glyphHh * 0.10f, cx - hw, centerY + glyphHh * 0.34f)
                    shape.close()
                } else {
                    rounded(min(hw * 0.28f, glyphHh * 0.36f))
                }
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
                Color.rgb(0, 158, 174)
            EveMood.HAPPY, EveMood.SURPRISED, EveMood.PLAYFUL ->
                Color.rgb(0, 205, 215)
            else -> Color.rgb(0, 181, 193)
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
        EyeSilhouette.CURIOUS, EyeSilhouette.PLAYFUL, EyeSilhouette.SKEPTIC, EyeSilhouette.SUSPICIOUS -> EyeContour(0.94f, 0.49f, 0.72f, 0.53f, 0f)
        EyeSilhouette.SOFT -> EyeContour(0.96f, 0.47f, 0.84f, 0.56f, 0f)
        EyeSilhouette.ALMOND -> EyeContour(0.90f, 0.46f, 0.80f, 0.54f, 0f)
        EyeSilhouette.NEUTRAL, EyeSilhouette.BLINK_HIGH, EyeSilhouette.BORED,
        EyeSilhouette.UNIMPRESSED -> EyeContour(0.90f, 0.46f, 0.80f, 0.54f, 0f)
    }
}
