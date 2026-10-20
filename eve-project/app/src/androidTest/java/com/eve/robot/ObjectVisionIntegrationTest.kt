package com.eve.robot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the production offline object detector on a pinned human portrait.
 * This is deliberately separate from facial-expression detection: locating a face or
 * estimating a facial expression is not the same as recognizing the object in the scene.
 */
@RunWith(AndroidJUnit4::class)
class ObjectVisionIntegrationTest {
    @Test
    fun bundledEfficientDetFindsPersonInPinnedSceneWithoutNetwork() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val decoded = requireNotNull(
            testContext.assets.open("face_fixture.jpg").use { BitmapFactory.decodeStream(it) }
        ) { "Pinned portrait fixture could not be decoded." }

        val longestSide = maxOf(decoded.width, decoded.height)
        val scale = minOf(1f, 640f / longestSide.toFloat())
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            decoded
        }

        val detector = EveLocalObjectDetector(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
        try {
            assertTrue(
                "The app APK is missing efficientdet_lite0.tflite or MediaPipe could not initialize it.",
                detector.isReady
            )
            Log.i("EVE_OBJECT_TEST", "OBJECT_MODEL_PROCESS_START input=${bitmap.width}x${bitmap.height} offline=true")
            val objects = detector.detect(bitmap)
            Log.i("EVE_OBJECT_TEST", "OBJECT_MODEL_RESULT $objects")
            assertFalse("EfficientDet returned no object boxes for the known portrait.", objects.isEmpty())
            assertTrue(
                "The detector did not recognize the person in the pinned portrait: $objects",
                objects.any { it.label == "person" && it.confidence >= EveLocalObjectDetector.DEFAULT_SCORE_THRESHOLD }
            )
            assertTrue(
                "Known human-only portrait produced an animal box; this is the person/animal confusion to prevent: $objects",
                objects.none { EveVisionEvidencePolicy.isAnimalLabel(it.label) }
            )
            assertTrue("Detector returned an invalid score.", objects.all { it.confidence.isFinite() && it.confidence in 0f..1f })
            assertTrue("Detector returned invalid box area.", objects.all { it.areaFraction.isFinite() && it.areaFraction in 0f..1f })
            System.out.println("OBJECT_MODEL_PASS source=efficientdet_lite0 result=$objects offline=true")
        } finally {
            detector.close()
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
    }

    @Test
    fun wholeImageAnimalGuessCannotOverrideKnownPersonWithoutIndependentAnimalBox() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val decoded = requireNotNull(
            testContext.assets.open("face_fixture.jpg").use { BitmapFactory.decodeStream(it) }
        ) { "Pinned portrait fixture could not be decoded." }
        val longestSide = maxOf(decoded.width, decoded.height)
        val scale = minOf(1f, 640f / longestSide.toFloat())
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else decoded

        val detector = EveLocalObjectDetector(InstrumentationRegistry.getInstrumentation().targetContext)
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        try {
            assertTrue("Offline object detector is unavailable.", detector.isReady)
            val objectBoxes = detector.detect(bitmap)
            assertTrue("Expected a person box in the human portrait: $objectBoxes", objectBoxes.any { it.label == "person" })
            val animalBoxes = objectBoxes.filter { EveVisionEvidencePolicy.isAnimalLabel(it.label) }
            assertTrue("Human-only portrait produced uncorroborated animal boxes: $animalBoxes", animalBoxes.isEmpty())

            val labels = Tasks.await(
                labeler.process(InputImage.fromBitmap(bitmap, 0)),
                90,
                java.util.concurrent.TimeUnit.SECONDS
            )
            val top = labels.maxByOrNull { it.confidence }
            if (top != null && EveVisionEvidencePolicy.isAnimalLabel(top.text)) {
                val suppress = EveVisionEvidencePolicy.shouldSuppressAnimalLabel(
                    label = top.text,
                    personFaceRecentlyDetected = true,
                    freshObjectLabels = objectBoxes.map { it.label }.toSet()
                )
                assertTrue(
                    "Top whole-image label '${top.text}' (${top.confidence}) was accepted as an animal " +
                        "although the pinned fixture is a person portrait and there is no animal box.",
                    suppress
                )
            }
            val marker = "PERSON_ANIMAL_CONFLICT_PASS topLabel=${top?.text ?: "none"} " +
                "confidence=${top?.confidence ?: 0f} objectBoxes=$objectBoxes offline=true"
            Log.i("EVE_TEST", marker)
            System.out.println(marker)
        } finally {
            labeler.close()
            detector.close()
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
    }

    @Test
    fun genericTextureAndBackgroundLabelsAreRejected() {
        val noise = setOf("pattern", "texture", "background", "wall", "floor", "ceiling", "???")
        assertTrue(
            "Generic scene/texture labels must not be promoted into detected objects.",
            noise.all(EveLocalObjectDetector::isSceneNoiseLabel)
        )
        assertFalse(EveLocalObjectDetector.isSceneNoiseLabel("person"))
        assertFalse(EveLocalObjectDetector.isSceneNoiseLabel("cup"))
    }
}
