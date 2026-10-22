package com.eve.robot

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EveAutonomousExperienceMemoryTest {
    @Test fun learnsRecurringPatternsWithoutUserLabelsAndDoesNotCountAdjacentFrames() {
        val file = File.createTempFile("eve-experience", ".properties").apply { delete() }
        val memory = EveAutonomousExperienceMemory(file)
        val labels = setOf("sandwich", "person", "food")
        val start = 1_800_000_000_000L

        val first = memory.observe(
            EveHumanActivity.EATING_POSSIBLE, 0.76f, 8, 4_000L, labels, start
        )!!
        assertEquals(1, first.recurrenceCount)
        assertFalse(first.familiar)
        val adjacentFrame = memory.observe(
            EveHumanActivity.EATING_POSSIBLE, 0.77f, 9, 4_200L, labels, start + 2_000L
        )!!
        assertEquals(1, adjacentFrame.recurrenceCount)
        assertFalse(adjacentFrame.recordedNewOccurrence)

        val second = memory.observe(
            EveHumanActivity.EATING_POSSIBLE, 0.75f, 8, 4_000L, labels, start + 31L * 60L * 1000L
        )!!
        assertEquals(2, second.recurrenceCount)
        assertFalse(second.familiar)

        val third = memory.observe(
            EveHumanActivity.EATING_POSSIBLE, 0.78f, 10, 5_000L, labels, start + 62L * 60L * 1000L
        )!!
        assertEquals(3, third.recurrenceCount)
        assertTrue(third.familiar)
        assertTrue(third.summaryArabic.contains("تكرر"))

        val restored = EveAutonomousExperienceMemory(file)
        assertEquals(3, restored.occurrenceCount(EveHumanActivity.EATING_POSSIBLE, labels))
        file.delete()
    }

    @Test fun rejectsWeakOrUnknownObservations() {
        val file = File.createTempFile("eve-experience", ".properties").apply { delete() }
        val memory = EveAutonomousExperienceMemory(file)
        assertNull(memory.observe(EveHumanActivity.UNKNOWN, 0.9f, 8, 4_000L, emptySet(), 100L))
        assertNull(memory.observe(EveHumanActivity.EATING_POSSIBLE, 0.4f, 8, 4_000L, emptySet(), 200L))
        assertNull(memory.observe(EveHumanActivity.EATING_POSSIBLE, 0.8f, 2, 500L, emptySet(), 300L))
        file.delete()
    }
}
