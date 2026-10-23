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

    @Test fun noLanguageModelStillUsesCuriousConversationalFallback() {
        val brain = NovaMind(InMemoryNovaMindMemory(), null)
        val reply = brain.respond(NovaMindInput("اشرح لي الذكاء", nowMillis = 20L))
        assertFalse(reply.usedLocalModel)
        assertTrue(reply.usedOwnModel)
        assertEquals(NovaIntent.QUESTION, reply.intent)
        assertTrue(reply.textArabic.contains("؟"))
        assertFalse(reply.textArabic.contains("نواتي العصبية الحالية"))
    }

    @Test fun remembersOnlyCoarseTopicAndDoesNotStoreRawConversation() {
        val memory = InMemoryNovaMindMemory()
        val brain = NovaMind(memory)
        val reply = brain.respond(NovaMindInput("كيف تعمل الكاميرا؟", nowMillis = 100L))
        assertTrue(reply.memoryUpdated)
        assertEquals("question", memory.read("last_topic"))
        assertNull(memory.read("كيف تعمل الكاميرا؟"))
        assertNotNull(memory.read("novamind_own_model_snapshot_v1"))
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

    @Test fun explicitPersonalMemoryIsSavedAndCanBeRecalledWithoutStoringTheConversation() {
        val memory = InMemoryNovaMindMemory()
        val brain = NovaMind(memory)
        val saved = brain.respond(NovaMindInput("تذكري أنني أحب القهوة", nowMillis = 10L))
        assertTrue(saved.textArabic.contains("حفظت هذه المعلومة"))
        assertEquals(NovaIntent.MEMORY_REQUEST, saved.intent)
        assertTrue(saved.textArabic.contains("القهوة"))
        assertNull(memory.read("تذكري أنني أحب القهوة"))

        val recalled = NovaMind(memory).respond(NovaMindInput("ماذا تتذكر عني؟", nowMillis = 20L))
        assertTrue(recalled.textArabic.contains("أحب القهوة"))
    }

    @Test fun personalMemoryCanForgetOneNoteOrClearAllNotes() {
        val memory = InMemoryNovaMindMemory()
        val brain = NovaMind(memory)
        brain.respond(NovaMindInput("تذكر أنني أحب القهوة", nowMillis = 10L))
        brain.respond(NovaMindInput("تذكر أنني أفضّل الهدوء", nowMillis = 11L))
        val forgot = brain.respond(NovaMindInput("انس أنني أحب القهوة", nowMillis = 12L))
        assertTrue(forgot.textArabic.contains("نسيت المعلومة"))
        val remaining = NovaMind(memory).respond(NovaMindInput("ماذا تتذكر عني", nowMillis = 13L))
        assertTrue(remaining.textArabic.contains("أفضّل الهدوء"))
        assertFalse(remaining.textArabic.contains("أحب القهوة"))

        val cleared = NovaMind(memory).respond(NovaMindInput("انس كل ما تتذكره عني", nowMillis = 14L))
        assertTrue(cleared.textArabic.contains("مسحت كل الملاحظات"))
        assertTrue(NovaMind(memory).respond(NovaMindInput("ماذا تتذكر عني", nowMillis = 15L)).textArabic.contains("لا توجد لديّ"))
    }

    @Test fun blankInputGetsSafeArabicFallback() {
        val reply = NovaMind(InMemoryNovaMindMemory()).respond(NovaMindInput("   "))
        assertTrue(reply.textArabic.isNotBlank())
        assertFalse(reply.usedLocalModel)
    }
}
