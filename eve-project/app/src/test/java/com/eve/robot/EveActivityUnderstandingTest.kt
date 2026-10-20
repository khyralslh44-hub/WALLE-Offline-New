package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EveActivityUnderstandingTest {
    @Test fun insufficientOrMissingPersonEvidenceNeverInventsAnActivity() {
        val engine = EveActivityUnderstanding()
        val result = engine.observe(
            EveHumanFrameEvidence(
                timestampMs = 1_000L,
                personConfidence = 0.1f,
                visibleObjectLabels = setOf("banana")
            )
        )
        assertEquals(EveHumanActivity.UNKNOWN, result.activity)
        assertEquals(0f, result.confidence)
        assertTrue(result.nextActionArabic.contains("لا تخمّن"))
    }

    @Test fun repeatedHandToMouthWithVisibleFoodProducesCautiousEatingHypothesis() {
        val engine = EveActivityUnderstanding()
        val pattern = listOf(
            0.04f, 0.08f, 0.02f, 0.075f, 0.025f, 0.085f, 0.02f, 0.07f, 0.03f
        )
        var last = EveActivityInterpretation(
            EveHumanActivity.UNKNOWN, 0f, 0, 0, "", "", 0L
        )
        for (i in pattern.indices) {
            val hand = if (i in setOf(1, 3, 5, 7)) 0.92f else 0.12f
            last = engine.observe(
                EveHumanFrameEvidence(
                    timestampMs = i * 800L,
                    personConfidence = 0.90f,
                    handToMouthConfidence = hand,
                    mouthOpeningRatio = pattern[i],
                    jointMotionScore = 0.08f,
                    visibleObjectLabels = setOf("banana")
                )
            )
        }
        assertEquals(EveHumanActivity.EATING_POSSIBLE, last.activity)
        assertTrue(last.confidence in 0.55f..0.82f)
        assertTrue(last.explanationArabic.contains("احتمال"))
        assertTrue(last.nextActionArabic.contains("لا تقاطع"))
    }

    @Test fun repeatedHandToMouthWithFoodCanProduceOnlyACautiousHypothesisWhenMouthSignalIsUnavailable() {
        val engine = EveActivityUnderstanding()
        var last = EveActivityInterpretation(
            EveHumanActivity.UNKNOWN, 0f, 0, 0, "", "", 0L
        )
        for (i in 0..8) {
            val handNearMouth = i in setOf(1, 3, 5, 7)
            last = engine.observe(
                EveHumanFrameEvidence(
                    timestampMs = i * 800L,
                    personConfidence = 0.88f,
                    handToMouthConfidence = if (handNearMouth) 0.86f else 0.08f,
                    // Mouth geometry can be unavailable on a blurry or side-facing frame.
                    mouthOpeningRatio = 0f,
                    visibleObjectLabels = setOf("banana")
                )
            )
        }
        assertEquals(EveHumanActivity.EATING_POSSIBLE, last.activity)
        assertTrue(last.confidence in 0.55f..0.82f)
        assertTrue(last.explanationArabic.contains("احتمال فقط"))
        assertTrue(last.nextActionArabic.contains("لا تقاطع"))
    }

    @Test fun aVisibleCupWithoutRepeatedHandToMouthEvidenceDoesNotImplyDrinking() {
        val engine = EveActivityUnderstanding()
        var last = EveActivityInterpretation(
            EveHumanActivity.UNKNOWN, 0f, 0, 0, "", "", 0L
        )
        for (i in 0..8) {
            last = engine.observe(
                EveHumanFrameEvidence(
                    timestampMs = i * 800L,
                    personConfidence = 0.88f,
                    handToMouthConfidence = 0.10f,
                    mouthOpeningRatio = 0f,
                    visibleObjectLabels = setOf("cup")
                )
            )
        }
        assertEquals(EveHumanActivity.UNKNOWN, last.activity)
        assertEquals(0f, last.confidence)
    }

    @Test fun repeatedMouthMotionWithoutFoodDoesNotClaimThatThePersonIsChewingGum() {
        val engine = EveActivityUnderstanding()
        val pattern = listOf(
            0.018f, 0.070f, 0.021f, 0.082f, 0.019f, 0.077f, 0.022f, 0.084f
        )
        var last = EveActivityInterpretation(
            EveHumanActivity.UNKNOWN, 0f, 0, 0, "", "", 0L
        )
        for (i in pattern.indices) {
            last = engine.observe(
                EveHumanFrameEvidence(
                    timestampMs = i * 800L,
                    personConfidence = 0.93f,
                    handToMouthConfidence = if (i % 2 == 0) 0.1f else 0.15f,
                    mouthOpeningRatio = pattern[i],
                    jointMotionScore = 0.04f
                )
            )
        }
        assertEquals(EveHumanActivity.CHEWING_MOTION_POSSIBLE, last.activity)
        assertTrue(last.explanationArabic.contains("لا أستطيع تمييز العلكة"))
        assertTrue(last.confidence <= 0.82f)
    }

    @Test fun cyclicKneeAndElbowMotionAcrossTimeSuggestsExerciseLikeMotion() {
        val engine = EveActivityUnderstanding()
        val knees = listOf(176f, 142f, 172f, 132f, 166f, 126f, 162f, 130f, 168f)
        var last = EveActivityInterpretation(
            EveHumanActivity.UNKNOWN, 0f, 0, 0, "", "", 0L
        )
        for (i in knees.indices) {
            val elbow = if (i % 2 == 0) 155f else 72f
            last = engine.observe(
                EveHumanFrameEvidence(
                    timestampMs = i * 850L,
                    personConfidence = 0.88f,
                    jointMotionScore = 0.44f,
                    leftKneeAngleDegrees = knees[i],
                    rightKneeAngleDegrees = knees[(i + 2) % knees.size],
                    leftElbowAngleDegrees = elbow,
                    rightElbowAngleDegrees = 180f - elbow
                )
            )
        }
        assertEquals(EveHumanActivity.EXERCISE_LIKE_MOTION, last.activity)
        assertTrue(last.confidence in 0.55f..0.82f)
        assertTrue(last.explanationArabic.contains("قد تكون تمرينًا"))
    }

    @Test fun staleFramesAreIgnoredInsteadOfRewindingTheWindow() {
        val engine = EveActivityUnderstanding()
        engine.observe(EveHumanFrameEvidence(2_000L, 0.9f))
        val stale = engine.observe(EveHumanFrameEvidence(1_000L, 0.9f))
        assertEquals(EveHumanActivity.UNKNOWN, stale.activity)
        assertTrue(stale.explanationArabic.contains("إطار قديم"))
    }
}
