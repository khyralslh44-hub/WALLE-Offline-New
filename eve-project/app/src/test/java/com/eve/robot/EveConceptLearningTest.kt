package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EveConceptLearningTest {
    private class InMemoryStore : EveConceptStore {
        var values: List<EveConcept> = emptyList()
        override fun readAll(): List<EveConcept> = values
        override fun writeAll(concepts: List<EveConcept>) {
            values = concepts.toList()
        }
    }

    @Test
    fun spokenCorrectionIsOnlyAHypothesisUntilSeparatedVisualEvidenceConfirmsIt() {
        val store = InMemoryStore()
        val memory = EveConceptLearning(store)

        val taught = memory.teachCurrentObservation(
            visualLabel = "car",
            visualConfidence = 0.93f,
            observationAgeMs = 1_200L,
            utterance = "هذه سيارة",
            allowBareAnswer = false,
            nowMs = 1_000_000L
        )
        assertEquals(EveConceptLearningStatus.WAITING_FOR_VERIFICATION, taught?.status)
        assertNull(memory.confirmedNameFor("car"))

        assertEquals(1, memory.observe("car", 0.91f, 1_003_000L)?.evidenceCount)
        assertEquals(2, memory.observe("car", 0.89f, 1_006_000L)?.evidenceCount)
        assertEquals(EveConceptLearningStatus.VERIFICATION_PROGRESS,
            memory.observe("car", 0.94f, 1_009_000L)?.status)
        // Three observations are not enough until the minimum evidence time span is reached.
        assertNull(memory.confirmedNameFor("car"))

        val confirmed = memory.observe("car", 0.92f, 1_012_000L)
        assertEquals(EveConceptLearningStatus.CONFIRMED, confirmed?.status)
        assertEquals("سيارة", memory.confirmedNameFor("car"))
        assertTrue(memory.isConfirmed("car"))
    }

    @Test
    fun repeatedCallbacksFromTheSameFrameDoNotIncreaseEvidenceCount() {
        val memory = EveConceptLearning(InMemoryStore())
        memory.teachCurrentObservation("cup", 0.95f, 500L, "هذا كوب", false, 2_000_000L)

        assertEquals(1, memory.observe("cup", 0.92f, 2_003_000L)?.evidenceCount)
        assertEquals(1, memory.observe("cup", 0.99f, 2_003_400L)?.evidenceCount)
        assertEquals(2, memory.observe("cup", 0.90f, 2_006_000L)?.evidenceCount)
        assertFalse(memory.isConfirmed("cup"))
    }

    @Test
    fun weakOrStaleVisualEvidenceCannotBeLinkedToAUserCorrection() {
        val memory = EveConceptLearning(InMemoryStore())
        val weak = memory.teachCurrentObservation("car", 0.51f, 500L, "هذه سيارة", false, 3_000_000L)
        assertEquals(EveConceptLearningStatus.REJECTED, weak?.status)
        assertNull(memory.confirmedNameFor("car"))

        val stale = memory.teachCurrentObservation("car", 0.95f, 120_000L, "هذه سيارة", false, 3_000_000L)
        assertEquals(EveConceptLearningStatus.REJECTED, stale?.status)
        assertFalse(memory.isConfirmed("car"))
    }

    @Test
    fun aBareWordIsAcceptedOnlyAsAnAnswerToEvesRecentQuestion() {
        val memory = EveConceptLearning(InMemoryStore())
        assertNull(
            memory.teachCurrentObservation("car", 0.92f, 2_000L, "سيارة", false, 4_000_000L)
        )

        val answer = memory.teachCurrentObservation(
            "car", 0.92f, 2_000L, "سيارة", true, 4_000_000L
        )
        assertEquals(EveConceptLearningStatus.WAITING_FOR_VERIFICATION, answer?.status)
        assertNull(memory.confirmedNameFor("car"))
    }

    @Test
    fun pendingAndConfirmedKnowledgeSurviveRecreatingTheLearningEngine() {
        val store = InMemoryStore()
        val first = EveConceptLearning(store)
        first.teachCurrentObservation("bicycle", 0.93f, 500L, "هذه دراجة", false, 5_000_000L)
        first.observe("bicycle", 0.91f, 5_003_000L)
        first.observe("bicycle", 0.94f, 5_006_000L)
        first.observe("bicycle", 0.96f, 5_009_000L)
        first.observe("bicycle", 0.95f, 5_012_000L)

        val afterRestart = EveConceptLearning(store)
        assertEquals("دراجة", afterRestart.confirmedNameFor("bicycle"))
        assertEquals(1, afterRestart.recentConfirmedConcepts().size)
    }

    @Test
    fun teachingAnUnrelatedSentenceDoesNotCreateAConcept() {
        val memory = EveConceptLearning(InMemoryStore())
        assertNull(
            memory.teachCurrentObservation(
                "car", 0.94f, 1_000L, "سأعود إلى المنزل لاحقا", true, 6_000_000L
            )
        )
    }
}
