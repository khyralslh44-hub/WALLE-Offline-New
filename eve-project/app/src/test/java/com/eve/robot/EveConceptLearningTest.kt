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
        , evidenceSource = EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)
        assertEquals(EveConceptLearningStatus.WAITING_FOR_VERIFICATION, taught?.status)
        assertNull(memory.confirmedNameFor("car"))

        assertEquals(1, memory.observe("car", 0.91f, 1_003_000L, EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)?.evidenceCount)
        assertEquals(2, memory.observe("car", 0.89f, 1_006_000L, EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)?.evidenceCount)
        assertEquals(EveConceptLearningStatus.VERIFICATION_PROGRESS,
            memory.observe("car", 0.94f, 1_009_000L, EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)?.status)
        // Three observations are not enough until the minimum evidence time span is reached.
        assertNull(memory.confirmedNameFor("car"))

        val confirmed = memory.observe("car", 0.92f, 1_012_000L, EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)
        assertEquals(EveConceptLearningStatus.CONFIRMED, confirmed?.status)
        assertEquals("سيارة", memory.confirmedNameFor("car"))
        assertTrue(memory.isConfirmed("car"))
    }

    @Test
    fun repeatedCallbacksFromTheSameFrameDoNotIncreaseEvidenceCount() {
        val memory = EveConceptLearning(InMemoryStore())
        memory.teachCurrentObservation("cup", 0.95f, 500L, "هذا كوب", false, 2_000_000L, evidenceSource = EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)

        assertEquals(1, memory.observe("cup", 0.92f, 2_003_000L, EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)?.evidenceCount)
        assertEquals(1, memory.observe("cup", 0.99f, 2_003_400L, EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)?.evidenceCount)
        assertEquals(2, memory.observe("cup", 0.90f, 2_006_000L, EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)?.evidenceCount)
        assertFalse(memory.isConfirmed("cup"))
    }

    @Test
    fun weakOrStaleVisualEvidenceCannotBeLinkedToAUserCorrection() {
        val memory = EveConceptLearning(InMemoryStore())
        val weak = memory.teachCurrentObservation("car", 0.51f, 500L, "هذه سيارة", false, 3_000_000L, evidenceSource = EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)
        assertEquals(EveConceptLearningStatus.REJECTED, weak?.status)
        assertNull(memory.confirmedNameFor("car"))

        val stale = memory.teachCurrentObservation("car", 0.95f, 120_000L, "هذه سيارة", false, 3_000_000L, evidenceSource = EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)
        assertEquals(EveConceptLearningStatus.REJECTED, stale?.status)
        assertFalse(memory.isConfirmed("car"))
    }

    @Test
    fun aBareWordIsAcceptedOnlyAsAnAnswerToEvesRecentQuestion() {
        val memory = EveConceptLearning(InMemoryStore())
        assertNull(
            memory.teachCurrentObservation("car", 0.92f, 2_000L, "سيارة", false, 4_000_000L, evidenceSource = EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)
        )

        val answer = memory.teachCurrentObservation(
            "car", 0.92f, 2_000L, "سيارة", true, 4_000_000L
        , evidenceSource = EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)
        assertEquals(EveConceptLearningStatus.WAITING_FOR_VERIFICATION, answer?.status)
        assertNull(memory.confirmedNameFor("car"))
    }

    @Test
    fun pendingAndConfirmedKnowledgeSurviveRecreatingTheLearningEngine() {
        val store = InMemoryStore()
        val first = EveConceptLearning(store)
        first.teachCurrentObservation("bicycle", 0.93f, 500L, "هذه دراجة", false, 5_000_000L, evidenceSource = EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)
        first.observe("bicycle", 0.91f, 5_003_000L, EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)
        first.observe("bicycle", 0.94f, 5_006_000L, EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)
        first.observe("bicycle", 0.96f, 5_009_000L, EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)
        first.observe("bicycle", 0.95f, 5_012_000L, EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)

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
            , evidenceSource = EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)
        )
    }
    @Test
    fun genericPersonAndFaceLabelsCannotBeStoredAsObjectConcepts() {
        val labels = listOf("person", "face", "human", "man", "شخص", "وجه", "انسان")
        labels.forEach { label ->
            val memory = EveConceptLearning(InMemoryStore())
            val result = memory.teachCurrentObservation(
                visualLabel = label,
                visualConfidence = 0.96f,
                observationAgeMs = 400L,
                utterance = "هذا صديقي",
                allowBareAnswer = false,
                nowMs = 7_000_000L
            , evidenceSource = EveConceptEvidenceSource.OBJECT_DETECTOR_BOX)
            assertEquals("Human label must not become an object concept: $label",
                EveConceptLearningStatus.REJECTED, result?.status)
            assertNull(memory.confirmedNameFor(label))
            assertFalse(memory.isConfirmed(label))
            assertNull(memory.observe(label, 0.99f, 7_010_000L, EveConceptEvidenceSource.OBJECT_DETECTOR_BOX))
        }
    }


    @Test
    fun wholeImageClassifierCannotTeachOrConfirmAConcept() {
        val memory = EveConceptLearning(InMemoryStore())
        val rejected = memory.teachCurrentObservation(
            visualLabel = "car",
            visualConfidence = 0.96f,
            observationAgeMs = 300L,
            utterance = "هذه سيارة",
            allowBareAnswer = false,
            nowMs = 8_000_000L,
            evidenceSource = EveConceptEvidenceSource.WHOLE_IMAGE_CLASSIFIER
        )
        assertEquals(EveConceptLearningStatus.REJECTED, rejected?.status)
        assertNull(memory.confirmedNameFor("car"))

        memory.teachCurrentObservation(
            visualLabel = "car",
            visualConfidence = 0.96f,
            observationAgeMs = 300L,
            utterance = "هذه سيارة",
            allowBareAnswer = false,
            nowMs = 8_000_000L,
            evidenceSource = EveConceptEvidenceSource.OBJECT_DETECTOR_BOX
        )
        assertNull(
            memory.observe(
                "car", 0.99f, 8_004_000L,
                EveConceptEvidenceSource.WHOLE_IMAGE_CLASSIFIER
            )
        )
        assertFalse(memory.isConfirmed("car"))
    }

}
