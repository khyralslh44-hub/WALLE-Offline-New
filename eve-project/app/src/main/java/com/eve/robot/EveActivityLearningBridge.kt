package com.eve.robot

import java.io.File
import java.util.Locale
import kotlin.math.min

/**
 * Connects explicitly spoken user corrections to the small offline prototype learner.
 *
 * This learns corrections to EVE's coarse activity hypotheses, not new visual concepts or neural
 * network weights. It only records a sample after explicit user confirmation/correction and only
 * applies a learned correction after enough examples are available and the match is unambiguous.
 */
class EveActivityLearningBridge(stateFile: File) {
    private val activities = EveHumanActivity.values().filter { it != EveHumanActivity.UNKNOWN }
    private val byLabel = activities.associateBy { labelFor(it) }
    private val learner = EveLocalPrototypeLearner(
        stateFile = stateFile,
        allowedLabels = byLabel.keys,
        featureCount = FEATURE_COUNT,
        minimumExamplesPerLabel = MIN_EXAMPLES
    )

    @Synchronized
    fun recordUserCorrection(
        rawInterpretation: EveActivityInterpretation,
        confirmedActivity: EveHumanActivity
    ): Int? {
        if (!rawInterpretation.isActionable ||
            rawInterpretation.activity == EveHumanActivity.UNKNOWN ||
            confirmedActivity == EveHumanActivity.UNKNOWN ||
            confirmedActivity !in activities
        ) return null

        val accepted = learner.learn(
            label = labelFor(confirmedActivity),
            features = featuresFor(rawInterpretation),
            source = EveLearningLabelSource.USER_CONFIRMED
        )
        if (!accepted) return null
        return learner.exampleCounts()[labelFor(confirmedActivity)] ?: 0
    }

    @Synchronized
    fun applyLearnedCorrection(
        rawInterpretation: EveActivityInterpretation
    ): EveActivityInterpretation {
        if (!rawInterpretation.isActionable ||
            rawInterpretation.activity == EveHumanActivity.UNKNOWN
        ) return rawInterpretation

        val prediction = learner.predict(featuresFor(rawInterpretation)) ?: return rawInterpretation
        if (prediction.confidence < MIN_PREDICTION_CONFIDENCE) return rawInterpretation
        val learnedActivity = byLabel[prediction.label] ?: return rawInterpretation
        if (learnedActivity == rawInterpretation.activity) return rawInterpretation

        val adjustedConfidence = min(
            rawInterpretation.confidence,
            (0.55f + prediction.confidence * 0.35f).coerceIn(0.55f, 0.90f)
        )
        return rawInterpretation.copy(
            activity = learnedActivity,
            confidence = adjustedConfidence,
            explanationArabic = "تعلّمت من تصحيحات سابقة مؤكدة نمطًا قد يشير إلى «${arabicName(learnedActivity)}» بدل التقدير الأولي؛ ما زال استنتاجًا بصريًا غير مؤكد.",
            nextActionArabic = "استخدمت تعلّمًا محليًا من أمثلة أكّدها المستخدم؛ لا أتعامل مع هذا التقدير كحقيقة مؤكدة."
        )
    }

    @Synchronized
    fun exampleCounts(): Map<EveHumanActivity, Int> =
        learner.exampleCounts().mapNotNull { (label, count) ->
            byLabel[label]?.let { it to count }
        }.toMap()

    private fun featuresFor(result: EveActivityInterpretation): FloatArray {
        // Eight class slots: UNKNOWN is excluded, leaving seven coarse actionable hypotheses.
        // Fractional one-hot values keep corrections between activity classes within the learner's
        // normalized distance range while still making the original hypothesis distinguishable.
        val classVector = activities.map { if (it == result.activity) 0.25f else 0f }
        val confidence = result.confidence.coerceIn(0f, 1f)
        val frameSupport = (result.evidenceCount / 12f).coerceIn(0f, 1f)
        val duration = (result.durationMs.coerceAtLeast(0L) / 8_000f).coerceIn(0f, 1f)
        return (classVector + listOf(confidence, frameSupport, duration)).toFloatArray()
    }

    private fun labelFor(activity: EveHumanActivity): String =
        activity.name.lowercase(Locale.ROOT)

    private fun arabicName(activity: EveHumanActivity): String = when (activity) {
        EveHumanActivity.EATING_POSSIBLE -> "تناول الطعام"
        EveHumanActivity.EATING_TREAT_POSSIBLE -> "تناول حلوى أو مثلجات"
        EveHumanActivity.DRINKING_POSSIBLE -> "الشرب"
        EveHumanActivity.CHEWING_MOTION_POSSIBLE -> "حركة مضغ محتملة"
        EveHumanActivity.PLAYING_GAME_POSSIBLE -> "اللعب"
        EveHumanActivity.DRAWING_OR_COLORING_POSSIBLE -> "الرسم أو التلوين"
        EveHumanActivity.EXERCISE_LIKE_MOTION -> "حركة شبيهة بالتمرين"
        EveHumanActivity.UNKNOWN -> "نشاط غير معروف"
    }

    companion object {
        private const val FEATURE_COUNT = 10
        private const val MIN_EXAMPLES = 3
        private const val MIN_PREDICTION_CONFIDENCE = 0.45f
    }
}
