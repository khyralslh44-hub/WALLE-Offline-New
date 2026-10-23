package com.nova.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaEmotionClassifierTest {
    @Test
    fun confidentLogitsAreReportedAsLikelyExpressionNotInnerTruth() {
        val note = NovaEmotionEvidence.describe(
            floatArrayOf(5f, -2f, -2f, -3f, -2f, -2f, -2f)
        )
        assertTrue(note.contains("الغضب"))
        assertTrue(note.contains("تقدير بصري"))
        assertTrue(note.contains("لا أستطيع معرفة الشعور الداخلي"))
    }

    @Test
    fun flatScoresDoNotInventAnExpression() {
        val note = NovaEmotionEvidence.describe(FloatArray(7) { 0f })
        assertTrue(note.contains("لا أستطيع تسميته بثقة"))
    }

    @Test
    fun outputProbabilityUsesTheDocumentedSevenClassOrder() {
        val note = NovaEmotionEvidence.describe(
            floatArrayOf(0.03f, 0.03f, 0.04f, 0.74f, 0.05f, 0.04f, 0.07f)
        )
        assertTrue(note.contains("الفرح"))
    }

    @Test
    fun fearSadnessAndDisgustMapToDistinctVisualExpressions() {
        assertEquals(
            NovaMood.AFRAID,
            NovaEmotionEvidence.predict(floatArrayOf(-5f, -5f, 6f, -5f, -5f, -5f, -5f)).mood
        )
        assertEquals(
            NovaMood.SAD,
            NovaEmotionEvidence.predict(floatArrayOf(-5f, -5f, -5f, -5f, -5f, 6f, -5f)).mood
        )
        assertEquals(
            NovaMood.DISGUSTED,
            NovaEmotionEvidence.predict(floatArrayOf(-5f, 6f, -5f, -5f, -5f, -5f, -5f)).mood
        )
    }

    @Test
    fun malformedScoresRemainUncertain() {
        assertTrue(NovaEmotionEvidence.describe(floatArrayOf(Float.NaN)).contains("لا أستطيع تسميته بثقة"))
    }
}
