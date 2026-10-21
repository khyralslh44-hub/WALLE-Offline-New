package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class EyeExpressionGeneratorTest {
    @Test fun strongShakeUsesItsOwnDizzyDrawingNotTheConfusedDrawing() {
        val context = EveDecision(
            mood = EveMood.CONCERNED,
            reason = "strong physical shake",
            valence = -0.3f,
            arousal = 0.9f
        )
        val dizzy = EyeExpressionGenerator.generateDizzy(context)
        val confused = EyeExpressionGenerator.generate(
            context.copy(mood = EveMood.CURIOUS),
            77L
        )

        assertEquals(EyeSilhouette.DIZZY, dizzy.silhouette)
        assertTrue("Dizzy eyes need a distinct profile, not a reused confused face.",
            dizzy.silhouette != confused.silhouette)
        assertTrue("The unique dizzy glyph should remain visibly open.",
            dizzy.opening >= 0.86f)
    }

    @Test fun jealousyPrideAndAffectionHaveThreeDistinctSingleDrawingSilhouettes() {
        val jealous = RobotBrain().observeSocialCompetition("social_competition", 0.90f)
        val proud = RobotBrain().recordAchievement("task_complete", 0.96f)
        val affectionate = RobotBrain().receiveReassurance("calm_voice", 0.92f)

        val jealousyPose = EyeExpressionGenerator.generate(jealous, 201L).silhouette
        val pridePose = EyeExpressionGenerator.generate(proud, 202L).silhouette
        val affectionPose = EyeExpressionGenerator.generate(affectionate, 203L).silhouette

        assertEquals(EyeSilhouette.JEALOUS, jealousyPose)
        assertEquals(EyeSilhouette.PROUD, pridePose)
        assertEquals(EyeSilhouette.AFFECTIONATE, affectionPose)
        assertEquals(
            "Jealousy, pride, and affection must not reuse or layer the same eye drawing.",
            3,
            setOf(jealousyPose, pridePose, affectionPose).size
        )
    }

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

    @Test fun learnedPlayfulnessChangesEyeMicroExpressionForSameMood() {
        val restrained = EveDecision(
            mood = EveMood.NEUTRAL,
            reason = "same context",
            valence = 0.20f,
            arousal = 0.40f,
            curiosity = 0.55f,
            familiarity = 0.10f,
            playfulness = 0.15f
        )
        val playful = restrained.copy(playfulness = 0.90f)
        val restrainedPose = EyeExpressionGenerator.generate(restrained, 44L)
        val playfulPose = EyeExpressionGenerator.generate(playful, 44L)

        assertEquals(EveMood.NEUTRAL, restrainedPose.mood)
        assertEquals(EveMood.NEUTRAL, playfulPose.mood)
        assertTrue(
            "A learned character trait must influence subtle face geometry even when mood is unchanged.",
            restrainedPose != playfulPose
        )
        assertTrue(
            "The playful character should have a more expressive eye asymmetry.",
            playfulPose.asymmetry > restrainedPose.asymmetry
        )
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
            glyph(EveMood.FAMILIAR, valence = 0.25f, familiarity = 0.50f),     // warm / soft
            glyph(EveMood.CONCERNED, valence = -0.08f, arousal = 0.36f,
                frustration = 0.10f),                                           // confused
            glyph(EveMood.CURIOUS, valence = 0.30f, arousal = 0.68f,
                curiosity = 0.70f),                                             // anticipating
            glyph(EveMood.CONCERNED, valence = -0.42f, arousal = 0.35f,
                fatigue = 0.10f, frustration = 0.10f),                           // disappointed
            EyeExpressionGenerator.generateDizzy(
                EveDecision(EveMood.CONCERNED, "confirmed strong physical shake")
            ).silhouette                                                        // dedicated dizzy pose
,
            EyeExpressionGenerator.generate(
                EveDecision(EveMood.IRRITATED, "high-confidence social competition", jealousy = 0.95f), 11L
            ).silhouette                                                        // unique jealousy glyph
,
            EyeExpressionGenerator.generate(
                EveDecision(EveMood.PLAYFUL, "confirmed achievement", pride = 0.95f), 12L
            ).silhouette                                                        // unique pride glyph
,
            EyeExpressionGenerator.generate(
                EveDecision(EveMood.FAMILIAR, "confirmed reassurance", reassurance = 0.95f), 13L
            ).silhouette                                                        // unique affection glyph
        )
        assertEquals(
            "Every reference-inspired glyph should be reachable from affect state and intent, not text labels.",
            EyeSilhouette.values().toSet(),
            actual
        )
    }

    @Test fun subtleEmotionStatesHaveDistinctReadableExpressions() {
        val confused = EyeExpressionGenerator.generate(
            EveDecision(EveMood.CONCERNED, "uncertain", valence = -0.08f, arousal = 0.36f,
                curiosity = 0.55f, fatigue = 0.10f, frustration = 0.10f), 1L)
        val anticipating = EyeExpressionGenerator.generate(
            EveDecision(EveMood.CURIOUS, "expecting a familiar event", valence = 0.30f,
                arousal = 0.68f, curiosity = 0.70f), 1L)
        val disappointed = EyeExpressionGenerator.generate(
            EveDecision(EveMood.CONCERNED, "mild disappointment", valence = -0.42f,
                arousal = 0.35f, fatigue = 0.10f, frustration = 0.10f), 1L)

        assertEquals(EyeSilhouette.CONFUSED, confused.silhouette)
        assertEquals(EyeSilhouette.ANTICIPATING, anticipating.silhouette)
        assertEquals(EyeSilhouette.DISAPPOINTED, disappointed.silhouette)
        assertEquals("Affect nuances change eye poses, not the established mood label.",
            EveMood.CONCERNED, confused.mood)
        assertTrue(setOf(confused.silhouette, anticipating.silhouette, disappointed.silhouette).size == 3)
    }

    @Test fun generatedFaceKeepsItsGoalForStateDrivenMicroMovement() {
        val exploring = EyeExpressionGenerator.generate(
            EveDecision(
                mood = EveMood.CURIOUS,
                reason = "a new unfamiliar object",
                valence = 0.30f,
                curiosity = 0.78f,
                arousal = 0.68f,
                intent = EveIntent.EXPLORE
            ),
            seed = 20261021L
        )
        val resting = EyeExpressionGenerator.generate(
            EveDecision(
                mood = EveMood.SLEEPY,
                reason = "energy recovery",
                fatigue = 0.85f,
                intent = EveIntent.REST
            ),
            seed = 20261021L
        )

        assertEquals(EveIntent.EXPLORE, exploring.intent)
        assertEquals(EveIntent.REST, resting.intent)
        assertEquals(EyeSilhouette.ANTICIPATING, exploring.silhouette)
        assertEquals(EyeSilhouette.DROOPY, resting.silhouette)
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

    @Test fun sleepyMoodUsesDroopyLidsInsteadOfBlinkSlitsAtHighFatigue() {
        val pose = EyeExpressionGenerator.generate(
            EveDecision(
                mood = EveMood.SLEEPY,
                reason = "long quiet period",
                valence = -0.12f,
                arousal = 0.12f,
                curiosity = 0.35f,
                fatigue = 0.94f
            ),
            seed = 2026L
        )
        assertEquals(
            "Sustained sleepiness must use droopy eyelids; BLINK_LOW is reserved for a blink expression.",
            EyeSilhouette.DROOPY,
            pose.silhouette
        )
    }

    @Test fun sustainedRestAndFatigueUseDroopyLidsNotBlinkSlits() {
        val tiredRest = EyeExpressionGenerator.generate(
            EveDecision(
                mood = EveMood.SLEEPY,
                reason = "autonomous rest",
                valence = -0.10f,
                arousal = 0.12f,
                curiosity = 0.25f,
                fatigue = 0.96f,
                intent = EveIntent.REST
            ),
            seed = 20261020L
        )
        val tiredFocus = EyeExpressionGenerator.generate(
            EveDecision(
                mood = EveMood.FOCUSED,
                reason = "slow focus",
                valence = 0f,
                arousal = 0.50f,
                curiosity = 0.30f,
                fatigue = 0.92f,
                intent = EveIntent.FOCUS
            ),
            seed = 20261020L
        )
        assertEquals(EyeSilhouette.DROOPY, tiredRest.silhouette)
        assertEquals(EyeSilhouette.DROOPY, tiredFocus.silhouette)
    }

    @Test
    fun socialAffectDrivesSelectJealousProudAndReassuredEyePoses() {
        val jealous = EyeExpressionGenerator.generate(
            EveDecision(
                mood = EveMood.IRRITATED,
                reason = "explicit social competition cue",
                jealousy = 0.90f
            ),
            seed = 20261021L
        )
        val proud = EyeExpressionGenerator.generate(
            EveDecision(
                mood = EveMood.PLAYFUL,
                reason = "confirmed achievement",
                pride = 0.90f
            ),
            seed = 20261021L
        )
        val reassured = EyeExpressionGenerator.generate(
            EveDecision(
                mood = EveMood.FAMILIAR,
                reason = "confirmed reassurance cue",
                reassurance = 0.90f
            ),
            seed = 20261021L
        )

        assertEquals(EyeSilhouette.SUSPICIOUS, jealous.silhouette)
        assertEquals(EyeSilhouette.GLEE, proud.silhouette)
        assertEquals(EyeSilhouette.SOFT, reassured.silhouette)
        assertEquals(
            "The new social-affect layer must reuse the established cyan LED expression system.",
            EveMood.IRRITATED,
            jealous.mood
        )
    }

    @Test fun primaryEmotionRemainsReadableWhenIntentSuggestsSomethingElse() {
        fun pose(
            mood: EveMood,
            intent: EveIntent,
            valence: Float = 0.12f,
            arousal: Float = 0.40f,
            fatigue: Float = 0f,
            frustration: Float = 0.20f
        ) = EyeExpressionGenerator.generate(
            EveDecision(
                mood = mood,
                reason = "emotion-legibility regression test",
                valence = valence,
                arousal = arousal,
                fatigue = fatigue,
                frustration = frustration,
                intent = intent
            ),
            seed = 20261021L
        )

        assertEquals(
            "A social goal must not hide a happy smile.",
            EyeSilhouette.SMILE,
            pose(EveMood.HAPPY, EveIntent.CONNECT, valence = 0.25f).silhouette
        )
        assertEquals(
            "A social goal must not hide an angry expression.",
            EyeSilhouette.SHARP,
            pose(EveMood.ANGRY, EveIntent.CONNECT, arousal = 0.50f).silhouette
        )
        assertEquals(
            "A play goal must not hide sustained sleepiness.",
            EyeSilhouette.DROOPY,
            pose(EveMood.SLEEPY, EveIntent.PLAY, fatigue = 0.92f).silhouette
        )
        assertEquals(
            "A rest goal must not hide surprise.",
            EyeSilhouette.ROUND_SURPRISE,
            pose(EveMood.SURPRISED, EveIntent.REST, valence = 0.12f).silhouette
        )
    }

}
