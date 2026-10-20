package com.eve.robot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Loads the production bundled ImageNet model and runs it against a pinned portrait while
 * the deep-verification workflow has networking disabled. This checks real on-device inference,
 * not merely the presence of a model filename in an APK.
 */
@RunWith(AndroidJUnit4::class)
class ImageNetVisionIntegrationTest {
    @Test
    fun broadImageClassifierProducesBoundedLocalCategories() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val decoded = requireNotNull(
            instrumentation.context.assets.open("face_fixture.jpg").use { BitmapFactory.decodeStream(it) }
        ) { "Pinned scene fixture could not be decoded." }
        val longest = maxOf(decoded.width, decoded.height)
        val scale = minOf(1f, 640f / longest.toFloat())
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).coerceAtLeast(1),
                true
            )
        } else {
            decoded
        }
        val classifier = EveLocalImageClassifier(instrumentation.targetContext)
        try {
            assertTrue(
                "The APK is missing efficientnet_lite0_int8.tflite or MediaPipe could not initialize the classifier.",
                classifier.isReady
            )
            val categories = classifier.classify(bitmap)
            assertFalse("ImageNet classifier returned no scene categories for the known portrait.", categories.isEmpty())
            assertTrue("Expected at most five ranked categories.", categories.size <= 5)
            assertTrue(
                "Classifier scores must be finite and within [0,1]: $categories",
                categories.all { it.confidence.isFinite() && it.confidence in 0f..1f && it.label.isNotBlank() }
            )
            assertTrue(
                "ImageNet categories should be ordered from highest to lowest score: $categories",
                categories.zipWithNext().all { (a, b) -> a.confidence >= b.confidence }
            )
            val marker = "IMAGENET_CLASSIFIER_PASS categories=" + categories.joinToString("|") {
                "${it.label}:${String.format(java.util.Locale.ROOT, "%.3f", it.confidence)}"
            } + " offline=true"
            Log.i("EVE_TEST", marker)
            System.out.println(marker)
        } finally {
            classifier.close()
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
    }
}
