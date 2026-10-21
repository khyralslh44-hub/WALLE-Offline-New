package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EveHumanWellbeingUnderstandingTest {
    @Test fun repeatedSadFaceMeansPossibleDistressButNotConfirmedCrying() {
        val engine = EveHumanWellbeingUnderstanding()
        var result = engine.observe(1_000L, true, "sad", 0.84f)
        result = engine.observe(2_000L, true, "sad", 0.86f)
        result = engine.observe(3_000L, true, "sad", 0.82f)

        assertEquals(EveHumanWellbeingCue.DISTRESS_POSSIBLE, result.cue)
        assertTrue(result.explanationArabic.contains("لا يثبت البكاء"))
    }

    @Test fun repeatedExplicitTearOrCryingLabelsSupportOnlyPossibleCrying() {
        val engine = EveHumanWellbeingUnderstanding()
        engine.observe(1_000L, true, "sad", 0.82f, listOf(EveImageClass("crying", 0.72f)))
        val result = engine.observe(2_200L, true, "sad", 0.85f, listOf(EveImageClass("tears", 0.66f)))

        assertEquals(EveHumanWellbeingCue.CRYING_POSSIBLE, result.cue)
        assertTrue(result.confidence < 0.90f)
        assertTrue(result.explanationArabic.contains("ليس تأكيدًا"))
    }

    @Test fun oneCoughLabelIsNotEnoughAndRepeatedLabelsAreNotADiagnosis() {
        val engine = EveHumanWellbeingUnderstanding()
        val first = engine.observe(1_000L, true, candidates = listOf(EveImageClass("coughing", 0.78f)))
        assertEquals(EveHumanWellbeingCue.UNKNOWN, first.cue)

        val result = engine.observe(3_000L, true, candidates = listOf(EveImageClass("coughing", 0.70f)))
        assertEquals(EveHumanWellbeingCue.COUGH_OR_SNEEZE_CUE_POSSIBLE, result.cue)
        assertTrue(result.explanationArabic.contains("لا يثبت وجود مرض"))
        assertTrue(result.explanationArabic.contains("لا يحدد تشخيصًا"))
    }

    @Test fun noPersonOrWeakCuesCannotCreateWellbeingConclusion() {
        val engine = EveHumanWellbeingUnderstanding()
        engine.observe(1_000L, true, "sad", 0.90f)
        val result = engine.observe(
            2_000L,
            false,
            "sad",
            0.95f,
            listOf(EveImageClass("crying", 0.99f), EveImageClass("sneezing", 0.99f))
        )
        assertEquals(EveHumanWellbeingCue.UNKNOWN, result.cue)
        assertEquals(0f, result.confidence)
    }

    @Test fun staleFramesDoNotChangeTheLatestWellbeingEstimate() {
        val engine = EveHumanWellbeingUnderstanding()
        engine.observe(5_000L, true, "sad", 0.82f)
        val result = engine.observe(4_000L, true, "sad", 0.91f)
        assertEquals(EveHumanWellbeingCue.UNKNOWN, result.cue)
        assertTrue(result.explanationArabic.contains("قديم"))
    }
}
