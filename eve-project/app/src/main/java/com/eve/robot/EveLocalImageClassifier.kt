package com.eve.robot

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import java.io.Closeable
import java.util.Locale

/** High-level, whole-frame category hypotheses from an on-device ImageNet classifier. */
data class EveImageClass(
    val label: String,
    val confidence: Float
)

/**
 * Complements the COCO object detector with a broader 1,000-class ImageNet classifier.
 *
 * This is a whole-image classifier, not an object detector: it has no bounding boxes, can be
 * uncertain on unfamiliar scenes, and must not override a higher-confidence detected object.
 * Both inference and model loading are local; the TFLite model is embedded in the APK assets.
 */
class EveLocalImageClassifier(context: Context) : Closeable {
    companion object {
        const val MODEL_ASSET = "efficientnet_lite0_int8.tflite"
        private const val TAG = "EVE_IMAGENET"
        private const val MAX_RESULTS = 5
    }

    private val classifier: ImageClassifier? = try {
        val options = ImageClassifier.ImageClassifierOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(MAX_RESULTS)
            .setScoreThreshold(0.05f)
            .setDisplayNamesLocale("en")
            .build()
        ImageClassifier.createFromOptions(context.applicationContext, options).also {
            Log.i(TAG, "IMAGENET_CLASSIFIER_READY model=$MODEL_ASSET classes~1000 backend=mediapipe_cpu offline=true")
        }
    } catch (error: Exception) {
        Log.e(TAG, "IMAGENET_CLASSIFIER_INIT_FAILED model=$MODEL_ASSET", error)
        null
    }

    val isReady: Boolean
        get() = classifier != null

    /** The caller owns the bitmap. Classification happens synchronously on the caller's worker. */
    @Synchronized
    fun classify(bitmap: Bitmap): List<EveImageClass> {
        val model = classifier ?: return emptyList()
        if (bitmap.isRecycled || bitmap.width < 32 || bitmap.height < 32) return emptyList()
        return try {
            val result = model.classify(BitmapImageBuilder(bitmap).build())
            result.classificationResult()
                .classifications()
                .flatMap { it.categories() }
                .mapNotNull { category ->
                    val display = category.displayName().trim()
                    val name = (display.ifBlank { category.categoryName() })
                        .trim()
                        .lowercase(Locale.ROOT)
                        .replace('_', ' ')
                        .replace(Regex("\\s+"), " ")
                    val score = category.score()
                    if (name.isBlank() || !score.isFinite() || score !in 0f..1f) {
                        null
                    } else {
                        EveImageClass(name, score)
                    }
                }
                .distinctBy { it.label }
                .sortedByDescending { it.confidence }
                .take(MAX_RESULTS)
        } catch (error: Exception) {
            Log.w(TAG, "IMAGENET_CLASSIFICATION_FAILED frame=${bitmap.width}x${bitmap.height}", error)
            emptyList()
        }
    }

    override fun close() {
        try {
            classifier?.close()
        } catch (error: Exception) {
            Log.w(TAG, "IMAGENET_CLASSIFIER_CLOSE_FAILED", error)
        }
    }
}
