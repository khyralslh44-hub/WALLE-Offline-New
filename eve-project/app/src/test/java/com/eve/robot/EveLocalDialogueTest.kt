package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EveLocalDialogueTest {
    @Test fun novelHighConfidenceCatCreatesContextualQuestion() {
        val thought = EveCuriosityEngine.consider(
            label = "cat",
            confidence = 0.93f,
            sightings = 1,
            curiosityBias = 0.76f,
            lastAnyQuestionAtMs = 0L,
            lastLabelQuestionAtMs = 0L,
            previousQuestionCount = 0,
            nowMs = 100_000L
        )
        assertNotNull(thought)
        assertTrue(thought!!.text.contains("قطة"))
        assertTrue(thought.text.contains("أتساءل") || thought.text.contains("هل تعرف"))
        assertEquals("cat", thought.label)
    }

    @Test fun uncertainOrGenericVisualLabelsDoNotTriggerCuriosity() {
        assertNull(EveCuriosityEngine.consider(
            "cat", 0.61f, 1, 0.9f, 0L, 0L, 0, 100_000L
        ))
        assertNull(EveCuriosityEngine.consider(
            "pattern", 0.97f, 1, 0.9f, 0L, 0L, 0, 100_000L
        ))
        assertNull(EveCuriosityEngine.consider(
            "cat", 0.97f, 1, 0.35f, 0L, 0L, 0, 100_000L
        ))
    }

    @Test fun globalAndPerLabelCooldownPreventSpam() {
        val duringGlobalCooldown = EveCuriosityEngine.consider(
            "cat", 0.96f, 1, 0.8f, 90_000L, 0L, 0, 100_000L
        )
        val duringSameLabelCooldown = EveCuriosityEngine.consider(
            "cat", 0.96f, 1, 0.8f, 0L, 90_000L, 0, 100_000L
        )
        assertNull(duringGlobalCooldown)
        assertNull(duringSameLabelCooldown)
    }

    @Test fun repeatedVisualCategoryCreatesMemoryAwareQuestion() {
        val thought = EveCuriosityEngine.consider(
            "cat", 0.91f, 7, 0.80f, 0L, 0L, 1, 100_000L
        )
        assertNotNull(thought)
        assertTrue(thought!!.text.contains("مرة أخرى"))
        assertTrue(thought.text.contains("الشيء نفسه"))
    }

    @Test fun asksWhatItSeesOnlyFromFreshConfidentLocalLabel() {
        val answer = OfflineEveResponder.answer(
            "ماذا ترين", "cat", 0.92f, 1000L
        )
        assertNotNull(answer)
        assertTrue(answer!!.text.contains("قطة"))
        assertTrue(answer.text.contains("قد يخطئ"))

        val stale = OfflineEveResponder.answer(
            "ماذا ترين", "cat", 0.92f, 30_000L
        )
        assertNotNull(stale)
        assertTrue(stale!!.text.contains("مشهدًا أوضح"))
        assertFalse(stale.text.contains("أرى قطة"))
    }

    @Test fun knownOfflineCapabilityQuestionGetsHonestLocalAnswer() {
        val answer = OfflineEveResponder.answer(
            "هل تعملين بدون انترنت", null, 0f, Long.MAX_VALUE
        )
        assertNotNull(answer)
        assertEquals("offline_capability", answer!!.topic)
        assertTrue(answer.text.contains("معرفتي محدودة"))
    }

    @Test fun unknownQuestionAdmitsKnowledgeLimitAndStatementIsNotAnsweredAsQuestion() {
        val unknown = OfflineEveResponder.answer(
            "لماذا تمطر على المريخ اليوم", null, 0f, Long.MAX_VALUE
        )
        assertNotNull(unknown)
        assertEquals("unknown_offline_question", unknown!!.topic)
        assertTrue(unknown.text.contains("لن أختلق معلومة"))

        assertNull(OfflineEveResponder.answer(
            "أرى قطة", "cat", 0.94f, 100L
        ))
    }
}
