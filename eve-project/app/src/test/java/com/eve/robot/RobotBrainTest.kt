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
        assertEquals(EveMood.CURIOUS, brain.observe("dog", 0.88f, 1).mood)
    }

    @Test fun idleEventuallySleeps() {
        assertEquals(EveMood.SLEEPY, brain.idle(45_000L, 1).mood)
        assertTrue(brain.idle(8_000L, 2).mood in setOf(EveMood.NEUTRAL, EveMood.CURIOUS, EveMood.FAMILIAR))
    }
}
