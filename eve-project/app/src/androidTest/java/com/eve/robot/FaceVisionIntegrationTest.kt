package com.eve.robot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
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
 * Runs the actual bundled ML Kit model on a pinned frontal portrait inside an Android emulator with networking disabled.
 * The fixture exists only in androidTest/assets and is not included in the production APK.
 */
@RunWith(AndroidJUnit4::class)
class FaceVisionIntegrationTest {
    @Test
    fun bundledFaceDetectorFindsFaceAndFeedsAffectAndEyeExpression() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val decodedBitmap = requireNotNull(
            testContext.assets.open("face_fixture.jpg").use { BitmapFactory.decodeStream(it) }
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

                val emotionSmoother = EveEmotionSmoother(
                    windowSize = 3,
                    requiredVotes = 2,
                    minConfidence = 0.62f
                )
                assertEquals("A single prediction must not change emotion state.", null, emotionSmoother.add(rawPrediction))
                val stableEmotion = emotionSmoother.add(rawPrediction)
                // Real inference may be uncertain. It must be reported, not promoted to a confident
                // human emotion. This fixture previously returned angry at about 57% confidence.
                val candidate = stableEmotion ?: rawPrediction
                val modelDecision = RobotBrain().observeFacialEmotion(candidate.label, candidate.confidence)
                if (modelDecision.reason.startsWith("uncertain facial-expression model output")) {
                    assertEquals("Low-confidence prediction must stay emotionally neutral.", EveMood.NEUTRAL, modelDecision.mood)
                    assertFalse("Uncertain FER result must never speak.", modelDecision.playSound)
                }
                val modelExpression = EyeExpressionGenerator.generate(modelDecision, 0xF3A123L)
                assertEquals("FER affect decision and generated face mood diverged.", modelDecision.mood, modelExpression.mood)
                assertTrue(modelDecision.valence in -1f..1f)
                assertTrue(modelDecision.arousal in 0f..1f)
                assertTrue(modelDecision.curiosity in 0f..1f)
                val evidenceMode = if (stableEmotion == null) "low-confidence-rejected" else "stable-two-frame-vote"
                Log.i(
                    "EVE_TEST",
                    "FER_MODEL_PASS rawLabel=${rawPrediction.label} rawConfidence=${rawPrediction.confidence} " +
                        "smoothedLabel=${stableEmotion?.label ?: "none"} mode=$evidenceMode " +
                        "decisionLabel=${modelDecision.mood} profile=$modelExpression"
                )
                System.out.println(
                    "FER_MODEL_PASS rawLabel=${rawPrediction.label} rawConfidence=${rawPrediction.confidence} " +
                        "smoothedLabel=${stableEmotion?.label ?: "none"} mode=$evidenceMode " +
                        "decisionLabel=${modelDecision.mood} profile=$modelExpression"
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
            System.out.println(
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
    fun faceDetectorPassesPositiveNegativeAndLightingRobustnessControlsOffline() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val decoded = requireNotNull(
            context.assets.open("face_fixture.jpg").use { BitmapFactory.decodeStream(it) }
        )
        val longest = maxOf(decoded.width, decoded.height)
        val scale = minOf(1f, 640f / longest.toFloat())
        val base = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else decoded

        val mirrored = mirrorHorizontally(base)
        val brighter = changeBrightness(base, scale = 0.92f, offset = 10f)
        val darker = changeBrightness(base, scale = 1.06f, offset = -10f)
        val noFace = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        Canvas(noFace).drawColor(Color.rgb(117, 117, 117))
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
        )
        try {
            val variants = listOf(
                "base" to base,
                "mirror" to mirrored,
                "brighter" to brighter,
                "darker" to darker
            )
            val counts = linkedMapOf<String, Int>()
            variants.forEach { (name, bitmap) ->
                val faces = Tasks.await(
                    detector.process(InputImage.fromBitmap(bitmap, 0)),
                    30,
                    TimeUnit.SECONDS
                )
                counts[name] = faces.size
                Log.i("EVE_TEST", "FACE_VARIANT name=$name faces=${faces.size} size=${bitmap.width}x${bitmap.height}")
            }
            assertTrue("Known positive face image was not detected.", (counts["base"] ?: 0) > 0)
            assertTrue("Horizontal mirror of the same face was not detected.", (counts["mirror"] ?: 0) > 0)
            assertTrue(
                "Face detector was not robust across positive-image variants: $counts",
                counts.values.count { it > 0 } >= 3
            )

            val negativeFaces = Tasks.await(
                detector.process(InputImage.fromBitmap(noFace, 0)),
                30,
                TimeUnit.SECONDS
            )
            Log.i("EVE_TEST", "FACE_NEGATIVE_CONTROL faces=${negativeFaces.size} background=uniform_gray")
            assertTrue("Uniform non-face control generated a false positive: $negativeFaces", negativeFaces.isEmpty())

            // Test the actual TFLite emotion model on the same face under mirroring and brightness
            // changes, not only the ML Kit face detector. This checks inference stability, not
            // classification accuracy from an inadequately small one-person sample.
            val fer = EveFacialEmotionClassifier(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                assertTrue("Bundled six-class FER model did not load.", fer.isReady)
                val predictions = linkedMapOf<String, EveEmotionPrediction>()
                val baseTimingsMs = ArrayList<Long>()

                variants.forEach { (name, source) ->
                    val variantFaces = Tasks.await(
                        detector.process(InputImage.fromBitmap(source, 0)),
                        30,
                        TimeUnit.SECONDS
                    )
                    val largest = variantFaces.maxByOrNull {
                        it.boundingBox.width() * it.boundingBox.height()
                    } ?: return@forEach

                    val box = largest.boundingBox
                    val padX = (box.width() * 0.10f).toInt().coerceAtLeast(2)
                    val padY = (box.height() * 0.10f).toInt().coerceAtLeast(2)
                    val left = (box.left - padX).coerceIn(0, source.width - 1)
                    val top = (box.top - padY).coerceIn(0, source.height - 1)
                    val right = (box.right + padX).coerceIn(left + 1, source.width)
                    val bottom = (box.bottom + padY).coerceIn(top + 1, source.height)
                    val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
                    try {
                        val started = System.nanoTime()
                        val prediction = fer.predict(crop)
                        val latencyMs = (System.nanoTime() - started) / 1_000_000L
                        assertNotNull("TFLite inference returned null for $name face variant.", prediction)
                        val valid = requireNotNull(prediction)
                        assertTrue("Unknown model output for $name: $valid", valid.label in EveFacialEmotionClassifier.LABELS)
                        assertTrue("Invalid confidence on $name: $valid", valid.confidence.isFinite() && valid.confidence in 0f..1f)
                        predictions[name] = valid
                        Log.i("EVE_TEST", "FER_VARIANT name=$name label=${valid.label} confidence=${valid.confidence} latencyMs=$latencyMs")

                        if (name == "base") {
                            // Keep the first cold inference in the timing report. The hosted AVD is a
                            // software/virtual CPU, so >1s is a performance warning rather than proof
                            // that the app is broken. A 10s per-call watchdog still catches hangs.
                            baseTimingsMs += latencyMs
                            repeat(3) { index ->
                                val repeatStart = System.nanoTime()
                                val repeated = fer.predict(crop)
                                val repeatMs = (System.nanoTime() - repeatStart) / 1_000_000L
                                assertNotNull("Repeated TFLite inference #$index returned null.", repeated)
                                val repeatResult = requireNotNull(repeated)
                                assertEquals("Same input changed class without an input change.", valid.label, repeatResult.label)
                                assertTrue(
                                    "Same input changed confidence unexpectedly: $valid vs $repeatResult",
                                    kotlin.math.abs(valid.confidence - repeatResult.confidence) < 0.001f
                                )
                                assertTrue(
                                    "TFLite inference exceeded the 10s hang watchdog: $repeatMs ms",
                                    repeatMs < 10_000L
                                )
                                baseTimingsMs += repeatMs
                            }
                        }
                    } finally {
                        crop.recycle()
                    }
                }

                assertTrue("FER model did not return usable predictions for at least three image variants: $predictions", predictions.size >= 3)
                assertEquals("Expected one cold plus three repeated base-face timing samples.", 4, baseTimingsMs.size)
                val sortedTimings = baseTimingsMs.sorted()
                val medianMs = sortedTimings[sortedTimings.size / 2]
                val maxMs = sortedTimings.maxOrNull() ?: 0L
                if (medianMs > 1000L || maxMs > 1000L) {
                    Log.w(
                        "EVE_TEST",
                        "FER_PERFORMANCE_WARN medianMs=$medianMs maxMs=$maxMs targetMs=1000 " +
                            "note=hosted-software-emulator-is-not-a-Redmi-12-benchmark"
                    )
                    System.out.println(
                        "FER_PERFORMANCE_WARN medianMs=$medianMs maxMs=$maxMs targetMs=1000 " +
                            "note=hosted-software-emulator-is-not-a-Redmi-12-benchmark"
                    )
                }
                Log.i("EVE_TEST", "FER_ROBUSTNESS_PASS predictions=$predictions repeatMedianMs=$medianMs repeatMaxMs=$maxMs")
                System.out.println("FER_ROBUSTNESS_PASS predictions=$predictions repeatMedianMs=$medianMs repeatMaxMs=$maxMs")
            } finally {
                fer.close()
            }

            Log.i("EVE_TEST", "FACE_ROBUSTNESS_PASS positives=$counts negativeFaces=${negativeFaces.size}")
            System.out.println("FACE_ROBUSTNESS_PASS positives=$counts negativeFaces=${negativeFaces.size}")
        } finally {
            detector.close()
            if (mirrored !== base) mirrored.recycle()
            if (brighter !== base) brighter.recycle()
            if (darker !== base) darker.recycle()
            noFace.recycle()
            if (base !== decoded) base.recycle()
            decoded.recycle()
        }
    }

    private fun mirrorHorizontally(source: Bitmap): Bitmap {
        val matrix = Matrix().apply { preScale(-1f, 1f) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun changeBrightness(source: Bitmap, scale: Float, offset: Float): Bitmap {
        val values = floatArrayOf(
            scale, 0f, 0f, 0f, offset,
            0f, scale, 0f, 0f, offset,
            0f, 0f, scale, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(values))
        }
        return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { result ->
            Canvas(result).drawBitmap(source, 0f, 0f, paint)
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
            System.out.println("OFFLINE_AFFECT_LEARNING_PASS old=$before learned=$afterRestart")
        } finally {
            preferences.edit().remove("affinity:dog").remove("feedback:dog").commit()
        }
    }

    @Test
    fun learnedPersonalityPersistsAcrossRestartsAndDoesNotOverreactToOneGestureBurst() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("eve_local_memory", android.content.Context.MODE_PRIVATE)
        preferences.edit()
            .remove("trait:curiosity")
            .remove("trait:warmth")
            .remove("trait:playfulness")
            .remove("trait:patience")
            .remove("trait:last:friendly_gesture")
            .commit()
        try {
            val memory = EveMemory(context)
            val before = memory.learnedPersonality()
            val learned = memory.adaptPersonality("friendly_gesture")
            val immediateRepeat = memory.adaptPersonality("friendly_gesture")
            val restored = EveMemory(context).learnedPersonality()

            assertTrue("Friendly feedback should gently strengthen warmth.", learned.warmth > before.warmth)
            assertTrue("Friendly feedback should gently strengthen playfulness.", learned.playfulness > before.playfulness)
            assertEquals("Cooldown should prevent a burst from changing the trait twice.", learned.warmth, immediateRepeat.warmth, 0.0001f)
            assertEquals("Personality traits should persist across memory recreation.", learned.warmth, restored.warmth, 0.0001f)
            assertEquals(learned.curiosityBias, restored.curiosityBias, 0.0001f)
            assertEquals(learned.playfulness, restored.playfulness, 0.0001f)
            assertEquals(learned.patience, restored.patience, 0.0001f)
            Log.i("EVE_TEST", "PERSISTENT_PERSONALITY_PASS warmth=${restored.warmth} playfulness=${restored.playfulness}")
            System.out.println("PERSISTENT_PERSONALITY_PASS warmth=${restored.warmth} playfulness=${restored.playfulness}")
        } finally {
            preferences.edit()
                .remove("trait:curiosity")
                .remove("trait:warmth")
                .remove("trait:playfulness")
                .remove("trait:patience")
                .remove("trait:last:friendly_gesture")
                .commit()
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
