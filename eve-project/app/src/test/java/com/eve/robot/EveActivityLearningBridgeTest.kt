package com.eve.robot

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EveActivityLearningBridgeTest {
    private fun result() = EveActivityInterpretation(
        activity = EveHumanActivity.EATING_POSSIBLE,
        confidence = 0.76f,
        evidenceCount = 8,
        durationMs = 4_800L,
        explanationArabic = "طعام ظاهر مع حركة متكررة؛ الأكل احتمال.",
        nextActionArabic = "لا تقاطع الشخص.",
        observedAtMs = 5_000L
    )

    @Test fun correctionIsNotAppliedBeforeThreeExplicitExamples() {
        val file = File.createTempFile("eve-activity-learn", ".properties").apply { delete() }
        val bridge = EveActivityLearningBridge(file)
        assertEquals(EveHumanActivity.EATING_POSSIBLE, bridge.applyLearnedCorrection(result()).activity)
        assertEquals(1, bridge.recordUserCorrection(result(), EveHumanActivity.CHEWING_MOTION_POSSIBLE))
        assertEquals(EveHumanActivity.EATING_POSSIBLE, bridge.applyLearnedCorrection(result()).activity)
        assertEquals(2, bridge.recordUserCorrection(result(), EveHumanActivity.CHEWING_MOTION_POSSIBLE))
        assertEquals(EveHumanActivity.EATING_POSSIBLE, bridge.applyLearnedCorrection(result()).activity)
        assertEquals(3, bridge.recordUserCorrection(result(), EveHumanActivity.CHEWING_MOTION_POSSIBLE))
        assertEquals(EveHumanActivity.CHEWING_MOTION_POSSIBLE, bridge.applyLearnedCorrection(result()).activity)
        file.delete()
    }

    @Test fun userCorrectionsPersistAcrossBridgeInstances() {
        val file = File.createTempFile("eve-activity-learn", ".properties").apply { delete() }
        val first = EveActivityLearningBridge(file)
        repeat(3) {
            assertNotNull(first.recordUserCorrection(result(), EveHumanActivity.CHEWING_MOTION_POSSIBLE))
        }
        val restored = EveActivityLearningBridge(file)
        assertEquals(EveHumanActivity.CHEWING_MOTION_POSSIBLE, restored.applyLearnedCorrection(result()).activity)
        assertEquals(3, restored.exampleCounts()[EveHumanActivity.CHEWING_MOTION_POSSIBLE])
        file.delete()
    }

    @Test fun unknownOrUnreliableObservationsAreNotTrainable() {
        val file = File.createTempFile("eve-activity-learn", ".properties").apply { delete() }
        val bridge = EveActivityLearningBridge(file)
        assertNull(bridge.recordUserCorrection(
            result().copy(activity = EveHumanActivity.UNKNOWN),
            EveHumanActivity.CHEWING_MOTION_POSSIBLE
        ))
        assertNull(bridge.recordUserCorrection(
            result().copy(confidence = 0.2f),
            EveHumanActivity.CHEWING_MOTION_POSSIBLE
        ))
        assertTrue(bridge.exampleCounts().isEmpty())
        file.delete()
    }
}
