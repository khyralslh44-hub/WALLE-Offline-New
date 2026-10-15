package com.eve.robot

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.exp
import kotlin.math.sin

/**
 * A locally synthesized, soft electronic character cue.
 * This is an original EVE-inspired timbre, not copied movie audio or an actor voice clone.
 * AudioTrack uses the media stream, so the phone's volume buttons control its loudness.
 */
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
        // Character cues are rare by design: no sound loop and no repeated mood chatter.
        if (!force && previousMood != null &&
            (elapsed < 35_000L || (previousMood == mood && elapsed < 120_000L))
        ) return
        lastPlayed.set(now)
        lastMood = mood
        try {
            executor.execute { play(mood) }
        } catch (_: Exception) {
            Log.w("EVE_VOICE", "Voice executor is not available.")
        }
    }

    private data class VoiceProfile(
        val pitch: Double,
        val pitchLift: Double,
        val pitchFall: Double,
        val formant1: Double,
        val formant2: Double,
        val durationMs: Int
    )

    private fun play(mood: EveMood) {
        if (closed) return
        // A low, stable, vowel-shaped electronic voice rather than fast, high-pitched bird chirps.
        val profile = when (mood) {
            EveMood.HAPPY -> VoiceProfile(205.0, 18.0, -5.0, 690.0, 1180.0, 900)
            EveMood.SURPRISED -> VoiceProfile(218.0, 26.0, -12.0, 720.0, 1280.0, 760)
            EveMood.SLEEPY -> VoiceProfile(178.0, 7.0, -15.0, 580.0, 990.0, 1050)
            EveMood.CONCERNED -> VoiceProfile(188.0, 5.0, -14.0, 610.0, 1030.0, 950)
            EveMood.FAMILIAR -> VoiceProfile(198.0, 13.0, -3.0, 670.0, 1120.0, 920)
            EveMood.FOCUSED -> VoiceProfile(190.0, 8.0, -4.0, 620.0, 1090.0, 820)
            EveMood.AWAKE -> VoiceProfile(202.0, 15.0, -3.0, 680.0, 1160.0, 850)
            EveMood.CURIOUS -> VoiceProfile(210.0, 17.0, -7.0, 700.0, 1210.0, 850)
            EveMood.NEUTRAL -> VoiceProfile(192.0, 9.0, -4.0, 640.0, 1080.0, 820)
            EveMood.PLAYFUL -> VoiceProfile(220.0, 28.0, -2.0, 720.0, 1240.0, 760)
            EveMood.IRRITATED -> VoiceProfile(184.0, 3.0, -18.0, 600.0, 1010.0, 650)
            EveMood.ANGRY -> VoiceProfile(174.0, 2.0, -24.0, 565.0, 940.0, 560)
        }

        val rate = 24_000
        val sampleCount = rate * profile.durationMs / 1000
        val pcm = ShortArray(sampleCount)
        var phase = 0.0
        val formant1 = profile.formant1
        val formant2 = profile.formant2

        for (i in 0 until sampleCount) {
            val p = i.toDouble() / (sampleCount - 1)
            // Slow, smooth pitch contour; no random note hopping or sharp whistles.
            val pitch = profile.pitch +
                profile.pitchLift * sin(PI * p) +
                profile.pitchFall * p
            phase += 2.0 * PI * pitch / rate

            val attack = (p / 0.12).coerceIn(0.0, 1.0)
            val release = ((1.0 - p) / 0.24).coerceIn(0.0, 1.0)
            val envelope = attack * release
            var sample = 0.0
            var weightSum = 0.0

            // Harmonics weighted around two formant regions create a soft, vowel-like timbre.
            // The fundamental stays dominant; upper harmonics are intentionally restrained.
            for (harmonic in 1..9) {
                val frequency = pitch * harmonic
                val base = 1.0 / (harmonic.toDouble().powSoft(1.25))
                val f1 = 0.38 * exp(-square((frequency - formant1) / 190.0))
                val f2 = 0.19 * exp(-square((frequency - formant2) / 250.0))
                val formantWeight = base + f1 + f2
                sample += sin(phase * harmonic) * formantWeight
                weightSum += formantWeight
            }

            val normalized = if (weightSum > 0.0) sample / weightSum else 0.0
            // A tiny slow shimmer avoids a sterile beep without creating a bird-like warble.
            val shimmer = 1.0 + 0.0015 * sin(2.0 * PI * 2.2 * p)
            pcm[i] = (normalized * envelope * shimmer * 0.13 * Short.MAX_VALUE)
                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            if (written == pcm.size && !closed) {
                // Gentle source gain; media volume buttons remain the master volume control.
                track.setVolume(0.22f)
                track.play()
                Log.i("EVE_VOICE", "PLAYBACK_STARTED mood=$mood samples=$sampleCount state=${track.playState}")
                Thread.sleep(profile.durationMs + 100L)
                Log.i("EVE_VOICE", "PLAYBACK_FINISHED mood=$mood")
            } else {
                Log.e("EVE_VOICE", "PLAYBACK_NOT_STARTED mood=$mood written=$written expected=${pcm.size}")
            }
        } catch (e: Exception) {
            Log.e("EVE_VOICE", "PLAYBACK_FAILED mood=$mood", e)
        } finally {
            try { track?.release() } catch (_: Exception) { }
        }
    }

    private fun square(value: Double) = value * value

    private fun Double.powSoft(exponent: Double): Double {
        // integer-loop version avoids bringing extra math libraries into this tiny offline synthesizer
        return this.pow(exponent)
    }

    fun close() {
        closed = true
        executor.shutdownNow()
    }
}
