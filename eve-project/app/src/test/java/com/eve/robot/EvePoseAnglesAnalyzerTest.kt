package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvePoseAnglesAnalyzerTest {
    @Test fun rightAngleIsMeasuredGeometrically() {
        val result = EvePoseAnglesAnalyzer.angleDegrees(
            EvePosePoint(0f, 0f),
            EvePosePoint(0f, 1f),
            EvePosePoint(1f, 1f)
        )
        assertNotNull(result)
        assertEquals(90f, result!!, 0.01f)
    }

    @Test fun degenerateJointGeometryIsUnknownRatherThanInvented() {
        assertNull(
            EvePoseAnglesAnalyzer.angleDegrees(
                EvePosePoint(0.5f, 0.5f),
                EvePosePoint(0.5f, 0.5f),
                EvePosePoint(0.9f, 0.8f)
            )
        )
    }

    @Test fun poseRequiresEnoughLandmarksAndRejectsNonFinitePoints() {
        assertNull(EvePoseAnglesAnalyzer.analyze(List(20) { EvePosePoint(0.5f, 0.5f) }, 640, 480))
        val points = MutableList(33) { EvePosePoint(0.5f, 0.5f) }
        points[23] = EvePosePoint(0.3f, 0.3f)
        points[25] = EvePosePoint(0.4f, 0.6f)
        points[27] = EvePosePoint(0.5f, 0.9f)
        points[24] = EvePosePoint(0.7f, 0.3f)
        points[26] = EvePosePoint(0.6f, 0.6f)
        points[28] = EvePosePoint(0.5f, 0.9f)
        points[11] = EvePosePoint(0.3f, 0.2f)
        points[13] = EvePosePoint(0.2f, 0.35f)
        points[15] = EvePosePoint(0.25f, 0.5f)
        points[12] = EvePosePoint(0.7f, 0.2f)
        points[14] = EvePosePoint(0.8f, 0.35f)
        points[16] = EvePosePoint(0.75f, 0.5f)
        assertNotNull(EvePoseAnglesAnalyzer.analyze(points, 640, 480))
        points[3] = EvePosePoint(Float.NaN, 0.2f)
        assertNull(EvePoseAnglesAnalyzer.analyze(points, 640, 480))
    }

    @Test fun bentKneesRaiseOnlyASeatedLikePostureConfidence() {
        val seated = EvePoseAnglesAnalyzer.sittingPoseConfidenceFromKneeAngles(94f, 101f)
        val standing = EvePoseAnglesAnalyzer.sittingPoseConfidenceFromKneeAngles(174f, 169f)
        val oneVisibleKnee = EvePoseAnglesAnalyzer.sittingPoseConfidenceFromKneeAngles(92f, null)
        assertTrue(seated > 0.85f)
        assertEquals(0f, standing, 0f)
        assertTrue(oneVisibleKnee < seated)
    }

    @Test fun repeatedJointChangesProduceNonZeroMotionScore() {
        val points = MutableList(33) { EvePosePoint(0.5f, 0.5f) }
        points[23] = EvePosePoint(0.3f, 0.3f)
        points[25] = EvePosePoint(0.4f, 0.6f)
        points[27] = EvePosePoint(0.5f, 0.9f)
        points[24] = EvePosePoint(0.7f, 0.3f)
        points[26] = EvePosePoint(0.6f, 0.6f)
        points[28] = EvePosePoint(0.5f, 0.9f)
        points[11] = EvePosePoint(0.3f, 0.2f)
        points[13] = EvePosePoint(0.2f, 0.35f)
        points[15] = EvePosePoint(0.25f, 0.5f)
        points[12] = EvePosePoint(0.7f, 0.2f)
        points[14] = EvePosePoint(0.8f, 0.35f)
        points[16] = EvePosePoint(0.75f, 0.5f)
        val first = EvePoseAnglesAnalyzer.analyze(points, 640, 480)
        points[25] = EvePosePoint(0.4f, 0.4f)
        points[27] = EvePosePoint(0.6f, 0.9f)
        val second = EvePoseAnglesAnalyzer.analyze(points, 640, 480, first)
        assertNotNull(second)
        assertTrue(second!!.jointMotionScore > 0f)
        assertTrue(second.jointMotionScore <= 1f)
    }
}
