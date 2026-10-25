package com.eve.robot

import java.util.Locale
import kotlin.math.sqrt

/**
 * EVE's custom cognitive arbitration layer.
 *
 * It does not replace the language model, vision models, affect engine, or memory. It decides
 * whether a visual observation deserves attention now, should be watched silently, or should be
 * rejected as weak/repetitive evidence. The result is deterministic and cheap enough for a phone.
 *
 * Recurrence means the label was seen repeatedly; it does NOT prove that the same physical
 * object or person was recognized.
 */
enum class EveCognitiveAction {
    ASK_QUESTION, OBSERVE_QUIETLY, WAIT_FOR_EVIDENCE, SUPPRESS_REPEAT, IGNORE_NOISE
}

data class EveCognitionInput(
    val label: String,
    val confidence: Float,
    val sightings: Int,
    val curiosityBias: Float,
    val nowMs: Long,
    val lastAnyQuestionAtMs: Long,
    val lastSameLabelQuestionAtMs: Long,
    val quietForMs: Long,
    val isUserConversationActive: Boolean = false
)

data class EveCognitionAssessment(
    val action: EveCognitiveAction,
    val normalizedLabel: String,
    val confidence: Float,
    val novelty: Float,
    val salience: Float,
    val uncertainty: Float,
    val informationValue: Float,
    val priority: Float,
    val reason: String
) {
    val shouldAsk: Boolean get() = action == EveCognitiveAction.ASK_QUESTION
}

class EveCognitionCore {
    companion object {
        const val MIN_CONFIDENCE = 0.82f
        const val MIN_PRIORITY = 0.60f
        const val GLOBAL_QUESTION_COOLDOWN_MS = 42_000L
        const val SAME_LABEL_COOLDOWN_MS = 45L * 60L * 1_000L
        const val MIN_QUIET_BEFORE_AUTONOMOUS_QUESTION_MS = 7_000L

        private val NOISE_LABELS = setOf(
            "pattern", "texture", "background", "wall", "floor", "ceiling", "line",
            "geometry", "grid", "tile", "color", "colour", "design", "lighting",
            "shadow", "unknown", "???", ""
        )
        private val PERSON_LABELS = setOf("person", "people", "human", "face", "man", "woman", "child", "boy", "girl")
        private val ANIMAL_LABELS = setOf("animal", "cat", "dog", "bird", "pet", "puppy", "kitten", "horse", "rabbit", "fish", "hamster", "turtle", "butterfly")
        private val FOOD_LABELS = setOf("food", "fruit", "banana", "apple", "pizza", "bread", "meal", "sandwich", "vegetable", "cake", "orange", "strawberry", "egg", "cookie")
        private val PLANT_LABELS = setOf("plant", "flower", "tree", "leaf", "grass", "garden", "cactus")
        private val OBJECT_LABELS = setOf(
            "phone", "mobile phone", "cell phone", "smartphone", "laptop", "computer", "keyboard",
            "mouse", "monitor", "tv", "tv monitor", "book", "cup", "bottle", "car", "bicycle",
            "motorcycle", "bus", "truck", "train", "airplane", "boat", "chair", "couch",
            "dining table", "table", "bed", "toilet", "toy", "clock", "camera", "headphones",
            "remote", "remote control", "microwave", "oven", "toaster", "sink", "refrigerator",
            "vase", "scissors", "teddy bear", "shoe", "umbrella", "bag", "backpack", "glasses",
            "watch", "suitcase", "handbag", "sports ball", "skateboard", "traffic light", "stop sign"
        )
    }

    fun evaluate(input: EveCognitionInput): EveCognitionAssessment {
        val label = input.label.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        val confidence = input.confidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val curiosity = input.curiosityBias.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val count = input.sightings.coerceAtLeast(1)
        val novelty = (1f / sqrt(count.toFloat())).coerceIn(0.20f, 1f)
        val salience = salienceFor(label)
        val uncertainty = (1f - confidence).coerceIn(0f, 1f)
        val informationValue = (novelty * (0.65f + 0.35f * uncertainty)).coerceIn(0f, 1f)
        val priority = (
            novelty * 0.30f +
                salience * 0.25f +
                curiosity * 0.20f +
                confidence * 0.15f +
                informationValue * 0.10f
            ).coerceIn(0f, 1f)

        fun result(action: EveCognitiveAction, reason: String) = EveCognitionAssessment(
            action = action, normalizedLabel = label, confidence = confidence,
            novelty = novelty, salience = salience, uncertainty = uncertainty,
            informationValue = informationValue, priority = priority, reason = reason
        )

        if (label in NOISE_LABELS || salience <= 0f) {
            return result(EveCognitiveAction.IGNORE_NOISE, "generic or unsupported label is not a useful belief")
        }
        if (confidence < MIN_CONFIDENCE) {
            return result(EveCognitiveAction.WAIT_FOR_EVIDENCE, "visual confidence is below the question threshold")
        }
        if (input.isUserConversationActive || input.quietForMs < MIN_QUIET_BEFORE_AUTONOMOUS_QUESTION_MS) {
            return result(EveCognitiveAction.OBSERVE_QUIETLY, "user interaction has priority over autonomous curiosity")
        }
        if (isWithinCooldown(input.nowMs, input.lastAnyQuestionAtMs, GLOBAL_QUESTION_COOLDOWN_MS) ||
            isWithinCooldown(input.nowMs, input.lastSameLabelQuestionAtMs, SAME_LABEL_COOLDOWN_MS)
        ) {
            return result(EveCognitiveAction.SUPPRESS_REPEAT, "question budget or same-label cooldown is active")
        }
        if (priority < MIN_PRIORITY) {
            return result(EveCognitiveAction.OBSERVE_QUIETLY, "current observation has low curiosity utility")
        }
        return result(
            EveCognitiveAction.ASK_QUESTION,
            "high-confidence evidence, meaningful label, available attention budget, priority=" +
                "%.2f".format(Locale.ROOT, priority)
        )
    }

    private fun isWithinCooldown(nowMs: Long, previousMs: Long, intervalMs: Long): Boolean {
        if (previousMs <= 0L) return false
        if (nowMs < previousMs) return true
        return nowMs - previousMs < intervalMs
    }

    private fun salienceFor(label: String): Float = when {
        label in PERSON_LABELS -> 0.95f
        label in ANIMAL_LABELS -> 0.92f
        label in FOOD_LABELS -> 0.84f
        label in PLANT_LABELS -> 0.80f
        label in OBJECT_LABELS -> 0.78f
        else -> 0f
    }
}
