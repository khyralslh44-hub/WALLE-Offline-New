package com.nova.robot

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Locale
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Nova's own small neural intent model.
 *
 * No pretrained weights, tokenizer, cloud API, or external ML library is used. The network
 * starts with random weights and learns from a small, reviewed Arabic seed set in this source.
 * It classifies intent; it is NOT a free-form language-generation model.
 */
enum class NovaIntent {
    GREETING, QUESTION, VISION_QUERY, MEMORY_REQUEST, POSITIVE_FEEDBACK, NEGATIVE_FEEDBACK, FAREWELL, UNKNOWN
}

data class NovaIntentPrediction(
    val intent: NovaIntent,
    val confidence: Float
)

class NovaOwnModel private constructor(
    private val w1: FloatArray,
    private val b1: FloatArray,
    private val w2: FloatArray,
    private val b2: FloatArray
) {
    @Synchronized
    fun classify(text: String): NovaIntentPrediction {
        if (text.isBlank()) return NovaIntentPrediction(NovaIntent.UNKNOWN, 0f)
        val probabilities = probabilities(features(text))
        var best = 0
        for (i in 1 until OUTPUT_SIZE) if (probabilities[i] > probabilities[best]) best = i
        val confidence = probabilities[best]
        if (confidence < UNKNOWN_THRESHOLD) return NovaIntentPrediction(NovaIntent.UNKNOWN, confidence)
        return NovaIntentPrediction(LABELS[best], confidence)
    }

    /**
     * Learns from a correction on-device. Call only when a user explicitly confirms the label.
     * The caller may persist [snapshot] in app-private storage to retain this learning offline.
     */
    @Synchronized
    fun learnCorrection(text: String, correctIntent: NovaIntent, rounds: Int = 8) {
        require(correctIntent != NovaIntent.UNKNOWN) { "UNKNOWN cannot be used as a training label" }
        if (text.isBlank()) return
        val x = features(text.take(MAX_TEXT_CHARS))
        val label = LABELS.indexOf(correctIntent)
        repeat(rounds.coerceIn(1, 20)) { trainOne(x, label, CORRECTION_LEARNING_RATE) }
    }

    /** A compact, versioned snapshot of learned weights (not user text). */
    @Synchronized
    fun snapshot(): String {
        val all = FloatArray(w1.size + b1.size + w2.size + b2.size)
        var offset = 0
        w1.copyInto(all, offset); offset += w1.size
        b1.copyInto(all, offset); offset += b1.size
        w2.copyInto(all, offset); offset += w2.size
        b2.copyInto(all, offset)
        val bytes = ByteBuffer.allocate(all.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        all.forEach { value -> bytes.putFloat(value) }
        return SNAPSHOT_PREFIX + Base64.getEncoder().encodeToString(bytes.array())
    }

    private fun probabilities(x: FloatArray): FloatArray {
        val hidden = hiddenLayer(x)
        val logits = FloatArray(OUTPUT_SIZE)
        for (o in 0 until OUTPUT_SIZE) {
            var sum = b2[o]
            for (h in 0 until HIDDEN_SIZE) sum += hidden[h] * w2[h * OUTPUT_SIZE + o]
            logits[o] = sum
        }
        return softmax(logits)
    }

    private fun hiddenLayer(x: FloatArray): FloatArray {
        val hidden = FloatArray(HIDDEN_SIZE)
        for (h in 0 until HIDDEN_SIZE) {
            var sum = b1[h]
            for (i in 0 until INPUT_SIZE) sum += x[i] * w1[i * HIDDEN_SIZE + h]
            hidden[h] = kotlin.math.tanh(sum)
        }
        return hidden
    }

    private fun trainOne(x: FloatArray, label: Int, rate: Float) {
        val hidden = hiddenLayer(x)
        val logits = FloatArray(OUTPUT_SIZE)
        for (o in 0 until OUTPUT_SIZE) {
            var sum = b2[o]
            for (h in 0 until HIDDEN_SIZE) sum += hidden[h] * w2[h * OUTPUT_SIZE + o]
            logits[o] = sum
        }
        val output = softmax(logits)
        val outputDelta = output.copyOf()
        outputDelta[label] -= 1f
        val hiddenDelta = FloatArray(HIDDEN_SIZE)
        for (h in 0 until HIDDEN_SIZE) {
            var back = 0f
            for (o in 0 until OUTPUT_SIZE) back += outputDelta[o] * w2[h * OUTPUT_SIZE + o]
            hiddenDelta[h] = back * (1f - hidden[h] * hidden[h])
        }
        for (h in 0 until HIDDEN_SIZE) {
            for (o in 0 until OUTPUT_SIZE) {
                w2[h * OUTPUT_SIZE + o] -= rate * hidden[h] * outputDelta[o]
            }
        }
        for (o in 0 until OUTPUT_SIZE) b2[o] -= rate * outputDelta[o]
        for (i in 0 until INPUT_SIZE) {
            val xi = x[i]
            if (xi == 0f) continue
            for (h in 0 until HIDDEN_SIZE) w1[i * HIDDEN_SIZE + h] -= rate * xi * hiddenDelta[h]
        }
        for (h in 0 until HIDDEN_SIZE) b1[h] -= rate * hiddenDelta[h]
    }

    companion object {
        private const val INPUT_SIZE = 192
        private const val WORD_BUCKETS = 96
        private const val HIDDEN_SIZE = 20
        private const val OUTPUT_SIZE = 7
        private const val UNKNOWN_THRESHOLD = 0.36f
        private const val MAX_TEXT_CHARS = 800
        private const val TRAINING_EPOCHS = 90
        private const val LEARNING_RATE = 0.075f
        private const val CORRECTION_LEARNING_RATE = 0.012f
        private const val SNAPSHOT_PREFIX = "nova-own-mlp-v1:"
        private val LABELS = listOf(
            NovaIntent.GREETING, NovaIntent.QUESTION, NovaIntent.VISION_QUERY,
            NovaIntent.MEMORY_REQUEST, NovaIntent.POSITIVE_FEEDBACK,
            NovaIntent.NEGATIVE_FEEDBACK, NovaIntent.FAREWELL
        )

        private data class Example(val text: String, val intent: NovaIntent)

        private val seedExamples = listOf(
            Example("مرحبا", NovaIntent.GREETING),
            Example("أهلا يا نوفا", NovaIntent.GREETING),
            Example("السلام عليكم", NovaIntent.GREETING),
            Example("صباح الخير", NovaIntent.GREETING),
            Example("مساء الخير يا صديقتي", NovaIntent.GREETING),
            Example("كيف حالك يا نوفا", NovaIntent.GREETING),
            Example("أهلا وسهلا", NovaIntent.GREETING),
            Example("هاي نوفا", NovaIntent.GREETING),

            Example("كيف تعملين", NovaIntent.QUESTION),
            Example("لماذا يحدث هذا", NovaIntent.QUESTION),
            Example("ماذا يعني الذكاء", NovaIntent.QUESTION),
            Example("اشرح لي الفكرة", NovaIntent.QUESTION),
            Example("ما هو النموذج", NovaIntent.QUESTION),
            Example("هل يمكنك المساعدة", NovaIntent.QUESTION),
            Example("أريد أن أفهم السبب", NovaIntent.QUESTION),
            Example("متى يبدأ الأمر", NovaIntent.QUESTION),

            Example("ماذا ترين في الكاميرا", NovaIntent.VISION_QUERY),
            Example("ما هذا أمامك", NovaIntent.VISION_QUERY),
            Example("صفي المشهد", NovaIntent.VISION_QUERY),
            Example("هل ترين شخصا", NovaIntent.VISION_QUERY),
            Example("ماذا يوجد في الصورة", NovaIntent.VISION_QUERY),
            Example("هل هذا كلب أم إنسان", NovaIntent.VISION_QUERY),
            Example("انظري إلى هذا الشيء", NovaIntent.VISION_QUERY),
            Example("صفي ما تشاهدينه", NovaIntent.VISION_QUERY),

            Example("تذكري أنني أحب الهدوء", NovaIntent.MEMORY_REQUEST),
            Example("احفظي هذا التفضيل", NovaIntent.MEMORY_REQUEST),
            Example("ماذا تتذكرين عني", NovaIntent.MEMORY_REQUEST),
            Example("تعلمي أن المساء هادئ", NovaIntent.MEMORY_REQUEST),
            Example("انسَي المعلومة السابقة", NovaIntent.MEMORY_REQUEST),
            Example("خزني هذه المعلومة", NovaIntent.MEMORY_REQUEST),
            Example("هل تتذكرين آخر مرة", NovaIntent.MEMORY_REQUEST),
            Example("أريد مسح ذاكرتي", NovaIntent.MEMORY_REQUEST),

            Example("شكرا لك", NovaIntent.POSITIVE_FEEDBACK),
            Example("ممتاز يا نوفا", NovaIntent.POSITIVE_FEEDBACK),
            Example("أحسنت", NovaIntent.POSITIVE_FEEDBACK),
            Example("أحب هذا التصرف", NovaIntent.POSITIVE_FEEDBACK),
            Example("رائع جدا", NovaIntent.POSITIVE_FEEDBACK),
            Example("أنت مفيدة", NovaIntent.POSITIVE_FEEDBACK),
            Example("هذا جميل", NovaIntent.POSITIVE_FEEDBACK),
            Example("أنا سعيد بهذا", NovaIntent.POSITIVE_FEEDBACK),

            Example("هذا خطأ", NovaIntent.NEGATIVE_FEEDBACK),
            Example("هذا مزعج", NovaIntent.NEGATIVE_FEEDBACK),
            Example("لم تفهميني", NovaIntent.NEGATIVE_FEEDBACK),
            Example("توقفي عن التكرار", NovaIntent.NEGATIVE_FEEDBACK),
            Example("هذا الرد سيئ", NovaIntent.NEGATIVE_FEEDBACK),
            Example("أنا غاضب", NovaIntent.NEGATIVE_FEEDBACK),
            Example("لا تفعلي ذلك", NovaIntent.NEGATIVE_FEEDBACK),
            Example("ما زلت لا تعملين", NovaIntent.NEGATIVE_FEEDBACK),

            Example("إلى اللقاء", NovaIntent.FAREWELL),
            Example("مع السلامة", NovaIntent.FAREWELL),
            Example("سأذهب الآن", NovaIntent.FAREWELL),
            Example("تصبحين على خير", NovaIntent.FAREWELL),
            Example("وداعا نوفا", NovaIntent.FAREWELL),
            Example("أراك لاحقا", NovaIntent.FAREWELL),
            Example("سأغلق التطبيق", NovaIntent.FAREWELL),
            Example("انتهينا الآن", NovaIntent.FAREWELL)
        )

        /** Initializes a new model from random weights and trains it locally from the seed set. */
        fun trainFromScratch(): NovaOwnModel {
            val random = Random(0x4E4F5641)
            val w1 = FloatArray(INPUT_SIZE * HIDDEN_SIZE) {
                (random.nextFloat() - 0.5f) * 0.18f
            }
            val b1 = FloatArray(HIDDEN_SIZE)
            val w2 = FloatArray(HIDDEN_SIZE * OUTPUT_SIZE) {
                (random.nextFloat() - 0.5f) * 0.18f
            }
            val b2 = FloatArray(OUTPUT_SIZE)
            val model = NovaOwnModel(w1, b1, w2, b2)
            val prepared = seedExamples.map { example ->
                features(example.text) to LABELS.indexOf(example.intent)
            }
            repeat(TRAINING_EPOCHS) { epoch ->
                prepared.shuffled(Random(0x4E4F5641 + epoch)).forEach { (x, label) ->
                    model.trainOne(x, label, LEARNING_RATE)
                }
            }
            return model
        }

        fun restore(snapshot: String?): NovaOwnModel? {
            if (snapshot == null || !snapshot.startsWith(SNAPSHOT_PREFIX)) return null
            return runCatching {
                val bytes = Base64.getDecoder().decode(snapshot.removePrefix(SNAPSHOT_PREFIX))
                val expectedCount = INPUT_SIZE * HIDDEN_SIZE + HIDDEN_SIZE +
                    HIDDEN_SIZE * OUTPUT_SIZE + OUTPUT_SIZE
                require(bytes.size == expectedCount * 4)
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val all = FloatArray(expectedCount) { buffer.float }
                require(all.all { it.isFinite() })
                var offset = 0
                val w1 = all.copyOfRange(offset, offset + INPUT_SIZE * HIDDEN_SIZE); offset += w1.size
                val b1 = all.copyOfRange(offset, offset + HIDDEN_SIZE); offset += b1.size
                val w2 = all.copyOfRange(offset, offset + HIDDEN_SIZE * OUTPUT_SIZE); offset += w2.size
                val b2 = all.copyOfRange(offset, offset + OUTPUT_SIZE)
                NovaOwnModel(w1, b1, w2, b2)
            }.getOrNull()
        }

        private fun features(text: String): FloatArray {
            val normalized = normalize(text.take(MAX_TEXT_CHARS))
            val x = FloatArray(INPUT_SIZE)
            val words = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
            words.forEach { word -> x[Math.floorMod(word.hashCode(), WORD_BUCKETS)] += 1f }
            for (i in 0 until words.size - 1) {
                val bigram = words[i] + "_" + words[i + 1]
                x[Math.floorMod(bigram.hashCode(), WORD_BUCKETS)] += 0.65f
            }
            val compact = normalized.replace(" ", "_")
            if (compact.length >= 3) {
                for (i in 0..compact.length - 3) {
                    val trigram = compact.substring(i, i + 3)
                    val bucket = WORD_BUCKETS + Math.floorMod(trigram.hashCode(), INPUT_SIZE - WORD_BUCKETS)
                    x[bucket] += 0.35f
                }
            }
            var normSquared = 0f
            for (value in x) normSquared += value * value
            val norm = sqrt(normSquared)
            if (norm > 0f) for (i in x.indices) x[i] /= norm
            return x
        }

        private fun normalize(text: String): String {
            return text.lowercase(Locale.ROOT)
                .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
                .replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي')
                .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        private fun softmax(logits: FloatArray): FloatArray {
            val maxLogit = logits.maxOrNull() ?: 0f
            val exps = FloatArray(logits.size) { exp((logits[it] - maxLogit).coerceIn(-30f, 30f)) }
            val sum = exps.sum().coerceAtLeast(1e-12f)
            return FloatArray(exps.size) { exps[it] / sum }
        }
    }
}
