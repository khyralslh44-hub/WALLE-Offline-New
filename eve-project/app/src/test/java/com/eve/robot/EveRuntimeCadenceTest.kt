package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EveRuntimeCadenceTest {
    @Test
    fun balancedProfilePreservesExistingEveModelCadence() {
        assertEquals(400L, EveRuntimeCadences.BALANCED.cameraFrameIntervalMs)
        assertEquals(1_400L, EveRuntimeCadences.BALANCED.objectDetectionIntervalMs)
        assertEquals(4_500L, EveRuntimeCadences.BALANCED.imageClassificationIntervalMs)
        assertEquals(2_200L, EveRuntimeCadences.BALANCED.poseInferenceIntervalMs)
        assertEquals(400L, EveRuntimeCadences.BALANCED.faceEmotionIntervalMs)
        assertEquals(400L, EveRuntimeCadences.BALANCED.handGestureIntervalMs)
        assertEquals(2_800L, EveRuntimeCadences.BALANCED.faceIdentityIntervalMs)
    }

    @Test
    fun everyCapabilityRemainsScheduledInPowerSaverProfile() {
        val cadence = EveRuntimeCadences.POWER_SAVER
        assertTrue(cadence.cameraFrameIntervalMs > 0L)
        assertTrue(cadence.objectDetectionIntervalMs > 0L)
        assertTrue(cadence.imageClassificationIntervalMs > 0L)
        assertTrue(cadence.poseInferenceIntervalMs > 0L)
        assertTrue(cadence.faceEmotionIntervalMs > 0L)
        assertTrue(cadence.handGestureIntervalMs > 0L)
        assertTrue(cadence.faceIdentityIntervalMs > 0L)
    }

    @Test
    fun onlySystemResourcePressureSelectsPowerSaverProfile() {
        assertEquals(
            EveRuntimeProfile.POWER_SAVER,
            EveRuntimeCadences.profileFor(powerSaveMode = true, lowMemory = false, lowRamDevice = false)
        )
        assertEquals(
            EveRuntimeProfile.POWER_SAVER,
            EveRuntimeCadences.profileFor(powerSaveMode = false, lowMemory = true, lowRamDevice = false)
        )
        assertEquals(
            EveRuntimeProfile.POWER_SAVER,
            EveRuntimeCadences.profileFor(powerSaveMode = false, lowMemory = false, lowRamDevice = true)
        )
        assertEquals(
            EveRuntimeProfile.BALANCED,
            EveRuntimeCadences.profileFor(powerSaveMode = false, lowMemory = false, lowRamDevice = false)
        )
    }

    @Test
    fun powerSaverChangesCadenceButNeverRemovesAFeature() {
        val balanced = EveRuntimeCadences.cadenceFor(EveRuntimeProfile.BALANCED)
        val saver = EveRuntimeCadences.cadenceFor(EveRuntimeProfile.POWER_SAVER)
        assertTrue(saver.cameraFrameIntervalMs >= balanced.cameraFrameIntervalMs)
        assertTrue(saver.objectDetectionIntervalMs >= balanced.objectDetectionIntervalMs)
        assertTrue(saver.imageClassificationIntervalMs >= balanced.imageClassificationIntervalMs)
        assertTrue(saver.poseInferenceIntervalMs >= balanced.poseInferenceIntervalMs)
        assertTrue(saver.faceEmotionIntervalMs >= balanced.faceEmotionIntervalMs)
        assertTrue(saver.handGestureIntervalMs >= balanced.handGestureIntervalMs)
        assertTrue(saver.faceIdentityIntervalMs >= balanced.faceIdentityIntervalMs)
        assertTrue(
            listOf(
                saver.cameraFrameIntervalMs,
                saver.objectDetectionIntervalMs,
                saver.imageClassificationIntervalMs,
                saver.poseInferenceIntervalMs,
                saver.faceEmotionIntervalMs,
                saver.handGestureIntervalMs,
                saver.faceIdentityIntervalMs
            ).all { it > 0L }
        )
    }
}
