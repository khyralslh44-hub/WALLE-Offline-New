package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EveFaceCueSmootherTest {
    private fun smile(value: Float, yaw: Float = 0f) =
        EveFaceCue(value, 0.90f, 0.92f, yaw)

    @Test fun aSingleSmileFrameDoesNotChangeTheEmotion() {
        val smoother = EveFaceCueSmoother()
        assertNull(smoother.add(smile(0.98f)))
        assertEquals(1, smoother.sampleCount())
    }

    @Test fun twoOfThreeStableSmilesProduceOneStableSmileCue() {
        val smoother = EveFaceCueSmoother()
        assertNull(smoother.add(smile(0.96f)))
        val stable = smoother.add(smile(0.92f))
        assertNotNull(stable)
        assertTrue(requireNotNull(stable).smilingProbability!! >= 0.72f)
        assertFalse(stable.headYawDegrees.absoluteValue > 22f)
    }

    @Test fun oneOutlierDoesNotOverrideTwoMatchingSamples() {
        val smoother = EveFaceCueSmoother()
        smoother.add(smile(0.91f))
        smoother.add(smile(0.18f, yaw = 40f))
        val stable = smoother.add(smile(0.95f))
        assertNotNull(stable)
        assertTrue(requireNotNull(stable).smilingProbability!! >= 0.72f)
    }

    @Test fun aSingleBlinkIsIgnoredAndRepeatedClosedEyeCuesNeverBecomeSadness() {
        val smoother = EveFaceCueSmoother()
        assertNull(smoother.add(EveFaceCue(0.05f, 0.05f, 0.08f, 0f)))
        assertNull(smoother.add(EveFaceCue(0.05f, 0.06f, 0.09f, 0f)))
        assertNull(smoother.add(EveFaceCue(0.05f, 0.08f, 0.07f, 0f)))
    }

    @Test fun aRotatedFaceRequiresRepeatedEvidenceAndIsNeverAHappyCue() {
        val smoother = EveFaceCueSmoother()
        assertNull(smoother.add(smile(0.99f, yaw = 35f)))
        val stable = smoother.add(smile(0.98f, yaw = 40f))
        assertNotNull(stable)
        assertTrue(requireNotNull(stable).headYawDegrees > 22f)
        assertFalse(requireNotNull(stable).smilingProbability!! >= 0.72f)
    }

    @Test fun missingAndNonFiniteCuesCannotBecomeAnEmotion() {
        val smoother = EveFaceCueSmoother()
        assertNull(smoother.add(EveFaceCue(null, null, null, 0f)))
        assertNull(smoother.add(EveFaceCue(Float.NaN, Float.NaN, 0.9f, 0f)))
        assertNull(smoother.add(EveFaceCue(0.99f, 0.99f, 0.99f, Float.NaN)))
        assertEquals(0, smoother.sampleCount())
    }

    @Test fun resetPreventsAStaleFaceFromAffectingTheNextScene() {
        val smoother = EveFaceCueSmoother()
        smoother.add(smile(0.97f))
        smoother.add(smile(0.95f))
        assertNotNull(smoother.add(smile(0.96f)))
        smoother.reset()
        assertNull(smoother.add(smile(0.96f)))
    }

    @Test fun lowConfidenceAmbiguityDoesNotTriggerStableCue() {
        val smoother = EveFaceCueSmoother()
        repeat(3) {
            assertNull(smoother.add(EveFaceCue(0.53f, 0.44f, 0.48f, 0f)))
        }
    }

    private val Float.absoluteValue: Float get() = kotlin.math.abs(this)
}
