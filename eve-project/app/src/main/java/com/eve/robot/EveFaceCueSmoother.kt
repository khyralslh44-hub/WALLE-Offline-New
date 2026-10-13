package com.eve.robot

import kotlin.math.abs

/** A single set of local ML Kit face-classification cues, not a claim about a person's inner feelings. */
data class EveFaceCue(
    val smilingProbability: Float?,
    val leftEyeOpenProbability: Float?,
    val rightEyeOpenProbability: Float?,
    val headYawDegrees: Float
)

private enum class FaceCueClass { TURNED, HAPPY, EYES_OPEN, BLINK, AMBIGUOUS, UNKNOWN }

/**
 * Short temporal filter for noisy frame-to-frame classifications.
 * At least two of the latest three observations must agree before a cue may steer the face/voice.
 * A one-frame blink, a missing probability, or a half-profile never creates a confident emotion.
 */
class EveFaceCueSmoother(
    private val windowSize: Int = 3,
    private val requiredVotes: Int = 2
) {
    private val window = ArrayList<Pair<FaceCueClass, EveFaceCue>>()

    init {
        require(windowSize >= 2) { "windowSize must be at least 2" }
        require(requiredVotes in 2..windowSize) { "requiredVotes must be in 2..windowSize" }
    }

    @Synchronized
    fun add(cue: EveFaceCue): EveFaceCue? {
        if (!cue.headYawDegrees.isFinite()) {
            reset()
            return null
        }
        val safe = EveFaceCue(
            smilingProbability = cue.smilingProbability?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
            leftEyeOpenProbability = cue.leftEyeOpenProbability?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
            rightEyeOpenProbability = cue.rightEyeOpenProbability?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
            headYawDegrees = cue.headYawDegrees.coerceIn(-90f, 90f)
        )
        window += classify(safe) to safe
        while (window.size > windowSize) window.removeAt(0)

        val winner = window.groupingBy { it.first }.eachCount()
            .filterKeys { it != FaceCueClass.UNKNOWN }
            .maxByOrNull { it.value }
            ?.takeIf { it.value >= requiredVotes }
            ?.key
            ?: return null

        // A blink or ambiguous cue must not change the emotional state or trigger sound.
        if (winner == FaceCueClass.BLINK || winner == FaceCueClass.AMBIGUOUS ||
            winner == FaceCueClass.UNKNOWN
        ) return null

        val winning = window.filter { it.first == winner }.map { it.second }
        fun mean(selector: (EveFaceCue) -> Float?): Float? {
            val values = winning.mapNotNull(selector)
            return if (values.isEmpty()) null else values.average().toFloat()
        }
        return EveFaceCue(
            smilingProbability = mean { it.smilingProbability },
            leftEyeOpenProbability = mean { it.leftEyeOpenProbability },
            rightEyeOpenProbability = mean { it.rightEyeOpenProbability },
            headYawDegrees = winning.map { it.headYawDegrees }.average().toFloat()
        )
    }

    @Synchronized
    fun reset() {
        window.clear()
    }

    @Synchronized
    fun sampleCount(): Int = window.size

    private fun classify(cue: EveFaceCue): FaceCueClass {
        if (abs(cue.headYawDegrees) > 22f) return FaceCueClass.TURNED
        val eyes = if (cue.leftEyeOpenProbability != null && cue.rightEyeOpenProbability != null) {
            (cue.leftEyeOpenProbability + cue.rightEyeOpenProbability) / 2f
        } else null
        val smile = cue.smilingProbability
        if (smile != null && smile >= 0.72f) return FaceCueClass.HAPPY
        if (eyes != null && eyes < 0.22f) return FaceCueClass.BLINK
        if (eyes != null && eyes >= 0.62f) return FaceCueClass.EYES_OPEN
        if (smile == null && eyes == null) return FaceCueClass.UNKNOWN
        return FaceCueClass.AMBIGUOUS
    }
}
