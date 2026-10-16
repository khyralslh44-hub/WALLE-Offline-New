package com.eve.robot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
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
 * Pixel-level audit of every expression glyph. It renders the same RobotFaceView used by the app
 * rather than testing only enum names, and exports a contact sheet that can be inspected by a human.
 */
@RunWith(AndroidJUnit4::class)
class RobotFaceRenderingTest {
    private data class Spec(
        val label: String,
        val decision: EveDecision,
        val expected: EyeSilhouette
    )

    private fun spec(
        label: String,
        mood: EveMood,
        expected: EyeSilhouette,
        reason: String = label.lowercase(),
        valence: Float = 0.12f,
        arousal: Float = 0.32f,
        curiosity: Float = 0.55f,
        fatigue: Float = 0f,
        frustration: Float = 0f
    ) = Spec(
        label,
        EveDecision(
            mood = mood,
            reason = reason,
            valence = valence,
            arousal = arousal,
            curiosity = curiosity,
            familiarity = 0.10f,
            fatigue = fatigue,
            frustration = frustration
        ),
        expected
    )

    @Test
    fun rendersAllReferenceInspiredGlyphsAndExportsContactSheet() {
        val specs = listOf(
            spec("Neutral", EveMood.NEUTRAL, EyeSilhouette.ALMOND, reason = "neutral"),
            spec("Awake", EveMood.AWAKE, EyeSilhouette.NEUTRAL, reason = "awake"),
            spec("Blink high", EveMood.NEUTRAL, EyeSilhouette.BLINK_HIGH, reason = "blink high"),
            spec("Happy", EveMood.HAPPY, EyeSilhouette.SMILE, reason = "happy", valence = 0.25f, arousal = 0.75f),
            spec("Glee", EveMood.HAPPY, EyeSilhouette.GLEE, reason = "happy", valence = 0.85f, arousal = 0.85f),
            spec("Blink low", EveMood.NEUTRAL, EyeSilhouette.BLINK_LOW, reason = "blink low"),
            spec("Sad down", EveMood.CONCERNED, EyeSilhouette.SAD_DOWN, reason = "sad down", valence = -0.70f),
            spec("Sad up", EveMood.CONCERNED, EyeSilhouette.SAD_UP, reason = "sad up", valence = -0.35f),
            spec("Worried", EveMood.CONCERNED, EyeSilhouette.WORRIED, reason = "worry", valence = 0.08f, arousal = 0.65f),
            spec("Focused", EveMood.FOCUSED, EyeSilhouette.FOCUSED, reason = "focus", arousal = 0.65f),
            spec("Annoyed", EveMood.IRRITATED, EyeSilhouette.ANNOYED, reason = "annoyed", arousal = 0.65f),
            spec("Surprised", EveMood.SURPRISED, EyeSilhouette.ROUND_SURPRISE, reason = "surprised", valence = 0.12f),
            spec("Skeptic", EveMood.PLAYFUL, EyeSilhouette.SKEPTIC, reason = "skeptic"),
            spec("Bored", EveMood.IRRITATED, EyeSilhouette.BORED, reason = "bored", arousal = 0.18f),
            spec("Unimpressed", EveMood.CONCERNED, EyeSilhouette.UNIMPRESSED, reason = "unimpressed", arousal = 0.42f),
            spec("Sleepy", EveMood.SLEEPY, EyeSilhouette.DROOPY, reason = "sleepy", fatigue = 0.30f),
            spec("Suspicious", EveMood.CURIOUS, EyeSilhouette.SUSPICIOUS, reason = "suspicious"),
            spec("Squint", EveMood.FOCUSED, EyeSilhouette.SQUINT, reason = "low focus", arousal = 0.10f),
            spec("Angry", EveMood.ANGRY, EyeSilhouette.SHARP, reason = "angry", arousal = 0.50f, frustration = 0.20f),
            spec("Furious", EveMood.ANGRY, EyeSilhouette.FURIOUS, reason = "angry", arousal = 0.88f, frustration = 0.88f),
            spec("Scared", EveMood.CONCERNED, EyeSilhouette.SCARED, reason = "fearful", arousal = 0.85f),
            spec("Awe", EveMood.SURPRISED, EyeSilhouette.AWE, reason = "surprised", valence = 0.85f, arousal = 0.86f),
            spec("Playful", EveMood.PLAYFUL, EyeSilhouette.PLAYFUL, reason = "playful", valence = 0.70f),
            spec("Warm", EveMood.FAMILIAR, EyeSilhouette.SOFT, reason = "familiar", valence = 0.25f)
        )

        assertEquals("The rendering audit must cover every declared eye glyph.", EyeSilhouette.values().toSet(), specs.map { it.expected }.toSet())

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val cellWidth = 320
        val faceHeight = 480
        val captionHeight = 48
        val cellHeight = faceHeight + captionHeight
        val columns = 4
        val rows = (specs.size + columns - 1) / columns
        val sheet = Bitmap.createBitmap(cellWidth * columns, cellHeight * rows, Bitmap.Config.ARGB_8888)
        val sheetCanvas = Canvas(sheet)
        sheetCanvas.drawColor(Color.rgb(3, 6, 8))
        val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            textAlign = Paint.Align.CENTER
        }
        val fingerprints = linkedMapOf<String, Long>()
        val boundsReport = ArrayList<String>()

        specs.forEachIndexed { index, item ->
            val profile = EyeExpressionGenerator.generate(item.decision, 77L)
            assertEquals("Wrong glyph selected for ${item.label}", item.expected, profile.silhouette)

            val face = RobotFaceView(context)
            face.measure(
                View.MeasureSpec.makeMeasureSpec(cellWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(faceHeight, View.MeasureSpec.EXACTLY)
            )
            face.layout(0, 0, cellWidth, faceHeight)
            face.setExpression(item.decision, 77L)
            // Let the 360ms parameter transition and 260ms silhouette crossfade settle.
            SystemClock.sleep(420L)
            val rendered = Bitmap.createBitmap(cellWidth, faceHeight, Bitmap.Config.ARGB_8888)
            face.draw(Canvas(rendered))

            val col = index % columns
            val row = index / columns
            val x = col * cellWidth
            val y = row * cellHeight
            sheetCanvas.drawBitmap(rendered, x.toFloat(), y.toFloat(), null)
            sheetCanvas.drawText(item.label, x + cellWidth / 2f, y + faceHeight + 31f, caption)

            var minX = cellWidth
            var minY = faceHeight
            var maxX = -1
            var maxY = -1
            var cyanPixels = 0
            var hash = -3750763034362895579L // FNV-1a offset basis, signed in Kotlin
            for (py in 0 until faceHeight) {
                for (px in 0 until cellWidth) {
                    val pixel = rendered.getPixel(px, py)
                    val red = Color.red(pixel)
                    val green = Color.green(pixel)
                    val blue = Color.blue(pixel)
                    val isLed = green > 75 && blue > 75 && green.toFloat() > red.toFloat() * 1.25f
                    hash = (hash xor if (isLed) 1L else 0L) * 1099511628211L
                    if (isLed) {
                        cyanPixels++
                        minX = minOf(minX, px)
                        minY = minOf(minY, py)
                        maxX = maxOf(maxX, px)
                        maxY = maxOf(maxY, py)
                    }
                }
            }
            assertTrue("${item.label} rendered too few cyan LED pixels: $cyanPixels", cyanPixels >= 80)
            assertTrue("${item.label} did not render both eye regions: bbox=${minX},${minY}-${maxX},${maxY}", maxX - minX >= 45)
            // Cozmo's high/low blink is deliberately a narrow horizontal LED slit and may
            // rasterize to a single pixel row at this gallery scale. Other expressions need
            // measurable height so a broken/empty shape cannot pass as a valid glyph.
            val minimumHeightPixels = when (item.expected) {
                EyeSilhouette.BLINK_HIGH, EyeSilhouette.BLINK_LOW -> 0
                else -> 2
            }
            assertTrue(
                "${item.label} has no measurable eye height: bbox=${minX},${minY}-${maxX},${maxY}; " +
                    "minimumHeightPixels=$minimumHeightPixels",
                maxY - minY >= minimumHeightPixels
            )
            fingerprints[item.label] = hash
            boundsReport += "${item.label}:pixels=$cyanPixels,bbox=${minX},${minY}-${maxX},${maxY},glyph=${profile.silhouette}"
            rendered.recycle()
        }

        val uniqueMasks = fingerprints.values.toSet().size
        assertTrue(
            "Too many expressions render the same binary pixel mask; unique=$uniqueMasks/24; $boundsReport",
            uniqueMasks >= 22
        )

        val outDir = requireNotNull(context.getExternalFilesDir(null)) {
            "External app-specific storage is not available to save the rendered expression audit."
        }
        val output = File(outDir, "eye-expression-contact-sheet.png")
        FileOutputStream(output).use { stream ->
            assertTrue("Could not encode eye-expression contact sheet.", sheet.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        sheet.recycle()

        val marker = "EYE_RENDER_GALLERY_PASS glyphs=${specs.size} uniqueBinaryMasks=$uniqueMasks path=${output.absolutePath}"
        Log.i("EVE_TEST", marker)
        System.out.println(marker)
        boundsReport.forEach { Log.i("EVE_EYE_BOUNDS", it) }
    }
}
