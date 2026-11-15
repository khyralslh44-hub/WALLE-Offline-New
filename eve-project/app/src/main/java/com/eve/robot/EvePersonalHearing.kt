package com.eve.robot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/**
 * EVE-owned personal hearing memory and acoustic phrase learner.
 *
 * This is not a wrapper around an ASR SDK. It owns EVE's persistent correction memory,
 * MFCC feature extraction and DTW phrase-template matcher. General open-vocabulary Arabic
 * transcription remains a separate capability and must not be confused with phrase learning.
 */
class EvePersonalHearing(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Match(val phrase: String, val distance: Float, val confidence: Float)

    fun learnCorrection(misheard: String, corrected: String): Boolean {
        val source = normalize(misheard)
        val target = normalize(corrected)
        if (source.isBlank() || target.isBlank() || source == target) return false
        val all = readCorrections()
        val previous = all.optJSONObject(source) ?: JSONObject()
        previous.put("text", target)
        previous.put("count", previous.optInt("count", 0) + 1)
        previous.put("updatedAt", System.currentTimeMillis())
        all.put(source, previous)
        prefs.edit().putString(KEY_CORRECTIONS, all.toString()).apply()
        return true
    }

    fun correctTranscript(transcript: String): String {
        val normalized = normalize(transcript)
        if (normalized.isBlank()) return transcript
        val corrections = readCorrections()
        corrections.optJSONObject(normalized)?.let { exact ->
            return exact.optString("text", transcript)
        }
        // Conservative token similarity: only apply a stored correction when it is a close,
        // unambiguous match. Never rewrite an unrelated utterance.
        var bestText: String? = null
        var bestScore = 0f
        var secondScore = 0f
        val keys = corrections.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val score = tokenSimilarity(normalized, key)
            if (score > bestScore) {
                secondScore = bestScore
                bestScore = score
                bestText = corrections.optJSONObject(key)?.optString("text")
            } else if (score > secondScore) {
                secondScore = score
            }
        }
        return if (bestScore >= 0.88f && bestScore - secondScore >= 0.12f) bestText ?: transcript else transcript
    }

    fun correctionCount(): Int = readCorrections().length()

    /** Save a user-approved phrase sample as EVE's own MFCC template. */
    fun learnAudioPhrase(label: String, pcm16: ShortArray, sampleRate: Int = 16_000): Boolean {
        val phrase = normalize(label)
        if (phrase.isBlank() || pcm16.size < sampleRate / 4) return false
        val features = EveMfcc.extract(pcm16, sampleRate)
        if (features.size < 5) return false
        val templates = readTemplates()
        val samples = templates.optJSONArray(phrase) ?: JSONArray()
        if (samples.length() >= MAX_SAMPLES_PER_PHRASE) samples.remove(0)
        samples.put(featuresToJson(features))
        templates.put(phrase, samples)
        prefs.edit().putString(KEY_TEMPLATES, templates.toString()).apply()
        return true
    }

    /** Returns only a phrase whose acoustic distance passes EVE's conservative threshold. */
    fun recognizeLearnedPhrase(pcm16: ShortArray, sampleRate: Int = 16_000): Match? {
        if (pcm16.size < sampleRate / 4) return null
        val query = EveMfcc.extract(pcm16, sampleRate)
        if (query.size < 5) return null
        val templates = readTemplates()
        var bestLabel: String? = null
        var bestDistance = Float.POSITIVE_INFINITY
        var secondDistance = Float.POSITIVE_INFINITY
        val keys = templates.keys()
        while (keys.hasNext()) {
            val label = keys.next()
            val samples = templates.optJSONArray(label) ?: continue
            for (i in 0 until samples.length()) {
                val candidate = jsonToFeatures(samples.optJSONArray(i) ?: continue)
                if (candidate.isEmpty()) continue
                val distance = EveMfcc.dtwDistance(query, candidate)
                if (distance < bestDistance) {
                    secondDistance = bestDistance
                    bestDistance = distance
                    bestLabel = label
                } else if (distance < secondDistance) {
                    secondDistance = distance
                }
            }
        }
        val label = bestLabel ?: return null
        val confidence = (1f - bestDistance / DTW_REJECT_DISTANCE).coerceIn(0f, 1f)
        if (bestDistance > DTW_REJECT_DISTANCE) return null
        if (secondDistance.isFinite() && secondDistance - bestDistance < MIN_DTW_MARGIN) return null
        return Match(label, bestDistance, confidence)
    }

    fun learnedPhraseCount(): Int = readTemplates().length()

    private fun readCorrections(): JSONObject = try {
        JSONObject(prefs.getString(KEY_CORRECTIONS, "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    private fun readTemplates(): JSONObject = try {
        JSONObject(prefs.getString(KEY_TEMPLATES, "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    private fun featuresToJson(features: Array<FloatArray>): JSONArray {
        val outer = JSONArray()
        features.forEach { frame ->
            val row = JSONArray()
            frame.forEach { row.put(it.toDouble()) }
            outer.put(row)
        }
        return outer
    }

    private fun jsonToFeatures(json: JSONArray): Array<FloatArray> =
        Array(json.length()) { i ->
            val row = json.optJSONArray(i) ?: JSONArray()
            FloatArray(row.length()) { j -> row.optDouble(j, 0.0).toFloat() }
        }

    companion object {
        private const val PREFS = "eve_personal_hearing_v1"
        private const val KEY_CORRECTIONS = "corrections"
        private const val KEY_TEMPLATES = "acoustic_templates"
        private const val MAX_SAMPLES_PER_PHRASE = 5
        private const val DTW_REJECT_DISTANCE = 8.5f
        private const val MIN_DTW_MARGIN = 0.35f

        fun normalize(text: String): String = text.trim().lowercase()
            .replace(Regex("[\\u064B-\\u065F\\u0670ـ]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه')
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ").trim()

        /** Parse explicit spoken corrections; arbitrary statements are never treated as teaching. */
        fun parseCorrection(text: String): String? {
            val normalized = normalize(text)
            val markers = listOf("لا اقصد ", "بل اقصد ", "الصحيح ", "اقصد ")
            val marker = markers.firstOrNull { normalized.startsWith(it) } ?: return null
            return normalized.removePrefix(marker).trim().takeIf { it.length >= 2 }
        }

        private fun tokenSimilarity(a: String, b: String): Float {
            val left = a.split(' ').filter { it.isNotBlank() }.toSet()
            val right = b.split(' ').filter { it.isNotBlank() }.toSet()
            if (left.isEmpty() || right.isEmpty()) return 0f
            return left.intersect(right).size.toFloat() / left.union(right).size.toFloat()
        }
    }
}

/** Small deterministic MFCC implementation owned by EVE; no external audio feature SDK. */
internal object EveMfcc {
    private const val FRAME_MS = 25
    private const val HOP_MS = 10
    private const val FILTERS = 26
    private const val COEFFICIENTS = 13

    fun extract(pcm: ShortArray, sampleRate: Int): Array<FloatArray> {
        if (sampleRate < 8_000 || pcm.isEmpty()) return emptyArray()
        val frameSize = (sampleRate * FRAME_MS / 1000).coerceAtLeast(128)
        val hop = (sampleRate * HOP_MS / 1000).coerceAtLeast(64)
        val fftSize = Integer.highestOneBit(frameSize - 1) shl 1
        if (pcm.size < frameSize) return emptyArray()
        val filterBank = melFilterBank(sampleRate, fftSize)
        val output = ArrayList<FloatArray>()
        var offset = 0
        while (offset + frameSize <= pcm.size) {
            val real = DoubleArray(fftSize)
            for (i in 0 until frameSize) {
                val pre = pcm[offset + i].toDouble() / 32768.0
                val previous = if (offset + i > 0) pcm[offset + i - 1].toDouble() / 32768.0 else 0.0
                val emphasized = pre - 0.97 * previous
                val window = 0.5 - 0.5 * cos(2.0 * PI * i / (frameSize - 1))
                real[i] = emphasized * window
            }
            val power = DoubleArray(fftSize / 2 + 1)
            for (k in power.indices) {
                var re = 0.0
                var im = 0.0
                for (n in real.indices) {
                    val angle = 2.0 * PI * k * n / fftSize
                    re += real[n] * cos(angle)
                    im -= real[n] * sin(angle)
                }
                power[k] = (re * re + im * im) / fftSize
            }
            val energies = DoubleArray(FILTERS) { f ->
                var sum = 0.0
                for (k in power.indices) sum += power[k] * filterBank[f][k]
                ln(sum.coerceAtLeast(1e-10))
            }
            val mfcc = FloatArray(COEFFICIENTS) { c ->
                var sum = 0.0
                for (f in energies.indices) sum += energies[f] * cos(PI * c * (f + 0.5) / FILTERS)
                (sum * sqrt(2.0 / FILTERS)).toFloat()
            }
            output.add(mfcc)
            offset += hop
        }
        return output.toTypedArray()
    }

    fun dtwDistance(a: Array<FloatArray>, b: Array<FloatArray>): Float {
        if (a.isEmpty() || b.isEmpty()) return Float.POSITIVE_INFINITY
        val previous = FloatArray(b.size + 1) { Float.POSITIVE_INFINITY }
        val current = FloatArray(b.size + 1) { Float.POSITIVE_INFINITY }
        previous[0] = 0f
        for (i in 1..a.size) {
            current.fill(Float.POSITIVE_INFINITY)
            for (j in 1..b.size) {
                var cost = 0f
                val dimensions = min(a[i - 1].size, b[j - 1].size)
                for (d in 0 until dimensions) {
                    val delta = a[i - 1][d] - b[j - 1][d]
                    cost += delta * delta
                }
                cost = sqrt(cost / dimensions.coerceAtLeast(1))
                current[j] = cost + minOf(previous[j], current[j - 1], previous[j - 1])
            }
            for (j in previous.indices) previous[j] = current[j]
        }
        return previous[b.size] / (a.size + b.size).toFloat()
    }

    private fun melFilterBank(sampleRate: Int, fftSize: Int): Array<DoubleArray> {
        fun mel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
        fun hz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
        val low = mel(60.0)
        val high = mel(sampleRate / 2.0)
        val points = DoubleArray(FILTERS + 2) { i -> hz(low + (high - low) * i / (FILTERS + 1)) }
        val bins = points.map { floor((fftSize + 1) * it / sampleRate).toInt().coerceIn(0, fftSize / 2) }
        return Array(FILTERS) { f ->
            DoubleArray(fftSize / 2 + 1) { k ->
                when {
                    k < bins[f] || k > bins[f + 2] -> 0.0
                    k <= bins[f + 1] -> (k - bins[f]).toDouble() / (bins[f + 1] - bins[f]).coerceAtLeast(1)
                    else -> (bins[f + 2] - k).toDouble() / (bins[f + 2] - bins[f + 1]).coerceAtLeast(1)
                }
            }
        }
    }
}
