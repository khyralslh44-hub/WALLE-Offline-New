package com.nova.robot

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Foreground-only voice input. Uses Android's ON-DEVICE recognizer exclusively and never falls
 * back to the general/cloud recognizer. Needs Android 12+ and an installed offline Arabic pack.
 */
internal class NovaOfflineSpeechInput(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onStatus: (String) -> Unit
) : AutoCloseable {
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var closed = false

    fun start(): Boolean {
        if (closed) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            onStatus("الاستماع المحلي يحتاج Android 12 أو أحدث؛ لن أستخدم التعرف عبر الإنترنت.")
            return false
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            onStatus("لا تتوفر خدمة تعرّف محلية على هذا الجهاز. ثبّت حزمة عربية دون اتصال ثم حاول مجددًا.")
            return false
        }
        return try {
            val current = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { created ->
                created.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        listening = true
                        onStatus("أستمع إليك محليًا…")
                    }
                    override fun onBeginningOfSpeech() {
                        onStatus("وصلني صوتك؛ أتابع الاستماع محليًا…")
                    }
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() { onStatus("أرتّب ما سمعته…") }
                    override fun onError(error: Int) {
                        listening = false
                        if (!closed) onStatus("تعذّر فهم الكلام محليًا (رمز " + error + "). يمكنك الكتابة بدلًا من ذلك.")
                    }
                    override fun onResults(results: Bundle?) {
                        listening = false
                        val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        if (phrase.isNotBlank()) onResult(phrase)
                        else onStatus("لم ألتقط كلمات واضحة؛ جرّب مرة أخرى أو اكتب لي.")
                    }
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                recognizer = created
            }
            val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("ar").toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            current.startListening(request)
            listening = true
            onStatus("أستمع إليك محليًا…")
            true
        } catch (error: Exception) {
            listening = false
            onStatus("لم أستطع تشغيل الاستماع المحلي؛ لن أرسل الصوت إلى الإنترنت.")
            false
        }
    }

    fun cancel() {
        listening = false
        runCatching { recognizer?.cancel() }
    }

    override fun close() {
        closed = true
        cancel()
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
