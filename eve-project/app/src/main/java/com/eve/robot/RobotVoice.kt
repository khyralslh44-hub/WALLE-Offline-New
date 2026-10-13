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
        // A soft, lower-register electronic voice colour; avoid sharp high-pitched bird chirps.
        val tones = when (mood) {
            EveMood.HAPPY -> intArrayOf(350, 425)
            EveMood.SURPRISED -> intArrayOf(295, 455)
            EveMood.SLEEPY -> intArrayOf(260, 235)
            EveMood.CONCERNED -> intArrayOf(285, 265)
            EveMood.FAMILIAR -> intArrayOf(330, 385)
            EveMood.FOCUSED -> intArrayOf(305, 350)
            EveMood.AWAKE -> intArrayOf(320, 400)
            else -> intArrayOf(300, 365)
        }
        val rate = 24000
        val segment = rate / 6
        val gap = rate / 30
        val pcm = ShortArray(tones.size * segment + (tones.size - 1) * gap)
        var offset = 0
        tones.forEachIndexed { index, base ->
            var phase = 0.0
            val detune = r.nextInt(-7, 8)
            for (i in 0 until segment) {
                val p = i.toFloat() / segment
                val hz = (base + detune + 34.0 * sin(p * PI)).toDouble()
                phase += 2.0 * PI * hz / rate
                val fadeIn = (i / (rate * 0.040f)).coerceIn(0f, 1f)
                val fadeOut = ((segment - i) / (rate * 0.075f)).coerceIn(0f, 1f)
                val harmonic = sin(phase) * 0.90 + sin(phase * 2.0) * 0.085 + sin(phase * 3.0) * 0.015
                val sample = harmonic * minOf(fadeIn, fadeOut) * 0.105
                pcm[offset++] = (sample * Short.MAX_VALUE).toInt().toShort()
            }
            if (index < tones.lastIndex) offset += gap
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
