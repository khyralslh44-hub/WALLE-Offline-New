package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class EyeExpressionGeneratorTest {
    @Test fun sameStateAndSeedProduceRepeatablePose() {
        val state = EveDecision(
            mood = EveMood.CURIOUS,
            reason = "test",
            valence = 0.4f,
            arousal = 0.7f,
            curiosity = 0.9f,
            familiarity = 0.2f,
            fatigue = 0.1f
        )
        assertEquals(
            EyeExpressionGenerator.generate(state, 1234L),
            EyeExpressionGenerator.generate(state, 1234L)
        )
    }

    @Test fun thousandsOfDistinctParameterizedEyePosesCanBeGenerated() {
        val moods = EveMood.values()
        val profiles = HashSet<EyeExpressionProfile>()
        for (i in 0 until 10_000) {
            val x = i / 10_000f
            val state = EveDecision(
                mood = moods[i % moods.size],
                reason = "expression space test",
                valence = sin(i * 0.053).toFloat(),
                arousal = ((i * 37) % 1000) / 999f,
                curiosity = ((i * 97) % 1000) / 999f,
                familiarity = ((i * 173) % 1000) / 999f,
                fatigue = ((i * 223) % 1000) / 999f
            )
            profiles += EyeExpressionGenerator.generate(state, i.toLong())
        }
        assertTrue("Expected thousands of distinct generated eye poses, got ${profiles.size}", profiles.size >= 9_000)
    }

    @Test fun rejectedLowConfidenceAngryLabelKeepsNeutralRobotEyes() {
        val rejected = EveDecision(
            mood = EveMood.NEUTRAL,
            reason = "uncertain facial-expression model output: label=angry confidence=0.5707551"
        )

        assertEquals(
            "A rejected human-expression prediction must not override EVE's neutral affect.",
            EyeSilhouette.ALMOND,
            EyeExpressionGenerator.generate(rejected, 19L).silhouette
        )
    }

    @Test fun observingAnAngryHumanDoesNotMakeEVELookAngry() {
        val decision = RobotBrain().observeFacialEmotion("angry", 0.85f)
        assertEquals(EveMood.CONCERNED, decision.mood)

        val pose = EyeExpressionGenerator.generate(decision, 23L)
        assertEquals(
            "EVE should react with concern rather than copy the person's angry expression.",
            EyeSilhouette.WORRIED,
            pose.silhouette
        )
        assertTrue(pose.silhouette !in setOf(EyeSilhouette.SHARP, EyeSilhouette.FURIOUS))
    }

    @Test fun affectValuesChangeEyePoseEvenForSameMood() {
        val calm = EveDecision(
            mood = EveMood.NEUTRAL, reason = "calm",
            valence = -0.4f, arousal = 0.15f, curiosity = 0.2f, familiarity = 0.1f, fatigue = 0.8f
        )
        val curious = calm.copy(
            reason = "curious", valence = 0.65f, arousal = 0.85f,
            curiosity = 0.95f, familiarity = 0.8f, fatigue = 0.05f
        )
        assertTrue(EyeExpressionGenerator.generate(calm, 42L) != EyeExpressionGenerator.generate(curious, 42L))
    }

    @Test fun vectorInspiredMoodContoursAreDistinctAndStable() {
        fun silhouette(mood: EveMood) = EyeExpressionGenerator.generate(
            EveDecision(mood = mood, reason = "contour mapping test"),
            seed = 11L
        ).silhouette

        assertEquals(EyeSilhouette.SMILE, silhouette(EveMood.HAPPY))
        assertEquals(EyeSilhouette.ROUND_SURPRISE, silhouette(EveMood.SURPRISED))
        assertEquals(EyeSilhouette.DROOPY, silhouette(EveMood.SLEEPY))
        assertEquals(EyeSilhouette.WORRIED, silhouette(EveMood.CONCERNED))
        assertEquals(EyeSilhouette.SHARP, silhouette(EveMood.ANGRY))
        assertEquals(EyeSilhouette.CURIOUS, silhouette(EveMood.CURIOUS))
        val count = EveMood.values().map(::silhouette).toSet().size
        assertTrue("Expected visibly distinct eye contours across the mood set", count >= 9)
    }
    private fun glyph(
        mood: EveMood,
        reason: String = "test",
        valence: Float = 0.12f,
        arousal: Float = 0.32f,
        fatigue: Float = 0f,
        frustration: Float = 0f
    ): EyeSilhouette = EyeExpressionGenerator.generate(
        EveDecision(
            mood = mood,
            reason = reason,
            valence = valence,
            arousal = arousal,
            fatigue = fatigue,
            frustration = frustration
        ),
        seed = 7L
    ).silhouette

    @Test fun changingDiagnosticReasonAloneDoesNotChangeTheFace() {
        val base = EveDecision(
            mood = EveMood.CURIOUS,
            reason = "saw a new object",
            valence = 0.22f,
            arousal = 0.71f,
            curiosity = 0.89f,
            familiarity = 0.20f,
            fatigue = 0.10f,
            frustration = 0.06f,
            playfulness = 0.70f,
            intent = EveIntent.EXPLORE
        )
        val changedWording = base.copy(reason = "a completely different diagnostic phrase in Arabic")
        assertEquals(
            "Diagnostic wording must not directly select the rendered expression.",
            EyeExpressionGenerator.generate(base, 17L),
            EyeExpressionGenerator.generate(changedWording, 999L)
        )
    }

    private fun glyph(
        mood: EveMood,
        valence: Float = 0.12f,
        arousal: Float = 0.32f,
        curiosity: Float = 0.55f,
        familiarity: Float = 0.05f,
        fatigue: Float = 0f,
        frustration: Float = 0f,
        intent: EveIntent? = null
    ): EyeSilhouette = EyeExpressionGenerator.generate(
        EveDecision(
            mood = mood,
            reason = "diagnostic only; this must not drive the face",
            valence = valence,
            arousal = arousal,
            curiosity = curiosity,
            familiarity = familiarity,
            fatigue = fatigue,
            frustration = frustration,
            intent = intent
        ),
        seed = 7L
    ).silhouette

    @Test fun allReferenceInspiredGlyphsAreReachableFromInternalState() {
        val actual = setOf(
            glyph(EveMood.AWAKE),                                               // neutral / awake
            glyph(EveMood.NEUTRAL),                                             // neutral pixels
            glyph(EveMood.CURIOUS),                                             // curious open eyes
            glyph(EveMood.NEUTRAL, arousal = 0.10f),                           // high blink
            glyph(EveMood.HAPPY, valence = 0.25f),                             // happy
            glyph(EveMood.HAPPY, valence = 0.85f, arousal = 0.70f),            // glee
            glyph(EveMood.NEUTRAL, fatigue = 0.90f),                            // low blink
            glyph(EveMood.CONCERNED, valence = -0.80f, fatigue = 0.75f),       // sad looking down
            glyph(EveMood.CONCERNED, valence = -0.80f, fatigue = 0.05f),       // sad looking up
            glyph(EveMood.CONCERNED, arousal = 0.65f),                         // worried
            glyph(EveMood.FOCUSED, arousal = 0.65f),                           // focused
            glyph(EveMood.IRRITATED, arousal = 0.65f),                         // annoyed
            glyph(EveMood.SURPRISED, valence = 0.12f),                         // surprised
            glyph(EveMood.PLAYFUL, valence = 0.00f),                           // skeptical
            glyph(EveMood.IRRITATED, arousal = 0.15f),                         // bored
            glyph(EveMood.IRRITATED, frustration = 0.65f),                     // unimpressed
            glyph(EveMood.SLEEPY, fatigue = 0.30f),                            // sleepy
            glyph(EveMood.CURIOUS, curiosity = 0.90f, valence = -0.40f),       // suspicious
            glyph(EveMood.FOCUSED, arousal = 0.10f),                          // squint
            glyph(EveMood.ANGRY, arousal = 0.50f, frustration = 0.20f),        // angry
            glyph(EveMood.ANGRY, arousal = 0.85f, frustration = 0.85f),        // furious
            glyph(EveMood.CONCERNED, valence = -0.45f, arousal = 0.90f),       // scared
            glyph(EveMood.SURPRISED, valence = 0.85f),                         // awe
            glyph(EveMood.PLAYFUL, valence = 0.40f),                           // playful
            glyph(EveMood.FAMILIAR, valence = 0.25f, familiarity = 0.50f)      // warm / soft
        )
        assertEquals(
            "Every reference-inspired glyph should be reachable from affect state and intent, not text labels.",
            EyeSilhouette.values().toSet(),
            actual
        )
    }

    @Test fun intentCanChangeEyesWithoutChangingTheCurrentMood() {
        val neutral = EveDecision(mood = EveMood.NEUTRAL, reason = "same mood")
        val explore = EyeExpressionGenerator.generate(neutral.copy(intent = EveIntent.EXPLORE), 8L)
        val rest = EyeExpressionGenerator.generate(neutral.copy(intent = EveIntent.REST), 8L)
        val connect = EyeExpressionGenerator.generate(neutral.copy(intent = EveIntent.CONNECT), 8L)

        assertEquals(EveMood.NEUTRAL, explore.mood)
        assertEquals(EveMood.NEUTRAL, rest.mood)
        assertEquals(EveMood.NEUTRAL, connect.mood)
        assertEquals(EyeSilhouette.CURIOUS, explore.silhouette)
        assertEquals(EyeSilhouette.DROOPY, rest.silhouette)
        assertEquals(EyeSilhouette.SOFT, connect.silhouette)
        assertTrue(setOf(explore.silhouette, rest.silhouette, connect.silhouette).size == 3)
    }
}
