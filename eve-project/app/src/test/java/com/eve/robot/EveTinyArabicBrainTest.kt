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

    @Test fun generatesDifferentButGroundedArabicRepliesForRepeatedGreeting() {
        val prompt = "أهلاً يا إيف"
        val first = EveTinyArabicBrain.answerUser(prompt, LocalLlmContext())
        val history = listOf("المستخدم: $prompt | إيف: ${first.replyArabic}")
        val second = EveTinyArabicBrain.answerUser(
            prompt,
            LocalLlmContext(recentDialogues = history)
        )
        assertNotNull(first.replyArabic)
        assertTrue(first.replyArabic.isNotBlank())
        assertTrue(second.replyArabic.isNotBlank())
        assertFalse(
            "The response generator should avoid repeating the previous assistant sentence.",
            first.replyArabic == second.replyArabic
        )
        assertTrue(
            "Both outputs should remain natural Arabic greetings.",
            listOf("أهلًا", "مرحباً", "أهلاً").any(first.replyArabic::contains) &&
                listOf("أهلًا", "مرحباً", "أهلاً").any(second.replyArabic::contains)
        )
    }

    @Test fun generationIsStableForIdenticalContextAndDoesNotUseRandomNoise() {
        val context = LocalLlmContext(currentMood = "CURIOUS")
        val first = EveTinyArabicBrain.answerUser("كيف حالك يا إيف؟", context)
        val second = EveTinyArabicBrain.answerUser("كيف حالك يا إيف؟", context)
        assertEquals(first.replyArabic, second.replyArabic)
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
        assertFalse("Stale labels must not be repeated as current facts.", oldAnswer.replyArabic.contains("القطة"))

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
        assertTrue("A generic person label must not become a guessed identity.", listOf("لا يكشف اسمه", "لا يعني معرفة صاحبه", "لا أنسب إليه اسماً").any(answer.replyArabic::contains))
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
    @Test fun dialectNormalizerPreservesGreetingsAndMapsCommonSpokenArabic() {
        assertEquals("هلا ايف", EveArabicTextUnderstanding.normalize("هَلا يا إيف").replace("يا ", ""))
        assertEquals("كيفك", EveArabicTextUnderstanding.normalize("شلونك"))
        assertEquals("ما هذا", EveArabicTextUnderstanding.normalize("شنو هذا؟"))
        assertEquals("ما ترين", EveArabicTextUnderstanding.normalize("وش تشوفين؟"))
        assertEquals("كيف اغير الكاميرا", EveArabicTextUnderstanding.normalize("ازاي أغيّر الكاميرا"))
        assertTrue(EveArabicTextUnderstanding.featureTokens("بالكاميرا").contains("كاميرا"))
    }

    @Test fun classifiesSpokenDialectFormsThroughTheSharedArabicFrontEnd() {
        val cases = listOf(
            "شلونك يا إيف" to "greeting",
            "شنو هذا؟" to "vision",
            "وش تشوفين؟" to "vision",
            "وين الكاميرا؟" to "camera",
            "ازاي اغير الكاميرا؟" to "camera"
        )
        cases.forEach { (phrase, expected) ->
            val prediction = EveTinyArabicBrain.classify(phrase)
            assertEquals(
                "Dialect phrase should reach the same intent: $phrase " +
                    "(similarity=${prediction.bestSimilarity}, margin=${prediction.margin})",
                expected,
                prediction.intent
            )
        }
    }

    @Test fun normalizationDoesNotForceUnrelatedDialectQuestionsIntoKnownIntents() {
        val unknown = EveTinyArabicBrain.classify("شنو سبب تشكل المجرات؟")
        assertNull("Dialect normalization must not turn an unsupported question into a known intent.", unknown.intent)
    }

    @Test fun semanticComposerUsesMeaningAndCurrentVisualContext() {
        val carContext = LocalLlmContext(
            latestVisionLabel = "car",
            visionConfidence = 0.91f,
            visionObservationAgeMs = 300L,
            latestSceneSummary = "جسم يشبه سيارة في منتصف الإطار",
            sceneObservationAgeMs = 500L,
            latestLearnedConcept = "سيارة"
        )
        val carReply = EveTinyArabicBrain.answerUser("ماذا ترين أمامك؟", carContext).replyArabic
        assertTrue(carReply.contains("سيارة"))
        assertTrue(carReply.contains("مشهد") || carReply.contains("الإطار"))

        val catContext = carContext.copy(
            latestVisionLabel = "cat",
            latestSceneSummary = "قطة تتحرك قرب الكرسي",
            latestLearnedConcept = null
        )
        val catReply = EveTinyArabicBrain.answerUser("ماذا ترين أمامك؟", catContext).replyArabic
        assertTrue(catReply.contains("قطة"))
        assertFalse("A reply should reflect the current scene, not a fixed answer.", carReply == catReply)
    }

    @Test fun semanticComposerBuildsQuestionSpecificExplanation() {
        val context = LocalLlmContext()
        val why = EveTinyArabicBrain.answerUser("لماذا تعملين بدون إنترنت؟", context).replyArabic
        val how = EveTinyArabicBrain.answerUser("كيف تفهمين الكلام دون إنترنت؟", context).replyArabic
        assertTrue(why.contains("السبب") || why.contains("لأن"))
        assertTrue(how.contains("التطبيع") || how.contains("تحليل") || how.contains("تمر"))
        assertFalse("Why and how should not receive the same generic sentence.", why == how)
    }

    @Test fun semanticComposerResolvesShortFollowUpFromRecentDialogue() {
        val context = LocalLlmContext(
            recentDialogues = listOf(
                "المستخدم: هل تعملين بدون إنترنت؟ | إيف: يعمل محرك الحوار محلياً على الهاتف."
            )
        )
        val reply = EveTinyArabicBrain.answerUser("ولماذا؟", context).replyArabic
        assertTrue(reply.contains("الاتصال بالإنترنت"))
        assertTrue(reply.contains("سؤالك المختصر") || reply.contains("حديثنا السابق"))
    }

    @Test fun semanticComposerRespondsToUserStateInsteadOfTreatingItAsUnknown() {
        val bored = EveTinyArabicBrain.answerUser("أنا زهقان من الشطرنج", LocalLlmContext())
        assertTrue(bored.replyArabic.contains("مللت"))
        assertTrue(bored.replyArabic.contains("اللعبة"))
        assertTrue(bored.moodHint == "irritated")

        val tired = EveTinyArabicBrain.answerUser("أنا تعبان", LocalLlmContext())
        assertTrue(tired.replyArabic.contains("التعب"))
        assertTrue(tired.moodHint == "sleepy")
    }

    @Test fun semanticComposerUsesSavedObservationsWhenAnsweringMemoryQuestion() {
        val context = LocalLlmContext(
            recentObservations = listOf("سيارة قرب النافذة", "شخص دخل الغرفة"),
            knownConcepts = listOf("سيارة", "كوب")
        )
        val reply = EveTinyArabicBrain.answerUser("ماذا تتذكرين؟", context).replyArabic
        assertTrue(reply.contains("سيارة") || reply.contains("شخص"))
        assertTrue(reply.contains("خلاصات") || reply.contains("المحفوظة"))
    }

}
