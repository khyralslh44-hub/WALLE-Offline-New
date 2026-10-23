package com.nova.robot

import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.hypot

/**
 * Low-cost temporal baseline over pose landmarks and object labels. This is not a trained CNN:
 * it reports tentative activity hypotheses only and keeps a short in-memory history.
 */
internal class NovaActivityRecognizer(
    private val historyMillis: Long = 18_000L,
    private val noteTtlMillis: Long = 9_000L
) {
    private data class Sample(val atMillis: Long, val points: FloatArray, val labels: Set<String>)
    private val samples = ArrayDeque<Sample>()
    private var lastNote: String? = null
    private var lastNoteAt = Long.MIN_VALUE

    @Synchronized
    fun observe(
        poseFeatures: FloatArray?,
        labels: List<NovaLabelEvidence>,
        nowMillis: Long
    ): String? {
        while (samples.isNotEmpty() && nowMillis >= samples.peekFirst().atMillis &&
            nowMillis - samples.peekFirst().atMillis > historyMillis
        ) samples.removeFirst()

        val added = poseFeatures != null && poseFeatures.size == FEATURE_COUNT && poseFeatures.all { it.isFinite() }
        if (added) {
            samples.addLast(Sample(
                nowMillis,
                poseFeatures!!.copyOf(),
                labels.filter { it.confidence.isFinite() && it.confidence >= 0.52f }
                    .map { it.label.trim().lowercase(Locale.ROOT) }.toSet()
            ))
            while (samples.size > MAX_SAMPLES) samples.removeFirst()
        }

        if (added) {
            val recent = samples.toList().takeLast(4)
            if (recent.size >= 3) {
                val hasFoodOrDrink = recent.any { sample -> sample.labels.any(::looksLikeFoodOrDrink) }
                val handsNearFace = recent.count { sample ->
                    minOf(distance(sample.points, NOSE, LEFT_WRIST), distance(sample.points, NOSE, RIGHT_WRIST)) < FACE_HAND_DISTANCE
                }
                if (hasFoodOrDrink && handsNearFace >= 3) {
                    lastNote = "ألاحظ أن اليد تقترب من الوجه مع وجود طعام أو إناء؛ قد يكون الشخص يأكل أو يشرب، لكنني لست متأكدة."
                    lastNoteAt = nowMillis
                    return lastNote
                }
            }
            if (recent.size >= 4) {
                val movingSteps = recent.zipWithNext().count { (before, after) ->
                    motionBetween(before.points, after.points) >= MOVEMENT_THRESHOLD
                }
                if (movingSteps >= 2) {
                    val exerciseEvidence = recent.any { sample -> sample.labels.any(::looksLikeExercise) }
                    lastNote = if (exerciseEvidence) "ألاحظ حركة متكررة، والتصنيف البصري قد يشير إلى نشاط رياضي؛ لا أستطيع تأكيد نوع التمرين من هذه المؤشرات وحدها." else "ألاحظ حركة متكررة في الأطراف؛ قد يكون الشخص يمشي أو يتحرك بنشاط. لا أستطيع تحديد نوع النشاط بثقة بعد."
                    lastNoteAt = nowMillis
                    return lastNote
                }
            }
        }

        return lastNote?.takeIf { nowMillis >= lastNoteAt && nowMillis - lastNoteAt <= noteTtlMillis }
    }

    @Synchronized
    fun reset() {
        samples.clear()
        lastNote = null
        lastNoteAt = Long.MIN_VALUE
    }

    private fun looksLikeFoodOrDrink(label: String): Boolean =
        listOf(
            "food", "fruit", "banana", "apple", "sandwich", "pizza", "bread", "bowl",
            "cup", "bottle", "fork", "spoon", "cake", "donut", "carrot", "broccoli",
            "orange", "hot dog", "dining table", "coffee", "tea", "drink"
        ).any { label == it || label.contains(it) }

    private fun looksLikeExercise(label: String): Boolean = listOf("exercise", "exercising", "fitness", "workout", "weightlifting", "push-up", "push up", "gym", "sport", "sports", "running", "treadmill").any { label == it || label.contains(it) }

    private fun motionBetween(a: FloatArray, b: FloatArray): Float {
        val ids = intArrayOf(LEFT_WRIST, RIGHT_WRIST, LEFT_ANKLE, RIGHT_ANKLE)
        return ids.map { distance(a, it, b, it) }.average().toFloat()
    }

    private fun distance(points: FloatArray, first: Int, second: Int): Float {
        val a = first * 2
        val b = second * 2
        return hypot(points[a] - points[b], points[a + 1] - points[b + 1])
    }

    private fun distance(a: FloatArray, first: Int, b: FloatArray, second: Int): Float {
        val i = first * 2
        val j = second * 2
        return hypot(a[i] - b[j], a[i + 1] - b[j + 1])
    }

    private companion object {
        const val FEATURE_COUNT = 22
        const val MAX_SAMPLES = 6
        const val MOVEMENT_THRESHOLD = 0.025f
        const val FACE_HAND_DISTANCE = 0.19f
        const val NOSE = 0
        const val LEFT_WRIST = 3
        const val RIGHT_WRIST = 4
        const val LEFT_ANKLE = 9
        const val RIGHT_ANKLE = 10
    }
}
