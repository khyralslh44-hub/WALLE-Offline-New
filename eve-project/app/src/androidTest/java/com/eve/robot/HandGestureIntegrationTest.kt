package com.eve.robot

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a pinned, known thumb-up image to verify the bundled offline model and reaction path. */
@RunWith(AndroidJUnit4::class)
class HandGestureIntegrationTest {
    @Test
    fun offlineThumbUpImageBecomesAHappyEyeExpression() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = requireNotNull(
            instrumentation.context.assets.open("gesture_thumb_up.jpg").use { input ->
                BitmapFactory.decodeStream(input)
            }
        ) { "Pinned thumb-up fixture could not be decoded." }

        val targetContext = instrumentation.targetContext
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("gesture_recognizer.task")
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setNumHands(1)
            .build()

        val recognizer = GestureRecognizer.createFromOptions(targetContext, options)
        try {
            val result = recognizer.recognize(BitmapImageBuilder(fixture).build())
            val points = result.landmarks().firstOrNull().orEmpty()
            val category = result.gestures().firstOrNull()?.firstOrNull()
            assertNotNull("MediaPipe found no hand category in the known thumb-up fixture.", category)
            val handCategory = requireNotNull(category)
            val label = handCategory.categoryName()
            val confidence = handCategory.score()
            assertTrue(
                "The pinned thumbs-up image should map to the model's like/thumb-up class, got '$label'.",
                label.trim().lowercase().replace(' ', '_') in setOf("thumb_up", "thumbup", "thumbs_up", "like")
            )
            assertTrue(
                "Thumb-up confidence $confidence is below the app's production threshold.",
                confidence >= 0.68f
            )
            assertTrue("MediaPipe returned too few hand landmarks.", points.size >= 10)

            val xs = points.map { it.x() }.filter { it.isFinite() }
            val ys = points.map { it.y() }.filter { it.isFinite() }
            assertTrue("Hand landmark coordinates were invalid.", xs.isNotEmpty() && ys.isNotEmpty())
            val centerX = xs.average().toFloat().coerceIn(0f, 1f)
            val span = maxOf(xs.maxOrNull()!! - xs.minOrNull()!!, ys.maxOrNull()!! - ys.minOrNull()!!)
                .coerceIn(0f, 1f)

            // The production temporal gate requires two stable frames before acting.
            val interpreter = HandGestureInterpreter()
            assertEquals(null, interpreter.observe(label, confidence, centerX, span, 1_000L))
            val signal = interpreter.observe(label, confidence, centerX, span, 1_200L)
            assertEquals("thumb_up", signal)

            val decision = RobotBrain().observeHandGesture(requireNotNull(signal), confidence)
            assertEquals(EveMood.HAPPY, decision.mood)
            assertTrue("A positive gesture should request one restrained reaction cue.", decision.playSound)
            val expression = EyeExpressionGenerator.generate(decision, 0x7A11L)
            assertEquals(EveMood.HAPPY, expression.mood)
            assertEquals(EyeSilhouette.SMILE, expression.silhouette)

            val marker = "HAND_GESTURE_FIXTURE_PASS label=$label confidence=$confidence " +
                "signal=$signal mood=${decision.mood} silhouette=${expression.silhouette}"
            Log.i("EVE_TEST", marker)
            System.out.println(marker)
        } finally {
            recognizer.close()
            fixture.recycle()
        }
    }
}
