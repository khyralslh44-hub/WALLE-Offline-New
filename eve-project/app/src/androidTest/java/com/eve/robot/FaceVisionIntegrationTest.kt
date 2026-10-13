package com.eve.robot

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Runs the actual bundled ML Kit model inside an Android emulator with emulator networking off.
 * The fixture exists only in androidTest/assets and is not included in the production APK.
 */
@RunWith(AndroidJUnit4::class)
class FaceVisionIntegrationTest {
    @Test
    fun bundledFaceDetectorFindsFaceAndFeedsAffectAndEyeExpression() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val bitmap = testContext.assets.open("face_smile.png").use { BitmapFactory.decodeStream(it) }
        assertNotNull("Pinned face fixture could not be decoded", bitmap)
        requireNotNull(bitmap)
        assertTrue("Fixture is too small for meaningful face analysis", bitmap.width >= 320 && bitmap.height >= 240)

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
        val detector = FaceDetection.getClient(options)
        try {
            val faces = Tasks.await(
                detector.process(InputImage.fromBitmap(bitmap, 0)),
                30,
                TimeUnit.SECONDS
            )
            assertFalse("Bundled face model detected no face in the known face fixture", faces.isEmpty())
            val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?: throw AssertionError("Face list unexpectedly became empty")
            assertTrue("Detected face bounding box is implausibly small", face.boundingBox.width() >= 40)

            val smile = face.smilingProbability
            val leftEye = face.leftEyeOpenProbability
            val rightEye = face.rightEyeOpenProbability
            assertNotNull("Face classification did not return a smile probability", smile)
            assertNotNull("Face classification did not return left-eye probability", leftEye)
            assertNotNull("Face classification did not return right-eye probability", rightEye)

            val yaw = face.headEulerAngleY
            val smoother = EveFaceCueSmoother(windowSize = 3, requiredVotes = 2)
            val rawCue = EveFaceCue(smile, leftEye, rightEye, yaw)
            assertEquals("A single noisy camera frame must not trigger an expression", null, smoother.add(rawCue))
            val stableCue = smoother.add(rawCue)
                ?: throw AssertionError("Two matching face frames did not produce a stable cue")
            val decision = RobotBrain().observeHumanFace(
                stableCue.smilingProbability,
                stableCue.leftEyeOpenProbability,
                stableCue.rightEyeOpenProbability,
                stableCue.headYawDegrees
            )
            val profile = EyeExpressionGenerator.generate(decision, 0xE7E123L)

            assertEquals("Expression engine must receive the same mood as the affect decision", decision.mood, profile.mood)
            assertTrue(decision.valence in -1f..1f)
            assertTrue(decision.arousal in 0f..1f)
            assertTrue(decision.curiosity in 0f..1f)
            if (kotlin.math.abs(yaw) > 22f) {
                assertEquals(EveMood.CURIOUS, decision.mood)
                assertFalse("A turned-away face must not trigger voice", decision.playSound)
            } else if (smile != null && smile >= 0.72f) {
                assertEquals(EveMood.HAPPY, decision.mood)
            } else if (leftEye != null && rightEye != null && (leftEye + rightEye) / 2f < 0.22f) {
                assertEquals(EveMood.FOCUSED, decision.mood)
                assertFalse("A blink-like image must not trigger voice", decision.playSound)
            }

            Log.i(
                "EVE_TEST",
                "FACE_FIXTURE_PASS faces=${faces.size} bounds=${face.boundingBox.width()}x${face.boundingBox.height()} " +
                    "smile=$smile leftEye=$leftEye rightEye=$rightEye yaw=$yaw mood=${decision.mood} " +
                    "playSound=${decision.playSound} expression=$profile"
            )
        } finally {
            detector.close()
            bitmap.recycle()
        }
    }

    @Test
    fun uncertainFaceEvidenceDoesNotCreateConfidentEmotionOrVoice() {
        val uncertainInputs = listOf(
            arrayOf<Float?>(null, null, null, 0f),
            arrayOf<Float?>(0.31f, 0.78f, 0.81f, 0f),
            arrayOf<Float?>(0.18f, 0.09f, 0.10f, 0f),
            arrayOf<Float?>(0.99f, 0.98f, 0.98f, 35f)
        )
        uncertainInputs.forEach { input ->
            val decision = RobotBrain().observeHumanFace(
                input[0], input[1], input[2], input[3] ?: 0f
            )
            assertFalse("Uncertain or turned-away evidence triggered voice: $decision", decision.playSound)
        }
    }
}
