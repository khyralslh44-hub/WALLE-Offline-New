package com.nova.robot

/**
 * Smooths frame-to-frame facial-expression predictions. A new expression must appear in two
 * consecutive confident observations before it can change the eye mood; an old mood expires
 * after a short hold when predictions become uncertain.
 */
internal class NovaExpressionStabilizer(
    private val staleAfterMillis: Long = 8_000L,
    private val minimumConfidence: Float = 0.40f
) {
    private var candidateMood: NovaMood? = null
    private var candidateStreak = 0
    private var stableMood: NovaMood? = null
    private var stableAtMillis = 0L

    @Synchronized
    fun observe(prediction: NovaEmotionPrediction?, nowMillis: Long): NovaMood? {
        val observed = prediction?.takeIf {
            it.mood != null && it.confidence.isFinite() && it.confidence >= minimumConfidence
        }?.mood

        if (observed == null) return activeStableMood(nowMillis)

        if (observed == stableMood) {
            candidateMood = null
            candidateStreak = 0
            stableAtMillis = nowMillis
            return stableMood
        }

        if (observed == candidateMood) {
            candidateStreak += 1
        } else {
            candidateMood = observed
            candidateStreak = 1
        }

        if (candidateStreak >= REQUIRED_CONSECUTIVE_HITS) {
            stableMood = observed
            stableAtMillis = nowMillis
            candidateMood = null
            candidateStreak = 0
        }
        return activeStableMood(nowMillis)
    }

    @Synchronized
    fun reset() {
        candidateMood = null
        candidateStreak = 0
        stableMood = null
        stableAtMillis = 0L
    }

    private fun activeStableMood(nowMillis: Long): NovaMood? {
        val mood = stableMood ?: return null
        if (nowMillis < stableAtMillis || nowMillis - stableAtMillis > staleAfterMillis) {
            stableMood = null
            candidateMood = null
            candidateStreak = 0
            return null
        }
        return mood
    }

    private companion object { const val REQUIRED_CONSECUTIVE_HITS = 2 }
}
