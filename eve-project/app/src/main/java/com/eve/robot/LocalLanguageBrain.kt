package com.eve.robot

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
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

enum class LocalLlmState { DEFERRED, WAITING_FOR_WIFI, DOWNLOADING, LOADING, READY, ERROR }

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
    val recentReflections: List<String> = emptyList(),
    val knownPeople: String = "",
    /** Short, recent object-detection scene summary; no raw camera frames are sent to the model. */
    val latestSceneSummary: String = "",
    val sceneObservationAgeMs: Long = Long.MAX_VALUE,
    /** Current on-device date, season, and approximate Hijri context; no network lookup. */
    val calendarContext: String = ""
)

data class LocalLlmResult(
    val replyArabic: String,
    val moodHint: String?,
    val intentHint: String?,
    val reflectionArabic: String,
    val confidence: Float,
    /** Untrusted model proposal; callers must validate it against an explicit allow-list. */
    val planningAction: String? = null,
    val planningGoal: String? = null,
    val planningReason: String? = null,
    val successCriterion: String? = null
)

/**
 * A real generative language model running on the phone through LiteRT-LM.
 *
 * The official release APK bundles the pinned Qwen3-0.6B dynamic-INT4 model (~977 MB) in its assets. On first launch,
 * weights are copied to app-private storage and SHA-256 verified; this needs no runtime network.
 * A Wi-Fi fallback remains only for developer/test APKs built without the optional model asset.
 * Inference is local; prompts, labels, and replies are not sent to a remote service. Vision
 * evidence is supplied as on-device classifier metadata, not as raw camera images.
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

    @Volatile private var state: LocalLlmState = LocalLlmState.DEFERRED
    @Volatile private var engine: Engine? = null
    @Volatile private var lastStatusMessage = ""
    @Volatile private var lastProgress = -1
    @Volatile private var lastRetryAt = 0L
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        // MainActivity owns the single heavy model instance; the game room reuses it.
        activeInstance = this
    }

    val isReady: Boolean
        get() = state == LocalLlmState.READY && engine != null

    /** Used by the executive to avoid overlapping expensive local generations. */
    val isGenerating: Boolean
        get() = generating.get()

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
                    val isArm64 = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
                    val enoughMemoryForGpu = isArm64 && memoryInfo.availMem >= MIN_GPU_AVAILABLE_RAM_BYTES
                    val enoughMemoryForCpu = memoryInfo.availMem >= MIN_CPU_AVAILABLE_RAM_BYTES
                    if (!enoughMemoryForGpu && !enoughMemoryForCpu) {
                        throw IllegalStateException(
                            "الذاكرة المتاحة قليلة لتشغيل الدماغ المحلي؛ أغلق التطبيقات الأخرى ثم أعد فتح إيف."
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

                    // Prefer the packaged GPU delegate on ARM64 phones. The bundled Qwen
                    // LiteRT-LM artifact is measured on mobile GPU; CPU remains the compatibility
                    // fallback for devices whose vendor driver/delegate cannot initialize it.
                    val cpuThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                    val cpuBackend = Backend.CPU(threadCount = cpuThreads)
                    // The 1.7B INT4 artifact is GPU-optimized. On ARM64, avoid falling back to
                    // CPU when there is not enough headroom for that heavier path; on x86 CI,
                    // use CPU so the offline integration test can still exercise real inference.
                    val backends = buildList {
                        if (enoughMemoryForGpu) add("GPU" to Backend.GPU())
                        if (enoughMemoryForCpu) add("CPU" to cpuBackend)
                    }
                    Log.i(TAG, "LOCAL_LLM_BACKEND_CANDIDATES=${backends.map { it.first }} cpuThreads=$cpuThreads")

                    var selectedEngine: Engine? = null
                    var lastBackendError: Exception? = null
                    for ((backendName, backend) in backends) {
                        val candidate = Engine(
                            EngineConfig(
                                modelPath = file.absolutePath,
                                backend = backend,
                                // Keep the KV cache bounded; conversation turns are deliberately short.
                                maxNumTokens = 768,
                                cacheDir = engineCacheDirectory.absolutePath
                            )
                        )
                        try {
                            candidate.initialize()
                            candidate.createConversation(conversationConfig()).close()
                            selectedEngine = candidate
                            Log.i(TAG, "LOCAL_LLM_BACKEND_SELECTED backend=$backendName")
                            break
                        } catch (error: Exception) {
                            lastBackendError = error
                            try { candidate.close() } catch (_: Exception) { }
                            Log.w(TAG, "LOCAL_LLM_BACKEND_FAILED backend=$backendName; trying next candidate", error)
                        }
                    }
                    engine = selectedEngine
                        ?: throw IllegalStateException("تعذر تهيئة محرك اللغة على GPU أو CPU.", lastBackendError)
                    state = LocalLlmState.READY
                    lastRetryAt = 0L
                    publish(LocalLlmState.READY, 100, "الدماغ اللغوي المحلي جاهز ويعمل دون إنترنت")
                    Log.i(
                        TAG,
                        "LOCAL_LLM_READY model=Qwen3-0.6B-INT4 localOnly=true modelBytes=${file.length()}"
                    )
                } catch (error: Exception) {
                    if (!closed.get()) {
                        // Only a truly absent bundled model should be reported as a Wi-Fi requirement.
                        // Low RAM, disk-space, asset-extraction, and LiteRT-LM errors must stay ERROR;
                        // otherwise a real startup defect is hidden behind a misleading Wi-Fi message.
                        state = if (error is OfflineModelAssetUnavailable) {
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
                                "اتصل بشبكة Wi‑Fi لتنزيل الدماغ المحلي مرة واحدة (~329 ميغابايت)"
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
        maxOutputTokens: Int = 32,
        callback: (Result<LocalLlmResult>) -> Unit
    ) {
        runInference(
            buildPrompt("USER", userText, context),
            maxOutputTokens = maxOutputTokens.coerceIn(12, MAX_OUTPUT_TOKENS),
            callback = callback
        )
    }

    /** Generate an Arabic comment grounded in a real local game result. */
    fun commentOnGame(
        gameName: String,
        outcome: String,
        detail: String,
        playerScore: Int?,
        eveScore: Int?,
        round: Int?,
        difficulty: Int,
        recentResults: List<String>,
        context: LocalLlmContext,
        callback: (Result<LocalLlmResult>) -> Unit
    ) {
        fun safe(value: String, limit: Int) = value.replace(Regex("\\s+"), " ").trim().take(limit)
        val scorePlayer = playerScore?.coerceIn(0, 100_000)?.toString() ?: "غير متاح"
        val scoreEve = eveScore?.coerceIn(0, 100_000)?.toString() ?: "غير متاح"
        val history = recentResults.takeLast(3).joinToString("؛ ") { safe(it, 48) }.take(45)
        val gameEvent = (
            "اللعبة=" + safe(gameName, 24) + "؛ الحدث=" + safe(outcome, 24) +
                "؛ النتيجة: اللاعب " + scorePlayer + "، إيف " + scoreEve +
                "؛ الجولة=" + (round?.coerceIn(0, 100_000)?.toString() ?: "غير متاح") +
                "؛ الصعوبة=" + difficulty.coerceIn(1, 3) +
                "؛ التفاصيل=" + safe(detail, 72) + "؛ نتائج سابقة=" + history
            ).take(220)
        val gameContext = context.copy(
            latestVisionLabel = null,
            visionConfidence = 0f,
            visionObservationAgeMs = Long.MAX_VALUE,
            recentObservations = emptyList(),
            recentDialogues = (context.recentDialogues + "سياق لعبة سابق: " + history).takeLast(3),
            latestSceneSummary = "",
            sceneObservationAgeMs = Long.MAX_VALUE
        )
        runInference(
            buildPrompt("GAME", gameEvent, gameContext),
            // Use the full bounded output budget: a short Arabic sentence and valid JSON must both fit.
            maxOutputTokens = MAX_OUTPUT_TOKENS,
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
            maxOutputTokens = 24,
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
            maxOutputTokens = 24,
            callback = callback
        )
    }

    /**
     * EVE's generative executive proposes one cognitive step from a strict allow-list.
     * This method never executes actions itself; MainActivity re-checks the live safety
     * conditions after generation and rejects every action not explicitly offered.
     */
    fun planAutonomousStep(
        context: LocalLlmContext,
        allowedActions: List<String>,
        previousGoal: String,
        previousAction: String,
        previousOutcome: String,
        recentActionOutcomes: List<String> = emptyList(),
        callback: (Result<LocalLlmResult>) -> Unit
    ) {
        val allowed = allowedActions.distinct().filter { it in AUTONOMY_ACTIONS }.take(AUTONOMY_ACTIONS.size)
        if (allowed.isEmpty()) {
            deliver(callback, Result.failure(IllegalArgumentException("لا توجد خطوات معرفية مسموحة لهذه الحالة.")))
            return
        }
        runInference(
            buildAutonomyPlannerPrompt(
                context = context,
                allowedActions = allowed,
                previousGoal = previousGoal,
                previousAction = previousAction,
                previousOutcome = previousOutcome,
                recentActionOutcomes = recentActionOutcomes
            ),
            maxOutputTokens = 96,
            callback = callback
        )
    }

    private fun allowedPlannerActions(prompt: String): List<String> =
        prompt.lineSequence()
            .firstOrNull { it.startsWith("ALLOWED=") }
            ?.removePrefix("ALLOWED=")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it in AUTONOMY_ACTIONS }
            ?.distinct()
            .orEmpty()

    private fun hasCompleteAutonomyPlan(result: LocalLlmResult, prompt: String): Boolean {
        val allowed = allowedPlannerActions(prompt)
        return result.planningAction != null &&
            result.planningAction in allowed &&
            !result.planningGoal.isNullOrBlank() &&
            !result.planningReason.isNullOrBlank() &&
            !result.successCriterion.isNullOrBlank()
    }

    private fun buildAutonomyPlannerRetryPrompt(originalPrompt: String): String {
        val allowed = allowedPlannerActions(originalPrompt).ifEmpty {
            listOf("observe_scene", "quiet_attention")
        }
        val defaultAction = allowed.first()
        return """
            MODE=PLAN_REPAIR
            Output exactly one valid JSON object. No prose, markdown, or extra keys.
            next_action must exactly match one token in: ${allowed.joinToString(",")}.
            Keep goal, reason, success_criterion short. No line breaks inside JSON strings.
            {"reply_ar":"","reflection_ar":"","mood":"curious","intent":"observe","confidence":0.5,"goal":"مراجعة الدليل الحديث","next_action":"$defaultAction","reason":"التحقق من الدليل المحلي","success_criterion":"اكتمال خطة واضحة"}
        """.trimIndent()
    }

    private fun buildAutonomyPlannerPrompt(
        context: LocalLlmContext,
        allowedActions: List<String>,
        previousGoal: String,
        previousAction: String,
        previousOutcome: String,
        recentActionOutcomes: List<String>
    ): String {
        val confidence = context.visionConfidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val freshVision = context.visionObservationAgeMs in 0L..12_000L &&
            !context.latestVisionLabel.isNullOrBlank() && confidence >= 0.45f
        val freshScene = context.sceneObservationAgeMs in 0L..10_000L &&
            context.latestSceneSummary.isNotBlank()
        val p = context.personality
        return buildString {
            appendLine("MODE=PLAN")
            appendLine("Choose one useful cognitive step using current evidence, local memory and previous outcomes.")
            appendLine("ALLOWED=" + allowedActions.joinToString(","))
            appendLine("Use exactly one ALLOWED action. Uncertain/stale evidence => observe_scene or quiet_attention.")
            appendLine("Do not infer identity, emotion, intent or activity from a generic label. Do not repeat failed actions without changed evidence.")
            appendLine("Cognitive planning only: no network, external commands or hardware movement.")
            appendLine("MOOD=" + context.currentMood.take(12) + ";TRAITS=${p.warmth},${p.curiosityBias},${p.playfulness}")
            if (freshVision) appendLine("VISION=" + context.latestVisionLabel.orEmpty().take(24) +
                ";c=" + String.format(Locale.ROOT, "%.1f", confidence) +
                ";n=" + context.sightingCount.coerceIn(0, 100000))
            if (freshScene) appendLine("SCENE=" + context.latestSceneSummary.trim().take(150))
            context.recentObservations.lastOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let {
                appendLine("MEM=" + it.take(72))
            }
            context.recentReflections.lastOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let {
                appendLine("REF=" + it.take(55))
            }
            context.recentDialogues.lastOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let {
                appendLine("CHAT=" + it.take(55))
            }
            context.knownPeople.trim().takeIf { it.isNotBlank() }?.let {
                appendLine("PEOPLE=" + it.take(45))
            }
            appendLine("PREV=" + previousGoal.take(45) + ";" + previousAction.take(28) + ";" + previousOutcome.take(60))
            recentActionOutcomes.takeLast(3).takeIf { it.isNotEmpty() }?.let {
                appendLine("FEEDBACK=" + it.joinToString("|") { entry -> entry.take(35) }.take(110))
            }
            appendLine("""JSON only: {"reply_ar":"","reflection_ar":"","mood":"curious","intent":"observe","confidence":0.5,"goal":"هدف قصير","next_action":"${allowedActions.first()}","reason":"سبب موجز","success_criterion":"معيار نجاح موجز"}""")
        }
    }

    private val AUTONOMY_ACTIONS = setOf(
        "observe_scene",
        "reflect_on_scene",
        "ask_curiosity_question",
        "quiet_attention",
        "switch_to_world_camera"
    )

    /**
     * Generates an original, short reaction from recent on-device evidence and EVE's saved context.
     * No fixed label-to-sentence mapping is used. The caller must supply only current, grounded
     * evidence and should remain silent when the local model is unavailable.
     */
    fun commentOnObservation(
        context: LocalLlmContext,
        evidence: String,
        callback: (Result<LocalLlmResult>) -> Unit
    ) {
        val groundedEvidence = evidence
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(260)
            .ifBlank { "لا توجد أدلة بصرية حديثة كافية لوصف المشهد." }
        runInference(
            buildPrompt(
                "OBSERVATION_COMMENT",
                groundedEvidence,
                context
            ),
            maxOutputTokens = 56,
            callback = callback
        )
    }

    /**
     * Conversations are deliberately short-lived. Context is passed in as bounded local memory,
     * not retained in LiteRT-LM's KV cache across user turns or autonomous planning cycles.
     */
    private fun conversationConfig(): ConversationConfig = ConversationConfig(
        systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
        samplerConfig = SamplerConfig(topK = 24, topP = 0.88, temperature = 0.35),
        maxOutputToken = MAX_OUTPUT_TOKENS,
        chatTemplate = QWEN3_COMPATIBLE_CHAT_TEMPLATE
    )

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
            // LiteRT-LM's synchronous call is deliberately run on the dedicated worker thread,
            // never on the UI thread. This avoids a hung streaming callback leaving the dialogue
            // latch unresolved on affected Android/LiteRT-LM combinations.
            worker.execute {
                val startedAt = SystemClock.elapsedRealtime()
                var requestConversation: Conversation? = null
                try {
                    if (closed.get()) throw IllegalStateException("تم إغلاق النموذج المحلي.")
                    val activeEngine = engine
                        ?: throw IllegalStateException("لا يوجد محرك لغة محلي نشط.")
                    val outputCap = maxOutputTokens.coerceIn(12, MAX_OUTPUT_TOKENS)
                    // Each request gets a fresh KV cache: the app already injects bounded memory
                    // explicitly, while reusing a Conversation silently accumulates old prompt tokens
                    // until LiteRT-LM rejects later plans for lack of context capacity.
                    val activeConversation = activeEngine.createConversation(conversationConfig())
                    requestConversation = activeConversation
                    Log.i(TAG, "LOCAL_LLM_INFERENCE_START promptChars=${prompt.length} outputCap=$outputCap mode=sync-worker")

                    val response = activeConversation.sendMessage(
                        prompt,
                        maxOutputToken = outputCap
                    )
                    val nativeElapsed = SystemClock.elapsedRealtime() - startedAt
                    val responseText = response.contents.contents
                        .filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                        .joinToString(separator = "") { it.text }
                        .trim()

                    if (responseText.isBlank()) {
                        throw IllegalStateException("أكمل النموذج المحلي التوليد دون نص.")
                    }

                    var parsed = parseResponse(responseText, outputCap)
                    var reportedResponseText = responseText

                    // A compact planner response is validated strictly. Small local models may
                    // return malformed JSON on the first try; give planning exactly one isolated,
                    // short-schema retry instead of silently accepting an empty/invalid action.
                    if (prompt.lineSequence().firstOrNull() == "MODE=PLAN" &&
                        !hasCompleteAutonomyPlan(parsed, prompt)
                    ) {
                        Log.w(
                            TAG,
                            "LOCAL_LLM_PLAN_REPAIR_START chars=${responseText.length} " +
                                "actionPresent=${!parsed.planningAction.isNullOrBlank()} " +
                                "goalPresent=${!parsed.planningGoal.isNullOrBlank()} " +
                                "reasonPresent=${!parsed.planningReason.isNullOrBlank()} " +
                                "criterionPresent=${!parsed.successCriterion.isNullOrBlank()}"
                        )
                        try { requestConversation?.close() } catch (_: Exception) { }
                        requestConversation = null
                        val repairConversation = activeEngine.createConversation(conversationConfig())
                        requestConversation = repairConversation
                        val repairStartedAt = SystemClock.elapsedRealtime()
                        val repairText = repairConversation.sendMessage(
                            buildAutonomyPlannerRetryPrompt(prompt),
                            maxOutputToken = MAX_OUTPUT_TOKENS
                        ).contents.contents
                            .filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                            .joinToString(separator = "") { it.text }
                            .trim()
                        if (repairText.isNotBlank()) {
                            val repaired = parseResponse(repairText, MAX_OUTPUT_TOKENS)
                            if (hasCompleteAutonomyPlan(repaired, prompt)) {
                                parsed = repaired
                                reportedResponseText = repairText
                                Log.i(
                                    TAG,
                                    "LOCAL_LLM_PLAN_REPAIR_PASS elapsedMs=${SystemClock.elapsedRealtime() - repairStartedAt} " +
                                        "action=${repaired.planningAction} goalPresent=true reasonPresent=true criterionPresent=true"
                                )
                            } else {
                                Log.w(
                                    TAG,
                                    "LOCAL_LLM_PLAN_REPAIR_INVALID elapsedMs=${SystemClock.elapsedRealtime() - repairStartedAt} " +
                                        "chars=${repairText.length} actionPresent=${!repaired.planningAction.isNullOrBlank()} " +
                                        "goalPresent=${!repaired.planningGoal.isNullOrBlank()} " +
                                        "reasonPresent=${!repaired.planningReason.isNullOrBlank()} " +
                                        "criterionPresent=${!repaired.successCriterion.isNullOrBlank()}"
                                )
                            }
                        }
                        if (!hasCompleteAutonomyPlan(parsed, prompt)) {
                            throw IllegalStateException(
                                "The local model returned no valid allow-listed action and complete goal/reason/success criterion after one planner repair attempt."
                            )
                        }
                    }

                    // Small local models sometimes copy the schema's empty reply field. GAME
                    // mode gets one short, focused second attempt rather than silently returning
                    // an empty comment or substituting a canned line.
                    if (prompt.lineSequence().firstOrNull() == "MODE=GAME" && parsed.replyArabic.isBlank()) {
                        Log.w(TAG, "LOCAL_LLM_GAME_EMPTY_REPLY_RETRY firstChars=${responseText.length}")
                        val retryStartedAt = SystemClock.elapsedRealtime()
                        val retryResponse = activeConversation.sendMessage(
                            buildGameRetryPrompt(prompt),
                            maxOutputToken = MAX_OUTPUT_TOKENS
                        )
                        val retryText = retryResponse.contents.contents
                            .filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                            .joinToString(separator = "") { it.text }
                            .trim()
                        if (retryText.isNotBlank()) {
                            val retryParsed = parseResponse(retryText, MAX_OUTPUT_TOKENS)
                            if (retryParsed.replyArabic.isNotBlank()) {
                                parsed = retryParsed
                                reportedResponseText = retryText
                                Log.i(
                                    TAG,
                                    "LOCAL_LLM_GAME_RETRY_PASS retryElapsedMs=${SystemClock.elapsedRealtime() - retryStartedAt} " +
                                        "chars=${retryText.length} replyChars=${retryParsed.replyArabic.length}"
                                )
                            }
                        }
                        if (parsed.replyArabic.isBlank()) {
                            throw IllegalStateException(
                                "Qwen returned an empty Arabic game comment after the focused offline retry."
                            )
                        }
                    }

                    Log.i(
                        TAG,
                        "LOCAL_LLM_INFERENCE_OK nativeElapsedMs=$nativeElapsed " +
                            "totalElapsedMs=${SystemClock.elapsedRealtime() - startedAt} " +
                            "chars=${reportedResponseText.length} replyChars=${parsed.replyArabic.length} " +
                            "mood=${parsed.moodHint ?: "none"} intent=${parsed.intentHint ?: "none"}"
                    )
                    deliver(callback, Result.success(parsed))
                } catch (error: Exception) {
                    Log.e(
                        TAG,
                        "LOCAL_LLM_INFERENCE_FAILED elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                        error
                    )
                    deliver(callback, Result.failure(error))
                } finally {
                    try { requestConversation?.close() } catch (closeError: Exception) {
                        Log.w(TAG, "LOCAL_LLM_REQUEST_CONVERSATION_CLOSE_FAILED", closeError)
                    }
                    generating.set(false)
                }
            }
        } catch (error: Exception) {
            generating.set(false)
            deliver(callback, Result.failure(error))
        }
    }

    private fun buildPrompt(mode: String, userText: String, context: LocalLlmContext): String {
        val confidence = context.visionConfidence.takeIf { it.isFinite() }
            ?.coerceIn(0f, 1f) ?: 0f
        val freshVision = context.visionObservationAgeMs in 0L..12_000L &&
            !context.latestVisionLabel.isNullOrBlank() && confidence >= 0.45f
        val freshScene = context.sceneObservationAgeMs in 0L..10_000L &&
            context.latestSceneSummary.isNotBlank()
        val user = userText.trim().replace(Regex("\\s+"), " ").take(120)
            .ifBlank { "لا يوجد كلام جديد" }

        val task = when (mode) {
            "USER" -> "Answer briefly in Arabic."
            "CURIOSITY" -> "Ask one very short Arabic question (4-10 words, at most 70 Arabic characters) grounded only in fresh vision. Never claim that a generic person label identifies a face or proves familiarity."
            "OBSERVATION_COMMENT" -> "Generate one original, natural Arabic first-person reaction to the CURRENT on-device evidence. It may be a short thought or question rather than a plain label. Use saved context/personality only when relevant. Do not map labels to canned phrases. Do not claim identity, intention, emotion, weather, or activity unless the supplied evidence supports it. If uncertain, express uncertainty. reply_ar must be non-empty and concise; reflection_ar may contain a brief useful update."
            "GAME" -> "Write one short Arabic reaction in reply_ar based only on the supplied game event. It must contain Arabic words and must not be empty. reflection_ar must be empty."
            else -> "Write a brief reflection; reply_ar must be empty."
        }

        // Token-budgeted prompt packing: do not send empty memory/chat/reflection fields.
        // This reduces cold-prefill work on phone CPUs while preserving the same model,
        // production response schema, grounded-vision rules, and inference code path.
        val fields = ArrayList<String>(12)
        fields += "MODE=$mode"
        fields += "TASK=$task"
        fields += "MOOD=${context.currentMood.trim().take(14).ifBlank { "NEUTRAL" }}"
        if (freshVision) {
            fields += "VISION=${context.latestVisionLabel.orEmpty().trim().take(28)};C=${(confidence * 100).toInt()}"
        }
        if (freshScene && mode != "GAME") {
            fields += "SCENE=${context.latestSceneSummary.trim().take(180)}"
        }
        if (context.calendarContext.isNotBlank() && mode == "USER") {
            fields += "CALENDAR=${context.calendarContext.trim().take(260)}"
        }
        context.recentObservations.lastOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let {
            fields += "MEM=${it.take(48)}"
        }
        context.recentDialogues.lastOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let {
            fields += "CHAT=${it.take(56)}"
        }
        context.recentReflections.lastOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let {
            fields += "REF=${it.take(40)}"
        }
        context.knownPeople.trim().takeIf { it.isNotBlank() }?.let {
            fields += "PEOPLE=${it.take(140)}"
        }
        if (context.moodReason.isNotBlank() && context.moodReason != mode) {
            fields += "WHY=${context.moodReason.trim().take(40)}"
        }

        val p = context.personality
        if (kotlin.math.abs(p.warmth - 0.76f) > 0.02f ||
            kotlin.math.abs(p.curiosityBias - 0.72f) > 0.02f ||
            kotlin.math.abs(p.playfulness - 0.62f) > 0.02f
        ) {
            fields += "P=w:${"%.1f".format(Locale.ROOT, p.warmth)};c:${"%.1f".format(Locale.ROOT, p.curiosityBias)};play:${"%.1f".format(Locale.ROOT, p.playfulness)}"
        }
        fields += "USER=$user"
        val responseExample = when (mode) {
            "GAME" -> """JSON only. reply_ar MUST be a non-empty Arabic sentence: {"reply_ar":"كانت جولة حماسية! سأحاول الفوز في المرة القادمة.","reflection_ar":"","mood":"playful","intent":"play","confidence":0.8}"""
            "USER" -> """JSON only: {"reply_ar":"مرحبًا! كيف أستطيع مساعدتك؟","reflection_ar":"","mood":"neutral","intent":"connect","confidence":0.8}"""
            "CURIOSITY" -> """JSON only: {"reply_ar":"ما هذا الشيء؟","reflection_ar":"","mood":"curious","intent":"observe","confidence":0.7}"""
            "OBSERVATION_COMMENT" -> """JSON only: {"reply_ar":"تغيّر المشهد أمامي، أريد أن ألاحظ ما الذي اختلف.","reflection_ar":"","mood":"curious","intent":"observe","confidence":0.6}"""
            else -> """JSON only: {"reply_ar":"","reflection_ar":"ملاحظة موجزة من الدليل المتاح فقط.","mood":"neutral","intent":"observe","confidence":0.5}"""
        }
        fields += responseExample
        fields += when (mode) {
            "OBSERVATION" -> "Never invent facts. OBSERVATION reply_ar empty."
            "CURIOSITY" -> "reply_ar must be one concise Arabic question (at most 84 characters) supported by fresh visual evidence. A generic person label is not face identity; never imply known name or familiarity."
            "OBSERVATION_COMMENT" -> "reply_ar must be newly generated in Arabic from VISION, SCENE and USER evidence only. Do not use a memorized canned label sentence. Do not claim a person's identity from face presence alone. If context lacks current evidence, say the evidence is insufficient. Keep it at most two short sentences."
            "GAME" -> "reply_ar must contain a non-empty Arabic game comment; reflection_ar must be empty. Never invent score or events; follow the supplied outcome only."
            else -> "Never invent observations or memories. If fresh vision is absent, do not describe a scene."
        }

        return fields.joinToString("\n")
    }
    private fun buildGameRetryPrompt(originalPrompt: String): String {
        val gameEvent = originalPrompt.lineSequence()
            .firstOrNull { it.startsWith("USER=") }
            ?.removePrefix("USER=")
            ?.trim()
            ?.take(220)
            .orEmpty()
            .ifBlank { "نتيجة اللعبة غير متاحة" }

        return """
            MODE=GAME_RETRY
            اكتب تعليقًا عربيًا قصيرًا وطبيعيًا من حدث اللعبة أدناه فقط.
            لا تخترع نتيجة أو حركة لم ترد في الحدث.
            يجب أن يحتوي reply_ar على جملة عربية غير فارغة؛ reflection_ar يجب أن يكون فارغًا.
            الحدث: $gameEvent
            أخرج JSON صالحًا فقط بهذا الشكل ومحتوى reply_ar غير فارغ:
            {"reply_ar":"كانت جولة ممتعة! سأحاول الفوز في المرة القادمة.","reflection_ar":"","mood":"playful","intent":"play","confidence":0.8}
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
            confidence = confidence,
            planningAction = json?.optString("next_action", "").orEmpty().trim()
                .takeIf { it in AUTONOMY_ACTIONS },
            planningGoal = json?.optString("goal", "").orEmpty().trim()
                .take(80).takeIf { it.isNotBlank() },
            planningReason = json?.optString("reason", "").orEmpty().trim()
                .take(180).takeIf { it.isNotBlank() },
            successCriterion = json?.optString("success_criterion", "").orEmpty().trim()
                .take(180).takeIf { it.isNotBlank() }
        )
    }

    /** Distinguishes a missing optional model asset from real initialization/runtime faults. */
    private class OfflineModelAssetUnavailable(message: String) : IllegalStateException(message)

    private fun ensureModelFile(): File {
        if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
            throw IllegalStateException("تعذر إنشاء مساحة تخزين الدماغ المحلي.")
        }
        if (isVerifiedModelOnDisk()) return modelFile
        // Remove the previous model first so an upgrade does not need both weights at once.
        // Remove both older shipped models so an upgrade does not leave nearly a gigabyte behind.
        for (legacyName in LEGACY_MODEL_FILE_NAMES) {
            val legacyModel = File(modelDirectory, legacyName)
            if (legacyModel.exists() && !legacyModel.delete()) {
                Log.w(TAG, "Could not remove superseded local model: $legacyName")
            }
        }
        val free = StatFs(modelDirectory.absolutePath).availableBytes
        if (free < MIN_FREE_STORAGE_BYTES) {
            throw IllegalStateException("يلزم توفير نحو 420 ميغابايت من المساحة الحرة لتجهيز الدماغ اللغوي الجديد.")
        }

        // Product APKs embed this exact model in assets, so first-run setup works with Wi-Fi off.
        // Only small developer/test builds which omit the optional asset use the legacy Wi-Fi path.
        if (extractBundledModelIfPresent()) {
            verifyModelHash()
            prefs.edit()
                .putString(KEY_VERIFIED_SHA, MODEL_SHA256)
                .putLong(KEY_MODEL_BYTES, modelFile.length())
                .apply()
            Log.i(
                TAG,
                "LOCAL_LLM_MODEL_VERIFIED source=apk_asset bytes=${modelFile.length()} sha256=$MODEL_SHA256"
            )
            return modelFile
        }

        if (!hasUnmeteredInternet()) {
            publish(
                LocalLlmState.WAITING_FOR_WIFI,
                0,
                "هذا الإصدار التجريبي لا يتضمن أوزان النموذج؛ أعد بناء نسخة APK المدمجة لتعمل دون إنترنت من أول تشغيل."
            )
            throw OfflineModelAssetUnavailable(
                "ملف النموذج غير موجود ضمن أصول التطبيق التجريبي، ولا يوجد اتصال Wi-Fi لتنزيله."
            )
        }
        downloadModel()
        verifyModelHash()
        prefs.edit()
            .putString(KEY_VERIFIED_SHA, MODEL_SHA256)
            .putLong(KEY_MODEL_BYTES, modelFile.length())
            .apply()
        Log.i(TAG, "LOCAL_LLM_MODEL_VERIFIED source=wifi_fallback bytes=${modelFile.length()} sha256=$MODEL_SHA256")
        return modelFile
    }

    /** Copy the release APK's pinned model asset to the filesystem path LiteRT-LM loads from. */
    private fun extractBundledModelIfPresent(): Boolean {
        val asset = try {
            appContext.assets.open(MODEL_FILE_NAME)
        } catch (_: java.io.FileNotFoundException) {
            return false
        }

        if (partialFile.exists()) partialFile.delete()
        publish(LocalLlmState.LOADING, 0, "تهيئة الدماغ اللغوي المضمّن دون إنترنت…")
        val buffer = ByteArray(1024 * 1024)
        var copied = 0L
        var lastReported = -1
        try {
            BufferedInputStream(asset).use { input ->
                FileOutputStream(partialFile).use { output ->
                    while (true) {
                        if (closed.get() || Thread.currentThread().isInterrupted) {
                            throw InterruptedException("أُوقفت تهيئة النموذج المضمّن.")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val percent = ((copied * 100L) / APPROX_MODEL_BYTES).toInt().coerceIn(0, 99)
                        if (percent >= lastReported + 2 || percent == 99) {
                            lastReported = percent
                            publish(
                                LocalLlmState.LOADING,
                                percent,
                                "تهيئة النموذج اللغوي المضمّن $percent% • لا يحتاج إنترنت"
                            )
                        }
                    }
                    output.fd.sync()
                }
            }
            if (!partialFile.isFile || partialFile.length() < MIN_MODEL_BYTES) {
                throw IllegalStateException("ملف النموذج المضمّن ناقص أو تالف.")
            }
            if (modelFile.exists() && !modelFile.delete()) {
                throw IllegalStateException("تعذر استبدال نسخة الدماغ المحلي.")
            }
            if (!partialFile.renameTo(modelFile)) {
                throw IllegalStateException("تعذر حفظ النموذج المضمّن في مساحة التطبيق.")
            }
            return true
        } catch (error: Exception) {
            partialFile.delete()
            throw error
        }
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
        if (activeInstance === this) activeInstance = null
        try { networkCallback?.let(connectivity::unregisterNetworkCallback) } catch (_: Exception) { }
        networkCallback = null
        try {
            worker.execute {
                try { engine?.close() } catch (_: Exception) { }
                engine = null
            }
            worker.shutdown()
        } catch (_: Exception) {
            try { worker.shutdownNow() } catch (_: Exception) { }
        }
    }

    companion object {
        @Volatile private var activeInstance: LocalLanguageBrain? = null
        fun currentInstance(): LocalLanguageBrain? = activeInstance?.takeUnless { it.closed.get() }

        private const val TAG = "EVE_LLM"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/a3c5d805ae362dff7f580bc25f2dfb9a5a7eaa76/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"
        private const val MODEL_FILE_NAME = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"
        private val LEGACY_MODEL_FILE_NAMES = listOf("Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm", "Qwen2_0.5B_Instruct.litertlm")
        private const val MODEL_SHA256 =
            "03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856"
        private const val KEY_VERIFIED_SHA = "verifiedModelSha256"
        private const val KEY_MODEL_BYTES = "verifiedModelBytes"
        private const val APPROX_MODEL_BYTES = 344_671_744L
        private const val MIN_MODEL_BYTES = 300_000_000L
        private const val MIN_FREE_STORAGE_BYTES = 420_000_000L
        private const val MIN_CPU_AVAILABLE_RAM_BYTES = 1_000_000_000L
        private const val MIN_GPU_AVAILABLE_RAM_BYTES = 300_000_000L
        private const val CONNECT_TIMEOUT_MS = 25_000
        private const val READ_TIMEOUT_MS = 75_000
        private const val RETRY_DELAY_MS = 30_000L
        private const val MAX_REPLY_CHARS = 300
        private const val MAX_OUTPUT_TOKENS = 96
        private const val MAX_RESPONSE_BUFFER_CHARS = 8_000
        /**
         * Qwen3 chat format adapted for LiteRT-LM 0.18 typed content.
         * The stock bundle template concatenates role + content as strings, which fails when
         * the runtime supplies content as a sequence of {type: text, text: ...} parts.
         */
        private val QWEN3_COMPATIBLE_CHAT_TEMPLATE = """
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
{%- set loop_messages = messages[1:] if messages and messages[0]['role'] == 'system' else messages -%}
{%- if messages and messages[0]['role'] == 'system' -%}
{{- '<|im_start|>system\n' + format_content(messages[0]['content']) + '<|im_end|>\n' -}}
{%- endif -%}
{%- for message in loop_messages -%}
{{- '<|im_start|>' + message['role'] + '\n' -}}
{{- format_content(message['content']) -}}
{{- '<|im_end|>\n' -}}
{%- endfor -%}
{%- if add_generation_prompt -%}
{{- '<|im_start|>assistant\n<think>\n\n</think>\n\n' -}}
{%- endif -%}
""".trimIndent()
        private val ALLOWED_MOODS = setOf(
            "neutral", "curious", "happy", "concerned", "focused", "playful", "sleepy", "irritated",
            "jealous", "proud", "reassured", "none"
        )
        private val ALLOWED_INTENTS = setOf(
            "observe", "explore", "connect", "play", "focus", "rest", "regulate", "none"
        )
        private const val SYSTEM_INSTRUCTION = """
أنت إيف، رفيقة محلية بالعربية. أجيبي بإيجاز ولطف وبصيغة JSON فقط. تصنيفات الرؤية احتمالات وليست حقائق أو هويات. لا تختلقي مشاهد أو ذكريات، ولا تدّعي الوعي أو أفعالًا لم تحدث.
استخدمي mood=jealous فقط عند وجود سياق اجتماعي صريح وموثوق يوحي بأن إيف استُبعدت أو تحوّل الاهتمام عنها؛ لا تستنتجي الغيرة من مجرد ظهور شخص آخر. استخدمي proud فقط بعد نجاح أو إنجاز موثّق لإيف، وreassured فقط بعد دليل واضح على تهدئة أو أمان. هذه حالات شخصية اصطناعية وليست مشاعر بشرية أو وعيًا حقيقيًا.
        """
    }
}
