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
        frustration: Float = 0f,
        jealousy: Float = 0f,
        pride: Float = 0f,
        reassurance: Float = 0f
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
            frustration = frustration,
            jealousy = jealousy,
            pride = pride,
            reassurance = reassurance
        ),
        expected
    )

    @Test
    fun rendersAllReferenceInspiredGlyphsAndExportsContactSheet() {
        val specs = listOf(
            spec("Neutral", EveMood.NEUTRAL, EyeSilhouette.ALMOND, reason = "neutral"),
            spec("Awake", EveMood.AWAKE, EyeSilhouette.NEUTRAL, reason = "awake"),
            spec("Curious", EveMood.CURIOUS, EyeSilhouette.CURIOUS, reason = "curiosity"),
            spec("Blink high", EveMood.NEUTRAL, EyeSilhouette.BLINK_HIGH, reason = "blink high", arousal = 0.10f),
            spec("Happy", EveMood.HAPPY, EyeSilhouette.SMILE, reason = "happy", valence = 0.25f, arousal = 0.75f),
            spec("Glee", EveMood.HAPPY, EyeSilhouette.GLEE, reason = "happy", valence = 0.85f, arousal = 0.85f),
            spec("Proud", EveMood.PLAYFUL, EyeSilhouette.PROUD, reason = "proud", valence = 0.70f, arousal = 0.72f, pride = 0.95f),
            spec("Blink low", EveMood.NEUTRAL, EyeSilhouette.BLINK_LOW, reason = "blink low", fatigue = 0.80f),
            spec("Sad down", EveMood.CONCERNED, EyeSilhouette.SAD_DOWN, reason = "sad down", valence = -0.70f, fatigue = 0.70f),
            spec("Sad up", EveMood.CONCERNED, EyeSilhouette.SAD_UP, reason = "sad up", valence = -0.70f, fatigue = 0.10f),
            spec("Worried", EveMood.CONCERNED, EyeSilhouette.WORRIED, reason = "worry", valence = 0.08f, arousal = 0.65f),
            spec("Focused", EveMood.FOCUSED, EyeSilhouette.FOCUSED, reason = "focus", arousal = 0.65f),
            spec("Annoyed", EveMood.IRRITATED, EyeSilhouette.ANNOYED, reason = "annoyed", arousal = 0.65f),
            spec("Surprised", EveMood.SURPRISED, EyeSilhouette.ROUND_SURPRISE, reason = "surprised", valence = 0.12f),
            spec("Skeptic", EveMood.PLAYFUL, EyeSilhouette.SKEPTIC, reason = "skeptic", valence = 0.0f),
            spec("Bored", EveMood.IRRITATED, EyeSilhouette.BORED, reason = "bored", arousal = 0.18f),
            spec("Unimpressed", EveMood.CONCERNED, EyeSilhouette.UNIMPRESSED, reason = "unimpressed", arousal = 0.20f),
            spec("Sleepy", EveMood.SLEEPY, EyeSilhouette.DROOPY, reason = "sleepy", fatigue = 0.30f),
            spec("Suspicious", EveMood.CURIOUS, EyeSilhouette.SUSPICIOUS, reason = "suspicious", valence = -0.50f, curiosity = 0.92f),
            spec("Jealous", EveMood.IRRITATED, EyeSilhouette.JEALOUS, reason = "jealous", valence = -0.25f, arousal = 0.45f, jealousy = 0.95f),
            spec("Squint", EveMood.FOCUSED, EyeSilhouette.SQUINT, reason = "low focus", arousal = 0.10f),
            spec("Angry", EveMood.ANGRY, EyeSilhouette.SHARP, reason = "angry", arousal = 0.50f, frustration = 0.20f),
            spec("Furious", EveMood.ANGRY, EyeSilhouette.FURIOUS, reason = "angry", arousal = 0.88f, frustration = 0.88f),
            spec("Scared", EveMood.CONCERNED, EyeSilhouette.SCARED, reason = "fearful", valence = -0.50f, arousal = 0.85f),
            spec("Awe", EveMood.SURPRISED, EyeSilhouette.AWE, reason = "surprised", valence = 0.85f, arousal = 0.86f),
            spec("Playful", EveMood.PLAYFUL, EyeSilhouette.PLAYFUL, reason = "playful", valence = 0.40f),
            spec("Warm", EveMood.FAMILIAR, EyeSilhouette.SOFT, reason = "familiar", valence = 0.25f),
            spec("Affectionate", EveMood.FAMILIAR, EyeSilhouette.AFFECTIONATE, reason = "affection", valence = 0.45f, reassurance = 0.95f),
            spec(
                "Confused", EveMood.CONCERNED, EyeSilhouette.CONFUSED,
                reason = "confused", valence = -0.05f, arousal = 0.40f, frustration = 0.10f
            ),
            spec(
                "Anticipating", EveMood.CURIOUS, EyeSilhouette.ANTICIPATING,
                reason = "anticipating", valence = 0.32f, arousal = 0.66f, curiosity = 0.78f
            ),
            spec(
                "Disappointed", EveMood.CONCERNED, EyeSilhouette.DISAPPOINTED,
                reason = "disappointed", valence = -0.40f, arousal = 0.40f, fatigue = 0.12f
            ),
            Spec(
                "Dizzy",
                EveDecision(EveMood.CONCERNED, "confirmed strong physical shake", valence = -0.20f, arousal = 0.90f),
                EyeSilhouette.DIZZY
            )
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
            val profile = if (item.expected == EyeSilhouette.DIZZY) {
                EyeExpressionGenerator.generateDizzy(item.decision)
            } else {
                EyeExpressionGenerator.generate(item.decision, 77L)
            }
            assertEquals("Wrong glyph selected for ${item.label}", item.expected, profile.silhouette)

            val face = RobotFaceView(context)
            face.measure(
                View.MeasureSpec.makeMeasureSpec(cellWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(faceHeight, View.MeasureSpec.EXACTLY)
            )
            face.layout(0, 0, cellWidth, faceHeight)
            if (item.expected == EyeSilhouette.DIZZY) {
                face.showDizzyReaction(4000L)
            } else {
                face.setExpression(item.decision, 77L)
            }
            // Let parameter interpolation settle. The renderer draws one silhouette per frame.
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
            "Too many expressions render the same binary pixel mask; unique=$uniqueMasks/${specs.size}; $boundsReport",
            uniqueMasks == specs.size
        )

        val output = File(context.filesDir, "eye-expression-contact-sheet.png")
        FileOutputStream(output).use { stream ->
            assertTrue("Could not encode eye-expression contact sheet.", sheet.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }

        // Duplicate the report to app-specific external storage as a fallback for emulators
        // whose run-as transport intermittently cannot read a just-written private PNG.
        val externalOutput = context.getExternalFilesDir(null)?.let {
            File(it, "eye-expression-contact-sheet.png")
        }
        if (externalOutput != null) {
            try {
                FileOutputStream(externalOutput).use { stream ->
                    assertTrue(
                        "Could not encode external eye-expression contact sheet.",
                        sheet.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    )
                }
            } catch (error: Exception) {
                Log.w("EVE_TEST", "Could not duplicate eye gallery to external app storage.", error)
            }
        }
        sheet.recycle()

        val marker = "EYE_RENDER_GALLERY_PASS glyphs=${specs.size} uniqueBinaryMasks=$uniqueMasks path=${output.absolutePath}"
        Log.i("EVE_TEST", marker)
        System.out.println(marker)
        boundsReport.forEach { Log.i("EVE_EYE_BOUNDS", it) }
    }
}
