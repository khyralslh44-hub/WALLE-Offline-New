package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotBrainTest {
    private val brain = RobotBrain()

    @Test fun repeatedItemBecomesFamiliar() {
        assertEquals(EveMood.FAMILIAR, brain.observe("plant", 0.91f, 3).mood)
    }

    @Test fun startupAndCameraEventsAreDecidedByTheCentralBrain() {
        val localBrain = RobotBrain()
        val awake = localBrain.awakenExperience()
        assertEquals(EveMood.AWAKE, awake.mood)
        assertEquals("experience started", awake.reason)

        val switched = localBrain.cameraSwitched("FRONT")
        assertEquals(EveMood.CURIOUS, switched.mood)
        assertTrue(switched.curiosity > awake.curiosity)

        val unavailable = localBrain.cameraUnavailable("permission denied")
        assertEquals(EveMood.CONCERNED, unavailable.mood)
        assertTrue(unavailable.reason.contains("permission denied"))
        assertFalse(unavailable.playSound)
    }

    @Test fun handSignalsChangeTheCentralAffectState() {
        val brain = RobotBrain()
        val wave = brain.observeHandGesture("wave", 0.93f)
        assertEquals(EveMood.HAPPY, wave.mood)
        assertTrue(wave.valence > 0.12f)
        assertTrue(wave.playSound)

        val victory = brain.observeHandGesture("victory", 0.92f)
        assertEquals(EveMood.PLAYFUL, victory.mood)

        val thumbDown = brain.observeHandGesture("thumb_down", 0.91f)
        assertEquals(EveMood.IRRITATED, thumbDown.mood)
        assertTrue(thumbDown.frustration > 0f)

        val punch = brain.observeHandGesture("punch", 0.90f)
        assertEquals(EveMood.CONCERNED, punch.mood)
        assertTrue(punch.arousal > 0.8f)
    }

    @Test fun uncertainHandSignalsDoNotInventAnEmotion() {
        val brain = RobotBrain()
        val veryLowConfidence = brain.observeHandGesture("victory", 0.40f)
        assertEquals(EveMood.CURIOUS, veryLowConfidence.mood)
        assertFalse(veryLowConfidence.playSound)

        // Keep the brain's acceptance gate aligned with the interpreter's 0.60 threshold.
        val belowSharedThreshold = brain.observeHandGesture("thumb_up", 0.59f)
        assertEquals(EveMood.CURIOUS, belowSharedThreshold.mood)
        assertFalse(belowSharedThreshold.playSound)
    }

    @Test fun thumbUpAtInterpreterThresholdProducesHappyAffect() {
        val decision = RobotBrain().observeHandGesture("thumb_up", 0.615f)
        assertEquals(EveMood.HAPPY, decision.mood)
        assertTrue(decision.playSound)
    }

    @Test fun lowConfidenceDoesNotClaimCertainty() {
        val decision = brain.observe("unknown", 0.2f, 0)
        assertEquals(EveMood.CONCERNED, decision.mood)
        assertFalse(decision.playSound)
    }

    @Test fun foodProducesPositiveReaction() {
        assertEquals(EveMood.HAPPY, brain.observe("fruit", 0.9f, 1).mood)
    }

    @Test fun curiosityIsUsedForAnimals() {
        val decision = brain.observe("dog", 0.88f, 1)
        assertEquals(EveMood.CURIOUS, decision.mood)
        assertTrue(decision.curiosity > 0.8f)
        assertTrue(decision.playSound)
    }

    @Test fun genericPatternLabelsDoNotTriggerNoisySound() {
        val decision = brain.observe("pattern", 0.92f, 1)
        assertEquals(EveMood.FOCUSED, decision.mood)
        assertFalse(decision.playSound)
    }

    @Test fun genericLabelsAreNotMistakenForLearnedFamiliarObjects() {
        assertEquals(EveMood.FOCUSED, brain.observe("pattern", 0.92f, 3).mood)
        assertFalse(brain.observe("pattern", 0.92f, 3).playSound)
    }

    @Test fun repeatedRealWorldExperienceChangesMoodToFamiliar() {
        val first = brain.observe("dog", 0.90f, 1)
        val remembered = brain.observe("dog", 0.90f, 3)
        assertEquals(EveMood.CURIOUS, first.mood)
        assertEquals(EveMood.FAMILIAR, remembered.mood)
        assertTrue(remembered.familiarity > first.familiarity)
        assertFalse(remembered.playSound)
    }

    @Test fun localPositiveAffinityMakesRepeatedObjectFeelFamiliar() {
        val noAffinity = RobotBrain().observe("dog", 0.90f, 2, learnedAffinity = 0f)
        val learned = RobotBrain().observe("dog", 0.90f, 2, learnedAffinity = 0.65f)
        assertTrue(learned.valence > noAffinity.valence)
        assertEquals(EveMood.FAMILIAR, learned.mood)
        assertTrue(learned.familiarity > noAffinity.familiarity)
        assertFalse(learned.playSound)
    }

    @Test fun casualTouchIsSilentButLongPressCanSpeak() {
        assertFalse(brain.touched(false).playSound)
        assertTrue(brain.touched(true).playSound)
    }

    @Test fun idleExpressionsNeverPlaySound() {
        assertFalse(brain.idle(12_000L, 3).playSound)
        assertFalse(brain.idle(45_000L, 8).playSound)
    }

    @Test fun strongHumanSmileProducesHappyAffectAndOneCue() {
        val first = brain.observeHumanFace(0.96f, 0.92f, 0.91f, 0f)
        assertEquals(EveMood.HAPPY, first.mood)
        assertTrue(first.valence > 0.2f)
        assertTrue(first.playSound)
        val repeated = brain.observeHumanFace(0.95f, 0.90f, 0.90f, 0f)
        assertEquals(EveMood.HAPPY, repeated.mood)
        assertFalse(repeated.playSound)
    }

    @Test fun partialFaceDoesNotInventAnEmotionOrPlaySound() {
        val decision = brain.observeHumanFace(null, null, null, 0f)
        assertEquals(EveMood.NEUTRAL, decision.mood)
        assertFalse(decision.playSound)
    }

    @Test fun sideFacingFaceDoesNotTriggerVoice() {
        val decision = brain.observeHumanFace(0.98f, 0.95f, 0.95f, 35f)
        assertEquals(EveMood.CURIOUS, decision.mood)
        assertFalse(decision.playSound)
    }

    @Test fun closedEyesOverrideConflictingHighSmileProbability() {
        val decision = RobotBrain().observeHumanFace(0.99f, 0.05f, 0.08f, 0f)
        assertEquals(EveMood.FOCUSED, decision.mood)
        assertFalse(decision.playSound)
    }


    @Test fun oneClosedEyeFrameIsNotCalledSadnessOrSleep() {
        val decision = brain.observeHumanFace(0.10f, 0.08f, 0.12f, 0f)
        assertEquals(EveMood.FOCUSED, decision.mood)
        assertFalse(decision.playSound)
    }

    @Test fun idleEventuallySleeps() {
        assertEquals(EveMood.SLEEPY, RobotBrain().idle(45_000L, 1).mood)
        assertEquals(EveMood.NEUTRAL, RobotBrain().idle(8_000L, 2).mood)
    }

    @Test fun autonomousIdleFollowsContextualStagesRatherThanRandomMoodPicks() {
        val localBrain = RobotBrain()
        val stages = listOf(8_000L, 18_000L, 35_000L, 65_000L, 100_000L)
            .mapIndexed { index, quietFor -> localBrain.idle(quietFor, index).mood }
        assertEquals(
            listOf(EveMood.NEUTRAL, EveMood.CURIOUS, EveMood.PLAYFUL, EveMood.SLEEPY, EveMood.CURIOUS),
            stages
        )
        assertEquals(RobotBrain().idle(18_000L, 1).mood, RobotBrain().idle(18_000L, 99).mood)
    }

    @Test fun repeatedRapidTouchesEscalateOnlyToBoundedIrritationAndAnger() {
        val localBrain = RobotBrain()
        val moments = listOf(1_000L, 1_800L, 2_600L, 3_400L, 4_200L)
        val decisions = moments.map { localBrain.touched(longPress = false, eventTimeMs = it) }

        assertEquals(EveMood.CURIOUS, decisions[0].mood)
        assertEquals(EveMood.CURIOUS, decisions[1].mood)
        assertEquals(EveMood.IRRITATED, decisions[2].mood)
        assertEquals(EveMood.IRRITATED, decisions[3].mood)
        assertEquals(EveMood.ANGRY, decisions[4].mood)
        assertTrue(decisions[2].frustration >= 0.42f)
        assertTrue(decisions[4].frustration >= 0.80f)
        assertTrue(decisions[2].playSound)
        assertFalse(decisions[3].playSound)
        assertTrue(decisions[4].playSound)
    }

    @Test fun gentleLongTouchReducesFrustrationAndReturnsToWarmMood() {
        val localBrain = RobotBrain()
        listOf(1_000L, 1_800L, 2_600L, 3_400L, 4_200L)
            .forEach { localBrain.touched(longPress = false, eventTimeMs = it) }
        val angry = localBrain.touched(longPress = false, eventTimeMs = 4_900L)
        val reassured = localBrain.touched(longPress = true, eventTimeMs = 6_000L)

        assertEquals(EveMood.ANGRY, angry.mood)
        assertEquals(EveMood.HAPPY, reassured.mood)
        assertTrue(reassured.frustration < angry.frustration)
        assertTrue(reassured.playSound)
    }

    @Test fun frustrationCoolsDownAfterQuietTime() {
        val localBrain = RobotBrain()
        listOf(1_000L, 1_800L, 2_600L, 3_400L, 4_200L)
            .forEach { localBrain.touched(longPress = false, eventTimeMs = it) }
        val angry = localBrain.touched(longPress = false, eventTimeMs = 4_900L)
        val cooling = localBrain.idle(6_000L, 1)
        val calmer = localBrain.idle(30_000L, 2)

        assertEquals(EveMood.ANGRY, angry.mood)
        assertTrue(cooling.frustration < angry.frustration)
        // A very high frustration level should remain visibly angry during a short pause;
        // cooling is gradual rather than an immediate forced drop to irritation.
        assertEquals(EveMood.ANGRY, cooling.mood)
        assertEquals(EveIntent.REGULATE, cooling.intent)
        assertTrue(calmer.frustration < cooling.frustration)
    }

    @Test fun aHumanAngryExpressionDoesNotMakeEVEAngryAtTheHuman() {
        val decision = RobotBrain().observeFacialEmotion("angry", 0.96f)
        assertEquals(EveMood.CONCERNED, decision.mood)
        assertFalse(decision.playSound)
    }

    @Test fun stablePersonalityTraitsShapeIdleBehaviorWithoutRandomSelection() {
        val playful = RobotBrain(EvePersonality(playfulness = 0.95f)).idle(35_000L, 4)
        val reserved = RobotBrain(EvePersonality(playfulness = 0.10f)).idle(35_000L, 4)
        assertEquals(EveMood.PLAYFUL, playful.mood)
        assertEquals(EveIntent.PLAY, playful.intent)
        assertEquals(EveMood.FOCUSED, reserved.mood)
        assertEquals(EveIntent.FOCUS, reserved.intent)
        assertEquals(playful.mood, RobotBrain(EvePersonality(playfulness = 0.95f)).idle(35_000L, 88).mood)
    }

    @Test fun autonomousGoalRespondsToRestNeedAndRecentFrustration() {
        val restedLater = RobotBrain().idle(180_000L, 30)
        assertEquals(EveIntent.REST, restedLater.intent)
        assertEquals(EveMood.SLEEPY, restedLater.mood)

        val overstimulated = RobotBrain()
        repeat(5) { index -> overstimulated.touched(longPress = false, eventTimeMs = 1_000L + index * 500L) }
        val regulating = overstimulated.idle(6_000L, 4)
        assertEquals(EveIntent.REGULATE, regulating.intent)
        assertEquals(EveMood.ANGRY, regulating.mood)
    }
    
    @Test fun learnedPersonalityUpdatesFutureResponsesWithoutResettingCurrentAffect() {
        val brain = RobotBrain(EvePersonality(warmth = 0.25f, curiosityBias = 0.30f))
        val before = brain.awakenExperience()
        brain.updatePersonality(EvePersonality(warmth = 0.90f, curiosityBias = 0.85f, playfulness = 0.90f))
        val after = brain.touched(longPress = true)

        assertTrue("A warmer learned personality should respond more warmly.", after.valence > before.valence)
        assertEquals(EveMood.HAPPY, after.mood)
        assertTrue(after.curiosity > before.curiosity)
        assertTrue(after.playfulness > before.playfulness)
    }

    @Test fun theMainEyeMoodsHaveClearlyDifferentSilhouettes() {
        fun pose(mood: EveMood) = EyeExpressionGenerator.generate(
            EveDecision(mood = mood, reason = "expression clarity check"),
            seed = 7L
        )

        val neutral = pose(EveMood.NEUTRAL)
        val surprised = pose(EveMood.SURPRISED)
        val happy = pose(EveMood.HAPPY)
        val irritated = pose(EveMood.IRRITATED)
        val angry = pose(EveMood.ANGRY)
        val sleepy = pose(EveMood.SLEEPY)
        val playful = pose(EveMood.PLAYFUL)

        assertTrue("Surprise should open the eyes wider than neutral", surprised.opening > neutral.opening)
        assertTrue("Happiness should use a softer, narrower smile-eye pose", happy.opening < neutral.opening)
        assertTrue("Irritation should narrow the eyes", irritated.opening < neutral.opening)
        assertTrue("Anger should be more narrowed than irritation", angry.opening < irritated.opening)
        assertTrue("Anger should use a sharper tilt than irritation", angry.tilt > irritated.tilt)
        assertTrue("Anger should squint more strongly than irritation", angry.squint < irritated.squint)
        assertTrue("Sleepiness should be the most closed expression", sleepy.opening < angry.opening)
        assertTrue("Playfulness should include a noticeable side glance", playful.gazeX >= 0.045f)
    }

    @Test fun sameAffectSnapshotAlwaysProducesTheSameEyePose() {
        val decision = RobotBrain().observe("cat", 0.92f, 1)
        val first = EyeExpressionGenerator.generate(decision, seed = 17L)
        val second = EyeExpressionGenerator.generate(decision, seed = 999L)
        assertEquals(first, second)
    }

    @Test fun familiarityInfluencesIdleResponseInsteadOfAForcedMood() {
        val localBrain = RobotBrain()
        repeat(4) { localBrain.observe("dog", 0.90f, 3) }
        assertEquals(EveMood.FAMILIAR, localBrain.idle(18_000L, 2).mood)
    }
    @Test
    fun gameOutcomesUpdateEvesSharedAffectStateWithoutVoiceSpam() {
        val localBrain = RobotBrain()

        val start = localBrain.gameOutcome("paint_shot", "start")
        assertEquals(EveMood.PLAYFUL, start.mood)
        assertFalse(start.playSound)

        val win = localBrain.gameOutcome("paint_shot", "player")
        assertEquals(EveMood.HAPPY, win.mood)
        assertFalse(win.playSound)
        assertTrue(win.valence > start.valence)

        val miss = localBrain.gameOutcome("target", "miss")
        assertEquals(EveMood.FOCUSED, miss.mood)
        assertFalse(miss.playSound)

        val draw = localBrain.gameOutcome("chess", "draw")
        assertEquals(EveMood.CURIOUS, draw.mood)
        assertFalse(draw.playSound)
    }

}
