package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EveActivityUnderstandingTest {
    @Test fun offlineIdentityAnswerUsesConfiguredEveNameAndDisclaimsHumanConsciousness() {
        val answer = OfflineEveResponder.answer("ما اسمك", null, 0f, Long.MAX_VALUE)
        assertNotNull(answer)
        assertEquals("identity", answer!!.topic)
        assertTrue(answer.text.contains("اسمي إيف"))
        assertTrue(answer.text.contains("لا أملك وعيًا بشريًا"))
        val intro = OfflineEveResponder.answer("عرفيني بنفسك", null, 0f, Long.MAX_VALUE)
        assertNotNull(intro)
        assertEquals("identity", intro!!.topic)
    }

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

    @Test fun repeatedHandToMouthWithLollipopSuggestsTreatConsumptionNotConfirmedLicking() {
        val engine = EveActivityUnderstanding()
        val mouth = listOf(0.02f,0.07f,0.025f,0.08f,0.02f,0.075f,0.018f,0.08f)
        var last = EveActivityInterpretation(EveHumanActivity.UNKNOWN,0f,0,0,"","",0L)
        for (i in mouth.indices) {
            last = engine.observe(EveHumanFrameEvidence(
                timestampMs = i * 800L, personConfidence = 0.9f,
                handToMouthConfidence = if (i in setOf(1,3,5,7)) 0.92f else 0.08f,
                mouthOpeningRatio = mouth[i], visibleObjectLabels = setOf("lollipop")
            ))
        }
        assertEquals(EveHumanActivity.EATING_TREAT_POSSIBLE,last.activity)
        assertTrue(last.explanationArabic.contains("لا أستطيع تأكيد اللعق"))
    }

    @Test fun gameControllerAndRepeatedHandMotionSuggestPossibleGamePlay() {
        val engine = EveActivityUnderstanding()
        val xs = listOf(0.20f,0.58f,0.24f,0.62f,0.21f,0.60f,0.23f,0.61f)
        var last = EveActivityInterpretation(EveHumanActivity.UNKNOWN,0f,0,0,"","",0L)
        for (i in xs.indices) {
            last = engine.observe(EveHumanFrameEvidence(
                timestampMs = i * 700L, personConfidence = 0.9f, jointMotionScore = 0.35f,
                handCenterX = xs[i], handCenterY = if (i % 2 == 0) 0.35f else 0.55f,
                visibleObjectLabels = setOf("game controller")
            ))
        }
        assertEquals(EveHumanActivity.PLAYING_GAME_POSSIBLE,last.activity)
        assertTrue(last.explanationArabic.contains("قد يكون الشخص يلعب"))
    }

    @Test fun artSupplyAndRepeatedHandMovementSuggestDrawingOrColouring() {
        val engine = EveActivityUnderstanding()
        val xs = listOf(0.20f,0.34f,0.25f,0.40f,0.23f,0.38f,0.21f,0.39f,0.24f)
        var last = EveActivityInterpretation(EveHumanActivity.UNKNOWN,0f,0,0,"","",0L)
        for (i in xs.indices) {
            last = engine.observe(EveHumanFrameEvidence(
                timestampMs = i * 650L, personConfidence = 0.9f, jointMotionScore = 0.22f,
                handCenterX = xs[i], handCenterY = if (i % 2 == 0) 0.54f else 0.46f,
                visibleObjectLabels = setOf("crayon", "coloring book")
            ))
        }
        assertEquals(EveHumanActivity.DRAWING_OR_COLORING_POSSIBLE,last.activity)
        assertTrue(last.explanationArabic.contains("قد يكون الشخص يرسم أو يلوّن"))
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

    @Test fun repeatedCyclicHandMotionCanOnlySuggestExerciseLikeMotion() {
        val engine = EveActivityUnderstanding()
        val positions = listOf(
            0.22f, 0.61f, 0.28f, 0.66f, 0.24f,
            0.64f, 0.27f, 0.62f, 0.23f
        )
        var last = EveActivityInterpretation(
            EveHumanActivity.UNKNOWN, 0f, 0, 0, "", "", 0L
        )
        for (i in positions.indices) {
            last = engine.observe(
                EveHumanFrameEvidence(
                    timestampMs = i * 800L,
                    personConfidence = 0.88f,
                    jointMotionScore = 0.45f,
                    handCenterX = positions[i],
                    handCenterY = if (i % 2 == 0) 0.35f else 0.72f
                )
            )
        }
        assertEquals(EveHumanActivity.EXERCISE_LIKE_MOTION, last.activity)
        assertTrue(last.confidence in 0.55f..0.68f)
        assertTrue(last.explanationArabic.contains("قد تكون تمرينًا"))
        assertTrue(last.explanationArabic.contains("تلويحًا"))
    }

    @Test fun staleFramesAreIgnoredInsteadOfRewindingTheWindow() {
        val engine = EveActivityUnderstanding()
        engine.observe(EveHumanFrameEvidence(2_000L, 0.9f))
        val stale = engine.observe(EveHumanFrameEvidence(1_000L, 0.9f))
        assertEquals(EveHumanActivity.UNKNOWN, stale.activity)
        assertTrue(stale.explanationArabic.contains("إطار قديم"))
    }
}
