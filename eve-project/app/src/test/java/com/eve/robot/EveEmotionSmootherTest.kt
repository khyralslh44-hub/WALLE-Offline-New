package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EveEmotionSmootherTest {
    @Test fun oneFrameCannotCreateAnEmotion() {
        val smoother = EveEmotionSmoother(windowSize = 3, requiredVotes = 2)
        assertNull(smoother.add(EveEmotionPrediction("happy", 0.92f)))
        assertEquals(1, smoother.sampleCount())
    }

    @Test fun twoOfThreeMatchingPredictionsBecomeStable() {
        val smoother = EveEmotionSmoother(windowSize = 3, requiredVotes = 2)
        assertNull(smoother.add(EveEmotionPrediction("happy", 0.91f)))
        val stable = smoother.add(EveEmotionPrediction("happy", 0.87f))
        assertEquals("happy", stable?.label)
        assertEquals(0.89f, stable?.confidence ?: 0f, 0.0001f)
    }

    @Test fun oneNoisyOutlierDoesNotWinTwoOfThreeVote() {
        val smoother = EveEmotionSmoother(windowSize = 3, requiredVotes = 2)
        assertNull(smoother.add(EveEmotionPrediction("happy", 0.92f)))
        assertNull(smoother.add(EveEmotionPrediction("sad", 0.90f)))
        assertEquals("happy", smoother.add(EveEmotionPrediction("happy", 0.88f))?.label)
    }

    @Test fun defaultSmootherRejectsBorderlineRealFacePrediction() {
        val smoother = EveEmotionSmoother()
        assertNull(smoother.add(EveEmotionPrediction("angry", 0.5707551f)))
        assertEquals(0, smoother.sampleCount())
    }

    @Test fun lowConfidenceOrInvalidOutputResetsVotingWindow() {
