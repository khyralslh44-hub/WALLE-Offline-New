package com.nova.robot

/**
 * NovaMind's deterministic, offline-first orchestration layer.
 *
 * The language model is injected through [LocalLanguageModel]. No network client is
 * implemented here: when no on-device model is installed, NovaMind uses a small,
 * explicit fallback rather than pretending that a template is neural reasoning.
 */
enum class NovaEmotion { CALM, CURIOUS, HAPPY, SURPRISED, FRUSTRATED, SLEEPY, PLAYFUL, THINKING }

data class NovaMindInput(
    val text: String,
    val visualSummary: String? = null,
    val nowMillis: Long = System.currentTimeMillis()
)

data class NovaMindReply(
    val textArabic: String,
    val emotion: NovaEmotion,
    val usedLocalModel: Boolean,
    val memoryUpdated: Boolean
)

data class NovaMemoryItem(
    val key: String,
    val value: String,
    val updatedAtMillis: Long
)

interface LocalLanguageModel {
    /** Must run locally and return null if unavailable, timed out, or unable to answer. */
    fun generate(prompt: String, maxTokens: Int): String?
}

interface NovaMindMemory {
    fun read(key: String): String?
    fun write(item: NovaMemoryItem)
    fun increment(key: String): Int
}

/** In-memory implementation for tests. Production storage should use app-private Room/SQLite. */
class InMemoryNovaMindMemory : NovaMindMemory {
    private val values = linkedMapOf<String, NovaMemoryItem>()
    private val counters = mutableMapOf<String, Int>()

    @Synchronized override fun read(key: String): String? = values[key]?.value

    @Synchronized override fun write(item: NovaMemoryItem) {
        values[item.key] = item
    }

    @Synchronized override fun increment(key: String): Int {
        val next = (counters[key] ?: 0) + 1
        counters[key] = next
        return next
    }
}

class NovaMind(
    private val memory: NovaMindMemory,
    private val localModel: LocalLanguageModel? = null,
    private val maxPromptChars: Int = 1800,
    private val maxOutputTokens: Int = 120
) {
    fun respond(input: NovaMindInput): NovaMindReply {
        val cleanedText = input.text.trim().take(1200)
        val visual = input.visualSummary?.trim()?.take(300).orEmpty()
        val interactionCount = memory.increment("interaction_count")
        val previousTopic = memory.read("last_topic")
        val topic = inferTopic(cleanedText)

        val prompt = buildString {
            append("You are Nova, a concise friendly robot. Reply in Arabic when appropriate. ")
            append("Work offline. Be honest about uncertainty; do not invent visual facts. ")
            append("Use a warm, consistent personality. Do not claim human feelings.\n")
            if (!previousTopic.isNullOrBlank()) append("Previous topic: ").append(previousTopic).append('\n')
            if (visual.isNotBlank()) append("Uncertain camera summary: ").append(visual).append('\n')
            append("User: ").append(cleanedText)
        }.take(maxPromptChars)

        val generated = runCatching { localModel?.generate(prompt, maxOutputTokens) }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2000 }
        val reply = generated ?: fallbackReply(cleanedText, visual, interactionCount)
        val memoryUpdated = topic.isNotBlank() && topic != previousTopic
        if (memoryUpdated) {
            memory.write(NovaMemoryItem("last_topic", topic, input.nowMillis))
        }
        val emotion = inferEmotion(cleanedText, interactionCount)
        return NovaMindReply(reply, emotion, usedLocalModel = generated != null, memoryUpdated = memoryUpdated)
    }

    private fun inferTopic(text: String): String {
        val lower = text.lowercase()
        return when {
            listOf("مرحبا", "أهلا", "السلام", "hello", "hi").any(lower::contains) -> "greeting"
            listOf("كاميرا", "أرى", "ترى", "صورة", "شاهد").any(lower::contains) -> "vision"
            listOf("تذكر", "ذاكرة", "تتعلم", "تعلم").any(lower::contains) -> "memory"
            listOf("كيف", "لماذا", "ما هو", "اشرح").any(lower::contains) -> "question"
            else -> lower.take(48)
        }
    }

    private fun inferEmotion(text: String, count: Int): NovaEmotion {
        val lower = text.lowercase()
        return when {
            listOf("غاضب", "مزعج", "توقف", "خطأ").any(lower::contains) -> NovaEmotion.FRUSTRATED
            listOf("رائع", "أحب", "ممتاز", "شكرا", "شكرًا").any(lower::contains) -> NovaEmotion.HAPPY
            listOf("؟", "لماذا", "كيف", "ماذا").any(lower::contains) -> NovaEmotion.CURIOUS
            count % 5 == 0 -> NovaEmotion.PLAYFUL
            else -> NovaEmotion.CALM
        }
    }

    private fun fallbackReply(text: String, visual: String, count: Int): String {
        if (text.isBlank()) return "أنا هنا. أعطني فكرة أو سؤالًا لأحاول مساعدتك."
        if (visual.isNotBlank() && listOf("ماذا ترين", "ماذا ترى", "ما هذا").any(text.lowercase()::contains)) {
            return "ملخص الكاميرا المتاح لي هو: $visual. هذا وصف أولي وقد يكون غير دقيق."
        }
        return when {
            listOf("مرحبا", "أهلا", "السلام", "hello", "hi").any(text.lowercase()::contains) ->
                "أهلًا، أنا نوفا. يسعدني أن نتحدث، وسأوضح لك عندما لا أكون متأكدة."
            listOf("من أنت", "من انتي", "من انتي", "من انتي").any(text.lowercase()::contains) ->
                "أنا نوفا، روبوت محلي قيد التطوير. أتعلم تنظيم تفاعلاتي، وقدراتي الحالية محدودة."
            else -> "وصلتني فكرتك. في وضع الاختبار المحلي الحالي لا يوجد نموذج لغوي مدمج بعد، لذلك لا أريد اختلاق إجابة. (التفاعل $count)"
        }
    }
}
