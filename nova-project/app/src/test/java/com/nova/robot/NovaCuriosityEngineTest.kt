package com.nova.robot

import org.junit.Assert.*
import org.junit.Test

class NovaCuriosityEngineTest {
    @Test fun asksOnceForNewUnmatchedFaceAndRateLimitsRepetition() {
        val engine = NovaCuriosityEngine(cooldownMillis = 90_000)
        val scene = "رصدت وجه شخص. لم تسجل نوفا هوية بصرية لهذا الشخص بعد"
        assertTrue(engine.questionFor(scene, 1_000L)!!.contains("تعرّفني بهذا الشخص"))
        assertNull(engine.questionFor(scene, 30_000L))
        assertTrue(engine.questionFor(scene, 92_000L)!!.contains("تعرّفني بهذا الشخص"))
    }

    @Test fun asksForHelpOnlyWhenEvidenceIsUncertain() {
        val engine = NovaCuriosityEngine()
        assertNull(engine.questionFor("قد يكون في المشهد: كوب (72٪).", 1_000L))
        assertTrue(engine.questionFor("لم أجد دليلًا بصريًا كافيًا لتسمية ما فيه", 1_001L)!!.contains("ما الذي تراه"))
    }

    @Test fun resetAllowsFreshQuestion() {
        val engine = NovaCuriosityEngine()
        val scene = "لم أستطع مطابقته بثقة مع الوجوه المحفوظة"
        assertNotNull(engine.questionFor(scene, 1_000L))
        engine.reset()
        assertNotNull(engine.questionFor(scene, 1_001L))
    }
}
