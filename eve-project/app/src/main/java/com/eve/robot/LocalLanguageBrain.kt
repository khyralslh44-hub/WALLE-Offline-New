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
    val sceneObservationAgeMs: Long = Long.MAX_VALUE
)

data class LocalLlmResult(
    val replyArabic: String,
    val moodHint: String?,
    val intentHint: String?,
    val reflectionArabic: String,
    val confidence: Float
)

/**
 * EVE's bespoke compact Arabic dialogue engine.
 *
 * Understanding and reply composition run in Kotlin on-device. There is no bundled
 * generative checkpoint, tokenizer download, llama.cpp runtime, or network fallback.
 * The small feature-based Arabic intent model and grounded response composer are the
 * primary path for user dialogue, curiosity, and game interaction.
 */
class LocalLanguageBrain(
    context: Context,
    private val statusListener: (LocalLlmState, Int, String) -> Unit,
    // Tests can inject an inline dispatcher; the app keeps main-thread UI callbacks by default.
    private val callbackDispatcher: ((() -> Unit) -> Unit)? = null
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "eve-tiny-arabic-brain").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val queuedInferenceCount = AtomicInteger(0)
    private val backgroundInferenceQueued = AtomicBoolean(false)
    private val generativeModel = EveLiteRtLanguageModel(context.applicationContext)
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
                    publish(LocalLlmState.READY, 100, "نموذج Qwen3 المحلي جاهز • التوليد على الهاتف دون إنترنت")
                } else {
                    publish(
                        LocalLlmState.WAITING_FOR_WIFI,
                        0,
                        "نموذج لغوي حقيقي غير مثبت؛ انقر شريط الحالة لتنزيله مرة واحدة (نحو 329 ميغابايت)."
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
        if (closed.get() || isReady || !modelLoadQueued.compareAndSet(false, true)) return
        try {
            worker.execute {
                try {
                    publish(LocalLlmState.DOWNLOADING, 0, "تنزيل نموذج Qwen3 الصغير لأول مرة؛ سيبقى محفوظاً على الهاتف.")
                    generativeModel.downloadAndLoad { percent ->
                        publish(LocalLlmState.DOWNLOADING, percent, "تنزيل النموذج اللغوي المحلي")
                    }
                    publish(LocalLlmState.LOADING, 0, "اكتمل التنزيل؛ جارٍ تشغيل النموذج على الهاتف.")
                    publish(LocalLlmState.READY, 100, "نموذج Qwen3 المحلي جاهز • يعمل دون إنترنت")
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
        } ?: "لا توجد نتيجة رؤية حديثة موثوقة"
        val freshScene = context.latestSceneSummary.takeIf {
            it.isNotBlank() && context.sceneObservationAgeMs in 0L..12_000L
        } ?: "لا يوجد وصف حديث للمشهد"
        return buildString {
            append("رسالة المستخدم: ").append(userText.take(700)).append("\n")
            append("مزاج إيف: ").append(context.currentMood).append("؛ السبب: ").append(context.moodReason.take(120)).append("\n")
            append("سمات الشخصية: دفء ").append(context.personality.warmth)
                .append("، فضول ").append(context.personality.curiosityBias)
                .append("، مرح ").append(context.personality.playfulness).append("\n")
            append("آخر دليل بصري حديث: ").append(freshVision)
                .append(" (الثقة ").append(context.visionConfidence).append(")\n")
            append("ملخص المشهد الحديث: ").append(freshScene).append("\n")
            append("المفاهيم التي تعلمتها: ").append(context.knownConcepts.take(12).joinToString("، ")).append("\n")
            append("الأشخاص الذين عرّفني بهم المستخدم: ").append(context.knownPeople.take(180)).append("\n")
            append("ملاحظاتي الأخيرة: ").append(context.recentObservations.takeLast(4).joinToString("؛ ").take(260)).append("\n")
            append("آخر حواراتنا: ").append(context.recentDialogues.takeLast(3).joinToString(" | ").take(320)).append("\n")
            append("تأملاتي الأخيرة: ").append(context.recentReflections.takeLast(3).joinToString(" | ").take(240)).append("\n")
            append("اكتبي ردك الطبيعي الآن، ولا تعرضي هذه البيانات كقائمة.")
        }
    }

    private fun generateWithFallback(
        prompt: String,
        fallback: () -> LocalLlmResult,
        intent: String = "conversation"
    ): LocalLlmResult {
        return try {
            val generated = generativeModel.generate(prompt).trim()
            if (generated.isBlank()) fallback() else {
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
                    reflectionArabic = "استجابة مولدة محلياً بواسطة Qwen3-0.6B اعتماداً على سياق إيف الحالي.",
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
