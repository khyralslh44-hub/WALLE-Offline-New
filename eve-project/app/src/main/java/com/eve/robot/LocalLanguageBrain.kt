package com.eve.robot

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class LocalLlmState { WAITING_FOR_WIFI, DOWNLOADING, LOADING, READY, ERROR }

data class LocalLlmContext(
    val latestVisionLabel: String? = null,
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
 * Local language facade for EVE.
 *
 * User dialogue first uses the bundled Arabic autoregressive GGUF model through llama.cpp.
 * If its asset or ARM64 native runtime is unavailable, the existing tiny intent/template engine
 * remains an offline fallback. Camera curiosity and game callers keep their stable APIs.
 */
class LocalLanguageBrain(
    context: Context,
    private val statusListener: (LocalLlmState, Int, String) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val generativeArabic = EveArabicLlmRuntime(appContext)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "eve-tiny-arabic-brain").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val queuedInferenceCount = AtomicInteger(0)
    private val backgroundInferenceQueued = AtomicBoolean(false)

    @Volatile private var state = LocalLlmState.WAITING_FOR_WIFI
    @Volatile private var statusMessage = "تهيئة الدماغ العربي الصغير…"

    init {
        activeInstance = this
    }

    val isReady: Boolean
        get() = !closed.get() && state == LocalLlmState.READY

    val currentState: LocalLlmState
        get() = state

    fun start() {
        if (closed.get()) return
        val generativeModelBundled = runCatching {
            appContext.assets.open("models/eve-arabic-50m-q4.gguf").use { }
            true
        }.getOrDefault(false)
        val readyMessage = if (generativeModelBundled) {
            "النموذج اللغوي العربي التوليدي موجود • يعمل محلياً دون إنترنت ويُحمّل عند أول محادثة"
        } else {
            "الدماغ المحلي الاحتياطي جاهز • نموذج التوليد غير مضمّن في هذا البناء"
        }
        publish(LocalLlmState.READY, 100, readyMessage)
        Log.i(
            TAG,
            "LOCAL_LANGUAGE_BRAIN_READY generativeModelBundled=$generativeModelBundled " +
                "fallback=hashed_char_ngram_prototypes offline=true"
        )
    }

    fun ensureReady() {
        if (!closed.get() && !isReady) start()
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
            // The autoregressive Arabic model is the primary generator. The tiny intent/template
            // engine remains only as a local fallback if the GGUF/native runtime cannot load.
            runBlocking {
                generativeArabic.answerUser(userText, context, maxOutputTokens)
            } ?: EveTinyArabicBrain.answerUser(userText, context)
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
            EveTinyArabicBrain.commentOnGame(
                gameName, outcome, safeDetail, playerScore, eveScore, round
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
        main.post {
            if (!closed.get()) statusListener(nextState, percent.coerceIn(0, 100), message)
        }
    }

    private fun deliver(
        callback: (Result<LocalLlmResult>) -> Unit,
        result: Result<LocalLlmResult>
    ) {
        main.post {
            if (!closed.get()) callback(result)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (activeInstance === this) activeInstance = null
        try {
            // Release the native model on the same serialized worker after any active inference.
            worker.execute { generativeArabic.close() }
            worker.shutdown()
        } catch (_: Exception) {
            try { generativeArabic.close() } catch (_: Exception) { }
            try { worker.shutdownNow() } catch (_: Exception) { }
        }
    }

    companion object {
        @Volatile private var activeInstance: LocalLanguageBrain? = null
        fun currentInstance(): LocalLanguageBrain? = activeInstance?.takeUnless { it.closed.get() }

        private const val TAG = "EVE_TINY_BRAIN"
        private const val MAX_PENDING_INFERENCES = 4
    }
}
