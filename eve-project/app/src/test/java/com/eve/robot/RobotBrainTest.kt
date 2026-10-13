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

    @Test fun idleExpressionsNeverPlaySound() {
        assertFalse(brain.idle(12_000L, 3).playSound)
        assertFalse(brain.idle(45_000L, 8).playSound)
    }

    @Test fun idleEventuallySleeps() {
        assertEquals(EveMood.SLEEPY, brain.idle(45_000L, 1).mood)
        assertTrue(brain.idle(8_000L, 2).mood in setOf(EveMood.NEUTRAL, EveMood.CURIOUS, EveMood.FAMILIAR))
    }
}
