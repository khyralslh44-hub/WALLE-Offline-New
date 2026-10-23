package com.nova.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaAffectEngineTest {
    @Test
    fun repeatedFailureBuildsFrustrationWithoutRandomness() {
        val engine = NovaAffectEngine()
        val first = engine.observe(NovaAffectEvent.REPEATED_FAILURE, 1_000L)
        val second = engine.observe(NovaAffectEvent.REPEATED_FAILURE, 2_000L)
        assertTrue(second.frustration > first.frustration)
        assertEquals(second, engine.state(2_000L))
    }

    @Test
    fun edgeRiskRaisesAlertnessAndProducesAlertMood() {
        val engine = NovaAffectEngine()
        repeat(3) { engine.observe(NovaAffectEvent.THREAT_OR_EDGE_RISK, 10_000L + it) }
        assertTrue(engine.state(10_002L).alertness >= 0.72f)
        assertEquals(NovaAffectMood.ALERT, engine.mood(10_002L))
    }

    @Test
    fun positiveEventsImproveComfortAndReduceFrustration() {
        val engine = NovaAffectEngine(NovaAffectState(frustration = 0.5f))
        val before = engine.state(100L)
        val after = engine.observe(NovaAffectEvent.USER_PRAISE, 101L)
        assertTrue(after.comfort > before.comfort)
        assertTrue(after.trust > before.trust)
        assertTrue(after.frustration < before.frustration)
    }

    @Test
    fun stateDecaysTowardBaselineOverTime() {
        val engine = NovaAffectEngine(
            NovaAffectState(curiosity = 1f, frustration = 1f, alertness = 1f, updatedAtMillis = 1L)
        )
        val later = engine.state(1L + 60L * 60L * 1000L)
        assertTrue(later.curiosity < 1f)
        assertTrue(later.frustration < 1f)
        assertTrue(later.alertness < 1f)
    }

    @Test
    fun snapshotRestoresValuesAndExactTimestamp() {
        val engine = NovaAffectEngine()
        engine.observe(NovaAffectEvent.NOVEL_OBSERVATION, 1_790_000_000_123L)
        val snapshot = engine.snapshot(1_790_000_000_123L)
        val restored = NovaAffectEngine()
        assertTrue(restored.restore(snapshot))
        assertEquals(engine.state(1_790_000_000_123L), restored.state(1_790_000_000_123L))
    }

    @Test
    fun invalidSnapshotsAreRejected() {
        val engine = NovaAffectEngine()
        assertFalse(engine.restore("0.1,0.2,0.3"))
        assertFalse(engine.restore("NaN,0.2,0.3,0.4,0.5,0.6,100"))
        assertFalse(engine.restore("1.1,0.2,0.3,0.4,0.5,0.6,100"))
    }
}
