package com.eve.robot

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Original synthesized electronic chirps; not an imitation or recording of a movie voice. */
class RobotVoice {
    private val executor = Executors.newSingleThreadExecutor()
    private val lastPlayed = AtomicLong(0L)
    @Volatile private var lastMood: EveMood? = null
    @Volatile private var closed = false

    fun speakMood(mood: EveMood, force: Boolean = false) {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        val previousMood = lastMood
        val elapsed = now - lastPlayed.get()
        // Quietly ignore rapid repeats, especially the same mood caused by a jittery classifier.
        if (!force && (elapsed < 14_000L || (previousMood == mood && elapsed < 45_000L))) return
        lastPlayed.set(now)
        lastMood = mood
        try { executor.execute { play(mood) } } catch (_: Exception) { }
    }

    private fun play(mood: EveMood) {
        if (closed) return
        val r = Random(System.nanoTime())
        // One continuous, softly gliding electronic tone per cue: no rapid multi-note chirps.
        val (baseHz, glideHz, endingSlope) = when (mood) {
            EveMood.HAPPY -> Triple(350.0, 72.0, 8.0)
            EveMood.SURPRISED -> Triple(325.0, 105.0, -12.0)
            EveMood.SLEEPY -> Triple(295.0, -24.0, -6.0)
            EveMood.CONCERNED -> Triple(305.0, -28.0, -8.0)
            EveMood.FAMILIAR -> Triple(330.0, 38.0, 5.0)
            EveMood.FOCUSED -> Triple(310.0, 18.0, -4.0)
            EveMood.AWAKE -> Triple(330.0, 55.0, 4.0)
            else -> Triple(315.0, 28.0, 0.0)
        }
        val rate = 24000
        val sampleCount = rate * 9 / 20 // 450 ms; a smooth voice-like swell, not a bird-like sequence.
        val pcm = ShortArray(sampleCount)
        var phase = 0.0
        val individualOffset = r.nextInt(-5, 6)
        for (i in 0 until sampleCount) {
            val p = i.toDouble() / (sampleCount - 1)
            val hz = baseHz + individualOffset + glideHz * sin(p * PI) + endingSlope * p
            phase += 2.0 * PI * hz / rate
            val fadeIn = (i / (rate * 0.075f)).coerceIn(0f, 1f)
            val fadeOut = ((sampleCount - i) / (rate * 0.11f)).coerceIn(0f, 1f)
            val envelope = minOf(fadeIn, fadeOut)
            val fundamental = sin(phase)
            val harmonic = fundamental * 0.89 +
                sin(phase * 2.0 + 0.18) * 0.085 +
                sin(phase * 3.0) * 0.025
            val subtleVibrato = 1.0 + 0.006 * sin(p * PI * 10.0)
            pcm[i] = (harmonic * envelope * subtleVibrato * 0.085 * Short.MAX_VALUE).toInt().toShort()
        }
        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            if (written == pcm.size && !closed) {
                track.setVolume(0.28f)
                track.play()
                Log.i("EVE_VOICE", "PLAYBACK_STARTED mood=$mood written=$written samples=${pcm.size} state=${track.playState}")
                Thread.sleep(pcm.size * 1000L / rate + 80L)
                Log.i("EVE_VOICE", "PLAYBACK_FINISHED mood=$mood")
            } else {
                Log.e("EVE_VOICE", "PLAYBACK_NOT_STARTED mood=$mood written=$written expected=${pcm.size} closed=$closed state=${track.state}")
            }
        } catch (e: Exception) {
            Log.e("EVE_VOICE", "PLAYBACK_FAILED mood=$mood", e)
        } finally {
            try { track?.release() } catch (_: Exception) { }
        }
    }

    fun close() {
        closed = true
        executor.shutdownNow()
    }
}
