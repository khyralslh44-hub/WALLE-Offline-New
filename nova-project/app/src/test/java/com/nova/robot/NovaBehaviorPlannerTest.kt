package com.nova.robot

import org.junit.Assert.assertEquals
import org.junit.Test

class NovaBehaviorPlannerTest {
    @Test
    fun confidentVisualExpressionControlsEyeMood() {
        assertEquals(NovaMood.FRUSTRATED, NovaBehaviorPlanner.chooseFaceMood(
            NovaMood.FRUSTRATED, NovaAffectMood.WARM, true
        ))
    }

    @Test
    fun uncertainExpressionFallsBackToStablePersonalityState() {
        assertEquals(NovaMood.CURIOUS, NovaBehaviorPlanner.chooseFaceMood(null, NovaAffectMood.CALM, true))
        assertEquals(NovaMood.THINKING, NovaBehaviorPlanner.chooseFaceMood(null, NovaAffectMood.CALM, false))
    }
}
