package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandGestureInterpreterTest {
    @Test
    fun mapsOnlyKnownConfidentMediaPipeHandSignals() {
        assertEquals("fist", HandGestureMapping.signal("Closed_Fist", 0.90f))
        assertEquals("open_palm", HandGestureMapping.signal("Open_Palm", 0.89f))
        assertEquals("victory", HandGestureMapping.signal("Victory", 0.93f))
        assertEquals("thumb_up", HandGestureMapping.signal("Thumb_Up", 0.91f))
        assertEquals(
            "The category mapping must match the shared 0.60 confidence floor.",
            "thumb_up",
            HandGestureMapping.signal("Thumb_Up", 0.615f)
        )
        assertNull("Below the shared confidence floor should be rejected.", HandGestureMapping.signal("Thumb_Up", 0.59f))
        assertEquals("thumb_down", HandGestureMapping.signal("Thumb_Down", 0.86f))
        assertNull(HandGestureMapping.signal("Open_Palm", 0.30f))
        assertNull(HandGestureMapping.signal("Unknown", 0.99f))
        assertNull(HandGestureMapping.signal("Victory", Float.NaN))
    }

    @Test
    fun requiresStableSamplesAndEmitsOneEventForAHeldPose() {
        val reader = HandGestureInterpreter()
        assertNull(reader.observe("Victory", 0.92f, 0.5f, 0.30f, 100L))
        assertEquals("victory", reader.observe("Victory", 0.92f, 0.5f, 0.30f, 300L))
        assertNull(reader.observe("Victory", 0.93f, 0.5f, 0.30f, 600L))
        assertNull(reader.observe("Victory", 0.94f, 0.5f, 0.30f, 900L))
    }

    @Test
    fun detectsWaveFromOpenPalmMovingSideToSideRatherThanOneStillFrame() {
        val reader = HandGestureInterpreter()
        val points = listOf(0.24f, 0.53f, 0.27f, 0.61f, 0.29f, 0.55f)
        var wave: String? = null
        var at = 100L
        points.forEach { x ->
            // Observe every frame result: a wave can be recognized on either sample in a pair.
            val firstResult = reader.observe("Open_Palm", 0.92f, x, 0.28f, at)
            if (firstResult == "wave") wave = firstResult
            at += 240L

            val secondResult = reader.observe("Open_Palm", 0.91f, x, 0.28f, at)
            if (secondResult == "wave") wave = secondResult
            at += 240L
        }
        assertEquals("wave", wave)
    }

    @Test
    fun detectsFastForwardMotionOfAClosedFistAsAPunchCue() {
        val reader = HandGestureInterpreter()
        assertNull(reader.observe("Closed_Fist", 0.92f, 0.5f, 0.15f, 100L))
        assertEquals("fist", reader.observe("Closed_Fist", 0.92f, 0.5f, 0.16f, 300L))
        assertEquals("punch", reader.observe("Closed_Fist", 0.93f, 0.51f, 0.25f, 520L))
        assertNull(reader.observe("Closed_Fist", 0.95f, 0.50f, 0.26f, 740L))
    }

    @Test
    fun lowConfidenceAndUnknownLabelsNeverClaimARecognizedSignal() {
        val reader = HandGestureInterpreter()
        repeat(4) { index ->
            assertNull(reader.observe("Pointing_Up", 0.40f, 0.5f, 0.25f, index * 200L))
            assertNull(reader.observe("Object", 0.99f, 0.5f, 0.25f, index * 200L + 50L))
        }
        assertFalse(HandGestureMapping.signal("Pointing_Up", 0.50f) != null)
    }
    @Test
    fun recognizedThumbUpCreatesAVisibleHappyEyeDecision() {
        val decision = RobotBrain().observeHandGesture("thumb_up", 0.94f)
        assertEquals(EveMood.HAPPY, decision.mood)
        assertTrue("A confident positive hand cue should be audible once.", decision.playSound)
        assertTrue(decision.reason.contains("thumb_up"))
        val profile = EyeExpressionGenerator.generate(decision, 42L)
        assertEquals(decision.mood, profile.mood)
        assertEquals(EyeSilhouette.SMILE, profile.silhouette)
    }

    @Test
    fun recognizedWaveCreatesAWarmResponseWithoutRequiringTouch() {
        val decision = RobotBrain().observeHandGesture("wave", 0.91f)
        assertTrue(decision.mood == EveMood.HAPPY || decision.mood == EveMood.FAMILIAR)
        assertTrue(decision.playSound)
        assertTrue(decision.reason.contains("wave"))
        val profile = EyeExpressionGenerator.generate(decision, 43L)
        assertTrue(profile.silhouette == EyeSilhouette.SMILE || profile.silhouette == EyeSilhouette.GLEE ||
            profile.silhouette == EyeSilhouette.SOFT)
    }

}
