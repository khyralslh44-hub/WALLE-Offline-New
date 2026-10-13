package com.eve.robot

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Original synthesized electronic chirps; not an imitation or recording of a movie voice. */
class RobotVoice {
    private val executor = Executors.newSingleThreadExecutor()
    private val lastPlayed = AtomicLong(0L)
    @Volatile private var closed = false

    fun speakMood(mood: EveMood, force: Boolean = false) {
        if (closed) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPlayed.get() < 1400L) return
        lastPlayed.set(now)
        try { executor.execute { play(mood) } } catch (_: Exception) { }
    }

    private fun play(mood: EveMood) {
        if (closed) return
        val r = Random(System.nanoTime())
        val tones = when (mood) {
            EveMood.HAPPY -> intArrayOf(760, 960, 1120)
            EveMood.SURPRISED -> intArrayOf(480, 1080, 840)
            EveMood.SLEEPY -> intArrayOf(550, 420)
            EveMood.CONCERNED -> intArrayOf(700, 610)
            EveMood.FAMILIAR -> intArrayOf(600, 760, 820)
            EveMood.FOCUSED -> intArrayOf(790)
            EveMood.AWAKE -> intArrayOf(500, 740, 930)
            else -> intArrayOf(620, 790)
        }
        val rate = 22050
        val segment = rate / 8
        val gap = rate / 40
        val pcm = ShortArray(tones.size * segment + (tones.size - 1) * gap)
        var offset = 0
        tones.forEachIndexed { index, base ->
            var phase = 0.0
            val detune = r.nextInt(-28, 29)
            for (i in 0 until segment) {
                val p = i.toFloat() / segment
                val hz = (base + detune + 95.0 * sin(p * PI)).toDouble()
                phase += 2.0 * PI * hz / rate
                val fadeIn = (i / (rate * 0.012f)).coerceIn(0f, 1f)
                val fadeOut = ((segment - i) / (rate * 0.035f)).coerceIn(0f, 1f)
                val harmonic = sin(phase) * 0.72 + sin(phase * 2.0) * 0.20 + sin(phase * 3.0) * 0.08
                val sample = harmonic * minOf(fadeIn, fadeOut) * 0.24
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
