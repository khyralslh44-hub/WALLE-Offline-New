package com.eve.robot

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
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
    private val reactionVariant = AtomicInteger(0)
    private val lastTouchCueAt = AtomicLong(0L)
    private val lastGameCueAt = AtomicLong(0L)
    @Volatile private var lastMood: EveMood? = null
    @Volatile private var closed = false

    fun speakMood(mood: EveMood, force: Boolean = false) {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        val previousMood = lastMood
        val elapsed = now - lastPlayed.get()
        // Keep sound cues coupled to meaningful brain decisions without making EVE silent
        // for long periods: short global cooldown, longer cooldown only for the same mood.
        if (!force && previousMood != null &&
            (elapsed < 4_500L || (previousMood == mood && elapsed < 16_000L))
        ) return
        lastPlayed.set(now)
        lastMood = mood
        try {
            executor.execute { play(mood) }
        } catch (_: Exception) {
            Log.w("EVE_VOICE", "Voice executor is not available.")
        }
    }

    /**
     * Short, nonverbal reply to a recognized utterance. It never speaks the transcript aloud.
     * A small set of deterministic vocal contours keeps the character from repeating one exact
     * tone each time while the cooldown prevents background speech from creating sound spam.
     */
    fun respondToSpeech(mood: EveMood, transcript: String) {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastPlayed.get()
        if (elapsed < SPEECH_RESPONSE_COOLDOWN_MS) {
            Log.d("EVE_VOICE", "SPEECH_RESPONSE_SUPPRESSED cooldownMs=$elapsed mood=$mood")
            return
        }
        lastPlayed.set(now)
        lastMood = mood
        val variant = reactionVariant.getAndIncrement() and 0x3
        try {
            executor.execute {
                play(mood, variant = variant, conversational = true)
            }
            Log.i(
                "EVE_VOICE",
                "SPEECH_RESPONSE_QUEUED mood=$mood variant=$variant transcriptLength=${transcript.length}"
            )
        } catch (_: Exception) {
            Log.w("EVE_VOICE", "Speech-response audio executor is not available.")
        }
    }

    /**
     * A deliberate touch gets a gentle acknowledgement, not only long-presses or moods
     * that happened to request sound. A separate cooldown keeps repeated taps quiet.
     */
    fun acknowledgeTouch(mood: EveMood, held: Boolean) {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        val previous = lastTouchCueAt.get()
        val cooldown = if (held) 950L else 800L
        if (previous != 0L && now - previous < cooldown) return
        if (!lastTouchCueAt.compareAndSet(previous, now)) return
        val variant = reactionVariant.getAndIncrement() and 0x3
        try {
            executor.execute { play(mood, variant = variant, conversational = true) }
            Log.i("EVE_VOICE", "TOUCH_CUE_QUEUED mood=$mood held=$held variant=$variant")
        } catch (error: Exception) {
            Log.w("EVE_VOICE", "Touch cue could not be queued.", error)
        }
    }

    /**
     * Short tactile audio cues for local games. These use generated PCM and need no network.
     */
    fun playGameCue(event: String) {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        val previous = lastGameCueAt.get()
        if (previous != 0L && now - previous < 100L) return
        if (!lastGameCueAt.compareAndSet(previous, now)) return
        val safeEvent = event.lowercase().filter { it.isLetterOrDigit() || it == '_' }
            .take(28).ifBlank { "game_move" }
        try {
            executor.execute { playGameSound(safeEvent) }
        } catch (error: Exception) {
            Log.w("EVE_VOICE", "Game sound could not be queued: $safeEvent", error)
        }
    }

    private fun playGameSound(event: String) {
        if (closed) return
        val rate = 24_000
        val capture = event.contains("capture") || event.contains("check") || event.contains("win")
        val durationMs = when {
            event.contains("win") -> 245
            event.contains("loss") -> 225
            event.contains("check") -> 210
            event.contains("capture") -> 195
            event.contains("invalid") -> 120
            event.contains("select") -> 90
            event.contains("start") -> 170
            else -> 135
        }
        val frequency = when {
            event.contains("loss") -> 250.0
            event.contains("invalid") -> 310.0
            event.contains("capture") -> 455.0
            event.contains("check") -> 790.0
            event.contains("win") -> 1_020.0
            event.contains("select") -> 920.0
            event.contains("start") -> 610.0
            else -> 660.0
        }
        val count = rate * durationMs / 1000
        val pcm = ShortArray(count)
        for (i in pcm.indices) {
            val t = i.toDouble() / rate
            val attack = (t / 0.003).coerceIn(0.0, 1.0)
            val decay = exp(-t * if (event.contains("win") || event.contains("loss") || event.contains("check") || event.contains("capture")) 10.0 else 18.0)
            val fundamental = sin(2.0 * PI * frequency * t)
            val overtone = sin(2.0 * PI * frequency * 2.13 * t) * 0.18
            val sample = (fundamental + overtone) * attack * decay * 0.54
            pcm[i] = (sample * Short.MAX_VALUE)
                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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
                track.setVolume(0.96f)
                track.play()
                Log.i("EVE_GAME_SOUND", "PLAYBACK_STARTED event=$event samples=$count state=${track.playState}")
                Thread.sleep(durationMs + 35L)
            } else {
                Log.e("EVE_GAME_SOUND", "PLAYBACK_NOT_STARTED event=$event written=$written expected=${pcm.size}")
            }
        } catch (error: Exception) {
            Log.e("EVE_GAME_SOUND", "PLAYBACK_FAILED event=$event", error)
        } finally {
            try { track?.release() } catch (_: Exception) { }
        }
    }

    private data class VoiceProfile(
        val pitch: Double,
        val pitchLift: Double,
        val pitchFall: Double,
        val formant1: Double,
        val formant2: Double,
        val durationMs: Int,
        val vibratoHz: Double = 3.2,
        val vibratoDepth: Double = 2.0,
        val pulseHz: Double = 1.2
    )

    private fun play(mood: EveMood, variant: Int = 0, conversational: Boolean = false) {
        if (closed) return
        // A low, stable, vowel-shaped electronic voice rather than fast, high-pitched bird chirps.
        val baseProfile = when (mood) {
            EveMood.HAPPY -> VoiceProfile(205.0, 18.0, -5.0, 690.0, 1180.0, 900, 4.0, 3.2, 1.8)
            EveMood.SURPRISED -> VoiceProfile(218.0, 26.0, -12.0, 720.0, 1280.0, 760, 5.2, 5.2, 2.6)
            EveMood.SLEEPY -> VoiceProfile(178.0, 7.0, -15.0, 580.0, 990.0, 1050, 1.8, 1.2, 0.6)
            EveMood.CONCERNED -> VoiceProfile(188.0, 5.0, -14.0, 610.0, 1030.0, 950, 2.6, 2.0, 0.9)
            EveMood.FAMILIAR -> VoiceProfile(198.0, 13.0, -3.0, 670.0, 1120.0, 920, 3.7, 2.6, 1.4)
            EveMood.FOCUSED -> VoiceProfile(190.0, 8.0, -4.0, 620.0, 1090.0, 820, 2.4, 1.0, 0.5)
            EveMood.AWAKE -> VoiceProfile(202.0, 15.0, -3.0, 680.0, 1160.0, 850, 4.5, 2.2, 1.0)
            EveMood.CURIOUS -> VoiceProfile(210.0, 17.0, -7.0, 700.0, 1210.0, 850, 5.0, 3.8, 2.1)
            EveMood.NEUTRAL -> VoiceProfile(192.0, 9.0, -4.0, 640.0, 1080.0, 820, 2.8, 1.0, 0.7)
            EveMood.PLAYFUL -> VoiceProfile(220.0, 28.0, -2.0, 720.0, 1240.0, 760, 6.0, 5.8, 3.0)
            EveMood.IRRITATED -> VoiceProfile(184.0, 3.0, -18.0, 600.0, 1010.0, 650, 3.2, 2.4, 1.1)
            EveMood.ANGRY -> VoiceProfile(174.0, 2.0, -24.0, 565.0, 940.0, 560, 2.0, 1.5, 0.45)
        }

        val profile = if (!conversational) {
            baseProfile
        } else {
            // Four restrained contours: warm acknowledgement, bright curiosity, gentle fall,
            // and playful lift. The sound remains wordless and is not a copied Cozmo recording.
            when (variant and 0x3) {
                0 -> baseProfile.copy(
                    pitchLift = baseProfile.pitchLift * 0.72,
                    pitchFall = baseProfile.pitchFall * 0.70,
                    formant1 = baseProfile.formant1 * 0.98,
                    durationMs = baseProfile.durationMs.coerceIn(460, 720),
                    vibratoDepth = baseProfile.vibratoDepth * 0.78,
                    pulseHz = maxOf(1.0, baseProfile.pulseHz * 0.90)
                )
                1 -> baseProfile.copy(
                    pitch = baseProfile.pitch * 1.045,
                    pitchLift = baseProfile.pitchLift * 0.88,
                    pitchFall = baseProfile.pitchFall * 0.55,
                    formant1 = baseProfile.formant1 * 1.025,
                    formant2 = baseProfile.formant2 * 1.045,
                    durationMs = 560,
                    vibratoHz = baseProfile.vibratoHz + 0.45,
                    vibratoDepth = baseProfile.vibratoDepth * 0.90,
                    pulseHz = 2.0
                )
                2 -> baseProfile.copy(
                    pitch = baseProfile.pitch * 0.955,
                    pitchLift = baseProfile.pitchLift * 0.60,
                    pitchFall = baseProfile.pitchFall * 1.22,
                    formant1 = baseProfile.formant1 * 0.96,
                    formant2 = baseProfile.formant2 * 0.94,
                    durationMs = 620,
                    vibratoHz = maxOf(1.5, baseProfile.vibratoHz - 0.40),
                    vibratoDepth = baseProfile.vibratoDepth * 0.68,
                    pulseHz = 1.05
                )
                else -> baseProfile.copy(
                    pitchLift = baseProfile.pitchLift * 1.12,
                    pitchFall = baseProfile.pitchFall * 0.68,
                    formant1 = baseProfile.formant1 * 1.01,
                    formant2 = baseProfile.formant2 * 1.06,
                    durationMs = 680,
                    vibratoHz = baseProfile.vibratoHz + 0.65,
                    vibratoDepth = baseProfile.vibratoDepth * 1.08,
                    pulseHz = 2.35
                )
            }
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
                profile.pitchFall * p +
                profile.vibratoDepth * sin(2.0 * PI * profile.vibratoHz * p)
            phase += 2.0 * PI * pitch / rate

            val attack = (p / 0.12).coerceIn(0.0, 1.0)
            val release = ((1.0 - p) / 0.24).coerceIn(0.0, 1.0)
            // Gentle amplitude pulses make each mood cue feel like a small robot vocalization
            // rather than the same flat beep, while keeping the signal soft.
            val pulse = 0.76 + 0.24 * (0.5 + 0.5 * sin(2.0 * PI * profile.pulseHz * p))
            val envelope = attack * release * pulse
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
            pcm[i] = (normalized * envelope * shimmer * 0.38 * Short.MAX_VALUE)
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
                track.setVolume(0.76f)
                track.play()
                Log.i("EVE_VOICE", "PLAYBACK_STARTED mood=$mood variant=$variant conversational=$conversational samples=$sampleCount state=${track.playState}")
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

    companion object {
        private const val SPEECH_RESPONSE_COOLDOWN_MS = 2_200L
    }

    fun close() {
        closed = true
        executor.shutdownNow()
    }
}
