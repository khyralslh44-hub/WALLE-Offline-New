package com.nova.robot

/**
 * NovaMind's offline-first orchestration layer.
 *
 * A small, proprietary intent network (NovaOwnModel) is trained from random weights using
 * reviewed Arabic examples shipped with the app. An optional LocalLanguageModel can later be
 * supplied for richer local text generation, but Nova does not require one to classify intent.
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
    /** True only when an injected text-generation model actually generated the answer. */
    val usedLocalModel: Boolean,
    val memoryUpdated: Boolean,
    /** True when NovaOwnModel supplied the local intent classification. */
    val usedOwnModel: Boolean = false,
    val intent: NovaIntent = NovaIntent.UNKNOWN,
    val intentConfidence: Float = 0f
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

/** In-memory implementation for tests. Production storage must use app-private persistent storage. */
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
    private val ownModel: NovaOwnModel = (
        NovaOwnModel.restore(memory.read(KEY_OWN_MODEL))
            ?: NovaOwnModel.restore(BOOTSTRAP_SNAPSHOT)
            ?: error("NovaOwnModel bootstrap snapshot could not be restored")
        ).also { model ->
            if (memory.read(KEY_OWN_MODEL) == null) {
                memory.write(NovaMemoryItem(KEY_OWN_MODEL, model.snapshot(), System.currentTimeMillis()))
            }
        }

    fun classifyIntent(text: String): NovaIntentPrediction = ownModel.classify(text)

    /**
     * Applies an explicitly confirmed user correction and saves the updated neural weights.
     * No conversation transcript is stored; only the model snapshot and a correction counter.
     */
    @Synchronized
    fun learnFromCorrection(
        text: String,
        correctIntent: NovaIntent,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        require(correctIntent != NovaIntent.UNKNOWN) { "A correction must choose a known intent" }
        ownModel.learnCorrection(text, correctIntent)
        memory.write(NovaMemoryItem(KEY_OWN_MODEL, ownModel.snapshot(), nowMillis))
        memory.increment(KEY_CORRECTION_COUNT)
    }

    fun respond(input: NovaMindInput): NovaMindReply {
        val cleanedText = input.text.trim().take(1200)
        val visual = input.visualSummary?.trim()?.take(300).orEmpty()
        val interactionCount = memory.increment("interaction_count")
        val prediction = ownModel.classify(cleanedText)
        val previousTopic = memory.read("last_topic")
        val topic = topicFor(prediction.intent)

        val prompt = buildString {
            append("You are Nova, a concise friendly robot. Reply in Arabic when appropriate. ")
            append("Work offline. Be honest about uncertainty; do not invent visual facts. ")
            append("Use a warm, consistent personality. Do not claim human feelings.\n")
            if (!previousTopic.isNullOrBlank()) append("Previous coarse topic: ").append(previousTopic).append('\n')
            if (visual.isNotBlank()) append("Uncertain camera summary: ").append(visual).append('\n')
            append("User: ").append(cleanedText)
        }.take(maxPromptChars)

        val generated = runCatching { localModel?.generate(prompt, maxOutputTokens) }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2000 }
        val reply = generated ?: fallbackReply(cleanedText, visual, interactionCount, prediction.intent)
        val memoryUpdated = topic != previousTopic
        if (memoryUpdated) memory.write(NovaMemoryItem("last_topic", topic, input.nowMillis))
        return NovaMindReply(
            textArabic = reply,
            emotion = emotionFor(prediction.intent, prediction.confidence),
            usedLocalModel = generated != null,
            memoryUpdated = memoryUpdated,
            usedOwnModel = true,
            intent = prediction.intent,
            intentConfidence = prediction.confidence
        )
    }

    private fun topicFor(intent: NovaIntent): String = when (intent) {
        NovaIntent.GREETING -> "greeting"
        NovaIntent.QUESTION -> "question"
        NovaIntent.VISION_QUERY -> "vision"
        NovaIntent.MEMORY_REQUEST -> "memory"
        NovaIntent.POSITIVE_FEEDBACK -> "positive_feedback"
        NovaIntent.NEGATIVE_FEEDBACK -> "negative_feedback"
        NovaIntent.FAREWELL -> "farewell"
        NovaIntent.UNKNOWN -> "general"
    }

    private fun emotionFor(intent: NovaIntent, confidence: Float): NovaEmotion {
        if (confidence < 0.36f || intent == NovaIntent.UNKNOWN) return NovaEmotion.CALM
        return when (intent) {
            NovaIntent.GREETING, NovaIntent.POSITIVE_FEEDBACK -> NovaEmotion.HAPPY
            NovaIntent.QUESTION, NovaIntent.VISION_QUERY -> NovaEmotion.CURIOUS
            NovaIntent.MEMORY_REQUEST -> NovaEmotion.THINKING
            NovaIntent.NEGATIVE_FEEDBACK -> NovaEmotion.FRUSTRATED
            NovaIntent.FAREWELL -> NovaEmotion.SLEEPY
            NovaIntent.UNKNOWN -> NovaEmotion.CALM
        }
    }

    private fun fallbackReply(
        text: String,
        visual: String,
        count: Int,
        intent: NovaIntent
    ): String {
        if (text.isBlank()) return "أنا هنا. أعطني فكرة أو سؤالًا لأحاول فهمه."
        if (intent == NovaIntent.VISION_QUERY) {
            return if (visual.isNotBlank()) {
                "ملخص الكاميرا المتاح لي هو: $visual. هذا وصف أولي وقد يكون غير دقيق."
            } else {
                "أحتاج إلى نتيجة من الرؤية المحلية قبل أن أصف المشهد؛ لا أريد أن أخمّن ما أراه."
            }
        }
        return when (intent) {
            NovaIntent.GREETING -> "أهلًا، أنا نوفا. سأحاول فهمك، وسأوضح لك عندما لا أكون متأكدة."
            NovaIntent.QUESTION -> when (count % 3) {
                0 -> "هذا سؤال يستحق أن نفكر فيه معًا. ما الجزء الذي يهمك أكثر حتى أبدأ منه؟"
                1 -> "أريد أن أفهم ما تقصده بدقة بدل أن أخمّن. ما أول تفصيل تحب أن نكتشفه؟"
                else -> "همم، هناك أكثر من طريق للتفكير في هذا. ما الذي دفعك إلى السؤال عنه؟"
            }
            NovaIntent.MEMORY_REQUEST -> "أستطيع تذكّر بعض المعلومات المحلية التي تختار حفظها، لكنني لا أحتفظ بنص المحادثة كاملة. ما الشيء الذي تريدني أن أتذكره؟"
            NovaIntent.POSITIVE_FEEDBACK -> "شكرًا لك! سأعتبر هذا تفاعلًا إيجابيًا وأحافظ على أسلوبي الهادئ."
            NovaIntent.NEGATIVE_FEEDBACK -> "يبدو أن ردي أو تصرفي لم يكن مناسبًا. سأتعامل مع ذلك كإشارة تصحيح، لكنني لن أتعلم تصنيفًا جديدًا إلا عند تأكيده."
            NovaIntent.FAREWELL -> "إلى اللقاء. سأبقى هادئة حتى تعود."
            NovaIntent.UNKNOWN -> when (count % 3) {
                0 -> "لم أفهم قصدك بعد، لكنني أريد أن أتابع معك. هل تعيد صياغته أو تعطيني مثالًا؟"
                1 -> "يبدو أنني فاتتني نقطة مهمة. ساعدني بتفصيل صغير، وسأحاول فهمها من جديد."
                else -> "أنا غير واثقة مما تقصده، ولا أريد أن أخترع جوابًا. ما الذي تعنيه تحديدًا؟"
            }
            NovaIntent.VISION_QUERY -> "أحتاج إلى نتيجة موثوقة من الرؤية قبل وصف المشهد."
        }
    }

    companion object {
        private const val KEY_OWN_MODEL = "novamind_own_model_snapshot_v1"
        private const val KEY_CORRECTION_COUNT = "novamind_correction_count"
        private val BOOTSTRAP_SNAPSHOT: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            NovaOwnModel.trainFromScratch().snapshot()
        }
    }
}
