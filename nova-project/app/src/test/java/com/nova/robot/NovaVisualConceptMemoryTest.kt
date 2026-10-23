package com.nova.robot

import org.junit.Assert.*
import org.junit.Test

class NovaVisualConceptMemoryTest {
    @Test
    fun genericObjectNeedsRepeatedMatchingEvidence() {
        val storage = InMemoryNovaMindMemory()
        val memory = NovaVisualConceptMemory(storage)
        val labels = listOf(NovaLabelEvidence("car", 0.93f), NovaLabelEvidence("vehicle", 0.78f))
        val taught = memory.handleCommand("علّمي هذا الشيء باسم سيارة", labels, true, 1_000L)
        assertTrue(taught?.contains("حفظت") == true)
        assertNull(memory.observe(labels, 1_000L))
        assertNull(memory.observe(labels, 2_000L))
        assertNull(memory.observe(labels, 4_000L))
        assertTrue(memory.observe(labels, 7_000L)?.contains("سيارة") == true)
        assertEquals(1, NovaVisualConceptMemory(storage).count())
    }

    @Test
    fun genericCarLabelDoesNotProveSpecificTeslaIdentity() {
        val storage = InMemoryNovaMindMemory()
        val memory = NovaVisualConceptMemory(storage)
        val genericCar = listOf(NovaLabelEvidence("car", 0.95f), NovaLabelEvidence("vehicle", 0.80f))
        val taught = memory.handleCommand("علّمي هذا الشيء باسم سيارة تسلا", genericCar, true, 1_000L)
        assertTrue(taught?.contains("لا تميّز") == true)
        assertNull(memory.observe(genericCar, 10_000L))
        assertNull(memory.observe(genericCar, 14_000L))

        val distinctive = genericCar + NovaLabelEvidence("Tesla", 0.88f)
        assertNull(memory.observe(distinctive, 18_000L))
        assertNull(memory.observe(distinctive, 21_000L))
        assertTrue(memory.observe(distinctive, 24_000L)?.contains("تسلا") == true)
    }

    @Test
    fun teachingNeedsFreshCameraEvidenceAndCanBeCleared() {
        val storage = InMemoryNovaMindMemory()
        val memory = NovaVisualConceptMemory(storage)
        val rejected = memory.handleCommand("علّمي هذا الشيء باسم كتاب", emptyList(), false, 1L)
        assertTrue(rejected?.contains("شغّلي الكاميرا") == true)
        val labels = listOf(NovaLabelEvidence("book", 0.91f))
        assertTrue(memory.handleCommand("علّمي هذا الشيء باسم كتاب", labels, true, 2L)?.contains("حفظت") == true)
        assertEquals(1, memory.count())
        assertTrue(memory.handleCommand("امسحي ذاكرة الأشياء", emptyList(), false, 3L)?.contains("مسحت") == true)
        assertEquals(0, memory.count())
    }

    @Test
    fun listCommandShowsUnverifiedConceptState() {
        val storage = InMemoryNovaMindMemory()
        val memory = NovaVisualConceptMemory(storage)
        memory.handleCommand("علّمي هذا الشيء باسم كتاب", listOf(NovaLabelEvidence("book", 0.9f)), true, 1L)
        val list = memory.handleCommand("اذكري أسماء الأشياء المحفوظة", emptyList(), false, 2L)
        assertTrue(list?.contains("ما زلت أتحقق") == true)
    }
}
