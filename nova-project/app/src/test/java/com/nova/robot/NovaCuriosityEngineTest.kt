package com.nova.robot

import org.junit.Assert.*
import org.junit.Test

class NovaCuriosityEngineTest {
    @Test fun asksOnceForNewUnmatchedFaceAndRateLimitsRepetition() {
        val engine = NovaCuriosityEngine(cooldownMillis = 90_000, minimumGapMillis = 0)
        val scene = "رصدت وجه شخص. لم تسجل نوفا هوية بصرية لهذا الشخص بعد"
        assertTrue(engine.questionFor(scene, 1_000L)!!.contains("تعرّفني بهذا الشخص"))
        assertNull(engine.questionFor(scene, 30_000L))
        assertNotNull(engine.questionFor(scene, 92_000L))
    }

    @Test fun asksForHelpOnlyWhenEvidenceIsUncertain() {
        val engine = NovaCuriosityEngine(minimumGapMillis = 0)
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

    @Test fun curiosityRespondsToVisibleObjectWithAContextualQuestion() {
        val engine = NovaCuriosityEngine(minimumGapMillis = 0)
        val response = engine.questionFor("أظن أن في محيطي: زهرة (82٪). هذه احتمالات تقريبية.", 1_000L)
        assertNotNull(response)
        assertTrue(response!!.contains("زهرة"))
        assertTrue(response.contains("؟"))
    }

    @Test fun facialExpressionPromptsVerificationNotClaimsAboutFeelings() {
        val engine = NovaCuriosityEngine(minimumGapMillis = 0)
        val response = engine.questionFor("تعبير الوجه أقرب إلى «الفرح» (تقدير بصري 74٪).", 2_000L)
        assertNotNull(response)
        assertTrue(response!!.contains("تعبير"))
        assertTrue(response.contains("قد"))
    }

    @Test fun rateLimitsDifferentVisualTriggersToAvoidNoisyChatter() {
        val engine = NovaCuriosityEngine(minimumGapMillis = 30_000L)
        assertNotNull(engine.questionFor("أرى وجهًا جديدًا بالنسبة إلى ذاكرتي", 1_000L))
        assertNull(engine.questionFor("أظن أن في محيطي: زهرة (82٪).", 15_000L))
        assertNotNull(engine.questionFor("أظن أن في محيطي: زهرة (82٪).", 32_000L))
    }

    @Test fun repeatedSameCueVariesItsWordingDeterministicallyAfterCooldown() {
        val engine = NovaCuriosityEngine(cooldownMillis = 100L, minimumGapMillis = 0)
        val first = engine.questionFor("أظن أن في محيطي: زهرة (82٪).", 1_000L)
        val second = engine.questionFor("أظن أن في محيطي: زهرة (82٪).", 1_101L)
        assertNotEquals(first, second)
    }
}
