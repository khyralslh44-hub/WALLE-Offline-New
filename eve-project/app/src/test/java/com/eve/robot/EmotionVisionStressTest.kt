package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Adversarial offline tests for the boundaries of the current affect/vision heuristics.
 * These tests feed synthetic ML Kit probabilities; they do not claim to test a real human camera feed.
 */
class EmotionVisionStressTest {

    @Test fun ambiguousAndMissingFaceCuesNeverTriggerVoice() {
        val cases = listOf(
            Triple(null, null, null),
            Triple(0.30f, 0.80f, 0.82f),
            Triple(0.55f, 0.65f, 0.60f),
            Triple(0.71f, 0.80f, 0.80f),
            Triple(0.20f, null, 0.15f),
            Triple(null, 0.95f, 0.96f)
        )
        cases.forEach { (smile, left, right) ->
            val brain = RobotBrain()
            val result = brain.observeHumanFace(smile, left, right, 0f)
            assertFalse("Ambiguous cue spoke for $smile/$left/$right", result.playSound)
            assertTrue(result.valence in -1f..1f)
            assertTrue(result.arousal in 0f..1f)
            assertTrue(result.curiosity in 0f..1f)
        }
    }

    @Test fun onlyVeryStrongNewSmileMayRequestOneVoiceCue() {
        val brain = RobotBrain()
        val first = brain.observeHumanFace(0.96f, 0.90f, 0.91f, 0f)
        val second = brain.observeHumanFace(0.97f, 0.92f, 0.93f, 0f)
        assertEquals(EveMood.HAPPY, first.mood)
        assertTrue(first.playSound)
        assertFalse(second.playSound)
    }

    @Test fun faceTurnedAwaySuppressesSmileInference() {
        for (yaw in listOf(-90f, -40f, -22.1f, 22.1f, 40f, 90f)) {
            val brain = RobotBrain()
            val result = brain.observeHumanFace(0.99f, 0.99f, 0.99f, yaw)
            assertEquals("yaw=$yaw", EveMood.CURIOUS, result.mood)
            assertFalse("yaw=$yaw triggered sound", result.playSound)
        }
    }

    @Test fun blinkLikeSingleFrameIsNotSadness() {
        for (p in listOf(0f, 0.05f, 0.10f, 0.21f)) {
            val result = RobotBrain().observeHumanFace(0.05f, p, p, 0f)
            assertEquals(EveMood.FOCUSED, result.mood)
            assertFalse(result.playSound)
        }
    }

    @Test fun extremeAndNonFiniteConfidenceIsHandledWithoutInvalidAffect() {
        val confidences = listOf(-5f, 0f, 0.44f, 0.45f, 0.58f, 0.84f, 1f, 3f)
        confidences.forEach { confidence ->
            val brain = RobotBrain()
            val decision = brain.observe("dog", confidence, 1)
            assertTrue("confidence=$confidence invalid valence", decision.valence in -1f..1f)
            assertTrue("confidence=$confidence invalid arousal", decision.arousal in 0f..1f)
            assertTrue("confidence=$confidence invalid curiosity", decision.curiosity in 0f..1f)
            if (confidence < 0.45f) {
                assertEquals(EveMood.CONCERNED, decision.mood)
                assertFalse(decision.playSound)
            }
        }
        val nonFinite = RobotBrain().observe("dog", Float.NaN, 1)
        // NaN bypasses a simple less-than check; the result should never be allowed to speak.
        assertFalse(nonFinite.playSound)
    }

    @Test fun generatedProfilesStayWithinSafeGeometryForManyInputs() {
        val moods = EveMood.values()
        for (i in 0 until 20_000) {
            val decision = EveDecision(
                mood = moods[i % moods.size],
                reason = "stress",
                valence = ((i % 2001) - 1000) / 1000f,
                arousal = (i % 1001) / 1000f,
                curiosity = ((i * 17) % 1001) / 1000f,
                familiarity = ((i * 31) % 1001) / 1000f,
                fatigue = ((i * 43) % 1001) / 1000f
            )
            val profile = EyeExpressionGenerator.generate(decision, i.toLong())
            assertTrue(profile.opening in 0.14f..1.20f)
            assertTrue(abs(profile.tilt) <= 22f)
            assertTrue(profile.gazeX in -0.16f..0.16f)
            assertTrue(profile.gazeY in -0.12f..0.12f)
            assertTrue(profile.glow in 0.22f..1f)
            assertTrue(profile.squint in 0.40f..1.25f)
            assertTrue(abs(profile.asymmetry) <= 0.38f)
            assertTrue(profile.eyeWidth in 0.76f..1.24f)
            assertTrue(profile.eyeHeight in 0.60f..1.25f)
            assertTrue(profile.spacing in 0.78f..1.22f)
        }
    }

    @Test fun differentAffectStatesCreateDifferentEyeGeometryAcrossSameMood() {
        val profiles = (0 until 500).map { i ->
            val x = i / 499f
            EyeExpressionGenerator.generate(
                EveDecision(
                    mood = EveMood.NEUTRAL,
                    reason = "same mood, varying affect",
                    valence = x * 2f - 1f,
                    arousal = x,
                    curiosity = 1f - x,
                    familiarity = (i % 13) / 12f,
                    fatigue = (i % 7) / 6f
                ),
                i.toLong()
            )
        }
        assertTrue("Expected at least 450 distinct profiles", profiles.toSet().size >= 450)
    }

    @Test fun knownLabelCategoriesProduceDifferentEmotionalResponses() {
        val food = RobotBrain().observe("banana", 0.90f, 1)
        val animal = RobotBrain().observe("dog", 0.90f, 1)
        val person = RobotBrain().observe("person", 0.90f, 1)
        val unknown = RobotBrain().observe("pattern", 0.90f, 1)
        assertEquals(EveMood.HAPPY, food.mood)
        assertEquals(EveMood.CURIOUS, animal.mood)
        assertEquals(EveMood.AWAKE, person.mood)
        assertEquals(EveMood.FOCUSED, unknown.mood)
        assertNotEquals(food, animal)
        assertFalse(unknown.playSound)
    }

    @Test fun genericClassifierLabelNeverBecomesFamiliarEvenAfterManySightings() {
        val brain = RobotBrain()
        repeat(100) {
            val result = brain.observe("pattern", 0.99f, it + 1)
            assertNotEquals(EveMood.FAMILIAR, result.mood)
            assertFalse(result.playSound)
        }
    }
}
