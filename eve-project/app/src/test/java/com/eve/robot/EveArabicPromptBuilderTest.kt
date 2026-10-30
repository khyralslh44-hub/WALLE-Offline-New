package com.eve.robot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EveArabicPromptBuilderTest {
    @Test fun staleVisionIsNotPassedAsCurrentEvidence() {
        val prompt = EveArabicPromptBuilder.systemPrompt(
            LocalLlmContext(
                latestVisionLabel = "dog",
                visionConfidence = 0.99f,
                visionObservationAgeMs = 90_000L
            )
        )
        assertTrue(prompt.contains("لا يوجد حالياً دليل بصري حديث"))
        assertFalse(prompt.contains("دليل بصري حديث محدود: التصنيف=dog"))
    }

    @Test fun aGenericPersonLabelCannotBeConvertedIntoIdentity() {
        val prompt = EveArabicPromptBuilder.systemPrompt(
            LocalLlmContext(
                latestVisionLabel = "person",
                visionConfidence = 0.98f,
                visionObservationAgeMs = 400L,
                knownPeople = "الأم: فاطمة"
            )
        )
        assertTrue(prompt.contains("كلمة «شخص» أو «وجه» لا تكشف الاسم"))
        assertTrue(prompt.contains("هذه الأسماء ليست دليلاً على هوية أي وجه"))
        assertTrue(prompt.contains("التصنيف=person"))
    }

    @Test fun learnedPersonalityAndRecentConversationShapeTheVoice() {
        val prompt = EveArabicPromptBuilder.systemPrompt(
            LocalLlmContext(
                personality = EvePersonality(
                    curiosityBias = 0.91f,
                    warmth = 0.88f,
                    playfulness = 0.24f,
                    patience = 0.95f
                ),
                recentDialogues = listOf("المستخدم: أحب النباتات | إيف: النباتات تضيف حياة للمكان."),
                recentObservations = listOf("رأيت نبتة قرب النافذة أمس.")
            )
        )
        assertTrue(prompt.contains("دفء=0.88"))
        assertTrue(prompt.contains("فضول=0.91"))
        assertTrue(prompt.contains("مرح=0.24"))
        assertTrue(prompt.contains("صبر=0.95"))
        assertTrue(prompt.contains("النباتات تضيف حياة للمكان"))
        assertTrue(prompt.contains("ليست دليلاً على المشهد الحالي"))
        assertTrue(prompt.contains("لا تبدئي كل رد"))
    }

    @Test fun cleansChatTokensAndBoundsGeneratedText() {
        val cleaned = EveArabicPromptBuilder.cleanGeneratedText(
            "assistant: أهلاً بكِ!<|im_end|>\n\n\nسعدت برؤيتك."
        )
        assertTrue(cleaned.startsWith("أهلاً"))
        assertFalse(cleaned.contains("<|im_end|>"))
        assertFalse(cleaned.contains("assistant:"))
        assertTrue(cleaned.length <= 1_200)
    }
}
