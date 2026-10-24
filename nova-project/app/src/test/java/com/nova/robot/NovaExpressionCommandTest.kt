package com.nova.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NovaExpressionCommandTest {
    @Test fun parsesJealousyInArabic() {
        val request = NovaExpressionCommand.parse("تعبير: الغيرة")
        assertNotNull(request)
        assertEquals(NovaMood.JEALOUS, request!!.mood)
    }

    @Test fun parsesDizzinessInArabic() {
        val request = NovaExpressionCommand.parse("تعبير: دوار")
        assertNotNull(request)
        assertEquals(NovaMood.DIZZY, request!!.mood)
    }

    @Test fun parsesDizzinessSynonym() {
        val request = NovaExpressionCommand.parse("تعبير: دوخة")
        assertNotNull(request)
        assertEquals(NovaMood.DIZZY, request!!.mood)
    }

    @Test fun parsesTheFourAddedEveExpressions() {
        assertEquals(NovaMood.ANGRY, NovaExpressionCommand.parse("تعبير: الغضب")!!.mood)
        assertEquals(NovaMood.EXCITED, NovaExpressionCommand.parse("تعبير: الحماس")!!.mood)
        assertEquals(NovaMood.PROUD, NovaExpressionCommand.parse("تعبير: الفخر")!!.mood)
        assertEquals(NovaMood.DETERMINED, NovaExpressionCommand.parse("تعبير: الإصرار")!!.mood)
    }
}
