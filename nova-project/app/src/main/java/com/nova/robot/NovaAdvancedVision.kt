package com.nova.robot

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

/**
 * Optional bundled MediaPipe models. All inference consumes an in-memory bitmap and does not
 * perform network requests. Missing/broken model assets fail closed so ML Kit can still operate.
 */
/** Alternating scene/object and facial-blendshape work reduces peak CPU load on mid-range phones. */
internal object NovaVisionSchedule {
    fun includeSceneModels(cycleIndex: Long): Boolean = cycleIndex % 2L == 0L
}

internal data class NovaAdvancedVisionResult(
    val labels: List<NovaLabelEvidence>,
    val poseNote: String?,
    /** In-memory normalized x/y for nose, shoulders, wrists, hips, knees, and ankles. */
    val poseFeatures: FloatArray? = null,
    val handGestureCue: NovaHandGestureCue? = null
)

internal class NovaAdvancedVision(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private var classifier: ImageClassifier? = null
    private var detector: ObjectDetector? = null
    private var poseLandmarker: PoseLandmarker? = null
    private var faceLandmarker: FaceLandmarker? = null
    private var gestureRecognizer: GestureRecognizer? = null

    @Synchronized
    fun analyze(
        bitmap: Bitmap,
        includeSceneModels: Boolean = true,
        includeFaceBlendshapes: Boolean = true,
        includePose: Boolean = true,
        includeHandGestures: Boolean = true
    ): NovaAdvancedVisionResult {
        if (bitmap.isRecycled) return NovaAdvancedVisionResult(emptyList(), null)
        val image = runCatching { BitmapImageBuilder(bitmap).build() }.getOrNull()
            ?: return NovaAdvancedVisionResult(emptyList(), null)
        val labels = linkedMapOf<String, NovaLabelEvidence>()

        if (includeSceneModels) {
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
        }

        val faceExpressionNote = if (includeFaceBlendshapes) runCatching {
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
            if (smile >= 0.65f) cues += "ألاحظ حركة في الوجه تشبه الابتسام"
            if (leftBlink >= 0.70f && rightBlink >= 0.70f) cues += "ألاحظ أن العينين قد أُغلقتا في هذه اللقطة"
            if (browDown >= 0.70f) cues += "ألاحظ انخفاض الحاجبين، لكنني لا أستنتج الغضب من ذلك وحده"
            if (cues.isEmpty()) null
            else cues.joinToString("، ") + ". ملاحظات شكلية تقريبية لا تثبت شعور الشخص."
        }.onFailure { Log.w(TAG, "Face expression model unavailable.", it) }.getOrNull() else null

        val handGestureCue = if (includeHandGestures) runCatching {
            val task = gestureRecognizer ?: GestureRecognizer.createFromOptions(
                appContext,
                GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.55f)
                    .setMinHandPresenceConfidence(0.55f)
                    .setMinTrackingConfidence(0.50f)
                    .build()
            ).also { gestureRecognizer = it }
            task.recognize(image).gestures().flatten()
                .filter { it.categoryName().isNotBlank() && !it.categoryName().equals("None", ignoreCase = true) }
                .maxByOrNull { it.score() }
                ?.let { NovaHandGestureCue(it.categoryName(), it.score()) }
        }.onFailure { Log.w(TAG, "Hand gesture model unavailable.", it) }.getOrNull() else null

        var poseFeatures: FloatArray? = null
        val poseNote = if (includePose) runCatching {
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
            else {
                val keyIndices = intArrayOf(0, 11, 12, 15, 16, 23, 24, 25, 26, 27, 28)
                poseFeatures = keyIndices.flatMap { index ->
                    val point = landmarks.getOrNull(index)
                    listOf(point?.x() ?: Float.NaN, point?.y() ?: Float.NaN)
                }.toFloatArray()
                "ألاحظ هيئة جسم بشري، وأتابع تغيّر وضعية الجسم عبر عدة لقطات."
            }
        }.onFailure { Log.w(TAG, "Pose Landmarker unavailable.", it) }.getOrNull() else null

        return NovaAdvancedVisionResult(
            labels.values.toList(),
            listOfNotNull(faceExpressionNote, poseNote).joinToString("\n").ifBlank { null },
            poseFeatures,
            handGestureCue
        )
    }

    @Synchronized
    override fun close() {
        runCatching { classifier?.close() }
        runCatching { detector?.close() }
        runCatching { poseLandmarker?.close() }
        runCatching { faceLandmarker?.close() }
        runCatching { gestureRecognizer?.close() }
        classifier = null
        detector = null
        poseLandmarker = null
        faceLandmarker = null
        gestureRecognizer = null
    }

    private companion object { const val TAG = "NOVA_ADV_VISION" }
}
