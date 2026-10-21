package com.eve.robot

import java.util.ArrayDeque
import java.util.Locale

enum class EveHumanWellbeingCue {
    UNKNOWN,
    DISTRESS_POSSIBLE,
    CRYING_POSSIBLE,
    COUGH_OR_SNEEZE_CUE_POSSIBLE
}

data class EveHumanWellbeingSnapshot(
    val cue: EveHumanWellbeingCue,
    val confidence: Float,
    val evidenceFrames: Int,
    val explanationArabic: String,
    val observedAtMs: Long
) {
    val isActionable: Boolean
        get() = cue != EveHumanWellbeingCue.UNKNOWN && confidence >= 0.55f
}

/**
 * Conservative temporal fusion of local image labels and the on-device facial-expression model.
 *
 * Sadness is not treated as proof of crying. Cough/sneeze-like labels are not a diagnosis.
 * An absent person, stale frame, weak cue, or one ambiguous frame returns UNKNOWN.
 */
class EveHumanWellbeingUnderstanding(
    private val windowMs: Long = 12_000L,
    private val maxSamples: Int = 10
) {
    private data class Sample(
        val at: Long,
        val faceEmotion: String,
        val faceConfidence: Float,
        val cryingScore: Float,
        val coughScore: Float,
        val sneezeScore: Float
    )

    private val history = ArrayDeque<Sample>()
    private var lastTimestampMs = 0L

    @Synchronized
    fun observe(
        timestampMs: Long,
        personPresent: Boolean,
        faceEmotionLabel: String? = null,
        faceEmotionConfidence: Float = 0f,
        candidates: List<EveImageClass> = emptyList()
    ): EveHumanWellbeingSnapshot {
        if (timestampMs < lastTimestampMs) {
            return unknown(timestampMs, "وصل إطار قديم؛ لم أغيّر تقدير حالة الشخص.")
        }
        lastTimestampMs = timestampMs
        if (!personPresent) {
            history.clear()
            return unknown(timestampMs, "لا يوجد وجه حديث وواضح يكفي لربط العلامات بالشخص.")
        }

        val emotion = faceEmotionLabel.orEmpty().trim().lowercase(Locale.ROOT)
        val emotionConfidence = faceEmotionConfidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val labels = candidates.asSequence()
            .filter { it.label.isNotBlank() && it.confidence.isFinite() && it.confidence in 0f..1f }
            .map { normalize(it.label) to it.confidence }
            .toList()
        val sample = Sample(
            at = timestampMs,
            faceEmotion = emotion,
            faceConfidence = emotionConfidence,
            cryingScore = maxLabelScore(labels, CRYING_TERMS),
            coughScore = maxLabelScore(labels, COUGH_TERMS),
            sneezeScore = maxLabelScore(labels, SNEEZE_TERMS)
        )
        history.addLast(sample)
        while (history.isNotEmpty() && timestampMs - history.first().at > windowMs) history.removeFirst()
        while (history.size > maxSamples) history.removeFirst()

        val explicitCrying = history.filter { it.cryingScore >= MIN_EXPLICIT_CUE_SCORE }
        val stableSadFaces = history.filter { it.faceEmotion == "sad" && it.faceConfidence >= MIN_FACE_EMOTION_CONFIDENCE }
        if (explicitCrying.size >= REQUIRED_CUE_FRAMES) {
            val cueAverage = explicitCrying.map { it.cryingScore }.average().toFloat()
            val sadSupport = stableSadFaces.size >= 2
            val confidence = (cueAverage * 0.78f + if (sadSupport) 0.12f else 0f).coerceIn(0.55f, 0.88f)
            return EveHumanWellbeingSnapshot(
                cue = EveHumanWellbeingCue.CRYING_POSSIBLE,
                confidence = confidence,
                evidenceFrames = explicitCrying.size,
                explanationArabic = "ظهرت مؤشرات بصرية متكررة قد تتوافق مع البكاء أو الدموع؛ هذا تقدير بصري وليس تأكيدًا.",
                observedAtMs = timestampMs
            )
        }

        // Persistent sadness can justify gentle concern, but never upgrades to "crying".
        if (stableSadFaces.size >= REQUIRED_SAD_FRAMES) {
            val average = stableSadFaces.map { it.faceConfidence }.average().toFloat()
            return EveHumanWellbeingSnapshot(
                cue = EveHumanWellbeingCue.DISTRESS_POSSIBLE,
                confidence = average.coerceIn(0.55f, 0.82f),
                evidenceFrames = stableSadFaces.size,
                explanationArabic = "تكررت ملامح وجه حزينة؛ قد يكون الشخص منزعجًا أو حزينًا، لكن ذلك لا يثبت البكاء أو سبب الحزن.",
                observedAtMs = timestampMs
            )
        }

        val coughs = history.filter { it.coughScore >= MIN_EXPLICIT_CUE_SCORE }
        val sneezes = history.filter { it.sneezeScore >= MIN_EXPLICIT_CUE_SCORE }
        val repeatedCough = coughs.size >= REQUIRED_CUE_FRAMES
        val repeatedSneeze = sneezes.size >= REQUIRED_CUE_FRAMES
        if (repeatedCough || repeatedSneeze) {
            val chosen = if (repeatedCough && repeatedSneeze) {
                coughs + sneezes
            } else if (repeatedCough) coughs else sneezes
            val avg = chosen.map { maxOf(it.coughScore, it.sneezeScore) }.average().toFloat()
            val words = buildList {
                if (repeatedCough) add("السعال")
                if (repeatedSneeze) add("العطاس")
            }.joinToString(" أو ")
            return EveHumanWellbeingSnapshot(
                cue = EveHumanWellbeingCue.COUGH_OR_SNEEZE_CUE_POSSIBLE,
                confidence = avg.coerceIn(0.55f, 0.82f),
                evidenceFrames = chosen.size,
                explanationArabic = "ظهرت تصنيفات بصرية متكررة قد توافق $words؛ هذا لا يثبت وجود مرض ولا يحدد تشخيصًا.",
                observedAtMs = timestampMs
            )
        }

        return unknown(timestampMs, "لم تتكرر مؤشرات كافية للبكاء أو علامات السعال والعطاس؛ أبقي التقدير غير محدد.")
    }

    @Synchronized
    fun reset() {
        history.clear()
        lastTimestampMs = 0L
    }

    private fun unknown(at: Long, explanation: String) =
        EveHumanWellbeingSnapshot(EveHumanWellbeingCue.UNKNOWN, 0f, 0, explanation, at)

    private fun maxLabelScore(labels: List<Pair<String, Float>>, terms: Set<String>): Float =
        labels.asSequence()
            .filter { (label, _) -> terms.any { term -> matchesWholePhrase(label, term) } }
            .maxOfOrNull { it.second } ?: 0f

    private fun matchesWholePhrase(label: String, term: String): Boolean =
        label == term || label.startsWith("$term ") || label.endsWith(" $term") || label.contains(" $term ")

    private fun normalize(raw: String): String = raw.trim().lowercase(Locale.ROOT)
        .replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ")

    companion object {
        private const val REQUIRED_CUE_FRAMES = 2
        private const val REQUIRED_SAD_FRAMES = 3
        private const val MIN_EXPLICIT_CUE_SCORE = 0.36f
        private const val MIN_FACE_EMOTION_CONFIDENCE = 0.70f

        private val CRYING_TERMS = setOf(
            "crying", "cry", "weeping", "sobbing", "tearful", "tears", "crying face", "person crying"
        )
        private val COUGH_TERMS = setOf("cough", "coughing", "person coughing")
        private val SNEEZE_TERMS = setOf("sneeze", "sneezing", "person sneezing")
    }
}
