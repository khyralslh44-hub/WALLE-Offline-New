package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EveRoomUnderstandingTest {
    @Test fun aSingleAmbiguousObjectDoesNotInventARoom() {
        val result = EveRoomUnderstanding().observe(1_000L, listOf("cup" to 0.95f))
        assertEquals(EveRoomType.UNKNOWN, result.room)
        assertTrue(result.confidence == 0f)
    }

    @Test fun repeatedKitchenEvidenceCanSuggestKitchenWithoutClaimingCertainty() {
        val model = EveRoomUnderstanding()
        model.observe(1_000L, listOf("refrigerator" to 0.72f, "microwave" to 0.70f))
        val result = model.observe(
            2_000L,
            listOf("refrigerator" to 0.74f, "microwave" to 0.71f, "kitchen" to 0.68f)
        )
        assertEquals(EveRoomType.KITCHEN, result.room)
        assertTrue(result.confidence >= EveRoomUnderstanding.MIN_ROOM_SCORE)
        assertTrue(result.explanationArabic.contains("احتمال"))
    }

    @Test fun bedroomAndBathroomEvidenceAreDistinguished() {
        val bedroom = EveRoomUnderstanding().observe(
            1_000L,
            listOf("bed" to 0.90f, "pillow" to 0.80f)
        )
        assertEquals(EveRoomType.BEDROOM, bedroom.room)

        val bathroom = EveRoomUnderstanding().observe(
            1_000L,
            listOf("toilet" to 0.89f, "shower" to 0.82f)
        )
        assertEquals(EveRoomType.BATHROOM, bathroom.room)
    }

    @Test fun staleFramesDoNotChangeTheCurrentRoomHypothesis() {
        val model = EveRoomUnderstanding()
        model.observe(2_000L, listOf("bed" to 0.89f, "pillow" to 0.81f))
        val stale = model.observe(1_000L, listOf("oven" to 0.99f))
        assertEquals(EveRoomType.UNKNOWN, stale.room)
    }

    @Test fun directRoomLabelCanBeUsedButStillRemainsAHypothesis() {
        val result = EveRoomUnderstanding().observe(1_000L, listOf("living room" to 0.80f))
        assertEquals(EveRoomType.LIVING_ROOM, result.room)
        assertTrue(result.explanationArabic.contains("يبدو"))
    }
}
