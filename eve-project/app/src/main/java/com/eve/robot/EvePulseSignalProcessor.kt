package com.eve.robot

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Estimates pulse rate from a time series extracted locally from fingertip camera frames.
 * It is an experimental signal-quality-gated estimate, not a medical device or diagnosis.
 */
data class EvePulseEstimate(
    val bpm: Int?,
    val periodicity: Double,
    val sampleCount: Int,
    val durationMs: Long,
    val samplingHz: Double,
    val reason: String
)

class EvePulseSignalProcessor(
    private val windowMs: Long = 16_000L,
    private val minimumDurationMs: Long = 8_000L
) {
    private data class Sample(val timeMs: Long, val value: Double)

    private val samples = ArrayDeque<Sample>()
    private var lastTimestampMs = Long.MIN_VALUE

    @Synchronized
    fun addSample(timestampMs: Long, normalizedRedSignal: Double) {
        if (!normalizedRedSignal.isFinite()) return
        if (timestampMs <= lastTimestampMs) return
        lastTimestampMs = timestampMs
        samples.addLast(Sample(timestampMs, normalizedRedSignal))
        val cutoff = timestampMs - windowMs
        while (samples.isNotEmpty() && samples.first().timeMs < cutoff) {
            samples.removeFirst()
        }
    }

    @Synchronized
    fun reset() {
        samples.clear()
        lastTimestampMs = Long.MIN_VALUE
    }

    @Synchronized
    fun sampleCount(): Int = samples.size

    @Synchronized
    fun estimate(nowMs: Long): EvePulseEstimate {
        val recent = samples.filter { nowMs >= it.timeMs && nowMs - it.timeMs <= windowMs }
        if (recent.size < 120) {
            return weak(null, 0.0, recent.size, 0L, 0.0, "تُجمع عينات كافية بعد")
        }

        val durationMs = recent.last().timeMs - recent.first().timeMs
        if (durationMs < minimumDurationMs) {
            return weak(null, 0.0, recent.size, durationMs, 0.0, "مدة القياس غير كافية")
        }

        val durationSeconds = durationMs / 1000.0
        val inputRate = (recent.size - 1) / durationSeconds
        if (inputRate < 12.0) {
            return weak(null, 0.0, recent.size, durationMs, inputRate, "معدل إطارات الكاميرا منخفض")
        }

        var maximumGapMs = 0L
        for (i in 1 until recent.size) {
            maximumGapMs = max(maximumGapMs, recent[i].timeMs - recent[i - 1].timeMs)
        }
        if (maximumGapMs > 350L) {
            return weak(null, 0.0, recent.size, durationMs, inputRate, "تقطّع في إشارة الكاميرا")
        }

        // Resample timestamps to a uniform 25 Hz grid before comparing periodic patterns.
        val fs = 25.0
        val count = floor(durationSeconds * fs).toInt() + 1
        if (count < 180) {
            return weak(null, 0.0, recent.size, durationMs, inputRate, "مدة الإشارة المنتظمة غير كافية")
        }

        val values = DoubleArray(count)
        var rightIndex = 0
        for (i in 0 until count) {
            val targetMs = recent.first().timeMs + (i * 1000.0 / fs)
            while (rightIndex < recent.lastIndex - 1 &&
                recent[rightIndex + 1].timeMs < targetMs
            ) {
                rightIndex++
            }
            val left = recent[rightIndex]
            val right = recent[min(rightIndex + 1, recent.lastIndex)]
            val span = (right.timeMs - left.timeMs).coerceAtLeast(1L).toDouble()
            val fraction = ((targetMs - left.timeMs) / span).coerceIn(0.0, 1.0)
            values[i] = left.value + (right.value - left.value) * fraction
        }

        // Remove slow illumination/exposure drift using a two-second moving baseline.
        val prefix = DoubleArray(count + 1)
        for (i in values.indices) prefix[i + 1] = prefix[i] + values[i]
        val radius = (fs * 1.0).toInt() // one second each side, two seconds total
        val detrended = DoubleArray(count)
        for (i in values.indices) {
            val left = max(0, i - radius)
            val right = min(count - 1, i + radius)
            val baseline = (prefix[right + 1] - prefix[left]) / (right - left + 1)
            detrended[i] = values[i] - baseline
        }

        // A small symmetric smoother suppresses frame noise without counting individual peaks.
        val filtered = DoubleArray(count)
        for (i in values.indices) {
            val before = detrended[max(0, i - 1)]
            val after = detrended[min(count - 1, i + 1)]
            filtered[i] = 0.25 * before + 0.5 * detrended[i] + 0.25 * after
        }
        val mean = filtered.average()
        var energy = 0.0
        for (i in filtered.indices) {
            filtered[i] -= mean
            energy += filtered[i] * filtered[i]
        }
        val rms = sqrt(energy / count)
        val originalMagnitude = abs(values.average()).coerceAtLeast(1e-9)
        if (rms < originalMagnitude * 0.00005) {
            return weak(null, 0.0, recent.size, durationMs, inputRate, "تغيّر لون الإصبع ضعيف جدًا")
        }

        // Search the plausible resting/ordinary pulse band using normalized autocorrelation.
        // A displayed number is withheld unless the signal has a reasonably clear repeating pattern.
        val minLag = ceil(fs * 60.0 / 180.0).toInt()
        val maxLag = floor(fs * 60.0 / 45.0).toInt()
        val correlations = DoubleArray(maxLag + 2) { Double.NaN }
        for (lag in minLag..maxLag) {
            var cross = 0.0
            var energyA = 0.0
            var energyB = 0.0
            for (i in 0 until count - lag) {
                val a = filtered[i]
                val b = filtered[i + lag]
                cross += a * b
                energyA += a * a
                energyB += b * b
            }
            val denominator = sqrt(energyA * energyB)
            correlations[lag] = if (denominator > 0.0) cross / denominator else 0.0
        }

        var bestLag = -1
        var bestScore = -1.0
        for (lag in minLag..maxLag) {
            val score = correlations[lag]
            val isLocalPeak = score >= correlations[lag - 1] &&
                score >= correlations[lag + 1]
            if (isLocalPeak && score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0 || bestScore < 0.30) {
            return weak(null, bestScore.coerceAtLeast(0.0), recent.size, durationMs, inputRate,
                "الإشارة غير منتظمة بما يكفي لعرض رقم")
        }

        val bpm = (60.0 * fs / bestLag).toInt()
        if (bpm !in 45..180) {
            return weak(null, bestScore, recent.size, durationMs, inputRate, "النتيجة خارج نطاق الخوارزمية التجريبية")
        }
        return EvePulseEstimate(
            bpm = bpm,
            periodicity = bestScore.coerceIn(0.0, 1.0),
            sampleCount = recent.size,
            durationMs = durationMs,
            samplingHz = inputRate,
            reason = "إشارة دورية قابلة للتقدير"
        )
    }

    private fun weak(
        bpm: Int?,
        score: Double,
        count: Int,
        duration: Long,
        rate: Double,
        reason: String
    ) = EvePulseEstimate(bpm, score.coerceIn(0.0, 1.0), count, duration, rate, reason)
}
