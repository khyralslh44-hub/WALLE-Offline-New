package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EveFacialEmotionClassifierTest {
    @Test fun emotionSmootherRequiresTwoConsistentPredictions() {
        val smoother = EveEmotionSmoother()
        assertNull(smoother.add(EveEmotionPrediction("happy", 0.91f)))
        val stable = smoother.add(EveEmotionPrediction("happy", 0.88f))
        assertNotNull(stable)
        assertEquals("happy", requireNotNull(stable).label)
        assertTrue(stable.confidence in 0.88f..0.92f)
    }

    @Test fun oneContradictoryFrameDoesNotReplaceTwoMatchingPredictions() {
        val smoother = EveEmotionSmoother()
        smoother.add(EveEmotionPrediction("happy", 0.90f))
        smoother.add(EveEmotionPrediction("happy", 0.87f))
        assertEquals("happy", smoother.add(EveEmotionPrediction("sad", 0.91f))?.label)
        assertEquals("sad", smoother.add(EveEmotionPrediction("sad", 0.93f))?.label)
    }

    @Test fun lowConfidenceUnknownAndNonFinitePredictionsResetTheWindow() {
        val smoother = EveEmotionSmoother()
        smoother.add(EveEmotionPrediction("happy", 0.90f))
        assertNull(smoother.add(EveEmotionPrediction("sad", 0.20f)))
        assertEquals(0, smoother.sampleCount())
        assertNull(smoother.add(EveEmotionPrediction("unlisted", 0.99f)))
        assertNull(smoother.add(EveEmotionPrediction("angry", Float.NaN)))
        assertNull(smoother.add(EveEmotionPrediction("fearful", Float.POSITIVE_INFINITY)))
    }

    @Test fun eachExpressionClassProducesDistinctAffectAppropriateMood() {
        val brain = RobotBrain()
        assertEquals(EveMood.HAPPY, brain.observeFacialEmotion("happy", 0.92f).mood)
        assertEquals(EveMood.CONCERNED, brain.observeFacialEmotion("sad", 0.83f).mood)
        assertEquals(EveMood.CONCERNED, brain.observeFacialEmotion("angry", 0.86f).mood)
        assertEquals(EveMood.NEUTRAL, brain.observeFacialEmotion("neutral", 0.88f).mood)
        assertEquals(EveMood.SURPRISED, brain.observeFacialEmotion("surprised", 0.89f).mood)
        assertEquals(EveMood.CONCERNED, brain.observeFacialEmotion("fearful", 0.87f).mood)
    }

    @Test fun uncertaintyNeverRequestsSound() {
        val brain = RobotBrain()
        assertFalse(brain.observeFacialEmotion("happy", 0.25f).playSound)
        assertFalse(brain.observeFacialEmotion("unknown", 0.99f).playSound)
        assertFalse(brain.observeFacialEmotion("angry", Float.NaN).playSound)
    }

    @Test fun happyModelCueCanRequestOnlyOneHighConfidenceTransitionSound() {
        val brain = RobotBrain()
        assertTrue(brain.observeFacialEmotion("happy", 0.97f).playSound)
        assertFalse(brain.observeFacialEmotion("happy", 0.98f).playSound)
        assertFalse(brain.observeFacialEmotion("sad", 0.99f).playSound)
    }
}
