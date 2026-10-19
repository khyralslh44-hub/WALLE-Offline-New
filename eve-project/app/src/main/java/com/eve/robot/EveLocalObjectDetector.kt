package com.eve.robot

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.Closeable
import java.util.Locale

/** A grounded object box returned by the bundled, fully offline COCO detector. */
data class EveVisualObject(
    val label: String,
    val confidence: Float,
    val areaFraction: Float
)

/**
 * Runs MediaPipe EfficientDet-Lite0 on the phone.
 *
 * ML Kit image labeling answers "what does this whole image resemble?" and can return generic
 * labels such as "pattern". This detector instead returns named object categories and bounding
 * boxes (for example person, cup, book, or cell phone). It does not identify a person's identity.
 */
class EveLocalObjectDetector(context: Context) : Closeable {
    companion object {
        const val MODEL_ASSET = "efficientdet_lite0.tflite"
        const val DEFAULT_SCORE_THRESHOLD = 0.42f
        private const val TAG = "EVE_OBJECTS"
        private const val MAX_RESULTS = 5
        private val NON_OBJECT_LABELS = setOf(
            "pattern", "texture", "background", "wall", "floor", "ceiling",
            "line", "geometry", "grid", "tile", "color", "colour", "lighting",
            "shadow", "unknown", "???"
        )

        fun isSceneNoiseLabel(label: String): Boolean =
            label.trim().lowercase(Locale.ROOT).replace('_', ' ') in NON_OBJECT_LABELS
    }

    private val detector: ObjectDetector? = try {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(DEFAULT_SCORE_THRESHOLD)
            .setMaxResults(MAX_RESULTS)
            .build()
        ObjectDetector.createFromOptions(context.applicationContext, options).also {
            Log.i(TAG, "OBJECT_DETECTOR_READY model=$MODEL_ASSET backend=mediapipe_cpu offline=true")
        }
    } catch (error: Exception) {
        Log.e(TAG, "OBJECT_DETECTOR_INIT_FAILED model=$MODEL_ASSET", error)
        null
    }

    val isReady: Boolean
        get() = detector != null

    /** The caller owns [bitmap]. This method never recycles or mutates it. */
    @Synchronized
    fun detect(bitmap: Bitmap): List<EveVisualObject> {
        val model = detector ?: return emptyList()
        if (bitmap.isRecycled || bitmap.width < 32 || bitmap.height < 32) return emptyList()
        return try {
            val result = model.detect(BitmapImageBuilder(bitmap).build())
            result.detections().mapNotNull { detection ->
                val category = detection.categories().maxByOrNull { it.score() }
                    ?: return@mapNotNull null
                val rawLabel = category.categoryName().ifBlank { category.displayName() }
                val label = rawLabel.trim().lowercase(Locale.ROOT)
                    .replace('_', ' ')
                    .replace(Regex("\\s+"), " ")
                val score = category.score()
                if (label.isBlank() || isSceneNoiseLabel(label) || !score.isFinite() ||
                    score < DEFAULT_SCORE_THRESHOLD
                ) {
                    return@mapNotNull null
                }
                val box = detection.boundingBox()
                val area = (box.width().coerceAtLeast(0f) * box.height().coerceAtLeast(0f) /
                    (bitmap.width.toFloat() * bitmap.height.toFloat()).coerceAtLeast(1f))
                    .coerceIn(0f, 1f)
                EveVisualObject(label, score.coerceIn(0f, 1f), area)
            }.sortedWith(
                compareByDescending<EveVisualObject> { it.confidence }
                    .thenByDescending { it.areaFraction }
            ).take(MAX_RESULTS)
        } catch (error: Exception) {
            Log.w(TAG, "OBJECT_DETECTION_FRAME_FAILED size=" + bitmap.width + "x" + bitmap.height, error)
            emptyList()
        }
    }

    override fun close() {
        try {
            detector?.close()
        } catch (error: Exception) {
            Log.w(TAG, "OBJECT_DETECTOR_CLOSE_FAILED", error)
        }
    }
}