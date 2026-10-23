package com.nova.robot

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.sin

/**
 * Nova's original offline sound identity.
 *
 * Synthesizes short, soft, layered electronic motifs locally instead of using generic beeps,
 * recorded speech, microphone input, or network services. These are expressive vocal-like cues,
 * not spoken language or a human voice.
 */
internal class NovaVoice(
    private val context: Context,
    private val mainHandler: Handler
) : AutoCloseable {
    private var activeTrack: AudioTrack? = null
    private var releaseRunnable: Runnable? = null
    private var cueIndex = 0
    private var lastCueAtElapsedMs = 0L
    private var lastCueMood: NovaMood? = null

    @Synchronized
    fun play(mood: NovaMood, isQuestion: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        // Ignore rapid duplicate triggers and dampen repeated same-mood cues without random behavior.
        if (now - lastCueAtElapsedMs < MIN_CUE_GAP_MS ||
            (lastCueMood == mood && now - lastCueAtElapsedMs < SAME_MOOD_COOLDOWN_MS)
        ) return
        lastCueAtElapsedMs = now
        lastCueMood = mood
        val motif = motifFor(mood, isQuestion, cueIndex++)
        val samples = synthesize(motif)
        stopCurrent()

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrNull() ?: return

        val written = runCatching { track.write(samples, 0, samples.size) }.getOrDefault(-1)
        if (written != samples.size) {
            runCatching { track.release() }
            return
        }
        activeTrack = track
        runCatching {
            track.setVolume(0.62f)
            track.play()
        }.onFailure {
            stopCurrent()
            return
        }

        val cleanup = Runnable {
            synchronized(this) {
                if (activeTrack === track) {
                    runCatching { track.stop() }
                    runCatching { track.release() }
                    activeTrack = null
                    releaseRunnable = null
                }
            }
        }
        releaseRunnable = cleanup
        mainHandler.postDelayed(cleanup, samples.size * 1000L / SAMPLE_RATE + 80L)
    }

    @Synchronized
    override fun close() {
        stopCurrent()
    }

    @Synchronized
    private fun stopCurrent() {
        releaseRunnable?.let(mainHandler::removeCallbacks)
        releaseRunnable = null
        activeTrack?.let { track ->
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        activeTrack = null
    }

    private fun motifFor(mood: NovaMood, question: Boolean, index: Int): DoubleArray {
        val base = when (mood) {
            NovaMood.CALM -> doubleArrayOf(330.0, 392.0)
            NovaMood.CURIOUS -> doubleArrayOf(349.0, 415.0, 392.0)
            NovaMood.HAPPY, NovaMood.WARM -> doubleArrayOf(392.0, 466.0, 440.0)
            NovaMood.SURPRISED, NovaMood.ALERT -> doubleArrayOf(294.0, 494.0)
            NovaMood.THINKING -> doubleArrayOf(330.0, 311.0, 370.0)
            NovaMood.SLEEPY, NovaMood.TIRED -> doubleArrayOf(262.0, 220.0)
            NovaMood.PLAYFUL -> doubleArrayOf(415.0, 349.0, 466.0)
            NovaMood.FRUSTRATED -> doubleArrayOf(294.0, 277.0, 311.0)
            NovaMood.CAUTIOUS -> doubleArrayOf(311.0, 294.0)
            NovaMood.SAD -> doubleArrayOf(294.0, 262.0, 247.0)
            NovaMood.DISGUSTED -> doubleArrayOf(311.0, 330.0)
            NovaMood.AFRAID -> doubleArrayOf(262.0, 392.0, 262.0)
            NovaMood.LOVE, NovaMood.ADMIRING -> doubleArrayOf(440.0, 523.0, 659.0)
            NovaMood.EMBARRASSED -> doubleArrayOf(392.0, 349.0, 330.0)
            NovaMood.JEALOUS -> doubleArrayOf(294.0, 311.0, 277.0)
            NovaMood.CONFUSED -> doubleArrayOf(330.0, 294.0, 370.0)
        }
        // Small, bounded pitch contour variations make the cue feel less mechanical without randomness.
        val shift = when (index % 4) {
            1 -> 1.035
            2 -> 0.97
            3 -> 1.015
            else -> 1.0
        }
        val result = base.map { it * shift }.toDoubleArray()
        if (question && result.size >= 2) result[result.lastIndex] *= 1.08
        return result
    }

    private fun synthesize(frequencies: DoubleArray): ShortArray {
        val noteSamples = (SAMPLE_RATE * NOTE_SECONDS).toInt()
        val gapSamples = (SAMPLE_RATE * GAP_SECONDS).toInt()
        val output = ShortArray(frequencies.size * noteSamples + (frequencies.size - 1) * gapSamples)
        var cursor = 0
        frequencies.forEachIndexed { noteIndex, baseFrequency ->
            for (i in 0 until noteSamples) {
                val t = i.toDouble() / SAMPLE_RATE
                val progress = i.toDouble() / noteSamples
                val attack = (progress / 0.16).coerceIn(0.0, 1.0)
                val release = ((1.0 - progress) / 0.24).coerceIn(0.0, 1.0)
                val envelope = attack * release
                val glide = 0.985 + 0.03 * progress
                val fundamental = sin(2.0 * PI * baseFrequency * glide * t)
                val harmonic = sin(2.0 * PI * baseFrequency * 2.003 * glide * t) * 0.20
                val softOvertone = sin(2.0 * PI * baseFrequency * 3.01 * glide * t) * 0.055
                val subtlePulse = 0.92 + 0.08 * sin(2.0 * PI * 4.0 * t)
                val sample = (fundamental + harmonic + softOvertone) * envelope * subtlePulse * 0.23
                output[cursor++] = (sample * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            if (noteIndex != frequencies.lastIndex) {
                repeat(gapSamples) { output[cursor++] = 0 }
            }
        }
        return output
    }

    companion object {
        private const val SAMPLE_RATE = 22_050
        private const val MIN_CUE_GAP_MS = 280L
        private const val SAME_MOOD_COOLDOWN_MS = 1_050L
        private const val NOTE_SECONDS = 0.092
        private const val GAP_SECONDS = 0.018
    }
}
