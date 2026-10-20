package com.eve.robot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Verifies the production MobileFaceNet model and private on-device reference store. */
@RunWith(AndroidJUnit4::class)
class FaceIdentityIntegrationTest {
    @Test
    fun bundledMobileFaceNetMatchesAnEnrolledFaceLocally() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val decoded = requireNotNull(
            instrumentation.context.assets.open("face_fixture.jpg").use { BitmapFactory.decodeStream(it) }
        ) { "Pinned face fixture could not be decoded." }
        val longestSide = maxOf(decoded.width, decoded.height)
        val scale = minOf(1f, 640f / longestSide.toFloat())
        val frame = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else decoded

        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
        )
        var crop: Bitmap? = null
        val matcher = EveFaceIdentityMatcher(instrumentation.targetContext)
        val memory = EveFaceIdentityMemory(instrumentation.targetContext)
        try {
            memory.deleteAll()
            val faces = Tasks.await(
                detector.process(InputImage.fromBitmap(frame, 0)),
                90,
                TimeUnit.SECONDS
            )
            val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?: throw AssertionError("The pinned identity fixture must contain a face.")
            val box = face.boundingBox
            val padX = (box.width() * 0.12f).toInt().coerceAtLeast(2)
            val padY = (box.height() * 0.12f).toInt().coerceAtLeast(2)
            val left = (box.left - padX).coerceIn(0, frame.width - 1)
            val top = (box.top - padY).coerceIn(0, frame.height - 1)
            val right = (box.right + padX).coerceIn(left + 1, frame.width)
            val bottom = (box.bottom + padY).coerceIn(top + 1, frame.height)
            val faceCrop = Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
            crop = faceCrop

            val selfSimilarity = matcher.similarity(faceCrop, faceCrop)
            assertTrue(
                "MobileFaceNet must produce a high same-image similarity, got $selfSimilarity.",
                selfSimilarity >= 0.98f
            )

            val person = EveKnownPerson(
                key = "friend",
                relationArabic = "الصديق",
                name = "اختبار وجه محلي"
            )
            val profile = memory.enroll(person, faceCrop)
            val match = memory.recognize(faceCrop, matcher)
            assertNotNull("A newly enrolled local face should match the same face crop.", match)
            assertEquals(profile.id, match?.profile?.id)
            assertTrue("Stored JPEG reference fell below the identity threshold.", match!!.similarity >= 0.80f)

            val marker = "FACE_IDENTITY_MODEL_PASS selfSimilarity=$selfSimilarity " +
                "storedReferenceSimilarity=${match.similarity} network=false localOnly=true"
            android.util.Log.i("EVE_TEST", marker)
            System.out.println(marker)
        } finally {
            memory.deleteAll()
            crop?.recycle()
            if (frame !== decoded) frame.recycle()
            decoded.recycle()
            detector.close()
            matcher.close()
        }
    }
}
