package com.nova.robot

import org.junit.Assert.*
import org.junit.Test

class NovaMindTest {
    private class FakeModel(private val output: String?) : LocalLanguageModel {
        var calls = 0
        override fun generate(prompt: String, maxTokens: Int): String? {
            calls++
            assertTrue(prompt.length <= 1800)
            assertTrue(maxTokens <= 120)
            return output
        }
    }

    @Test fun usesInjectedLocalModelAndMarksItsUse() {
        val model = FakeModel("أهلًا! أنا نوفا.")
        val brain = NovaMind(InMemoryNovaMindMemory(), model)
        val reply = brain.respond(NovaMindInput("مرحبا", nowMillis = 10L))
        assertEquals("أهلًا! أنا نوفا.", reply.textArabic)
        assertTrue(reply.usedLocalModel)
        assertEquals(1, model.calls)
    }

    @Test fun missingModelDoesNotPretendFallbackIsNeural() {
        val brain = NovaMind(InMemoryNovaMindMemory(), null)
        val reply = brain.respond(NovaMindInput("اشرح لي الذكاء", nowMillis = 20L))
        assertFalse(reply.usedLocalModel)
        assertTrue(reply.textArabic.contains("لا يوجد نموذج لغوي مدمج"))
    }

    @Test fun remembersOnlyCoarseTopicAndDoesNotStoreRawConversation() {
        val memory = InMemoryNovaMindMemory()
        val brain = NovaMind(memory)
        val reply = brain.respond(NovaMindInput("كيف تعمل الكاميرا؟", nowMillis = 100L))
        assertTrue(reply.memoryUpdated)
        assertEquals("question", memory.read("last_topic"))
        assertNull(memory.read("كيف تعمل الكاميرا؟"))
    }

    @Test fun flagsFrustrationAndPositiveToneDeterministically() {
        val brain = NovaMind(InMemoryNovaMindMemory())
        assertEquals(NovaEmotion.FRUSTRATED, brain.respond(NovaMindInput("هذا خطأ")).emotion)
        assertEquals(NovaEmotion.HAPPY, brain.respond(NovaMindInput("ممتاز شكرًا")).emotion)
    }

    @Test fun trimsLongInputAndBoundsOutput() {
        val model = FakeModel("تم.")
        val brain = NovaMind(InMemoryNovaMindMemory(), model)
        val reply = brain.respond(NovaMindInput("س".repeat(5000)))
        assertEquals("تم.", reply.textArabic)
        assertTrue(reply.usedLocalModel)
    }

    @Test fun blankInputGetsSafeArabicFallback() {
        val reply = NovaMind(InMemoryNovaMindMemory()).respond(NovaMindInput("   "))
        assertTrue(reply.textArabic.isNotBlank())
        assertFalse(reply.usedLocalModel)
    }
}
