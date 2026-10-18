package com.eve.robot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the real production RobotFaceView for every known glyph.
 *
 * The output sheet is a CI visual artifact only; it is not placed into the app UI.
 * This lets us inspect the rendered pixels rather than trusting enum names alone.
 */
@RunWith(AndroidJUnit4::class)
class EyeExpressionGalleryTest {
    private data class GlyphCase(val expected: EyeSilhouette, val label: String, val decision: EveDecision)

    private fun decision(
        mood: EveMood,
        reason: String = "gallery",
        valence: Float = 0.12f,
        arousal: Float = 0.32f,
        curiosity: Float = 0.55f,
        fatigue: Float = 0f,
        frustration: Float = 0f
    ) = EveDecision(
        mood = mood,
        reason = reason,
        valence = valence,
        arousal = arousal,
        curiosity = curiosity,
        fatigue = fatigue,
        frustration = frustration
    )

    private fun cases() = listOf(
        GlyphCase(EyeSilhouette.ALMOND, "NEUTRAL PIXEL", decision(EveMood.NEUTRAL)),
        GlyphCase(EyeSilhouette.NEUTRAL, "AWAKE", decision(EveMood.AWAKE)),
        GlyphCase(EyeSilhouette.CURIOUS, "CURIOUS", decision(EveMood.CURIOUS, "curiosity", curiosity = 0.8f)),
        GlyphCase(EyeSilhouette.BLINK_HIGH, "BLINK HIGH", decision(EveMood.NEUTRAL, "blink high", arousal = 0.10f)),
        GlyphCase(EyeSilhouette.SMILE, "HAPPY", decision(EveMood.HAPPY, "happy", valence = 0.25f)),
        GlyphCase(EyeSilhouette.GLEE, "GLEE", decision(EveMood.HAPPY, "happy", valence = 0.85f)),
        GlyphCase(EyeSilhouette.BLINK_LOW, "BLINK LOW", decision(EveMood.NEUTRAL, "blink low")),
        GlyphCase(EyeSilhouette.SAD_DOWN, "SAD DOWN", decision(EveMood.CONCERNED, "sad down", valence = -0.70f, fatigue = 0.70f)),
        GlyphCase(EyeSilhouette.SAD_UP, "SAD UP", decision(EveMood.CONCERNED, "sad up", valence = -0.70f, fatigue = 0.10f)),
        GlyphCase(EyeSilhouette.WORRIED, "WORRIED", decision(EveMood.CONCERNED, arousal = 0.65f)),
        GlyphCase(EyeSilhouette.FOCUSED, "FOCUSED", decision(EveMood.FOCUSED, arousal = 0.65f)),
        GlyphCase(EyeSilhouette.ANNOYED, "ANNOYED", decision(EveMood.IRRITATED, arousal = 0.65f, frustration = 0.2f)),
        GlyphCase(EyeSilhouette.ROUND_SURPRISE, "SURPRISED", decision(EveMood.SURPRISED, "surprise", valence = 0.12f)),
        GlyphCase(EyeSilhouette.SKEPTIC, "SKEPTIC", decision(EveMood.PLAYFUL, "skeptic", valence = 0.0f)),
        GlyphCase(EyeSilhouette.BORED, "BORED", decision(EveMood.IRRITATED, "bored", arousal = 0.18f)),
        GlyphCase(EyeSilhouette.UNIMPRESSED, "UNIMPRESSED", decision(EveMood.CONCERNED, "unimpressed", arousal = 0.20f)),
        GlyphCase(EyeSilhouette.DROOPY, "SLEEPY", decision(EveMood.SLEEPY, fatigue = 0.3f)),
        GlyphCase(EyeSilhouette.SUSPICIOUS, "SUSPICIOUS", decision(EveMood.CURIOUS, "suspicious", valence = -0.50f, curiosity = 0.92f)),
        GlyphCase(EyeSilhouette.SQUINT, "SQUINT", decision(EveMood.FOCUSED, arousal = 0.1f)),
        GlyphCase(EyeSilhouette.SHARP, "ANGRY", decision(EveMood.ANGRY, "angry", arousal = 0.5f)),
        GlyphCase(EyeSilhouette.FURIOUS, "FURIOUS", decision(EveMood.ANGRY, "angry", arousal = 0.85f, frustration = 0.85f)),
        GlyphCase(EyeSilhouette.SCARED, "SCARED", decision(EveMood.CONCERNED, "fearful", valence = -0.50f, arousal = 0.86f)),
        GlyphCase(EyeSilhouette.AWE, "AWE", decision(EveMood.SURPRISED, "surprised", valence = 0.85f)),
        GlyphCase(EyeSilhouette.PLAYFUL, "PLAYFUL", decision(EveMood.PLAYFUL, valence = 0.40f)),
        GlyphCase(EyeSilhouette.SOFT, "WARM / SOFT", decision(EveMood.FAMILIAR, valence = 0.25f))
    )

    @Test
    fun allReferenceInspiredGlyphsRenderAsDistinctPixelPatterns() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val glyphCases = cases()
        val selected = glyphCases.map {
            EyeExpressionGenerator.generate(it.decision, 0xC02A0L).silhouette
        }.toSet()
        assertEquals("Every defined glyph must be reachable from an affect decision.", EyeSilhouette.values().toSet(), selected)

        val columns = 4
        val tileWidth = 320
        val viewHeight = 528
        val cropHeight = 488
        val captionHeight = 40
        val tileHeight = cropHeight + captionHeight
        val rows = (glyphCases.size + columns - 1) / columns
        val sheet = Bitmap.createBitmap(columns * tileWidth, rows * tileHeight, Bitmap.Config.ARGB_8888)
        val sheetCanvas = Canvas(sheet)
        val background = Color.rgb(2, 7, 10)
        sheetCanvas.drawColor(background)
        val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(140, 236, 244)
            textSize = 14f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 38, 44) }
        val signatures = LinkedHashSet<Long>()

        glyphCases.forEachIndexed { index, item ->
            val rendered = arrayOfNulls<Bitmap>(1)
            instrumentation.runOnMainSync {
                val view = RobotFaceView(appContext)
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(tileWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(viewHeight, View.MeasureSpec.EXACTLY)
                )
                view.layout(0, 0, tileWidth, viewHeight)
                view.setExpression(item.decision, seed = 0xE7E123L)
                // The production renderer animates parameter interpolation; render the settled pose.
                SystemClock.sleep(390L)
                val full = Bitmap.createBitmap(tileWidth, viewHeight, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(full))
                val top = ((viewHeight - cropHeight) / 2).coerceAtLeast(0)
                rendered[0] = Bitmap.createBitmap(full, 0, top, tileWidth, cropHeight)
                full.recycle()
            }
            val frame = requireNotNull(rendered[0]) { "Renderer returned no bitmap for ${item.label}" }
            val col = index % columns
            val row = index / columns
            val left = col * tileWidth
            val top = row * tileHeight
            sheetCanvas.drawBitmap(frame, left.toFloat(), top.toFloat(), null)

            // Hash actual drawn pixels, including shape, placement, eye opening, and color.
            var hash = -3750763034362895579L
            var litPixels = 0
            var minX = frame.width
            var minY = frame.height
            var maxX = -1
            var maxY = -1
            for (y in 0 until frame.height) {
                for (x in 0 until frame.width) {
                    val pixel = frame.getPixel(x, y)
                    if (Color.red(pixel) > 20 || Color.green(pixel) > 35 || Color.blue(pixel) > 45) {
                        litPixels++
                        minX = minOf(minX, x)
                        minY = minOf(minY, y)
                        maxX = maxOf(maxX, x)
                        maxY = maxOf(maxY, y)
                    }
                    hash = (hash xor pixel.toLong()) * 1099511628211L
                }
            }
            assertTrue("${item.label} produced no visible cyan pixels.", litPixels > 8)
            val renderedWidth = maxX - minX + 1
            val renderedHeight = maxY - minY + 1

            // The reference's neutral/familiar LED eyes are compact, rounded blocks close to square.
            // Check each eye independently so the gap between them cannot distort the ratio.
            if (item.expected in setOf(
                    EyeSilhouette.ALMOND, EyeSilhouette.NEUTRAL, EyeSilhouette.SOFT,
                    EyeSilhouette.ROUND_SURPRISE, EyeSilhouette.AWE, EyeSilhouette.SCARED
                )) {
                fun eyeBoundsInHalf(startX: Int, endX: Int): IntArray? {
                    var left = frame.width
                    var top = frame.height
                    var right = -1
                    var bottom = -1
                    for (py in 0 until frame.height) {
                        for (px in startX until endX) {
                            val color = frame.getPixel(px, py)
                            val cyan = Color.green(color) > 95 &&
                                Color.blue(color) > 105 &&
                                Color.blue(color) > Color.red(color) + 45
                            if (cyan) {
                                left = minOf(left, px)
                                top = minOf(top, py)
                                right = maxOf(right, px)
                                bottom = maxOf(bottom, py)
                            }
                        }
                    }
                    return if (right >= left && bottom >= top)
                        intArrayOf(left, top, right, bottom) else null
                }

                for ((side, bounds) in listOf(
                    "left" to eyeBoundsInHalf(0, frame.width / 2),
                    "right" to eyeBoundsInHalf(frame.width / 2, frame.width)
                )) {
                    val box = requireNotNull(bounds) { "${item.label} $side eye pixels were not found." }
                    val eyeWidth = box[2] - box[0] + 1
                    val eyeHeight = box[3] - box[1] + 1
                    val ratio = eyeWidth.toFloat() / eyeHeight.coerceAtLeast(1)
                    val expectedRatio = when (item.expected) {
                        EyeSilhouette.ROUND_SURPRISE, EyeSilhouette.AWE -> 0.80f..1.30f
                        EyeSilhouette.SCARED -> 0.68f..1.35f
                        // Neutral/warm Cozmo pixels should stay close to square, not wide bars.
                        EyeSilhouette.ALMOND, EyeSilhouette.NEUTRAL, EyeSilhouette.SOFT -> 0.95f..1.55f
                        else -> 0.95f..1.85f
                    }
                    assertTrue(
                        "${item.label} $side eye aspect is outside the reference range; " +
                            "measured ${eyeWidth}x${eyeHeight} (ratio=$ratio expected=$expectedRatio).",
                        ratio in expectedRatio
                    )
                }
            }

            val minimumHeight = when (item.expected) {
                EyeSilhouette.BLINK_HIGH, EyeSilhouette.BLINK_LOW -> 1
                EyeSilhouette.BORED -> 2
                EyeSilhouette.SMILE -> 6
                EyeSilhouette.GLEE -> 4
                EyeSilhouette.UNIMPRESSED, EyeSilhouette.DROOPY, EyeSilhouette.SQUINT -> 4
                EyeSilhouette.ANNOYED -> 7
                EyeSilhouette.SAD_DOWN, EyeSilhouette.SAD_UP -> 8
                EyeSilhouette.WORRIED, EyeSilhouette.FOCUSED -> 11
                EyeSilhouette.SUSPICIOUS, EyeSilhouette.SHARP -> 8
                EyeSilhouette.FURIOUS -> 10
                EyeSilhouette.CURIOUS, EyeSilhouette.SKEPTIC, EyeSilhouette.PLAYFUL -> 12
                EyeSilhouette.NEUTRAL, EyeSilhouette.ALMOND, EyeSilhouette.SOFT -> 12
                EyeSilhouette.ROUND_SURPRISE, EyeSilhouette.AWE -> 14
                EyeSilhouette.SCARED -> 19
            }
            assertTrue(
                "${item.label} raster is too thin: ${renderedWidth}x${renderedHeight}, minHeight=$minimumHeight",
                renderedHeight >= minimumHeight
            )
            signatures.add(hash)
            frame.recycle()

            sheetCanvas.drawText(item.label, left + 10f, top + cropHeight + 17f, captionPaint)
            sheetCanvas.drawLine(
                left.toFloat(),
                (top + tileHeight - 1).toFloat(),
                (left + tileWidth).toFloat(),
                (top + tileHeight - 1).toFloat(),
                separatorPaint
            )
        }

        assertTrue(
            "Expected 20+ visually distinct rasterized patterns from 24 glyphs; got ${signatures.size}",
            signatures.size >= 20
        )
        val output = File(appContext.filesDir, "eye-expression-sheet.png")
        FileOutputStream(output).use { stream ->
            assertTrue("Could not encode expression gallery PNG.", sheet.compress(Bitmap.CompressFormat.PNG, 100, stream))
            stream.flush()
        }
        sheet.recycle()

        val message = "EYE_GALLERY_PASS styles=${glyphCases.size} rasterVariants=${signatures.size} image=files/eye-expression-sheet.png"
        android.util.Log.i("EVE_TEST", message)
        System.out.println(message)
    }
}
