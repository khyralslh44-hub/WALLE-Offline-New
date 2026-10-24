package com.nova.robot

import org.junit.Assert.*
import org.junit.Test

class NovaCuriosityEngineTest {
    @Test fun doesNotAskUnknownPeopleForPersonalInformation() {
        val engine = NovaCuriosityEngine(cooldownMillis = 90_000, minimumGapMillis = 0)
        val scene = "رصدت وجه شخص. لم تسجل نوفا هوية بصرية لهذا الشخص بعد"
        assertNull(engine.questionFor(scene, 1_000L))
        assertNull(engine.questionFor(scene, 30_000L))
    }

    @Test fun asksForHelpOnlyWhenEvidenceIsUncertain() {
        val engine = NovaCuriosityEngine(minimumGapMillis = 0)
        assertNull(engine.questionFor("قد يكون في المشهد: كوب (72٪).", 1_000L))
        val response = engine.questionFor("لم أجد دليلًا بصريًا كافيًا لتسمية ما فيه", 1_001L)
        assertNotNull(response)
        assertTrue(response!!.contains("لم تتضح لي الصورة") || response.contains("تفصيل"))
    }

    @Test fun resetAllowsFreshQuestion() {
        val engine = NovaCuriosityEngine(minimumGapMillis = 0)
        val scene = "أظن أن في محيطي: زهرة (82٪)."
        assertNotNull(engine.questionFor(scene, 1_000L))
        engine.reset()
        assertNotNull(engine.questionFor(scene, 1_001L))
    }

    @Test fun curiosityRespondsToVisibleObjectWithAnEvidenceLedObservation() {
        val engine = NovaCuriosityEngine(minimumGapMillis = 0)
        val response = engine.questionFor("أظن أن في محيطي: زهرة (82٪). هذه احتمالات تقريبية.", 1_000L)
        assertNotNull(response)
        assertTrue(response!!.contains("زهرة"))
    }

    @Test fun facialExpressionPromptsVerificationNotClaimsAboutFeelings() {
        val engine = NovaCuriosityEngine(minimumGapMillis = 0)
        val response = engine.questionFor("تعبير الوجه أقرب إلى «الفرح» (تقدير بصري 74٪).", 2_000L)
        assertNotNull(response)
        assertTrue(response!!.contains("تعبير") || response.contains("الملامح"))
        assertTrue(response.contains("قد") || response.contains("أفترض"))
    }

    @Test fun rateLimitsDifferentVisualTriggersToAvoidNoisyChatter() {
        val engine = NovaCuriosityEngine(minimumGapMillis = 30_000L)
        assertNull(engine.questionFor("أرى وجهًا جديدًا بالنسبة إلى ذاكرتي", 1_000L))
        assertNull(engine.questionFor("أظن أن في محيطي: زهرة (82٪).", 15_000L))
        assertNotNull(engine.questionFor("أظن أن في محيطي: زهرة (82٪).", 32_000L))
    }

    @Test fun repeatedSameCueVariesItsWordingDeterministicallyAfterCooldown() {
        val engine = NovaCuriosityEngine(cooldownMillis = 100L, minimumGapMillis = 0)
        val first = engine.questionFor("أظن أن في محيطي: زهرة (82٪).", 1_000L)
        val second = engine.questionFor("أظن أن في محيطي: زهرة (82٪).", 1_101L)
        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(first, second)
    }

    @Test fun questionCooldownSurvivesEngineRecreation() {
        val memory = InMemoryNovaMindMemory()
        val first = NovaCuriosityEngine(memory, cooldownMillis = 90_000L, minimumGapMillis = 0L)
        assertNotNull(first.questionFor("أظن أن في محيطي: زهرة (82٪).", 1_000L))
        val recreated = NovaCuriosityEngine(memory, cooldownMillis = 90_000L, minimumGapMillis = 0L)
        assertNull(recreated.questionFor("أظن أن في محيطي: زهرة (82٪).", 30_000L))
        assertNotNull(recreated.questionFor("أظن أن في محيطي: زهرة (82٪).", 92_000L))
    }
}
