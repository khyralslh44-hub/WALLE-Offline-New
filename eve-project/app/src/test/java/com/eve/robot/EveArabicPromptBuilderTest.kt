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
