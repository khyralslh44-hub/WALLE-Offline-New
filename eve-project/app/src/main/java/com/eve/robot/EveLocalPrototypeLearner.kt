package com.eve.robot

import java.io.File
import java.util.Properties
import kotlin.math.sqrt

/**
 * Tiny, offline, incremental prototype learner for explicitly verified observations.
 *
 * This is real local model updating for a small feature classifier, not fine-tuning of Qwen or
 * any bundled vision model. It deliberately refuses labels produced only by EVE's own prediction
 * to reduce self-reinforcing errors. Store the file in app-private storage.
 */
enum class EveLearningLabelSource {
    USER_CONFIRMED,
    TRUSTED_SENSOR,
    MODEL_PREDICTION
}

data class EveLearnedPrediction(
    val label: String,
    val confidence: Float,
    val examplesForLabel: Int
)

class EveLocalPrototypeLearner(
    private val stateFile: File,
    private val allowedLabels: Set<String>,
    private val featureCount: Int,
    private val minimumExamplesPerLabel: Int = 3,
    private val maxExamplesPerLabel: Int = 500
) {
    private data class Prototype(var count: Int, val mean: DoubleArray)
    private val prototypes = linkedMapOf<String, Prototype>()

    init {
        require(featureCount in 1..256)
        require(minimumExamplesPerLabel >= 1)
        require(maxExamplesPerLabel >= minimumExamplesPerLabel)
        require(allowedLabels.isNotEmpty() && allowedLabels.all { it.matches(Regex("[a-z][a-z0-9_]{0,47}")) })
        load()
    }

    /** Only independently verified labels can change the local model. */
    @Synchronized
    fun learn(
        label: String,
        features: FloatArray,
        source: EveLearningLabelSource
    ): Boolean {
        if (source == EveLearningLabelSource.MODEL_PREDICTION) return false
        if (label !in allowedLabels || !validFeatures(features)) return false

        val prototype = prototypes[label]
        if (prototype == null) {
            prototypes[label] = Prototype(1, features.map { it.toDouble() }.toDoubleArray())
        } else {
            if (prototype.count >= maxExamplesPerLabel) return false
            val nextCount = prototype.count + 1
            for (i in 0 until featureCount) {
                prototype.mean[i] += (features[i].toDouble() - prototype.mean[i]) / nextCount
            }
            prototype.count = nextCount
        }
        persist()
        return true
    }

    @Synchronized
    fun predict(features: FloatArray): EveLearnedPrediction? {
        if (!validFeatures(features)) return null
        val eligible = prototypes.filterValues { it.count >= minimumExamplesPerLabel }
        if (eligible.isEmpty()) return null

        var bestLabel: String? = null
        var bestDistance = Double.POSITIVE_INFINITY
        var secondDistance = Double.POSITIVE_INFINITY
        var bestCount = 0
        for ((label, prototype) in eligible) {
            val distance = euclideanDistance(features, prototype.mean)
            if (distance < bestDistance) {
                secondDistance = bestDistance
                bestDistance = distance
                bestLabel = label
                bestCount = prototype.count
            } else if (distance < secondDistance) {
                secondDistance = distance
            }
        }
        val label = bestLabel ?: return null
        // Refuse ambiguous results instead of forcing a label.
        if (bestDistance > 0.35 || (secondDistance.isFinite() && secondDistance - bestDistance < 0.05)) {
            return null
        }
        val confidence = (1.0 - bestDistance / 0.35).coerceIn(0.0, 1.0).toFloat()
        return EveLearnedPrediction(label, confidence, bestCount)
    }

    @Synchronized
    fun exampleCounts(): Map<String, Int> = prototypes.mapValues { it.value.count }

    private fun validFeatures(features: FloatArray): Boolean =
        features.size == featureCount && features.all { it.isFinite() && it in 0f..1f }

    private fun euclideanDistance(features: FloatArray, mean: DoubleArray): Double {
        var sum = 0.0
        for (i in 0 until featureCount) {
            val delta = features[i].toDouble() - mean[i]
            sum += delta * delta
        }
        return sqrt(sum / featureCount)
    }

    private fun load() {
        if (!stateFile.isFile) return
        runCatching {
            val props = Properties()
            stateFile.inputStream().use(props::load)
            for (label in allowedLabels) {
                val count = props.getProperty("$label.count")?.toIntOrNull() ?: continue
                if (count !in 1..maxExamplesPerLabel) continue
                val values = (0 until featureCount).map { i ->
                    props.getProperty("$label.$i")?.toDoubleOrNull()
                }
                if (values.any { it == null || !it.isFinite() || it !in 0.0..1.0 }) continue
                prototypes[label] = Prototype(count, values.map { it!! }.toDoubleArray())
            }
        }
    }

    private fun persist() {
        stateFile.parentFile?.mkdirs()
        val props = Properties()
        for ((label, prototype) in prototypes) {
            props.setProperty("$label.count", prototype.count.toString())
            for (i in 0 until featureCount) props.setProperty("$label.$i", prototype.mean[i].toString())
        }
        val temp = File(stateFile.parentFile ?: File("."), stateFile.name + ".tmp")
        temp.outputStream().use { props.store(it, "EVE offline learner; verified examples only") }
        if (!temp.renameTo(stateFile)) {
            stateFile.outputStream().use { props.store(it, "EVE offline learner; verified examples only") }
            temp.delete()
        }
    }
}
