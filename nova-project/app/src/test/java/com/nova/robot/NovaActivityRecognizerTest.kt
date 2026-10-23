package com.nova.robot

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaActivityRecognizerTest {
    @Test
    fun onePoseSnapshotDoesNotInventAnActivity() {
        assertNull(NovaActivityRecognizer().observe(pose(), emptyList(), 1000L))
    }

    @Test
    fun repeatedHandNearFaceWithFoodEvidenceIsTentative() {
        val recognizer = NovaActivityRecognizer()
        var note: String? = null
        repeat(4) { index ->
            note = recognizer.observe(pose(handsNearFace = true), listOf(NovaLabelEvidence("banana", .91f)), 1000L + index * 3600L)
        }
        assertTrue(note?.contains("قد يكون الشخص يأكل أو يشرب") == true)
        assertTrue(note?.contains("لست متأكدة") == true)
    }

    @Test
    fun repeatedLimbMotionGetsOnlyAGeneralActivityHypothesis() {
        val recognizer = NovaActivityRecognizer()
        var note: String? = null
        repeat(5) { index ->
            note = recognizer.observe(pose(step = index), emptyList(), 1000L + index * 3600L)
        }
        assertTrue(note?.contains("حركة متكررة") == true)
    }

    private fun pose(handsNearFace: Boolean = false, step: Int = 0): FloatArray {
        val p = FloatArray(22) { .5f }
        p[0] = .50f; p[1] = .18f
        p[2] = .42f; p[3] = .30f
        p[4] = .58f; p[5] = .30f
        p[6] = if (handsNearFace) .47f else .24f + step * .09f
        p[7] = if (handsNearFace) .22f else .43f
        p[8] = if (handsNearFace) .53f else .76f - step * .08f
        p[9] = if (handsNearFace) .22f else .41f
        p[10] = .44f; p[11] = .52f
        p[12] = .56f; p[13] = .52f
        p[14] = .43f; p[15] = .68f
        p[16] = .57f; p[17] = .68f
        p[18] = .36f + step * .08f; p[19] = .85f
        p[20] = .64f - step * .08f; p[21] = .86f
        return p
    }
}
