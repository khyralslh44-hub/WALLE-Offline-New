package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EveEmotionSmootherTest {
    @Test
    fun oneFrameCannotCreateAnEmotion() {
        val smoother = EveEmotionSmoother(windowSize = 3, requiredVotes = 2)
        assertNull(smoother.add(EveEmotionPrediction("happy", 0.92f)))
        assertEquals(1, smoother.sampleCount())
    }

    @Test
    fun twoOfThreeMatchingPredictionsBecomeStable() {
        val smoother = EveEmotionSmoother(windowSize = 3, requiredVotes = 2)
        assertNull(smoother.add(EveEmotionPrediction("happy", 0.91f)))
        val stable = smoother.add(EveEmotionPrediction("happy", 0.87f))
        assertEquals("happy", stable?.label)
        assertEquals(0.89f, stable?.confidence ?: 0f, 0.0001f)
    }

    @Test
    fun oneNoisyOutlierDoesNotWinTwoOfThreeVote() {
        val smoother = EveEmotionSmoother(windowSize = 3, requiredVotes = 2)
        assertNull(smoother.add(EveEmotionPrediction("happy", 0.92f)))
        assertNull(smoother.add(EveEmotionPrediction("sad", 0.90f)))
        assertEquals("happy", smoother.add(EveEmotionPrediction("happy", 0.88f))?.label)
    }

    @Test
    fun defaultSmootherRejectsBorderlineRealFacePrediction() {
        val smoother = EveEmotionSmoother()
        assertNull(smoother.add(EveEmotionPrediction("angry", 0.5707551f)))
        assertEquals(0, smoother.sampleCount())
    }

    @Test
    fun lowConfidenceOrInvalidOutputResetsVotingWindow() {
        val smoother = EveEmotionSmoother(windowSize = 3, requiredVotes = 2, minConfidence = 0.38f)
        assertNull(smoother.add(EveEmotionPrediction("happy", 0.85f)))
        assertNull(smoother.add(EveEmotionPrediction("happy", 0.20f)))
        assertEquals(0, smoother.sampleCount())
        assertNull(smoother.add(EveEmotionPrediction("unknown", 0.99f)))
        assertEquals(0, smoother.sampleCount())
        assertNull(smoother.add(EveEmotionPrediction("sad", Float.NaN)))
        assertEquals(0, smoother.sampleCount())
    }

    @Test
    fun sameModelOutputDoesNotCreateRepeatedVoiceCues() {
        val brain = RobotBrain()
        val first = brain.observeFacialEmotion("happy", 0.98f)
        val repeated = brain.observeFacialEmotion("happy", 0.98f)
        assertEquals(EveMood.HAPPY, first.mood)
        assertTrue(first.playSound)
        assertFalse(repeated.playSound)
    }

    @Test
    fun allSupportedEmotionLabelsMapToSafeAffectValues() {
        EveFacialEmotionClassifier.LABELS.forEach { label ->
            val decision = RobotBrain().observeFacialEmotion(label, 0.85f)
            assertTrue("$label: invalid valence", decision.valence in -1f..1f)
            assertTrue("$label: invalid arousal", decision.arousal in 0f..1f)
            assertTrue("$label: invalid curiosity", decision.curiosity in 0f..1f)
        }
    }
}
