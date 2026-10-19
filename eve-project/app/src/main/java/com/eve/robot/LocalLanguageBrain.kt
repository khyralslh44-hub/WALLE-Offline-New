package com.eve.robot

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class LocalLlmState { WAITING_FOR_WIFI, DOWNLOADING, LOADING, READY, ERROR }

data class LocalLlmContext(
    val latestVisionLabel: String? = null,
    val visionConfidence: Float = 0f,
    /** Age of the latest visual label; stale labels remain in memory but are not current evidence. */
    val visionObservationAgeMs: Long = Long.MAX_VALUE,
    val sightingCount: Int = 0,
    val learnedAffinity: Float = 0f,
    val currentMood: String = "NEUTRAL",
    val moodReason: String = "",
    val personality: EvePersonality = EvePersonality(),
    val recentObservations: List<String> = emptyList(),
    val recentDialogues: List<String> = emptyList(),
    val recentReflections: List<String> = emptyList()
)

data class LocalLlmResult(
    val replyArabic: String,
    val moodHint: String?,
    val intentHint: String?,
    val reflectionArabic: String,
    val confidence: Float
)

/**
 * A real generative language model running on the phone through LiteRT-LM.
 *
 * The 647 MB model is fetched only once, on an unmetered internet connection, then retained
 * in app-private storage. Inference is local; prompts, camera labels, and replies are not
 * sent to a remote service. Vision evidence is supplied as on-device classifier metadata,
 * not as raw camera images.
 */
class LocalLanguageBrain(
    context: Context,
    private val statusListener: (LocalLlmState, Int, String) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "eve-local-llm").apply { isDaemon = true }
    }
    private val initializing = AtomicBoolean(false)
    private val generating = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val prefs = appContext.getSharedPreferences("eve_local_llm", Context.MODE_PRIVATE)
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val modelDirectory = File(appContext.filesDir, "local-language-brain")
    private val modelFile = File(modelDirectory, MODEL_FILE_NAME)
    private val partialFile = File(modelDirectory, "$MODEL_FILE_NAME.part")

    @Volatile private var state: LocalLlmState = LocalLlmState.WAITING_FOR_WIFI
    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var lastStatusMessage = ""
    @Volatile private var lastProgress = -1
    @Volatile private var lastRetryAt = 0L
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    val isReady: Boolean
        get() = state == LocalLlmState.READY && conversation != null

    val currentState: LocalLlmState
        get() = state

    fun refreshStatus() {
        if (closed.get()) return
        val currentState = state
        val currentProgress = lastProgress.coerceAtLeast(0)
        val currentMessage = lastStatusMessage.ifBlank { "الدماغ اللغوي المحلي لم يبدأ بعد." }
        main.post {
            if (!closed.get()) statusListener(currentState, currentProgress, currentMessage)
        }
    }

    fun start() {
        if (closed.get()) return
        registerNetworkCallback()
        ensureReady()
    }

    /** Safe to call on startup/resume/network changes; simultaneous calls collapse into one. */
    fun ensureReady() {
        if (closed.get() || isReady) return
        if (System.currentTimeMillis() < lastRetryAt) return
        if (!initializing.compareAndSet(false, true)) return
        try {
            worker.execute {
                try {
                    if (closed.get()) return@execute
                    val file = ensureModelFile()
                    publish(LocalLlmState.LOADING, 0, "تحميل النموذج اللغوي محليًا…")
                    val memory = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val memoryInfo = ActivityManager.MemoryInfo()
                    memory.getMemoryInfo(memoryInfo)
                    // Keep the camera and Android responsive on lower-RAM phones.
                    if (memoryInfo.availMem < MIN_AVAILABLE_RAM_BYTES) {
                        throw IllegalStateException(
                            "الذاكرة المتاحة قليلة لتشغيل النموذج الآن؛ أغلق التطبيقات الأخرى ثم أعد فتح إيف."
                        )
                    }

                    // LiteRT-LM requires its cache directory to exist and be writable before Engine.initialize().
                    val engineCacheDirectory = File(appContext.cacheDir, "eve-litertlm-cache")
                    if (!engineCacheDirectory.exists() && !engineCacheDirectory.mkdirs()) {
                        throw IllegalStateException(
                            "تعذر إنشاء مجلد التخزين المؤقت للنموذج المحلي."
                        )
                    }
                    if (!engineCacheDirectory.isDirectory || !engineCacheDirectory.canWrite()) {
                        throw IllegalStateException(
                            "مجلد التخزين المؤقت للنموذج المحلي غير قابل للكتابة."
                        )
                    }

                    val newEngine = Engine(
                        EngineConfig(
                            modelPath = file.absolutePath,
                            backend = Backend.CPU(),
                            cacheDir = engineCacheDirectory.absolutePath
                        )
                    )
                    try {
                        newEngine.initialize()
                        val newConversation = newEngine.createConversation(
                            ConversationConfig(
                                systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
                                samplerConfig = SamplerConfig(
                                    topK = 24,
                                    topP = 0.88,
                                    temperature = 0.35
                                ),
                                maxOutputToken = MAX_OUTPUT_TOKENS,
                                // The bundled Qwen2 file has the legacy string-content template.
                                // LiteRT-LM 0.18 passes message content as typed parts, so override
                                // the metadata template to safely flatten text parts before formatting.
                                chatTemplate = QWEN2_COMPATIBLE_CHAT_TEMPLATE
                            )
                        )
                        engine = newEngine
                        conversation = newConversation
                    } catch (error: Exception) {
                        try { newEngine.close() } catch (_: Exception) { }
                        throw error
                    }

                    state = LocalLlmState.READY
                    lastRetryAt = 0L
                    publish(LocalLlmState.READY, 100, "الدماغ اللغوي المحلي جاهز ويعمل دون إنترنت")
                    Log.i(
                        TAG,
                        "LOCAL_LLM_READY model=Qwen2-0.5B-Instruct backend=CPU localOnly=true modelBytes=${file.length()}"
                    )
                } catch (error: Exception) {
                    if (!closed.get()) {
                        state = if (!hasUnmeteredInternet() && !isVerifiedModelOnDisk()) {
                            LocalLlmState.WAITING_FOR_WIFI
                        } else {
                            LocalLlmState.ERROR
                        }
                        // No timer is needed while waiting for Wi-Fi: the network callback and
                        // activity resume must be allowed to begin the first download immediately.
                        lastRetryAt = if (state == LocalLlmState.WAITING_FOR_WIFI) {
                            0L
                        } else {
                            System.currentTimeMillis() + RETRY_DELAY_MS
                        }
                        val message = error.message.orEmpty().take(180).ifBlank { "تعذر تهيئة الدماغ المحلي." }
                        publish(
                            state,
                            0,
                            if (state == LocalLlmState.WAITING_FOR_WIFI) {
                                "اتصل بشبكة Wi‑Fi لتنزيل الدماغ المحلي مرة واحدة (~647 ميغابايت)"
                            } else message
                        )
                        Log.e(TAG, "LOCAL_LLM_INIT_FAILED state=$state", error)
                    }
                } finally {
                    initializing.set(false)
                }
            }
        } catch (error: Exception) {
            initializing.set(false)
            lastRetryAt = System.currentTimeMillis() + RETRY_DELAY_MS
            state = LocalLlmState.ERROR
            publish(state, 0, "تعذر تشغيل عامل النموذج المحلي.")
            Log.e(TAG, "Could not queue local LLM initialization.", error)
        }
    }

    fun answerUser(
        userText: String,
        context: LocalLlmContext,
        callback: (Result<LocalLlmResult>) -> Unit
    ) {
        runInference(
            buildPrompt("USER", userText, context),
            maxOutputTokens = 144,
            callback = callback
        )
    }

    /** Lets the local LLM create one contextual question about a novel, high-confidence observation. */
    fun askFromCuriosity(
        context: LocalLlmContext,
        label: String,
        confidence: Float,
        sightings: Int,
        fallbackQuestion: String,
        callback: (Result<LocalLlmResult>) -> Unit
    ) {
        val groundedContext = context.copy(
            latestVisionLabel = label.trim().take(48),
            visionConfidence = confidence.coerceIn(0f, 1f),
            visionObservationAgeMs = 0L,
            sightingCount = sightings.coerceIn(0, 100_000)
        )
        runInference(
            buildPrompt("CURIOSITY", fallbackQuestion, groundedContext),
            maxOutputTokens = 112,
            callback = callback
        )
    }

    /** Periodically lets the LLM reason over fresh visual labels and saved experience, without speaking. */
    fun reflectOnObservation(
        context: LocalLlmContext,
        callback: (Result<LocalLlmResult>) -> Unit
    ) {
        runInference(
            buildPrompt("OBSERVATION", "", context),
            maxOutputTokens = 96,
            callback = callback
        )
    }

    private fun runInference(
        prompt: String,
        maxOutputTokens: Int,
        callback: (Result<LocalLlmResult>) -> Unit
    ) {
        if (!isReady) {
            ensureReady()
            deliver(callback, Result.failure(IllegalStateException("الدماغ اللغوي المحلي لم يصبح جاهزًا بعد.")))
            return
        }
        if (!generating.compareAndSet(false, true)) {
            deliver(callback, Result.failure(IllegalStateException("النموذج المحلي مشغول بطلب سابق.")))
            return
        }
        try {
            worker.execute {
                val settled = AtomicBoolean(false)
                val responseText = StringBuilder()
                val chunkCount = java.util.concurrent.atomic.AtomicInteger(0)
                val inferenceStartedAt = SystemClock.elapsedRealtime()

                fun finishInference(result: Result<LocalLlmResult>) {
                    if (!settled.compareAndSet(false, true)) return
                    generating.set(false)
                    deliver(callback, result)
                }

                try {
                    if (closed.get()) throw IllegalStateException("تم إغلاق النموذج المحلي.")
                    val activeConversation = conversation
                        ?: throw IllegalStateException("لا توجد محادثة محلية نشطة.")
                    val outputCap = maxOutputTokens.coerceIn(12, MAX_OUTPUT_TOKENS)
                    Log.i(
                        TAG,
                        "LOCAL_LLM_INFERENCE_START promptChars=${prompt.length} outputCap=$outputCap modeAsync=true"
                    )

                    // Async generation avoids holding this worker until the native model finishes.
                    // Collect streamed text chunks and complete only after LiteRT-LM signals onDone.
                    activeConversation.sendMessageAsync(
                        prompt,
                        object : MessageCallback {
                            override fun onMessage(message: Message) {
                                val chunk = message.contents.contents
                                    .filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                                    .joinToString(separator = "") { it.text }
                                val count = chunkCount.incrementAndGet()
                                if (chunk.isNotEmpty()) {
                                    synchronized(responseText) {
                                        val remaining = MAX_RESPONSE_BUFFER_CHARS - responseText.length
                                        if (remaining > 0) responseText.append(chunk.take(remaining))
                                    }
                                }
                                if (count == 1 || count % 16 == 0) {
                                    val currentChars = synchronized(responseText) { responseText.length }
                                    Log.i(TAG, "LOCAL_LLM_STREAM chunks=$count chars=$currentChars")
                                }
                            }

                            override fun onDone() {
                                try {
                                    val completedText = synchronized(responseText) {
                                        responseText.toString().trim()
                                    }
                                    val elapsed = SystemClock.elapsedRealtime() - inferenceStartedAt
                                    if (completedText.isBlank()) {
                                        Log.e(
                                            TAG,
                                            "LOCAL_LLM_INFERENCE_EMPTY elapsedMs=$elapsed chunks=${chunkCount.get()}"
                                        )
                                        finishInference(
                                            Result.failure(
                                                IllegalStateException("النموذج المحلي أكمل التوليد دون نص.")
                                            )
                                        )
                                        return
                                    }

                                    val parsed = parseResponse(completedText, outputCap)
                                    Log.i(
                                        TAG,
                                        "LOCAL_LLM_INFERENCE_OK elapsedMs=$elapsed chunks=${chunkCount.get()} " +
                                            "chars=${completedText.length} mood=${parsed.moodHint ?: "none"} " +
                                            "intent=${parsed.intentHint ?: "none"}"
                                    )
                                    finishInference(Result.success(parsed))
                                } catch (error: Exception) {
                                    Log.e(TAG, "LOCAL_LLM_PARSE_FAILED", error)
                                    finishInference(Result.failure(error))
                                }
                            }

                            override fun onError(throwable: Throwable) {
                                Log.e(
                                    TAG,
                                    "LOCAL_LLM_INFERENCE_FAILED elapsedMs=${SystemClock.elapsedRealtime() - inferenceStartedAt} " +
                                        "chunks=${chunkCount.get()}",
                                    throwable
                                )
                                finishInference(Result.failure(throwable))
                            }
                        },
                        maxOutputToken = maxOutputTokens.coerceIn(12, MAX_OUTPUT_TOKENS)
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "LOCAL_LLM_ASYNC_START_FAILED", error)
                    finishInference(Result.failure(error))
                }
            }
        } catch (error: Exception) {
            generating.set(false)
            deliver(callback, Result.failure(error))
        }
    }

    private fun buildPrompt(mode: String, userText: String, context: LocalLlmContext): String {
        val latestConfidence = context.visionConfidence.takeIf { it.isFinite() }
            ?.coerceIn(0f, 1f) ?: 0f
        val hasFreshVisualEvidence = context.visionObservationAgeMs in 0L..12_000L &&
            context.latestVisionLabel != null && latestConfidence >= 0.45f
        val label = if (hasFreshVisualEvidence) context.latestVisionLabel.orEmpty().take(48) else "none"
        val confidence = if (hasFreshVisualEvidence) {
            String.format(Locale.ROOT, "%.2f", latestConfidence)
        } else "0.00"
        val observations = context.recentObservations.take(3).joinToString("؛ ").take(180)
            .ifBlank { "لا توجد ملاحظات" }
        val dialogues = context.recentDialogues.take(2).joinToString("\n").take(220)
            .ifBlank { "لا توجد محادثات" }
        val reflections = context.recentReflections.take(2).joinToString("؛ ").take(140)
            .ifBlank { "لا توجد استنتاجات" }
        val reason = context.moodReason.take(72).replace("\n", " ")
        val user = userText.trim().take(280).ifBlank { "لا يوجد كلام جديد" }
        val personality = "warmth=${context.personality.warmth}; curiosity=${context.personality.curiosityBias}; " +
            "play=${context.personality.playfulness}; patience=${context.personality.patience}"
        val task = when (mode) {
            "USER" ->
                "أجب بالعربية الطبيعية وباختصار مفيد، وكأنك شخصية رفيقة لها أسلوبها. تابعي آخر ما قاله المستخدم وما تتذكرينه من الحوار؛ لا تبدئي من الصفر إذا كان الكلام امتدادًا لسابقه. لا تصفي المشهد إلا إذا كانت الرؤية حديثة وموثوقة."
            "CURIOSITY" ->
                "صوغي سؤال فضول عربيًا واحدًا فقط، قصيرًا وطبيعيًا، منطلقًا من التصنيف البصري الحديث وذاكرة الحوار والشخصية. اجعليه محددًا وغير مكرر، ولا تدّعي معرفة هوية شخص أو تفاصيل لا يثبتها التصنيف."
            else ->
                "استنتجي ملاحظة داخلية قصيرة. اجعلي reply_ar فارغًا ولا تخاطبي المستخدم."
        }
        return """
MODE=$mode
TASK=$task
VISION=$label
VISION_CONFIDENCE=$confidence
VISION_FRESH=$hasFreshVisualEvidence
VISION_AGE_MS=${context.visionObservationAgeMs.coerceAtLeast(0L)}
SIGHTINGS=${context.sightingCount.coerceIn(0, 100000)}
AFFINITY=${context.learnedAffinity.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f}
MOOD=${context.currentMood.take(24)} ($reason)
PERSONALITY=$personality
MEMORY=$observations
DIALOGUE=$dialogues
REFLECTIONS=$reflections
USER=$user
Return ONLY this JSON schema:
{"reply_ar":"short Arabic reply or empty","reflection_ar":"short or empty","mood":"neutral|curious|happy|concerned|focused|playful|sleepy|irritated|none","intent":"observe|explore|connect|play|focus|rest|regulate|none","confidence":0.0}
Rules: if VISION_FRESH=false, do not describe what is currently in view. Labels are uncertain categories, not identity or proof of private feelings. Never invent memory, claim consciousness, or request external actions. The Kotlin behavior engine validates hints. If MODE=OBSERVATION, reply_ar must be empty. If MODE=CURIOSITY, reply_ar must contain exactly one brief Arabic question related to the current high-confidence label and its context; do not ask for a name or identity from a generic person label, do not repeat the fallback question word-for-word unless necessary, and do not invent visual details.
        """.trimIndent()
    }

    private fun parseResponse(raw: String, maxOutputTokens: Int): LocalLlmResult {
        val safeRaw = raw.trim().take(1200)
        val jsonCandidate = safeRaw.indexOf('{').let { start ->
            val end = safeRaw.lastIndexOf('}')
            if (start >= 0 && end > start) safeRaw.substring(start, end + 1) else ""
        }
        val json = try {
            if (jsonCandidate.isBlank()) null else JSONObject(jsonCandidate)
        } catch (_: Exception) {
            null
        }
        val rawReply = json?.optString("reply_ar", "").orEmpty().trim()
        val reflection = json?.optString("reflection_ar", "").orEmpty().trim().take(240)
        val mood = json?.optString("mood", "").orEmpty().trim().lowercase(Locale.ROOT)
            .takeIf { it in ALLOWED_MOODS }
        val intent = json?.optString("intent", "").orEmpty().trim().lowercase(Locale.ROOT)
            .takeIf { it in ALLOWED_INTENTS }
        val confidence = json?.optDouble("confidence", 0.0)?.toFloat()
            ?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val fallbackReply = if (json == null) safeRaw.replace(Regex("(?s)<[^>]{0,60}>"), "").trim() else ""
        val reply = (rawReply.ifBlank { fallbackReply }).take(MAX_REPLY_CHARS)
        return LocalLlmResult(
            replyArabic = reply,
            moodHint = mood,
            intentHint = intent,
            reflectionArabic = reflection,
            confidence = confidence
        )
    }

    private fun ensureModelFile(): File {
        if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
            throw IllegalStateException("تعذر إنشاء مساحة تخزين الدماغ المحلي.")
        }
        if (isVerifiedModelOnDisk()) return modelFile
        val free = StatFs(modelDirectory.absolutePath).availableBytes
        if (free < MIN_FREE_STORAGE_BYTES) {
            throw IllegalStateException("يلزم توفير 750 ميغابايت على الأقل من المساحة الحرة لتثبيت الدماغ المحلي.")
        }
        if (!hasUnmeteredInternet()) {
            publish(
                LocalLlmState.WAITING_FOR_WIFI,
                0,
                "اتصل بشبكة Wi‑Fi لتنزيل الدماغ المحلي مرة واحدة (~647 ميغابايت)"
            )
            throw IllegalStateException("يلزم اتصال Wi‑Fi لأول تنزيل فقط؛ بعدها يعمل الدماغ دون إنترنت.")
        }
        downloadModel()
        verifyModelHash()
        prefs.edit().putString(KEY_VERIFIED_SHA, MODEL_SHA256).putLong(KEY_MODEL_BYTES, modelFile.length()).apply()
        Log.i(TAG, "LOCAL_LLM_MODEL_VERIFIED bytes=${modelFile.length()} sha256=$MODEL_SHA256")
        return modelFile
    }

    private fun isVerifiedModelOnDisk(): Boolean {
        if (!modelFile.isFile || modelFile.length() < MIN_MODEL_BYTES) return false
        return prefs.getString(KEY_VERIFIED_SHA, null) == MODEL_SHA256 &&
            prefs.getLong(KEY_MODEL_BYTES, -1L) == modelFile.length()
    }

    private fun downloadModel() {
        var offset = partialFile.takeIf { it.isFile }?.length() ?: 0L
        var connection = openConnection(offset)
        var code = try {
            connection.responseCode
        } catch (error: Exception) {
            connection.disconnect()
            throw error
        }

        if (offset > 0L && code != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            partialFile.delete()
            offset = 0L
            connection = openConnection(0L)
            code = connection.responseCode
        }
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            throw IllegalStateException("تعذر تنزيل ملف النموذج (HTTP $code). أعد المحاولة على Wi‑Fi.")
        }

        state = LocalLlmState.DOWNLOADING
        val append = offset > 0L && code == HttpURLConnection.HTTP_PARTIAL
        val initialBytes = if (append) offset else 0L
        val responseLength = connection.getHeaderFieldLong("Content-Length", -1L)
        val totalBytes = if (responseLength > 0L) initialBytes + responseLength else APPROX_MODEL_BYTES
        val buffer = ByteArray(128 * 1024)
        var written = initialBytes
        var lastReported = -1
        try {
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(partialFile, append).use { output ->
                    while (true) {
                        if (closed.get() || Thread.currentThread().isInterrupted) {
                            throw InterruptedException("أُوقف تنزيل النموذج.")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        val percent = ((written * 100L) / totalBytes.coerceAtLeast(1L)).toInt().coerceIn(0, 99)
                        if (percent >= lastReported + 2 || percent == 99) {
                            lastReported = percent
                            publish(LocalLlmState.DOWNLOADING, percent, "تنزيل الدماغ اللغوي المحلي $percent% • لمرة واحدة")
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
        if (!partialFile.isFile || partialFile.length() < MIN_MODEL_BYTES) {
            throw IllegalStateException("اكتمل اتصال التنزيل لكن ملف النموذج غير مكتمل؛ سيُستكمل عند إعادة المحاولة.")
        }
        if (modelFile.exists() && !modelFile.delete()) {
            throw IllegalStateException("تعذر استبدال ملف النموذج القديم.")
        }
        if (!partialFile.renameTo(modelFile)) {
            throw IllegalStateException("تعذر حفظ ملف النموذج بعد التنزيل.")
        }
    }

    private fun openConnection(offset: Long): HttpURLConnection {
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "EVE-Robot-Offline/1.0")
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
        connection.connect()
        return connection
    }

    private fun verifyModelHash() {
        publish(LocalLlmState.LOADING, 100, "التحقق من ملف الدماغ المحلي…")
        val digest = MessageDigest.getInstance("SHA-256")
        modelFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }
        if (!actual.equals(MODEL_SHA256, ignoreCase = true)) {
            modelFile.delete()
            partialFile.delete()
            throw IllegalStateException("فشل التحقق من سلامة نموذج Qwen؛ حُذف الملف غير الصحيح.")
        }
    }

    private fun hasUnmeteredInternet(): Boolean {
        val active = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(active) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            !connectivity.isActiveNetworkMetered
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        try {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    ensureReady()
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    ) ensureReady()
                }
            }
            connectivity.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (error: Exception) {
            Log.w(TAG, "Unable to observe network changes; model setup will retry on resume.", error)
        }
    }

    private fun publish(nextState: LocalLlmState, percent: Int, message: String) {
        val safePercent = percent.coerceIn(0, 100)
        val changed = state != nextState || lastStatusMessage != message || lastProgress != safePercent
        state = nextState
        if (!changed) return
        lastStatusMessage = message
        lastProgress = safePercent
        main.post {
            if (!closed.get()) statusListener(nextState, safePercent, message)
        }
    }

    private fun deliver(callback: (Result<LocalLlmResult>) -> Unit, result: Result<LocalLlmResult>) {
        main.post {
            if (!closed.get()) callback(result)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { networkCallback?.let(connectivity::unregisterNetworkCallback) } catch (_: Exception) { }
        networkCallback = null
        try {
            worker.execute {
                try { conversation?.close() } catch (_: Exception) { }
                conversation = null
                try { engine?.close() } catch (_: Exception) { }
                engine = null
            }
            worker.shutdown()
        } catch (_: Exception) {
            try { worker.shutdownNow() } catch (_: Exception) { }
        }
    }

    companion object {
        private const val TAG = "EVE_LLM"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen2-0.5B-Instruct/resolve/main/Qwen2_0.5B_Instruct.litertlm"
        private const val MODEL_FILE_NAME = "Qwen2_0.5B_Instruct.litertlm"
        private const val MODEL_SHA256 =
            "0f01cc004b8eb62b92ba6be85ed05a248ba0d2f78af94c4949b313eccfb4c157"
        private const val KEY_VERIFIED_SHA = "verifiedModelSha256"
        private const val KEY_MODEL_BYTES = "verifiedModelBytes"
        private const val APPROX_MODEL_BYTES = 647_000_000L
        private const val MIN_MODEL_BYTES = 500_000_000L
        private const val MIN_FREE_STORAGE_BYTES = 750_000_000L
        private const val MIN_AVAILABLE_RAM_BYTES = 1_200_000_000L
        private const val CONNECT_TIMEOUT_MS = 25_000
        private const val READ_TIMEOUT_MS = 75_000
        private const val RETRY_DELAY_MS = 30_000L
        private const val MAX_REPLY_CHARS = 650
        private const val MAX_OUTPUT_TOKENS = 64
        private const val MAX_RESPONSE_BUFFER_CHARS = 8_000
        /**
         * Qwen2 chat format adapted for LiteRT-LM 0.18 typed content.
         * The stock bundle template concatenates role + content as strings, which fails when
         * the runtime supplies content as a sequence of {type: text, text: ...} parts.
         */
        private val QWEN2_COMPATIBLE_CHAT_TEMPLATE = """
{%- macro format_content(content) -%}
{%- if content is string -%}
{{- content -}}
{%- else -%}
{%- for item in content -%}
{%- if item['type'] == 'text' -%}
{{- item['text'] -}}
{%- elif item['type'] == 'tool_response' -%}
{%- if item['response'] is mapping or item['response'] is sequence -%}
{{- item['response'] | tojson -}}
{%- else -%}
{{- item['response'] | string -}}
{%- endif -%}
{%- endif -%}
{%- endfor -%}
{%- endif -%}
{%- endmacro -%}
{%- for message in messages -%}
{{- '<|im_start|>' + message['role'] + '\n' -}}
{{- format_content(message['content']) -}}
{{- '<|im_end|>\n' -}}
{%- endfor -%}
{%- if add_generation_prompt -%}
{{- '<|im_start|>assistant\n' -}}
{%- endif -%}
""".trimIndent()
        private val ALLOWED_MOODS = setOf(
            "neutral", "curious", "happy", "concerned", "focused", "playful", "sleepy", "irritated", "none"
        )
        private val ALLOWED_INTENTS = setOf(
            "observe", "explore", "connect", "play", "focus", "rest", "regulate", "none"
        )
        private const val SYSTEM_INSTRUCTION = """
You are the local language component of EVE, a small companion robot. Speak concise, natural Arabic in a warm character voice, but do not claim consciousness. Use the supplied current visual classifier labels and local memory carefully. They are uncertain categories, not ground truth, not face identity, and not proof of a person's internal emotions. Never fabricate sensory details. Return the JSON format requested in every user prompt and choose only its allowed fields. The deterministic Kotlin behaviour engine validates your suggestions; do not claim to have executed actions.
"""
    }
}
