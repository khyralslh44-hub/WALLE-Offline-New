package com.nova.robot

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

/**
 * Optional bundled MediaPipe models. All inference consumes an in-memory bitmap and does not
 * perform network requests. Missing/broken model assets fail closed so ML Kit can still operate.
 */
internal data class NovaAdvancedVisionResult(
    val labels: List<NovaLabelEvidence>,
    val poseNote: String?
)

internal class NovaAdvancedVision(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private var classifier: ImageClassifier? = null
    private var detector: ObjectDetector? = null
    private var poseLandmarker: PoseLandmarker? = null

    @Synchronized
    fun analyze(bitmap: Bitmap): NovaAdvancedVisionResult {
        if (bitmap.isRecycled) return NovaAdvancedVisionResult(emptyList(), null)
        val image = runCatching { BitmapImageBuilder(bitmap).build() }.getOrNull()
            ?: return NovaAdvancedVisionResult(emptyList(), null)
        val labels = linkedMapOf<String, NovaLabelEvidence>()

        runCatching {
            val task = classifier ?: ImageClassifier.createFromOptions(
                appContext,
                ImageClassifier.ImageClassifierOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientnet_lite0.tflite").build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(3)
                    .setScoreThreshold(0.25f)
                    .build()
            ).also { classifier = it }
            task.classify(image).classifications().flatMap { it.categories() }
                .forEach { category ->
                    val name = category.categoryName().trim()
                    if (name.isNotBlank()) {
                        val old = labels[name]
                        if (old == null || category.score() > old.confidence) {
                            labels[name] = NovaLabelEvidence(name, category.score())
                        }
                    }
                }
        }.onFailure { Log.w(TAG, "EfficientNet-Lite classification unavailable.", it) }

        runCatching {
            val task = detector ?: ObjectDetector.createFromOptions(
                appContext,
                ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(5)
                    .setScoreThreshold(0.40f)
                    .build()
            ).also { detector = it }
            task.detect(image).detections().flatMap { it.categories() }
                .forEach { category ->
                    val name = category.categoryName().trim()
                    if (name.isNotBlank()) {
                        val old = labels[name]
                        if (old == null || category.score() > old.confidence) {
                            labels[name] = NovaLabelEvidence(name, category.score())
                        }
                    }
                }
        }.onFailure { Log.w(TAG, "EfficientDet-Lite object detection unavailable.", it) }

        val poseNote = runCatching {
            val task = poseLandmarker ?: PoseLandmarker.createFromOptions(
                appContext,
                PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(0.55f)
                    .setMinPosePresenceConfidence(0.55f)
                    .build()
            ).also { poseLandmarker = it }
            val landmarks = task.detect(image).landmarks().firstOrNull()
            if (landmarks.isNullOrEmpty()) null
            else "رصدت نموذجًا لهيئة جسم بشري؛ لا يمكن استنتاج الرياضة أو الأكل من هذه اللقطة وحدها."
        }.onFailure { Log.w(TAG, "Pose Landmarker unavailable.", it) }.getOrNull()

        return NovaAdvancedVisionResult(labels.values.toList(), poseNote)
    }

    @Synchronized
    override fun close() {
        runCatching { classifier?.close() }
        runCatching { detector?.close() }
        runCatching { poseLandmarker?.close() }
        classifier = null
        detector = null
        poseLandmarker = null
    }

    private companion object { const val TAG = "NOVA_ADV_VISION" }
}
