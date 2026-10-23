package com.nova.robot

import org.junit.Assert.*
import org.junit.Test

class NovaBrainTest {
    private class FakeMemory : NovaMemory {
        private val ints = mutableMapOf<String, Int>()
        private val longs = mutableMapOf<String, Long>()
        override fun getInt(key: String, defaultValue: Int) = ints[key] ?: defaultValue
        override fun getLong(key: String, defaultValue: Long) = longs[key] ?: defaultValue
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun putLong(key: String, value: Long) { longs[key] = value }
    }

    @Test fun firstTouchIsCuriousAndMemoryStaysSmall() {
        val memory = FakeMemory()
        val brain = NovaBrain(memory)
        val event = brain.onTap()
        assertEquals(NovaMood.CURIOUS, event.mood)
        assertTrue(event.messageArabic.contains("نوفا"))
        assertEquals(1, memory.getInt("touch_count"))
    }

    @Test fun doubleTapHasPlayfulResponse() {
        val brain = NovaBrain(FakeMemory())
        val event = brain.onDoubleTap()
        assertEquals(NovaMood.PLAYFUL, event.mood)
        assertTrue(event.messageArabic.isNotBlank())
    }

    @Test fun longPressAsksAThoughtAndRotatesItsPrompt() {
        val memory = FakeMemory()
        val brain = NovaBrain(memory)
        val first = brain.thinkNow()
        val second = brain.thinkNow()
        assertTrue(first.isQuestion)
        assertTrue(second.isQuestion)
        assertNotEquals(first.messageArabic, second.messageArabic)
        assertEquals(2, memory.getInt("thought_count"))
    }

    @Test fun idleQuestionRequiresThreeMinutesOfVisibleIdleTime() {
        val brain = NovaBrain(FakeMemory())
        val tooEarly = brain.idleEvent(179_999L, 0L, 1_000_000L, 14, true)
        assertNull(tooEarly)
        val hidden = brain.idleEvent(200_000L, 0L, 1_000_001L, 14, false)
        assertNull(hidden)
        val event = brain.idleEvent(200_001L, 0L, 1_000_002L, 14, true)
        assertNotNull(event)
        assertTrue(event!!.isQuestion)
    }

    @Test fun idleQuestionCooldownPreventsRepeatedPrompts() {
        val memory = FakeMemory()
        val brain = NovaBrain(memory)
        val first = brain.idleEvent(200_000L, 0L, 2_000_000L, 12, true)
        assertNotNull(first)
        val second = brain.idleEvent(900_000L, 0L, 2_599_999L, 12, true)
        assertNull(second)
        val third = brain.idleEvent(900_001L, 0L, 2_600_001L, 12, true)
        assertNotNull(third)
    }
}
