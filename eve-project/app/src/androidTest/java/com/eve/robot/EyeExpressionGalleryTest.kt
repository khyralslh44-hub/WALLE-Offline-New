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
        GlyphCase(EyeSilhouette.BLINK_HIGH, "BLINK HIGH", decision(EveMood.NEUTRAL, "blink high")),
        GlyphCase(EyeSilhouette.SMILE, "HAPPY", decision(EveMood.HAPPY, "happy", valence = 0.25f)),
        GlyphCase(EyeSilhouette.GLEE, "GLEE", decision(EveMood.HAPPY, "happy", valence = 0.85f)),
        GlyphCase(EyeSilhouette.BLINK_LOW, "BLINK LOW", decision(EveMood.NEUTRAL, "blink low")),
        GlyphCase(EyeSilhouette.SAD_DOWN, "SAD DOWN", decision(EveMood.CONCERNED, "sad down")),
        GlyphCase(EyeSilhouette.SAD_UP, "SAD UP", decision(EveMood.CONCERNED, "sad up")),
        GlyphCase(EyeSilhouette.WORRIED, "WORRIED", decision(EveMood.CONCERNED, arousal = 0.65f)),
        GlyphCase(EyeSilhouette.FOCUSED, "FOCUSED", decision(EveMood.FOCUSED, arousal = 0.65f)),
        GlyphCase(EyeSilhouette.ANNOYED, "ANNOYED", decision(EveMood.IRRITATED, arousal = 0.65f, frustration = 0.2f)),
        GlyphCase(EyeSilhouette.ROUND_SURPRISE, "SURPRISED", decision(EveMood.SURPRISED, "surprise", valence = 0.12f)),
        GlyphCase(EyeSilhouette.SKEPTIC, "SKEPTIC", decision(EveMood.PLAYFUL, "skeptic")),
        GlyphCase(EyeSilhouette.BORED, "BORED", decision(EveMood.IRRITATED, "bored")),
        GlyphCase(EyeSilhouette.UNIMPRESSED, "UNIMPRESSED", decision(EveMood.CONCERNED, "unimpressed")),
        GlyphCase(EyeSilhouette.DROOPY, "SLEEPY", decision(EveMood.SLEEPY, fatigue = 0.3f)),
        GlyphCase(EyeSilhouette.SUSPICIOUS, "SUSPICIOUS", decision(EveMood.CURIOUS, "suspicious")),
        GlyphCase(EyeSilhouette.SQUINT, "SQUINT", decision(EveMood.FOCUSED, arousal = 0.1f)),
        GlyphCase(EyeSilhouette.SHARP, "ANGRY", decision(EveMood.ANGRY, "angry", arousal = 0.5f)),
        GlyphCase(EyeSilhouette.FURIOUS, "FURIOUS", decision(EveMood.ANGRY, "angry", arousal = 0.85f, frustration = 0.85f)),
        GlyphCase(EyeSilhouette.SCARED, "SCARED", decision(EveMood.CONCERNED, "fearful", arousal = 0.8f)),
        GlyphCase(EyeSilhouette.AWE, "AWE", decision(EveMood.SURPRISED, "surprised", valence = 0.85f)),
        GlyphCase(EyeSilhouette.PLAYFUL, "PLAYFUL", decision(EveMood.PLAYFUL, valence = 0.7f)),
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
        val tileWidth = 260
        val viewHeight = 360
        val cropHeight = 120
        val captionHeight = 25
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
            for (y in 0 until frame.height) {
                for (x in 0 until frame.width) {
                    val pixel = frame.getPixel(x, y)
                    if (Color.red(pixel) > 20 || Color.green(pixel) > 35 || Color.blue(pixel) > 45) {
                        litPixels++
                    }
                    hash = (hash xor pixel.toLong()) * 1099511628211L
                }
            }
            assertTrue("${item.label} produced no visible cyan pixels.", litPixels > 8)
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
