package com.eve.robot

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class LocalLlmState { WAITING_FOR_WIFI, DOWNLOADING, LOADING, READY, ERROR }

data class LocalLlmContext(
    // Raw candidate emitted by the visual classifier; never replaced by a user-taught name.
    val latestVisionLabel: String? = null,
    val latestLearnedConcept: String? = null,
    val knownConcepts: List<String> = emptyList(),
    val visionConfidence: Float = 0f,
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
    val latestSceneSummary: String = "",
    val sceneObservationAgeMs: Long = Long.MAX_VALUE,
    // A quality-gated, consented wellbeing cue; never a diagnosis.
    val wellbeingObservation: String = ""
)

data class LocalLlmResult(
    val replyArabic: String,
    val moodHint: String?,
    val intentHint: String?,
    val reflectionArabic: String,
    val confidence: Float
)

/**
 * EVE's private student language brain runs through TensorFlow Lite when its trained artifact is installed;
 * the compact Arabic intent model and grounded composer remain the offline fallback.
 */
class LocalLanguageBrain(
    context: Context,
    private val statusListener: (LocalLlmState, Int, String) -> Unit,
    // Tests can inject an inline dispatcher; the app keeps main-thread UI callbacks by default.
    private val callbackDispatcher: ((() -> Unit) -> Unit)? = null
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "eve-local-language-brain").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val queuedInferenceCount = AtomicInteger(0)
    private val backgroundInferenceQueued = AtomicBoolean(false)
    private val generativeModel = EvePrivateStudentLanguageModel(context.applicationContext)
    private val startupQueued = AtomicBoolean(false)
    private val modelLoadQueued = AtomicBoolean(false)

    @Volatile private var state = LocalLlmState.WAITING_FOR_WIFI
    @Volatile private var statusMessage = "تهيئة الدماغ العربي الصغير…"

    init {
        activeInstance = this
    }

    // The compact Arabic engine remains available as a fallback while the generative model is absent/downloading.
    val isReady: Boolean
        get() = !closed.get() && state != LocalLlmState.DOWNLOADING && state != LocalLlmState.LOADING

    val isGenerativeModelReady: Boolean
        get() = !closed.get() && state == LocalLlmState.READY

    val currentState: LocalLlmState
        get() = state

    fun start() {
        if (closed.get() || state == LocalLlmState.READY || state == LocalLlmState.DOWNLOADING ||
            state == LocalLlmState.LOADING || !startupQueued.compareAndSet(false, true)
        ) return
        worker.execute {
            try {
                if (generativeModel.isInstalled) {
                    publish(LocalLlmState.LOADING, 0, "تم العثور على النموذج المحلي؛ جارٍ تحميله.")
                    generativeModel.load()
                    publish(LocalLlmState.READY, 100, "نموذج إيف الخاص جاهز • التوليد على الهاتف دون إنترنت")
                } else {
                    publish(
                        LocalLlmState.WAITING_FOR_WIFI,
                        0,
                        "نموذج إيف الخاص غير مثبت؛ انقر شريط الحالة لتنزيله بعد اكتمال التدريب."
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "ON_DEVICE_LLM_LOAD_FAILED", error)
                publish(LocalLlmState.ERROR, 0, error.message ?: "تعذر تحميل النموذج المحلي.")
            } finally {
                startupQueued.set(false)
            }
        }
    }

    val isModelInstalled: Boolean
        get() = generativeModel.isInstalled

    fun downloadAndLoad() {
        if (closed.get() || isGenerativeModelReady || state == LocalLlmState.DOWNLOADING ||
            state == LocalLlmState.LOADING || !modelLoadQueued.compareAndSet(false, true)
        ) return
        try {
            worker.execute {
                try {
                    publish(LocalLlmState.DOWNLOADING, 0, "تنزيل نموذج إيف الخاص لأول مرة؛ سيبقى محفوظاً على الهاتف.")
                    generativeModel.downloadAndLoad { percent ->
                        publish(LocalLlmState.DOWNLOADING, percent, "تنزيل النموذج اللغوي المحلي")
                    }
                    publish(LocalLlmState.LOADING, 0, "اكتمل التنزيل؛ جارٍ تشغيل النموذج على الهاتف.")
                    publish(LocalLlmState.READY, 100, "نموذج إيف الخاص جاهز • يعمل دون إنترنت")
                } catch (error: Exception) {
                    Log.e(TAG, "ON_DEVICE_LLM_DOWNLOAD_OR_LOAD_FAILED", error)
                    publish(LocalLlmState.ERROR, 0, error.message ?: "تعذر تنزيل أو تشغيل النموذج.")
                } finally {
                    modelLoadQueued.set(false)
                }
            }
        } catch (error: Exception) {
            modelLoadQueued.set(false)
            publish(LocalLlmState.ERROR, 0, error.message ?: "تعذر بدء تنزيل النموذج.")
        }
    }

    fun ensureReady() {
        if (!closed.get() && state != LocalLlmState.READY &&
            state != LocalLlmState.DOWNLOADING && state != LocalLlmState.LOADING
        ) start()
    }

    fun refreshStatus() {
        if (closed.get()) return
        publish(state, if (isReady) 100 else 0, statusMessage)
    }

    fun answerUser(
        userText: String,
        context: LocalLlmContext,
        maxOutputTokens: Int = 32,
        callback: (Result<LocalLlmResult>) -> Unit
    ) {
        enqueue(background = false, operation = {
            generateWithFallback(
                buildConversationPrompt(userText, context),
                fallback = { EveTinyArabicBrain.answerUser(userText, context) }
            )
        }, callback = callback)
    }

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
        val safeDetail = buildString {
            append(detail.trim().take(100))
            val history = recentResults.takeLast(2).joinToString("؛ ").take(90)
            if (history.isNotBlank()) append(" • نتائج سابقة: ").append(history)
            append(" • مستوى التحدي: ").append(difficulty.coerceIn(1, 3))
        }
        enqueue(background = false, operation = {
            generateWithFallback(
                "تحدثي عن هذه الجولة في لعبة $gameName. الحدث: $outcome. التفاصيل الفعلية: $safeDetail. " +
                    "النتيجة: اللاعب ${playerScore ?: "غير متاحة"}، إيف ${eveScore ?: "غير متاحة"}، الجولة ${round ?: "غير متاحة"}. " +
                    "مزاجك الحالي: ${context.currentMood}. علّقي على ما حدث بدقة ولا تخترعي حركة أو نتيجة.",
                fallback = {
                    EveGameDialogueGenerator.generate(
                        gameName, outcome, safeDetail, playerScore, eveScore, round, context
                    )
                },
                intent = "play"
            )
        }, callback = callback)
    }

    fun askFromCuriosity(
        context: LocalLlmContext,
        label: String,
        confidence: Float,
        sightings: Int,
        fallbackQuestion: String,
        callback: (Result<LocalLlmResult>) -> Unit
    ) {
        enqueue(background = true, operation = {
            EveTinyArabicBrain.askFromCuriosity(
                context, label, confidence, sightings, fallbackQuestion
            )
        }, callback = callback)
    }

    fun reflectOnObservation(
        context: LocalLlmContext,
        callback: (Result<LocalLlmResult>) -> Unit
    ) {
        enqueue(background = true, operation = {
            EveTinyArabicBrain.reflectOnObservation(context)
        }, callback = callback)
    }

    private fun buildConversationPrompt(userText: String, context: LocalLlmContext): String {
        val freshVision = context.latestVisionLabel?.takeIf {
            context.visionObservationAgeMs in 0L..12_000L
        } ?: "لا توجد ملاحظة بصرية حديثة"
        val freshScene = context.latestSceneSummary.takeIf {
            it.isNotBlank() && context.sceneObservationAgeMs in 0L..12_000L
        } ?: ""
        val memory = context.recentObservations.takeLast(2).joinToString("؛ ").take(55)
        val concepts = context.knownConcepts.take(5).joinToString("، ").take(45)
        return buildString {
            append("المستخدم: ").append(userText.take(115)).append("\n")
            append("سياق إيف: المزاج ").append(context.currentMood)
                .append("؛ الفضول ").append(context.personality.curiosityBias)
                .append("؛ الرؤية ").append(freshVision.take(35))
            if (freshScene.isNotBlank()) append("؛ المشهد ").append(freshScene.take(45))
            if (concepts.isNotBlank()) append("؛ معرفة ").append(concepts)
            if (memory.isNotBlank()) append("؛ تجربة ").append(memory)
            if (context.knownPeople.isNotBlank()) append("؛ أشخاص أتذكرهم ").append(context.knownPeople.take(100))
            val dialogueMemory = context.recentDialogues.takeLast(2).joinToString("؛ ").take(100)
            if (dialogueMemory.isNotBlank()) append("؛ حوار سابق ").append(dialogueMemory)
            if (context.wellbeingObservation.isNotBlank()) append("؛ ملاحظة للاطمئنان ").append(context.wellbeingObservation.take(180))
            append("\nإيف:")
        }
    }

    private fun generateWithFallback(
        prompt: String,
        fallback: () -> LocalLlmResult,
        intent: String = "conversation"
    ): LocalLlmResult {
        return try {
            val generated = generativeModel.generate(prompt).trim()
            if (generated.isBlank() || generated.count { it in '\u0600'..'\u06FF' } < 2) fallback() else {
                val lower = generated.lowercase()
                val mood = when {
                    listOf("سعيد", "فرح", "رائع", "أحب").any(lower::contains) -> "happy"
                    listOf("أزعج", "غاضب", "منزعج").any(lower::contains) -> "irritated"
                    listOf("فضول", "أتساءل", "أريد أن أعرف").any(lower::contains) -> "curious"
                    else -> null
                }
                LocalLlmResult(
                    replyArabic = generated.take(900),
                    moodHint = mood,
                    intentHint = intent,
                    reflectionArabic = "استجابة مولدة محلياً بواسطة EVE-GRU-192 اعتماداً على سياق إيف الحالي.",
                    confidence = 0.78f
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "ON_DEVICE_LLM_INFERENCE_FALLBACK", error)
            fallback()
        }
    }

    private fun enqueue(
        background: Boolean,
        operation: () -> LocalLlmResult,
        callback: (Result<LocalLlmResult>) -> Unit
    ) {
        if (closed.get()) {
            deliver(callback, Result.failure(IllegalStateException("تم إغلاق دماغ إيف المحلي.")))
            return
        }
        if (!isReady) start()

        // At most one low-priority vision/curiosity task may be queued. A user request can
        // always queue behind it and is never rejected merely because the worker is busy.
        if (background && (
                queuedInferenceCount.get() > 0 ||
                    !backgroundInferenceQueued.compareAndSet(false, true)
                )
        ) {
            deliver(
                callback,
                Result.failure(IllegalStateException("تم تأجيل التفكير الخلفي لإعطاء الأولوية للتفاعل."))
            )
            return
        }

        val pending = queuedInferenceCount.incrementAndGet()
        if (pending > MAX_PENDING_INFERENCES) {
            queuedInferenceCount.decrementAndGet()
            if (background) backgroundInferenceQueued.set(false)
            deliver(
                callback,
                Result.failure(IllegalStateException("قائمة الطلبات ممتلئة؛ أعد المحاولة بعد لحظة."))
            )
            return
        }

        try {
            worker.execute {
                try {
                    if (closed.get()) throw IllegalStateException("تم إغلاق دماغ إيف المحلي.")
                    val result = operation()
                    Log.i(
                        TAG,
                        "TINY_INFERENCE_OK background=$background intentConfidence=${result.confidence} " +
                            "replyChars=${result.replyArabic.length} offline=true"
                    )
                    deliver(callback, Result.success(result))
                } catch (error: Exception) {
                    Log.e(TAG, "TINY_INFERENCE_FAILED background=$background", error)
                    deliver(callback, Result.failure(error))
                } finally {
                    queuedInferenceCount.decrementAndGet()
                    if (background) backgroundInferenceQueued.set(false)
                }
            }
        } catch (error: Exception) {
            queuedInferenceCount.decrementAndGet()
            if (background) backgroundInferenceQueued.set(false)
            deliver(callback, Result.failure(error))
        }
    }

    private fun publish(nextState: LocalLlmState, percent: Int, message: String) {
        state = nextState
        statusMessage = message
        if (closed.get()) return
        dispatch {
            if (!closed.get()) statusListener(nextState, percent.coerceIn(0, 100), message)
        }
    }

    private fun deliver(
        callback: (Result<LocalLlmResult>) -> Unit,
        result: Result<LocalLlmResult>
    ) {
        dispatch {
            if (!closed.get()) callback(result)
        }
    }

    private fun dispatch(action: () -> Unit) {
        val injectedDispatcher = callbackDispatcher
        if (injectedDispatcher != null) {
            injectedDispatcher(action)
        } else {
            main.post { action() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (activeInstance === this) activeInstance = null
        // Close the conversation before the engine on the same owner worker, exactly once.
        try {
            worker.execute {
                try {
                    generativeModel.close()
                } catch (error: Exception) {
                    Log.w(TAG, "ON_DEVICE_LLM_CLOSE_FAILED", error)
                }
            }
        } catch (_: Exception) {
            // The worker may already be shutting down; no further inference can be submitted.
        }
        worker.shutdown()
    }

    companion object {
        @Volatile private var activeInstance: LocalLanguageBrain? = null
        fun currentInstance(): LocalLanguageBrain? = activeInstance?.takeUnless { it.closed.get() }

        private const val TAG = "EVE_TINY_BRAIN"
        private const val MAX_PENDING_INFERENCES = 4
    }
}
