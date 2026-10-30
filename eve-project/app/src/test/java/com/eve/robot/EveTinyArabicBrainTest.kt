package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EveTinyArabicBrainTest {
    @Test fun recognizesCommonArabicCompanionIntents() {
        val cases = listOf(
            "أهلاً يا إيف" to "greeting",
            "هل تعملين من دون اتصال بالإنترنت؟" to "offline",
            "ماذا ترين الآن؟" to "vision",
            "لماذا تسألين كثيراً؟" to "curiosity",
            "ماذا تتذكرين من تجاربنا؟" to "memory",
            "هيا نلعب الشطرنج" to "play"
        )
        cases.forEach { (phrase, expected) ->
            val prediction = EveTinyArabicBrain.classify(phrase)
            assertEquals("Wrong intent for: $phrase (similarity=${prediction.bestSimilarity}, margin=${prediction.margin})", expected, prediction.intent)
            assertTrue("Confidence should be bounded", prediction.confidence in 0f..1f)
        }
    }

    @Test fun unknownGeneralKnowledgeQuestionIsNotForcedIntoAKnownIntent() {
        val prediction = EveTinyArabicBrain.classify("لماذا تمطر على المريخ اليوم؟")
        assertNull("The compact model must admit unsupported questions.", prediction.intent)
        val answer = EveTinyArabicBrain.answerUser(
            "لماذا تمطر على المريخ اليوم؟",
            LocalLlmContext()
        )
        assertTrue(answer.replyArabic.contains("لم أفهم"))
        assertTrue(answer.reflectionArabic.contains("غير مألوف"))
    }

    @Test fun visualAnswerRequiresFreshEvidence() {
        val oldEvidence = LocalLlmContext(
            latestVisionLabel = "cat",
            visionConfidence = 0.95f,
            visionObservationAgeMs = 60_000L
        )
        val oldAnswer = EveTinyArabicBrain.answerUser("ماذا ترين؟", oldEvidence)
        assertTrue(oldAnswer.replyArabic.contains("لا أملك ملاحظة بصرية حديثة"))

        val freshEvidence = oldEvidence.copy(visionObservationAgeMs = 800L)
        val freshAnswer = EveTinyArabicBrain.answerUser("ماذا ترين؟", freshEvidence)
        assertTrue(freshAnswer.replyArabic.contains("القطة"))
        assertTrue(freshAnswer.replyArabic.contains("قد يخطئ"))
    }

    @Test fun genericPersonLabelNeverClaimsToKnowThePersonsIdentity() {
        val answer = EveTinyArabicBrain.answerUser(
            "ماذا ترين؟",
            LocalLlmContext(
                latestVisionLabel = "person",
                visionConfidence = 0.96f,
                visionObservationAgeMs = 300L
            )
        )
        assertTrue(answer.replyArabic.contains("لا يكشف اسمه أو هويته"))
        assertFalse(answer.replyArabic.contains("أهلًا أحمد"))
    }

    @Test fun observationReflectionDoesNotInventAConclusionFromStaleVision() {
        val stale = EveTinyArabicBrain.reflectOnObservation(
            LocalLlmContext(
                latestVisionLabel = "car",
                visionConfidence = 0.99f,
                visionObservationAgeMs = 40_000L
            )
        )
        assertEquals("", stale.replyArabic)
        assertTrue(stale.reflectionArabic.contains("لا توجد ملاحظة حديثة"))

        val fresh = EveTinyArabicBrain.reflectOnObservation(
            LocalLlmContext(
                latestVisionLabel = "car",
                visionConfidence = 0.91f,
                visionObservationAgeMs = 400L,
                sightingCount = 1
            )
        )
        assertTrue(fresh.reflectionArabic.contains("car"))
        assertTrue(fresh.confidence > 0.8f)
    }

    @Test fun curiosityQuestionRemainsGroundedAndDoesNotAssignIdentity() {
        val person = EveTinyArabicBrain.askFromCuriosity(
            LocalLlmContext(), "person", 0.94f, 1, "سؤال احتياطي؟"
        )
        assertTrue(person.replyArabic.contains("شخصاً"))
        assertFalse(person.replyArabic.contains("أعرفك"))
        assertTrue(person.replyArabic.length <= 84)

        val uncertain = EveTinyArabicBrain.askFromCuriosity(
            LocalLlmContext(), "pattern", 0.40f, 1, "سؤال احتياطي؟"
        )
        assertEquals("سؤال احتياطي؟", uncertain.replyArabic)
    }

    @Test fun gameCommentUsesOnlySuppliedOutcomeAndScore() {
        val reply = EveTinyArabicBrain.commentOnGame(
            gameName = "chess",
            outcome = "player",
            detail = "فاز اللاعب فعلاً.",
            playerScore = 1,
            eveScore = 0,
            round = 3
        )
        assertTrue(reply.replyArabic.contains("الشطرنج"))
        assertTrue(reply.replyArabic.contains("أنت 1"))
        assertTrue(reply.replyArabic.contains("أنا 0"))
    }
}
