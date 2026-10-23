package com.nova.robot

import org.junit.Assert.*
import org.junit.Test

class NovaVisionEvidenceTest {
    @Test fun faceDetectionSuppressesConflictingGenericAnimalLabel() {
        val summary = NovaVisionEvidence.summarize(
            faceFound = true,
            labels = listOf(
                NovaLabelEvidence("Dog", 0.94f),
                NovaLabelEvidence("Chair", 0.78f)
            )
        )
        assertTrue(summary.contains("أرى أمامي شخصًا"))
        assertTrue(summary.contains("كرسي"))
        assertFalse(summary.contains("كلب"))
        assertFalse(summary.contains("dog"))
    }

    @Test fun singleFrameLabelsAreClearlyDescribedAsUncertain() {
        val summary = NovaVisionEvidence.summarize(
            faceFound = false,
            labels = listOf(NovaLabelEvidence("cup", 0.75f))
        )
        assertTrue(summary.contains("أظن أن في محيطي"))
        assertTrue(summary.contains("كوب"))
        assertTrue(summary.contains("تخمينات"))
    }

    @Test fun lowConfidenceAndUnknownLabelsDoNotBecomeFacts() {
        val summary = NovaVisionEvidence.summarize(
            faceFound = false,
            labels = listOf(
                NovaLabelEvidence("chair", 0.45f),
                NovaLabelEvidence("some unknown obscure label", 0.99f),
                NovaLabelEvidence("dog", Float.NaN)
            )
        )
        assertTrue(summary.contains("دليلًا كافيًا") || summary.contains("لم أتعرف"))
        assertFalse(summary.contains("كرسي"))
    }

    @Test fun weatherAndActivityLabelsRemainTentative() {
        val rain = NovaVisionEvidence.summarize(
            faceFound = false,
            labels = listOf(NovaLabelEvidence("rain", 0.91f))
        )
        assertTrue(rain.contains("مطر"))
        assertTrue(rain.contains("أظن أن في محيطي"))
        assertTrue(rain.contains("تخمينات"))

        val eating = NovaVisionEvidence.summarize(
            faceFound = true,
            labels = listOf(NovaLabelEvidence("person eating", 0.88f))
        )
        assertTrue(eating.contains("أرى أمامي شخصًا"))
        assertTrue(eating.contains("شخص يتناول الطعام"))
        assertTrue(eating.contains("تقريبية"))

        val exercise = NovaVisionEvidence.summarize(
            faceFound = false,
            labels = listOf(NovaLabelEvidence("exercising", 0.90f))
        )
        assertTrue(exercise.contains("يمارس الرياضة"))
        assertTrue(exercise.contains("أظن أن في محيطي"))
    }

    @Test fun faceWithoutOtherEvidenceDoesNotGuessIdentityOrEmotion() {
        val summary = NovaVisionEvidence.summarize(faceFound = true, labels = emptyList())
        assertTrue(summary.contains("لم أتعرف إلى هويته") || summary.contains("لا أستطيع تأكيد شعوره"))
    }

    @Test fun animalBreedLabelsAreSuppressedWhenFaceDetectorFindsPerson() {
        val summary = NovaVisionEvidence.summarize(
            faceFound = true,
            labels = listOf(
                NovaLabelEvidence("Golden Retriever", 0.98f),
                NovaLabelEvidence("German Shepherd", 0.92f),
                NovaLabelEvidence("Table", 0.80f)
            )
        )
        assertTrue(summary.contains("أرى أمامي شخصًا"))
        assertTrue(summary.contains("طاولة"))
        assertFalse(summary.contains("كلب"))
        assertFalse(summary.contains("Golden Retriever"))
        assertFalse(summary.contains("German Shepherd"))
    }

    @Test fun confidenceIsClampedBeforeDisplay() {
        val summary = NovaVisionEvidence.summarize(
            faceFound = false,
            labels = listOf(NovaLabelEvidence("chair", 1.7f))
        )
        assertTrue(summary.contains("كرسي (100٪)"))
        assertFalse(summary.contains("170٪"))
    }
}
