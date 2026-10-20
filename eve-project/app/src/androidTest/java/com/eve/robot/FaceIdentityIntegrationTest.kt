package com.eve.robot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Verifies same-person and different-person comparisons with the bundled offline MobileFaceNet model. */
@RunWith(AndroidJUnit4::class)
class FaceIdentityIntegrationTest {
    @Test
    fun bundledMobileFaceNetMatchesEnrolledFaceAndRejectsDifferentPerson() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val appContext = instrumentation.targetContext
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
        )
        val matcher = EveFaceIdentityMatcher(appContext)
        val memory = EveFaceIdentityMemory(appContext)
        var enrolledCrop: Bitmap? = null
        var differentCrop: Bitmap? = null
        try {
            memory.deleteAll()
            // Both fixtures are test-only; neither is included in the production APK.
            val enrolledFace = loadLargestFaceCrop(testContext, "face_fixture.jpg", detector)
            enrolledCrop = enrolledFace
            val differentFace = loadLargestFaceCrop(testContext, "face_fixture_biden.jpg", detector)
            differentCrop = differentFace

            val selfSimilarity = matcher.similarity(enrolledFace, enrolledFace)
            assertTrue(
                "MobileFaceNet must produce high same-image similarity, got $selfSimilarity.",
                selfSimilarity >= 0.98f
            )
            val differentPersonSimilarity = matcher.similarity(enrolledFace, differentFace)
            assertTrue(
                "Different-person fixture must stay below the acceptance threshold; score=$differentPersonSimilarity.",
                differentPersonSimilarity < EveFaceIdentityMatcher.DEFAULT_MATCH_THRESHOLD
            )

            val person = EveKnownPerson(
                key = "friend",
                relationArabic = "الصديق",
                name = "اختبار وجه محلي"
            )
            val profile = memory.enroll(person, enrolledFace)
            val match = memory.recognize(enrolledFace, matcher)
            assertNotNull("The newly enrolled local face should match the same face crop.", match)
            assertEquals(profile.id, match?.profile?.id)
            assertTrue("Stored JPEG reference fell below threshold.", match!!.similarity >= 0.80f)
            assertNull(
                "A different person's face must not be assigned the enrolled name.",
                memory.recognize(differentFace, matcher)
            )

            val marker = "FACE_IDENTITY_MODEL_PASS selfSimilarity=$selfSimilarity " +
                "differentPersonSimilarity=$differentPersonSimilarity storedReferenceSimilarity=${match.similarity} " +
                "network=false localOnly=true"
            android.util.Log.i("EVE_TEST", marker)
            System.out.println(marker)
        } finally {
            memory.deleteAll()
            enrolledCrop?.recycle()
            differentCrop?.recycle()
            detector.close()
            matcher.close()
        }
    }

    private fun loadLargestFaceCrop(context: Context, name: String, detector: FaceDetector): Bitmap {
        val decoded = requireNotNull(
            context.assets.open(name).use { BitmapFactory.decodeStream(it) }
        ) { "Pinned face fixture '$name' could not be decoded." }
        val maxSide = maxOf(decoded.width, decoded.height)
        val scale = minOf(1f, 640f / maxSide.toFloat())
        val frame = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else decoded
        try {
            val faces = Tasks.await(
                detector.process(InputImage.fromBitmap(frame, 0)),
                90,
                TimeUnit.SECONDS
            )
            val face: Face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?: throw AssertionError("Pinned fixture '$name' must contain a face.")
            val box = face.boundingBox
            val padX = (box.width() * 0.12f).toInt().coerceAtLeast(2)
            val padY = (box.height() * 0.12f).toInt().coerceAtLeast(2)
            val left = (box.left - padX).coerceIn(0, frame.width - 1)
            val top = (box.top - padY).coerceIn(0, frame.height - 1)
            val right = (box.right + padX).coerceIn(left + 1, frame.width)
            val bottom = (box.bottom + padY).coerceIn(top + 1, frame.height)
            return Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
        } finally {
            if (frame !== decoded) frame.recycle()
            decoded.recycle()
        }
    }
}
