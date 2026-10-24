package com.nova.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaEmotionalDecisionEngineTest {
    private class Memory : NovaMemory {
        private val ints = mutableMapOf<String, Int>()
        private val longs = mutableMapOf<String, Long>()
        override fun getInt(key: String, defaultValue: Int) = ints[key] ?: defaultValue
        override fun getLong(key: String, defaultValue: Long) = longs[key] ?: defaultValue
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun putLong(key: String, value: Long) { longs[key] = value }
    }

    @Test
    fun oneAttentionShiftDoesNotImmediatelyInferJealousy() {
        val engine = NovaEmotionalDecisionEngine(Memory())
        val decision = engine.decide(NovaObservation(NovaSignal.ATTENTION_SHIFTED_AWAY))
        assertTrue(decision.mood != NovaMood.JEALOUS)
    }

    @Test
    fun repeatedAttentionChangesCanLeadToGentleJealousExpression() {
        val engine = NovaEmotionalDecisionEngine(Memory())
        var decision = engine.decide(NovaObservation(NovaSignal.ATTENTION_SHIFTED_AWAY))
        repeat(12) {
            decision = engine.decide(NovaObservation(NovaSignal.ATTENTION_SHIFTED_AWAY))
        }
        assertEquals(NovaMood.JEALOUS, decision.mood)
        assertEquals(NovaNextAction.SEEK_GENTLE_INTERACTION, decision.nextAction)
    }

    @Test
    fun repeatedBlockedTasksLeadToAlternativeOrHelpInsteadOfLooping() {
        val engine = NovaEmotionalDecisionEngine(Memory())
        var decision = engine.decide(NovaObservation(NovaSignal.TASK_BLOCKED))
        repeat(4) {
            decision = engine.decide(NovaObservation(NovaSignal.TASK_BLOCKED))
        }
        assertEquals(NovaMood.FRUSTRATED, decision.mood)
        assertTrue(
            decision.nextAction == NovaNextAction.TRY_DIFFERENT_APPROACH ||
                decision.nextAction == NovaNextAction.ASK_FOR_HELP
        )
    }

    @Test
    fun successfulTaskReducesFrustrationAndUnknownThingTriggersVerification() {
        val engine = NovaEmotionalDecisionEngine(Memory())
        repeat(3) { engine.decide(NovaObservation(NovaSignal.TASK_BLOCKED)) }
        val before = engine.decide(NovaObservation(NovaSignal.TASK_BLOCKED)).frustration
        val after = engine.decide(NovaObservation(NovaSignal.TASK_SUCCEEDED)).frustration
        assertTrue(after < before)

        val curious = engine.decide(NovaObservation(NovaSignal.NOVEL_OBJECT_UNCERTAIN))
        assertEquals(NovaMood.CURIOUS, curious.mood)
        assertEquals(NovaNextAction.OBSERVE_AND_VERIFY, curious.nextAction)
    }

    @Test
    fun emotionalStateIsPersistedAcrossEngineInstances() {
        val memory = Memory()
        val first = NovaEmotionalDecisionEngine(memory)
        repeat(5) { first.decide(NovaObservation(NovaSignal.ATTENTION_SHIFTED_AWAY)) }
        val second = NovaEmotionalDecisionEngine(memory)
        val decision = second.decide(NovaObservation(NovaSignal.ATTENTION_SHIFTED_AWAY))
        assertTrue(decision.attentionConcern > 0f)
    }
}
