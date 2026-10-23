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
        assertTrue(summary.contains("رصدت وجه شخص"))
        assertTrue(summary.contains("كرسي"))
        assertFalse(summary.contains("كلب"))
        assertFalse(summary.contains("dog"))
    }

    @Test fun singleFrameLabelsAreClearlyDescribedAsUncertain() {
        val summary = NovaVisionEvidence.summarize(
            faceFound = false,
            labels = listOf(NovaLabelEvidence("cup", 0.75f))
        )
        assertTrue(summary.contains("قد يكون"))
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
        assertTrue(summary.contains("غير متأكدة") || summary.contains("دليلًا بصريًا كافيًا"))
        assertFalse(summary.contains("كرسي"))
    }

    @Test fun faceWithoutOtherEvidenceDoesNotGuessIdentityOrEmotion() {
        val summary = NovaVisionEvidence.summarize(faceFound = true, labels = emptyList())
        assertTrue(summary.contains("لا أستطيع تحديد هويته أو شعوره"))
    }
}
