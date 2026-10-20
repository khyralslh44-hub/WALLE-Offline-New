package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EveAutonomousMindTest {
    private fun context(
        now: Long = 30_000L,
        inactive: Long = 24_000L,
        sceneAge: Long = 1_000L,
        scene: String = "أجسام محددة: كوب",
        label: String = "cup",
        confidence: Float = 0.91f,
        face: Boolean = false,
        familiar: Boolean = false,
        activity: EveHumanActivity = EveHumanActivity.UNKNOWN,
        activityConfidence: Float = 0f,
        explore: Boolean = false,
        llmReady: Boolean = false,
        lastReflection: Long = 0L,
        cameraFront: Boolean = false,
        switchAllowed: Boolean = false
    ) = EveMindContext(
        nowMs = now,
        inactiveMs = inactive,
        sceneAgeMs = sceneAge,
        sceneSummary = scene,
        latestLabel = label,
        labelConfidence = confidence,
        faceSeenRecently = face,
        familiarPersonRecognized = familiar,
        activity = activity,
        activityConfidence = activityConfidence,
        explorationEnabled = explore,
        localLanguageReady = llmReady,
        lastReflectionAtMs = lastReflection,
        cameraIsFront = cameraFront,
        cameraSwitchAllowed = switchAllowed
    )

    @Test fun staleScenePlansObservationInsteadOfInventingWhatItSees() {
        val plan = EveAutonomousMind().plan(
            context(sceneAge = Long.MAX_VALUE, scene = "", label = "", confidence = 0f)
        )
        assertEquals(EveMindGoal.OBSERVE_SCENE, plan.goal)
        assertEquals(EveMindAction.WAIT_FOR_FRESH_FRAME, plan.steps.first().action)
        assertTrue(plan.rationaleArabic.contains("قديم"))
    }

    @Test fun humanActivityChangesTheNextGoalAndRemainsQuiet() {
        val plan = EveAutonomousMind().plan(
            context(activity = EveHumanActivity.EXERCISE_LIKE_MOTION, activityConfidence = 0.71f)
        )
        assertEquals(EveMindGoal.FOCUS_ON_HUMAN_ACTIVITY, plan.goal)
        assertEquals(EveMindAction.REVIEW_RECENT_EVIDENCE, plan.steps.first().action)
        assertTrue(plan.rationaleArabic.contains("احتمال"))
    }

    @Test fun explorationCanRequestLocalReflectionOnlyForFreshConfidentEvidence() {
        val plan = EveAutonomousMind().plan(context(explore = true, llmReady = true))
        assertEquals(EveMindGoal.REFLECT_ON_NEW_EVIDENCE, plan.goal)
        assertTrue(plan.steps.any { it.action == EveMindAction.REQUEST_LOCAL_REFLECTION })
        assertTrue(plan.steps.size <= 3)
    }

    @Test fun explorationNeverPlansAnAutomaticCameraLensSwitchWithoutExplicitSafeConditions() {
        val plan = EveAutonomousMind().plan(context(explore = true, llmReady = false))
        assertFalse(plan.steps.any { it.action == EveMindAction.SWITCH_TO_WORLD_CAMERA })
    }

    @Test fun longQuietPeriodChoosesRearCameraExplorationOnlyWhenNoFaceIsPresent() {
        val plan = EveAutonomousMind().plan(
            context(
                now = 60_000L,
                inactive = EveAutonomousMind.AUTO_CAMERA_SWITCH_IDLE_MS,
                sceneAge = Long.MAX_VALUE,
                scene = "",
                label = "",
                confidence = 0f,
                face = false,
                explore = true,
                cameraFront = true,
                switchAllowed = true
            )
        )
        assertEquals(EveMindGoal.EXPLORE_CURRENT_VIEW, plan.goal)
        assertEquals(EveMindAction.SWITCH_TO_WORLD_CAMERA, plan.steps.first().action)
        assertTrue(plan.rationaleArabic.contains("الكاميرا الخلفية"))
    }

    @Test fun explorationWillNotSwitchCameraWhileAPersonIsVisible() {
        val plan = EveAutonomousMind().plan(
            context(
                now = 60_000L,
                inactive = 40_000L,
                sceneAge = Long.MAX_VALUE,
                scene = "",
                label = "",
                confidence = 0f,
                face = true,
                explore = true,
                cameraFront = true,
                switchAllowed = true
            )
        )
        assertFalse(plan.steps.any { it.action == EveMindAction.SWITCH_TO_WORLD_CAMERA })
    }

    @Test fun explorationWillNotSwitchCameraWhenTheCooldownOrPermissionBlocksIt() {
        val plan = EveAutonomousMind().plan(
            context(
                now = 60_000L,
                inactive = 40_000L,
                sceneAge = Long.MAX_VALUE,
                scene = "",
                label = "",
                confidence = 0f,
                explore = true,
                cameraFront = true,
                switchAllowed = false
            )
        )
        assertFalse(plan.steps.any { it.action == EveMindAction.SWITCH_TO_WORLD_CAMERA })
    }

    @Test fun repeatedEvidenceDoesNotGenerateEndlessReflectionLoops() {
        val mind = EveAutonomousMind()
        val first = mind.plan(context(explore = true, llmReady = true))
        mind.recordFeedback(
            EveMindFeedback(
                goal = first.goal,
                action = EveMindAction.REQUEST_LOCAL_REFLECTION,
                succeeded = true,
                note = "cup|أجسام محددة: كوب",
                atMs = 31_000L
            )
        )
        val next = mind.plan(context(now = 32_000L, explore = true, llmReady = true))
        assertTrue(next.goal != EveMindGoal.REFLECT_ON_NEW_EVIDENCE)
    }

    @Test fun aFaceAloneDoesNotBecomeKnownIdentity() {
        val plan = EveAutonomousMind().plan(
            context(face = true, familiar = false, explore = false, llmReady = false)
        )
        assertEquals(EveMindGoal.OBSERVE_SCENE, plan.goal)
        assertTrue(plan.rationaleArabic.contains("لا أفترض هويته"))
    }

    @Test fun onlyARecognizedEnrolledPersonGetsTheConnectionGoal() {
        val plan = EveAutonomousMind().plan(
            context(face = true, familiar = true, explore = false, llmReady = false)
        )
        assertEquals(EveMindGoal.CONNECT_WITH_RECOGNIZED_PERSON, plan.goal)
        assertTrue(plan.steps.any { it.action == EveMindAction.MAINTAIN_QUIET_ATTENTION })
    }

    @Test fun sameInputAndStateAlwaysProducesTheSamePlan() {
        val context = context(explore = true, llmReady = true)
        val first = EveAutonomousMind().plan(context)
        val second = EveAutonomousMind().plan(context)
        assertEquals(first.goal, second.goal)
        assertEquals(first.steps, second.steps)
        assertEquals(first.rationaleArabic, second.rationaleArabic)
    }

    @Test fun plansHaveBoundedStepsAndExpiry() {
        val plan = EveAutonomousMind().plan(context())
        assertTrue(plan.steps.size in 1..3)
        assertEquals(plan.createdAtMs + EveAutonomousMind.PLAN_TTL_MS, plan.expiresAtMs)
    }
}
