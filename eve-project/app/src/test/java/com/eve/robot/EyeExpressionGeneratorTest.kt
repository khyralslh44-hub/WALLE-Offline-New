package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class EyeExpressionGeneratorTest {
    @Test fun sameStateAndSeedProduceRepeatablePose() {
        val state = EveDecision(
            mood = EveMood.CURIOUS,
            reason = "test",
            valence = 0.4f,
            arousal = 0.7f,
            curiosity = 0.9f,
            familiarity = 0.2f,
            fatigue = 0.1f
        )
        assertEquals(
            EyeExpressionGenerator.generate(state, 1234L),
            EyeExpressionGenerator.generate(state, 1234L)
        )
    }

    @Test fun thousandsOfDistinctParameterizedEyePosesCanBeGenerated() {
        val moods = EveMood.values()
        val profiles = HashSet<EyeExpressionProfile>()
        for (i in 0 until 10_000) {
            val x = i / 10_000f
            val state = EveDecision(
                mood = moods[i % moods.size],
                reason = "expression space test",
                valence = sin(i * 0.053).toFloat(),
                arousal = ((i * 37) % 1000) / 999f,
                curiosity = ((i * 97) % 1000) / 999f,
                familiarity = ((i * 173) % 1000) / 999f,
                fatigue = ((i * 223) % 1000) / 999f
            )
            profiles += EyeExpressionGenerator.generate(state, i.toLong())
        }
        assertTrue("Expected thousands of distinct generated eye poses, got ${profiles.size}", profiles.size >= 9_000)
    }

    @Test fun affectValuesChangeEyePoseEvenForSameMood() {
        val calm = EveDecision(
            mood = EveMood.NEUTRAL, reason = "calm",
            valence = -0.4f, arousal = 0.15f, curiosity = 0.2f, familiarity = 0.1f, fatigue = 0.8f
        )
        val curious = calm.copy(
            reason = "curious", valence = 0.65f, arousal = 0.85f,
            curiosity = 0.95f, familiarity = 0.8f, fatigue = 0.05f
        )
        assertTrue(EyeExpressionGenerator.generate(calm, 42L) != EyeExpressionGenerator.generate(curious, 42L))
    }
}
