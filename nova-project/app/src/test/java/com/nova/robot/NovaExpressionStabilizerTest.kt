package com.nova.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovaExpressionStabilizerTest {
    @Test
    fun requiresTwoConsecutiveConfidentPredictionsToSetAnExpression() {
        val stable = NovaExpressionStabilizer()
        assertNull(stable.observe(prediction(NovaMood.HAPPY), 1_000L))
        assertEquals(NovaMood.HAPPY, stable.observe(prediction(NovaMood.HAPPY), 2_800L))
    }

    @Test
    fun aSingleContradictoryPredictionDoesNotFlipTheEyes() {
        val stable = NovaExpressionStabilizer()
        stable.observe(prediction(NovaMood.HAPPY), 1_000L)
        stable.observe(prediction(NovaMood.HAPPY), 2_800L)
        assertEquals(NovaMood.HAPPY, stable.observe(prediction(NovaMood.FRUSTRATED), 4_600L))
        assertEquals(NovaMood.FRUSTRATED, stable.observe(prediction(NovaMood.FRUSTRATED), 6_400L))
    }

    @Test
    fun staleMoodExpiresWhenModelStopsBeingConfident() {
        val stable = NovaExpressionStabilizer(staleAfterMillis = 5_000L)
        stable.observe(prediction(NovaMood.HAPPY), 1_000L)
        stable.observe(prediction(NovaMood.HAPPY), 2_000L)
        assertNull(stable.observe(null, 8_000L))
    }

    private fun prediction(mood: NovaMood) =
        NovaEmotionPrediction(labelArabic = null, confidence = 0.82f, mood = mood, note = "test")
}
