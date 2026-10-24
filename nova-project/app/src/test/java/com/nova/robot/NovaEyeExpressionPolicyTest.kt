package com.nova.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaEyeExpressionPolicyTest {
    @Test
    fun sadnessFearAndDisgustHaveDistinctVisualProfiles() {
        val sad = NovaEyeExpressionPolicy.profile(NovaMood.SAD, true)
        val afraid = NovaEyeExpressionPolicy.profile(NovaMood.AFRAID, true)
        val disgusted = NovaEyeExpressionPolicy.profile(NovaMood.DISGUSTED, true)
        assertEquals(NovaEyePalette.VIOLET, sad.palette)
        assertTrue(sad.tear)
        assertEquals(NovaEyePalette.VIOLET, afraid.palette)
        assertTrue(afraid.heightFactor > sad.heightFactor)
        assertEquals(NovaEyePalette.LIME, disgusted.palette)
        assertNotEquals(sad.heightFactor, disgusted.heightFactor)
    }

    @Test
    fun loveUsesHeartEyesAndHappyUsesAnOpenFriendlyShape() {
        val love = NovaEyeExpressionPolicy.profile(NovaMood.LOVE, true)
        val happy = NovaEyeExpressionPolicy.profile(NovaMood.HAPPY, true)
        assertTrue(love.heartShaped)
        assertFalse(happy.heartShaped)
        assertTrue(happy.smiling)
    }

    @Test
    fun jealousyAndDizzinessHaveDistinctReadableEyeProfiles() {
        val jealous = NovaEyeExpressionPolicy.profile(NovaMood.JEALOUS, true)
        val dizzyLeft = NovaEyeExpressionPolicy.profile(NovaMood.DIZZY, true)
        val dizzyRight = NovaEyeExpressionPolicy.profile(NovaMood.DIZZY, false)
        assertEquals(NovaEyePalette.LIME, jealous.palette)
        assertTrue(jealous.brow)
        assertEquals(NovaEyePalette.VIOLET, dizzyLeft.palette)
        assertNotEquals(dizzyLeft.tiltDegrees, dizzyRight.tiltDegrees)
        assertNotEquals(dizzyLeft.heightFactor, dizzyRight.heightFactor)
    }

    @Test
    fun eveDerivedExpressionsAreVisuallyDistinct() {
        val angry = NovaEyeExpressionPolicy.profile(NovaMood.ANGRY, true)
        val excited = NovaEyeExpressionPolicy.profile(NovaMood.EXCITED, true)
        val proud = NovaEyeExpressionPolicy.profile(NovaMood.PROUD, true)
        val determined = NovaEyeExpressionPolicy.profile(NovaMood.DETERMINED, true)
        assertTrue(angry.brow)
        assertTrue(excited.smiling)
        assertTrue(proud.smiling)
        assertTrue(determined.brow)
        assertNotEquals(angry.heightFactor, excited.heightFactor)
        assertNotEquals(proud.palette, determined.palette)
    }

    @Test
    fun curiosityAndConfusionHaveDifferentAsymmetry() {
        val curious = NovaEyeExpressionPolicy.profile(NovaMood.CURIOUS, true)
        val confused = NovaEyeExpressionPolicy.profile(NovaMood.CONFUSED, true)
        assertNotEquals(curious.heightFactor, confused.heightFactor)
        assertTrue(confused.brow)
    }
}
