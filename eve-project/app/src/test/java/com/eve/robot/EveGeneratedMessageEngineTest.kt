package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EveGeneratedMessageEngineTest {
    @Test
    fun messageUsesRecentHighConfidenceObservation() {
        val message = EveGeneratedMessageEngine.generate(
            EveNotificationContext(
                hourOfDay = 14,
                sentCount = 0,
                generationSeed = 120L,
                mood = "SLEEPY",
                lastObservationLabel = "cat",
                observationConfidence = 0.94f,
                observationCount = 4,
                observationAgeMinutes = 12L
            )
        )
        assertEquals("recent_visual_observation", message.source)
        assertTrue(message.body.contains("قطة"))
    }

    @Test
    fun recentGameOutcomeTakesPriorityOverVisualObservation() {
        val message = EveGeneratedMessageEngine.generate(
            EveNotificationContext(
                hourOfDay = 15,
                sentCount = 2,
                generationSeed = 450L,
                mood = "HAPPY",
                lastObservationLabel = "banana",
                observationConfidence = 0.99f,
                observationCount = 5,
                observationAgeMinutes = 4L,
                lastGameName = "chess",
                lastGameResult = "player",
                gameAgeMinutes = 35L
            )
        )
        assertEquals("recent_game_player_win", message.source)
        assertTrue(message.body.contains("الشطرنج"))
    }

    @Test
    fun staleOrLowConfidenceObservationIsNotPresentedAsFact() {
        val lowConfidence = EveGeneratedMessageEngine.generate(
            EveNotificationContext(
                hourOfDay = 14,
                sentCount = 0,
                generationSeed = 120L,
                mood = "HAPPY",
                lastObservationLabel = "cat",
                observationConfidence = 0.51f,
                observationCount = 9,
                observationAgeMinutes = 3L
            )
        )
        val stale = EveGeneratedMessageEngine.generate(
            EveNotificationContext(
                hourOfDay = 14,
                sentCount = 0,
                generationSeed = 120L,
                mood = "HAPPY",
                lastObservationLabel = "cat",
                observationConfidence = 0.99f,
                observationCount = 9,
                observationAgeMinutes = 2000L
            )
        )
        assertEquals("mood_warm", lowConfidence.source)
        assertEquals("mood_warm", stale.source)
        assertFalse(lowConfidence.body.contains("قطة"))
        assertFalse(stale.body.contains("قطة"))
    }

    @Test
    fun outputIsDeterministicForTheSameLocalSnapshot() {
        val context = EveNotificationContext(
            hourOfDay = 10,
            sentCount = 3,
            generationSeed = 831L,
            mood = "NEUTRAL",
            moodReason = "contextual idle"
        )
        assertEquals(
            EveGeneratedMessageEngine.generate(context),
            EveGeneratedMessageEngine.generate(context)
        )
    }

    @Test
    fun differentSnapshotsCanGenerateDifferentMessages() {
        val base = EveNotificationContext(
            hourOfDay = 16,
            sentCount = 0,
            generationSeed = 70L,
            mood = "HAPPY"
        )
        val concerned = base.copy(mood = "CONCERNED", moodReason = "uncertain visual label")
        assertNotEquals(
            EveGeneratedMessageEngine.generate(base).source,
            EveGeneratedMessageEngine.generate(concerned).source
        )
    }

    @Test
    fun generatedMessageAlwaysHasReadableTitleAndBody() {
        for (mood in listOf("NEUTRAL", "HAPPY", "SLEEPY", "CONCERNED", "IRRITATED", "ANGRY")) {
            val generated = EveGeneratedMessageEngine.generate(
                EveNotificationContext(
                    hourOfDay = 22,
                    sentCount = mood.length,
                    generationSeed = mood.hashCode().toLong(),
                    mood = mood
                )
            )
            assertTrue("Title should not be blank for $mood", generated.title.isNotBlank())
            assertTrue("Body should not be blank for $mood", generated.body.isNotBlank())
        }
    }
}
