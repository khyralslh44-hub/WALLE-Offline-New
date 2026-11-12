package com.eve.robot

import java.io.InputStream
import kotlin.math.exp
import kotlin.math.max

/**
 * Tiny, dataset-trained polarity component for Libyan Arabic restaurant comments.
 * This is deliberately domain-limited: its training corpus contains restaurant reviews,
 * not general conversation. Unknown or weakly supported text returns null.
 */
internal class LibyanSentimentModel private constructor(
    private val bias: Float,
    private val minimumConfidence: Float,
    private val weights: Map<String, Float>,
    val trainingRows: Int,
    val validationAccuracy: Float,
    val validationMacroF1: Float
) {
    data class Prediction(
        val polarity: Polarity,
        val positiveProbability: Float,
        val confidence: Float,
        val matchedFeatures: Int
    )

    enum class Polarity { POSITIVE, NEGATIVE }

    fun predict(rawText: String): Prediction? {
        val normalized = EveArabicTextUnderstanding.normalize(rawText)
        if (normalized.isBlank()) return null
        val features = extractFeatures(normalized)
        if (features.isEmpty()) return null

        var score = bias
        var matched = 0
        for (feature in features) {
            val weight = weights[feature] ?: continue
            score += weight
            matched++
        }
        if (matched < 1) return null

        val positive = sigmoid(score)
        val confidence = max(positive, 1f - positive)
        if (!confidence.isFinite() || confidence < minimumConfidence) return null
        return Prediction(
            polarity = if (positive >= 0.5f) Polarity.POSITIVE else Polarity.NEGATIVE,
            positiveProbability = positive,
            confidence = confidence,
            matchedFeatures = matched
        )
    }

    companion object {
        private const val HEADER = "EVE_LIBYAN_SENTIMENT_V1"
        private const val DEFAULT_MIN_CONFIDENCE = 0.64f

        fun load(input: InputStream): LibyanSentimentModel =
            input.bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }

        fun parse(text: String): LibyanSentimentModel {
            val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            require(lines.firstOrNull() == HEADER) { "Unsupported Libyan sentiment model header." }

            var bias = 0f
            var threshold = DEFAULT_MIN_CONFIDENCE
            var trainingRows = 0
            var accuracy = 0f
            var macroF1 = 0f
            val weights = HashMap<String, Float>()

            for (line in lines.drop(1)) {
                if (line.startsWith("#")) continue
                val parts = line.split('\t')
                when (parts.firstOrNull()) {
                    "bias" -> if (parts.size >= 2) bias = parts[1].toFloat()
                    "threshold" -> if (parts.size >= 2) threshold = parts[1].toFloat()
                    "rows" -> if (parts.size >= 2) trainingRows = parts[1].toInt()
                    "accuracy" -> if (parts.size >= 2) accuracy = parts[1].toFloat()
                    "macro_f1" -> if (parts.size >= 2) macroF1 = parts[1].toFloat()
                    "f" -> if (parts.size >= 3) {
                        val feature = parts[1]
                        val weight = parts[2].toFloat()
                        if (feature.isNotBlank() && weight.isFinite()) weights[feature] = weight
                    }
                }
            }
            require(weights.isNotEmpty()) { "Libyan sentiment model has no learned features." }
            require(bias.isFinite() && threshold in 0.5f..0.95f) { "Invalid sentiment model calibration." }
            return LibyanSentimentModel(
                bias, threshold, weights.toMap(), trainingRows, accuracy, macroF1
            )
        }

        private fun extractFeatures(normalizedText: String): Set<String> {
            val tokens = normalizedText.split(Regex("\\s+")).filter(String::isNotBlank)
            if (tokens.isEmpty()) return emptySet()
            val features = LinkedHashSet<String>()
            tokens.forEach { token ->
                features += "w:$token"
                val padded = "^$token\$"
                for (n in 2..4) {
                    if (padded.length >= n) {
                        for (start in 0..(padded.length - n)) {
                            features += "c:${padded.substring(start, start + n)}"
                        }
                    }
                }
            }
            for (index in 0 until tokens.lastIndex) {
                features += "b:${tokens[index]}_${tokens[index + 1]}"
            }
            return features
        }

        private fun sigmoid(value: Float): Float {
            val bounded = value.coerceIn(-20f, 20f)
            return (1.0 / (1.0 + exp(-bounded.toDouble()))).toFloat()
        }
    }
}
