package com.nova.robot

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
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
    private var faceLandmarker: FaceLandmarker? = null

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
            task.classify(image).classificationResult().classifications().flatMap { it.categories() }
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

        val faceExpressionNote = runCatching {
            val task = faceLandmarker ?: FaceLandmarker.createFromOptions(
                appContext,
                FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(true)
                    .build()
            ).also { faceLandmarker = it }
            val categories: Map<String, Float> = task.detect(image).faceBlendshapes()
                .orElse(emptyList())
                .flatten()
                .associate { category -> category.categoryName() to category.score() }
            val smile = ((categories["mouthSmileLeft"] ?: 0f) + (categories["mouthSmileRight"] ?: 0f)) / 2f
            val leftBlink = categories["eyeBlinkLeft"] ?: 0f
            val rightBlink = categories["eyeBlinkRight"] ?: 0f
            val browDown = ((categories["browDownLeft"] ?: 0f) + (categories["browDownRight"] ?: 0f)) / 2f
            val cues = mutableListOf<String>()
            if (smile >= 0.65f) cues += "نموذج تعابير الوجه رصد نمطًا بصريًا قريبًا من الابتسام"
            if (leftBlink >= 0.70f && rightBlink >= 0.70f) cues += "نموذج الوجه رصد إغلاقًا محتملًا للعينين"
            if (browDown >= 0.70f) cues += "نموذج الوجه رصد انخفاضًا في الحاجبين؛ لا يحدد ذلك الغضب وحده"
            if (cues.isEmpty()) null
            else cues.joinToString("، ") + ". إشارات شكلية تقريبية وليست قراءة مؤكدة للمشاعر."
        }.onFailure { Log.w(TAG, "Face expression model unavailable.", it) }.getOrNull()

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

        return NovaAdvancedVisionResult(labels.values.toList(), listOfNotNull(faceExpressionNote, poseNote).joinToString("\n").ifBlank { null })
    }

    @Synchronized
    override fun close() {
        runCatching { classifier?.close() }
        runCatching { detector?.close() }
        runCatching { poseLandmarker?.close() }
        runCatching { faceLandmarker?.close() }
        classifier = null
        detector = null
        poseLandmarker = null
        faceLandmarker = null
    }

    private companion object { const val TAG = "NOVA_ADV_VISION" }
}
