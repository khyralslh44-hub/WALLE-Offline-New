package com.nova.robot

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice

/**
 * Speaks only with an already-installed Arabic TTS voice marked as not requiring a network.
 * If unavailable, Nova keeps text and local electronic cues instead of using cloud speech.
 */
internal class NovaLocalTts(context: Context) : AutoCloseable {
    private var engine: TextToSpeech? = null
    @Volatile private var ready = false

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val tts = engine
                val localArabicVoice: Voice? = tts?.voices
                    ?.filter { it.locale.language.equals("ar", ignoreCase = true) && !it.isNetworkConnectionRequired }
                    ?.maxByOrNull { it.quality }
                if (tts != null && localArabicVoice != null) {
                    val voiceStatus = runCatching {
                        tts.voice = localArabicVoice
                        tts.setLanguage(localArabicVoice.locale)
                    }.getOrDefault(TextToSpeech.ERROR)
                    ready = voiceStatus != TextToSpeech.ERROR &&
                        voiceStatus != TextToSpeech.LANG_MISSING_DATA &&
                        voiceStatus != TextToSpeech.LANG_NOT_SUPPORTED
                    if (ready) {
                        tts.setSpeechRate(0.91f)
                        tts.setPitch(1.06f)
                    }
                } else ready = false
            } else ready = false
        }
    }

    fun isAvailable(): Boolean = ready

    fun speak(text: String): Boolean {
        val tts = engine ?: return false
        if (!ready || text.isBlank()) return false
        return runCatching {
            tts.speak(text.take(450), TextToSpeech.QUEUE_FLUSH, Bundle(), "nova-local-reply") == TextToSpeech.SUCCESS
        }.getOrDefault(false)
    }

    override fun close() {
        ready = false
        engine?.let {
            runCatching { it.stop() }
            runCatching { it.shutdown() }
        }
        engine = null
    }
}
