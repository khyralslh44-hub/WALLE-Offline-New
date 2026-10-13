package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotBrainTest {
    private val brain = RobotBrain()

    @Test fun repeatedItemBecomesFamiliar() {
        assertEquals(EveMood.FAMILIAR, brain.observe("plant", 0.91f, 3).mood)
    }

    @Test fun lowConfidenceDoesNotClaimCertainty() {
        val decision = brain.observe("unknown", 0.2f, 0)
        assertEquals(EveMood.CONCERNED, decision.mood)
        assertFalse(decision.playSound)
    }

    @Test fun foodProducesPositiveReaction() {
        assertEquals(EveMood.HAPPY, brain.observe("fruit", 0.9f, 1).mood)
    }

    @Test fun curiosityIsUsedForAnimals() {
        val decision = brain.observe("dog", 0.88f, 1)
        assertEquals(EveMood.CURIOUS, decision.mood)
        assertTrue(decision.curiosity > 0.8f)
        assertTrue(decision.playSound)
    }

    @Test fun genericPatternLabelsDoNotTriggerNoisySound() {
        val decision = brain.observe("pattern", 0.92f, 1)
        assertEquals(EveMood.FOCUSED, decision.mood)
        assertFalse(decision.playSound)
    }

    @Test fun genericLabelsAreNotMistakenForLearnedFamiliarObjects() {
        assertEquals(EveMood.FOCUSED, brain.observe("pattern", 0.92f, 3).mood)
        assertFalse(brain.observe("pattern", 0.92f, 3).playSound)
    }

    @Test fun repeatedRealWorldExperienceChangesMoodToFamiliar() {
        val first = brain.observe("dog", 0.90f, 1)
        val remembered = brain.observe("dog", 0.90f, 3)
        assertEquals(EveMood.CURIOUS, first.mood)
        assertEquals(EveMood.FAMILIAR, remembered.mood)
        assertTrue(remembered.familiarity > first.familiarity)
        assertFalse(remembered.playSound)
    }

    @Test fun localPositiveAffinityMakesRepeatedObjectFeelFamiliar() {
        val noAffinity = RobotBrain().observe("dog", 0.90f, 2, learnedAffinity = 0f)
        val learned = RobotBrain().observe("dog", 0.90f, 2, learnedAffinity = 0.65f)
        assertTrue(learned.valence > noAffinity.valence)
        assertEquals(EveMood.FAMILIAR, learned.mood)
        assertTrue(learned.familiarity > noAffinity.familiarity)
        assertFalse(learned.playSound)
    }

    @Test fun casualTouchIsSilentButLongPressCanSpeak() {
        assertFalse(brain.touched(false).playSound)
        assertTrue(brain.touched(true).playSound)
    }

    @Test fun idleExpressionsNeverPlaySound() {
        assertFalse(brain.idle(12_000L, 3).playSound)
        assertFalse(brain.idle(45_000L, 8).playSound)
    }

    @Test fun strongHumanSmileProducesHappyAffectAndOneCue() {
        val first = brain.observeHumanFace(0.96f, 0.92f, 0.91f, 0f)
        assertEquals(EveMood.HAPPY, first.mood)
        assertTrue(first.valence > 0.2f)
        assertTrue(first.playSound)
        val repeated = brain.observeHumanFace(0.95f, 0.90f, 0.90f, 0f)
        assertEquals(EveMood.HAPPY, repeated.mood)
        assertFalse(repeated.playSound)
    }

    @Test fun partialFaceDoesNotInventAnEmotionOrPlaySound() {
        val decision = brain.observeHumanFace(null, null, null, 0f)
        assertEquals(EveMood.NEUTRAL, decision.mood)
        assertFalse(decision.playSound)
    }

    @Test fun sideFacingFaceDoesNotTriggerVoice() {
        val decision = brain.observeHumanFace(0.98f, 0.95f, 0.95f, 35f)
        assertEquals(EveMood.CURIOUS, decision.mood)
        assertFalse(decision.playSound)
    }

    @Test fun closedEyesOverrideConflictingHighSmileProbability() {
        val decision = RobotBrain().observeHumanFace(0.99f, 0.05f, 0.08f, 0f)
        assertEquals(EveMood.FOCUSED, decision.mood)
        assertFalse(decision.playSound)
    }

    @Test fun oneClosedEyeFrameIsNotCalledSadnessOrSleep() {
        val decision = brain.observeHumanFace(0.10f, 0.08f, 0.12f, 0f)
        assertEquals(EveMood.FOCUSED, decision.mood)
        assertFalse(decision.playSound)
    }

    @Test fun idleEventuallySleeps() {
        assertEquals(EveMood.SLEEPY, brain.idle(45_000L, 1).mood)
        assertTrue(brain.idle(8_000L, 2).mood in setOf(EveMood.NEUTRAL, EveMood.CURIOUS, EveMood.FAMILIAR))
    }
}
