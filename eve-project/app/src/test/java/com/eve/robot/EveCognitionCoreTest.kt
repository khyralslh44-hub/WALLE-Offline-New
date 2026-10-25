package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EveCognitionCoreTest {
    private fun input(
        label: String = "cat",
        confidence: Float = 0.93f,
        sightings: Int = 1,
        quietForMs: Long = 20_000L,
        nowMs: Long = 100_000L,
        lastAny: Long = 0L,
        lastSame: Long = 0L,
        conversationActive: Boolean = false
    ) = EveCognitionInput(
        label = label, confidence = confidence, sightings = sightings,
        curiosityBias = 0.78f, nowMs = nowMs,
        lastAnyQuestionAtMs = lastAny, lastSameLabelQuestionAtMs = lastSame,
        quietForMs = quietForMs, isUserConversationActive = conversationActive
    )

    @Test fun novelHighConfidenceMeaningfulObjectCanEarnAQuestion() {
        val result = EveCognitionCore().evaluate(input())
        assertEquals(EveCognitiveAction.ASK_QUESTION, result.action)
        assertTrue(result.shouldAsk)
        assertTrue(result.priority >= EveCognitionCore.MIN_PRIORITY)
        assertTrue(result.novelty > 0.9f)
    }

    @Test fun uncertainVisionWaitsForBetterEvidence() {
        val result = EveCognitionCore().evaluate(input(confidence = 0.68f))
        assertEquals(EveCognitiveAction.WAIT_FOR_EVIDENCE, result.action)
        assertFalse(result.shouldAsk)
    }

    @Test fun textureAndBackgroundLabelsAreNeverTreatedAsThingsToLearn() {
        assertEquals(EveCognitiveAction.IGNORE_NOISE, EveCognitionCore().evaluate(input(label = "pattern")).action)
        assertEquals(EveCognitiveAction.IGNORE_NOISE, EveCognitionCore().evaluate(input(label = "background")).action)
    }

    @Test fun activeConversationWinsOverAutonomousCuriosity() {
        assertEquals(EveCognitiveAction.OBSERVE_QUIETLY, EveCognitionCore().evaluate(input(conversationActive = true)).action)
        assertEquals(EveCognitiveAction.OBSERVE_QUIETLY, EveCognitionCore().evaluate(input(quietForMs = 1_000L)).action)
    }

    @Test fun cooldownCannotBeBypassedByRepeatedCameraFrames() {
        assertEquals(EveCognitiveAction.SUPPRESS_REPEAT, EveCognitionCore().evaluate(input(lastAny = 90_000L, nowMs = 100_000L)).action)
        assertEquals(EveCognitiveAction.SUPPRESS_REPEAT, EveCognitionCore().evaluate(input(lastSame = 90_000L, nowMs = 100_000L)).action)
    }

    @Test fun noveltyDeclinesWithRepeatedSightingsWithoutPretendingIdentityWasRecognized() {
        val core = EveCognitionCore()
        val first = core.evaluate(input(sightings = 1))
        val repeated = core.evaluate(input(sightings = 9))
        assertTrue(first.novelty > repeated.novelty)
        assertTrue(first.informationValue > repeated.informationValue)
        assertFalse(repeated.reason.contains("same physical object"))
    }

    @Test fun backwardsClockDoesNotDisableQuestionCooldown() {
        val result = EveCognitionCore().evaluate(input(lastAny = 200_000L, nowMs = 100_000L))
        assertEquals(EveCognitiveAction.SUPPRESS_REPEAT, result.action)
    }

    @Test fun nonFiniteConfidenceIsHandledAsUncertainEvidence() {
        val result = EveCognitionCore().evaluate(input(confidence = Float.NaN))
        assertEquals(EveCognitiveAction.WAIT_FOR_EVIDENCE, result.action)
    }
}
