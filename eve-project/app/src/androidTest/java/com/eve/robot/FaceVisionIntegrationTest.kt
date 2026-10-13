package com.eve.robot

import android.graphics.Bitmap
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
        val decodedBitmap = requireNotNull(
            testContext.assets.open("face_smile.png").use { BitmapFactory.decodeStream(it) }
        ) { "Pinned face fixture could not be decoded" }
        assertTrue(
            "Fixture is too small for meaningful face analysis",
            decodedBitmap.width >= 320 && decodedBitmap.height >= 240
        )
        // Some downloaded fixtures are multi-megapixel photos. A 640px maximum edge is enough
        // to verify the face detector but avoids pathological CPU-only emulator runtimes.
        val largestSide = maxOf(decodedBitmap.width, decodedBitmap.height)
        val scale = minOf(1f, 640f / largestSide.toFloat())
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decodedBitmap,
                (decodedBitmap.width * scale).toInt().coerceAtLeast(1),
                (decodedBitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            decodedBitmap
        }

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
        val detector = FaceDetection.getClient(options)
        try {
            Log.i(
                "EVE_TEST",
                "FACE_MODEL_PROCESS_START original=${decodedBitmap.width}x${decodedBitmap.height} " +
                    "input=${bitmap.width}x${bitmap.height} offline=true"
            )
            val faceTask = detector.process(InputImage.fromBitmap(bitmap, 0))
            faceTask
                .addOnSuccessListener { found ->
                    Log.i("EVE_TEST", "FACE_MODEL_TASK_SUCCESS count=${found.size}")
                }
                .addOnFailureListener { error ->
                    Log.e("EVE_TEST", "FACE_MODEL_TASK_FAILURE", error)
                }
            val faces = try {
                // Bundled ML Kit initialization on a cold, software-rendered emulator can be slow.
                Tasks.await(faceTask, 90, TimeUnit.SECONDS)
            } catch (error: Exception) {
                Log.e("EVE_TEST", "FACE_MODEL_TASK_DID_NOT_COMPLETE", error)
                throw error
            }
            assertFalse("Bundled face model detected no face in the known face fixture", faces.isEmpty())
            val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?: throw AssertionError("Face list unexpectedly became empty")
            assertTrue("Detected face bounding box is implausibly small", face.boundingBox.width() >= 40)

            // Exercise the actual bundled TFLite facial-expression model on the detected face crop.
            // This proves inference is wired into the emotion state and expression generator, not only
            // that a Kotlin class with a model name exists.
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val fer = EveFacialEmotionClassifier(appContext)
            try {
                assertTrue(
                    "The APK is missing eve_emotion_model.tflite or the model tensor contract is wrong.",
                    fer.isReady
                )
                val bounds = face.boundingBox
                val padX = (bounds.width() * 0.10f).toInt().coerceAtLeast(2)
                val padY = (bounds.height() * 0.10f).toInt().coerceAtLeast(2)
                val left = (bounds.left - padX).coerceIn(0, bitmap.width - 1)
                val top = (bounds.top - padY).coerceIn(0, bitmap.height - 1)
                val right = (bounds.right + padX).coerceIn(left + 1, bitmap.width)
                val bottom = (bounds.bottom + padY).coerceIn(top + 1, bitmap.height)
                val faceCrop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                val rawPrediction = try {
                    fer.predict(faceCrop)
                } finally {
                    faceCrop.recycle()
                }
                assertNotNull("TFLite FER model returned no prediction for the detected face.", rawPrediction)
                requireNotNull(rawPrediction)
                assertTrue("Unexpected FER output label: ${rawPrediction.label}", rawPrediction.label in EveFacialEmotionClassifier.LABELS)
                assertTrue("FER confidence is not finite/in-range.", rawPrediction.confidence.isFinite() && rawPrediction.confidence in 0f..1f)

                val emotionSmoother = EveEmotionSmoother(windowSize = 3, requiredVotes = 2)
                assertEquals("A single prediction must not change emotion state.", null, emotionSmoother.add(rawPrediction))
                val stableEmotion = emotionSmoother.add(rawPrediction)
                    ?: throw AssertionError("Two matching FER predictions did not produce a stable emotion.")
                val modelDecision = RobotBrain().observeFacialEmotion(stableEmotion.label, stableEmotion.confidence)
                val modelExpression = EyeExpressionGenerator.generate(modelDecision, 0xF3A123L)
                assertEquals("FER affect decision and generated face mood diverged.", modelDecision.mood, modelExpression.mood)
                assertTrue(modelDecision.valence in -1f..1f)
                assertTrue(modelDecision.arousal in 0f..1f)
                assertTrue(modelDecision.curiosity in 0f..1f)
                Log.i(
                    "EVE_TEST",
                    "FER_MODEL_PASS label=${stableEmotion.label} confidence=${stableEmotion.confidence} " +
                        "mood=${modelDecision.mood} profile=$modelExpression"
                )
            } finally {
                fer.close()
            }

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
            if (bitmap !== decodedBitmap) bitmap.recycle()
            decodedBitmap.recycle()
        }
    }

    @Test
    fun positiveReinforcementPersistsOfflineAndChangesLaterObservation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("eve_local_memory", android.content.Context.MODE_PRIVATE)
        preferences.edit()
            .remove("affinity:dog")
            .remove("feedback:dog")
            .commit()
        try {
            val memory = EveMemory(context)
            val before = memory.learnedAffinity("dog")
            val first = memory.reinforce("dog", 1f)
            val second = memory.reinforce("dog", 1f)
            val third = memory.reinforce("dog", 1f)
            assertTrue(first > before)
            assertTrue(second > first)
            assertTrue(third > second)
            assertEquals(3, memory.feedbackCount("dog"))

            val afterRestart = EveMemory(context).learnedAffinity("dog")
            assertEquals(third, afterRestart, 0.0001f)
            val base = RobotBrain().observe("dog", 0.91f, 2, before)
            val adapted = RobotBrain().observe("dog", 0.91f, 2, afterRestart)
            assertTrue(adapted.valence > base.valence)
            assertEquals(EveMood.FAMILIAR, adapted.mood)
            Log.i("EVE_TEST", "OFFLINE_AFFECT_LEARNING_PASS old=$before learned=$afterRestart")
        } finally {
            preferences.edit().remove("affinity:dog").remove("feedback:dog").commit()
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
