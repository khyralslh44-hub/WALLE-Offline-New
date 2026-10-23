package com.nova.robot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaVisionScheduleTest {
    @Test
    fun sceneAndBlendshapePassesAlternateButPoseIsAlwaysEnabledByActivityPolicy() {
        assertTrue(NovaVisionSchedule.includeSceneModels(0L))
        assertFalse(NovaVisionSchedule.includeSceneModels(1L))
        assertTrue(NovaVisionSchedule.includeSceneModels(2L))
        assertFalse(NovaVisionSchedule.includeSceneModels(3L))
    }
}
