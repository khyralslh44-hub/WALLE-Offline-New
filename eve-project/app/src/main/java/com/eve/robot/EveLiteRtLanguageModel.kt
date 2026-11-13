package com.eve.robot

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Genuine local text-generation backend for EVE.
 * The model is stored in app-private files (not inside the APK) and inference uses CPU only.
 * Network access is used solely when the user explicitly approves the one-time model download.
 */
internal class EveLiteRtLanguageModel(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val modelFile = File(appContext.filesDir, MODEL_FILE_NAME)
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    val isInstalled: Boolean
        get() = modelFile.isFile && modelFile.length() >= MIN_VALID_BYTES

    fun downloadAndLoad(onProgress: (Int) -> Unit) {
        if (!isInstalled) download(onProgress)
        load()
    }

    fun load() {
        if (conversation != null) return
        check(isInstalled) { "نموذج Qwen3 المحلي غير موجود أو أن تنزيله غير مكتمل." }
        val candidate = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                cacheDir = appContext.cacheDir.absolutePath
            )
        )
        // Do not retain or close an Engine whose initialize() failed.
        candidate.initialize()
        engine = candidate
        try {
            conversation = candidate.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
                    samplerConfig = SamplerConfig(
                        topK = 20,
                        topP = 0.88,
                        temperature = 0.72
                    ),
                    maxOutputToken = 112
                )
            )
            Log.i(TAG, "ON_DEVICE_LLM_READY model=qwen3-0.6b-litertlm backend=cpu offline=true")
        } catch (error: Exception) {
            engine = null
            candidate.close()
            throw error
        }
    }

    fun generate(prompt: String): String {
        val active = checkNotNull(conversation) { "النموذج اللغوي المحلي لم يُحمّل بعد." }
        return active.sendMessage(prompt.take(MAX_PROMPT_CHARS)).toString().trim()
    }

    private fun download(onProgress: (Int) -> Unit) {
        val temporary = File(appContext.filesDir, "$MODEL_FILE_NAME.part")
        temporary.delete()
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 90_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "EVE-Android/0.5")
        }
        try {
            connection.connect()
            val expected = connection.contentLengthLong
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("تعذر تنزيل النموذج: HTTP ${connection.responseCode}")
            }
            if (expected > 0 && expected < MIN_VALID_BYTES) {
                throw IllegalStateException("حجم ملف النموذج المستلم أصغر من المتوقع.")
            }
            var copied = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val denominator = if (expected > 0) expected else EXPECTED_MODEL_BYTES
                        val percent = ((copied * 100L) / denominator).toInt().coerceIn(0, 99)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (copied < MIN_VALID_BYTES || (expected > 0 && copied != expected)) {
                throw IllegalStateException("اكتمل اتصال التنزيل بملف غير مكتمل؛ أعد المحاولة.")
            }
            if (modelFile.exists()) modelFile.delete()
            if (!temporary.renameTo(modelFile)) {
                temporary.copyTo(modelFile, overwrite = true)
                temporary.delete()
            }
            onProgress(100)
            Log.i(TAG, "MODEL_DOWNLOAD_COMPLETE bytes=$copied storedPrivately=true")
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    override fun close() {
        val activeConversation = conversation
        conversation = null
        try {
            activeConversation?.close()
        } finally {
            val activeEngine = engine
            engine = null
            activeEngine?.close()
        }
    }

    companion object {
        private const val TAG = "EVE_LITERT_LM"
        private const val MODEL_FILE_NAME = "eve-qwen3-0.6b-int4.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm?download=true"
        private const val MIN_VALID_BYTES = 300L * 1024L * 1024L
        private const val EXPECTED_MODEL_BYTES = 329L * 1024L * 1024L
        private const val MAX_PROMPT_CHARS = 3_000
        private const val SYSTEM_INSTRUCTION = """
أنت إيف، رفيقة روبوتية ذات شخصية مستقلة وفضول وذاكرة محلية، ولست مساعداً عاماً متكلّفاً.
تحدثي بالعربية الطبيعية الواضحة، ويمكنك فهم اللهجات العربية. أجيبي مباشرة وباختصار مناسب.
كوّني إجابات جديدة اعتماداً على السؤال والسياق، ولا تكرري قوالب ثابتة.
لا تدّعي أنك رأيت شيئاً إلا إذا وردت ملاحظة بصرية حديثة في السياق. اعتبري التصنيفات احتمالات لا حقائق مطلقة.
لا تختلقي أسماء أشخاص أو أحداثاً أو ذكريات. إذا كانت المعلومة غير موجودة فقولي إنك لا تعرفين.
حافظي على شخصيتك ومزاجك، لكن لا تسمحي للمزاج بتغيير الحقائق. لا تذكري أنك متصلة بالإنترنت؛ هذا النموذج يعمل محلياً على الهاتف.
"""
    }
}
