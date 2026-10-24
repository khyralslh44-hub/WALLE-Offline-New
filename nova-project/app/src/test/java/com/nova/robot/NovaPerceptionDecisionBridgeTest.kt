package com.nova.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaPerceptionDecisionBridgeTest {
    private class Memory : NovaMemory {
        private val ints = mutableMapOf<String, Int>()
        private val longs = mutableMapOf<String, Long>()
        override fun getInt(key: String, defaultValue: Int) = ints[key] ?: defaultValue
        override fun getLong(key: String, defaultValue: Long) = longs[key] ?: defaultValue
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun putLong(key: String, value: Long) { longs[key] = value }
    }

    @Test
    fun cameraPresenceIsDeduplicatedAcrossRepeatedFrames() {
        val bridge = NovaPerceptionDecisionBridge(NovaBrain(Memory()))
        assertNotNull(bridge.onPersonPresence(true, 0.9f))
        assertNull(bridge.onPersonPresence(true, 0.9f))
        assertNull(bridge.onPersonPresence(false, 0.9f))
        assertNull(bridge.onPersonPresence(false, 0.9f))
        assertNotNull(bridge.onPersonPresence(true, 0.9f))
    }

    @Test
    fun uncertainLowConfidenceVisionDoesNotDriveEmotion() {
        val bridge = NovaPerceptionDecisionBridge(NovaBrain(Memory()))
        assertNull(bridge.onUnknownObject(0.25f))
        assertNull(bridge.onPersonPresence(true, 0.25f))
    }

    @Test
    fun blockedTaskReturnsAPlanInsteadOfOnlyAnExpression() {
        val bridge = NovaPerceptionDecisionBridge(NovaBrain(Memory()))
        var result = bridge.onTaskBlocked()
        repeat(3) { result = bridge.onTaskBlocked() }
        assertEquals(NovaMood.FRUSTRATED, result.first.mood)
        assertTrue(
            result.second == NovaNextAction.TRY_DIFFERENT_APPROACH ||
                result.second == NovaNextAction.ASK_FOR_HELP
        )
    }
}
