package com.eve.robot

import android.content.Context
import android.util.Log
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Real autoregressive Arabic text generation through llama.cpp.
 * The compact Arabic GGUF is packaged by CI at assets/models/eve-arabic-50m-q4.gguf.
 * It is copied to app-private storage and inferred locally. There is no network client.
 */
internal class EveArabicLlmRuntime(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val modelMutex = Mutex()
    @Volatile private var loadedModel: LlamaModel? = null

    suspend fun answerUser(
        rawText: String,
        context: LocalLlmContext,
        maxOutputTokens: Int
    ): LocalLlmResult? {
        val userText = rawText.trim().take(800)
        if (userText.isBlank()) return null

        return try {
            val model = loadModel() ?: return null
            val result = Llama.complete(
                model = model,
                prompt = userText,
                systemPrompt = EveArabicPromptBuilder.systemPrompt(context),
                maxTokens = maxOutputTokens.coerceIn(24, 48)
            )
            val answer = EveArabicPromptBuilder.cleanGeneratedText(result.text)
            if (answer.isBlank() || answer.none { it in '\u0600'..'\u06FF' }) {
                Log.w(TAG, "MODEL_OUTPUT_REJECTED reason=empty_or_non_arabic")
                return null
            }

            Log.i(
                TAG,
                "ARABIC_LLM_GENERATED tokens=${result.tokensGenerated} " +
                    "tokensPerSecond=${result.tokensPerSecond} chars=${answer.length} offline=true"
            )
            // Keep the character's emotional state machine in charge of affect. The LLM
            // writes the natural-language reply, while the deterministic companion planner
            // supplies only bounded mood/goal hints and internal reflection context.
            val behavior = EveTinyArabicBrain.answerUser(userText, context)
            LocalLlmResult(
                replyArabic = answer,
                moodHint = behavior.moodHint ?: context.currentMood.lowercase(),
                intentHint = behavior.intentHint ?: "connect",
                reflectionArabic = behavior.reflectionArabic,
                // The current app result contract has no calibrated token-confidence field.
                // This is a neutral availability score, not a probability of factual correctness.
                confidence = 0.55f
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LinkageError) {
            // For example, the AAR's ARM64 JNI library is unavailable on an x86 emulator.
            Log.w(TAG, "ARABIC_LLM_NATIVE_UNAVAILABLE fallback=EveTinyArabicBrain", failure)
            null
        } catch (failure: Exception) {
            // Missing asset or model load/inference failure returns to the deterministic fallback.
            Log.w(TAG, "ARABIC_LLM_UNAVAILABLE fallback=EveTinyArabicBrain", failure)
            null
        }
    }

    private suspend fun loadModel(): LlamaModel? = modelMutex.withLock {
        loadedModel?.takeIf { it.isLoaded }?.let { return@withLock it }

        val modelFile = File(appContext.filesDir, "models/eve-arabic-50m-q4.gguf")
        withContext(Dispatchers.IO) {
            if (!modelFile.isFile || modelFile.length() < MIN_MODEL_BYTES ||
                modelFile.length() >= MAX_MODEL_BYTES
            ) {
                modelFile.parentFile?.mkdirs()
                appContext.assets.open(MODEL_ASSET).use { input ->
                    modelFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        if (!modelFile.isFile || modelFile.length() !in MIN_MODEL_BYTES until MAX_MODEL_BYTES) {
            throw IllegalStateException("The bundled EVE Arabic 50M GGUF asset is missing, oversized, or truncated.")
        }

        val config = LlamaConfig(
            contextSize = 1_024,
            threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
            gpuLayers = 0,
            temperature = 0.70f,
            topP = 0.80f,
            topK = 20
        )
        Llama.loadModel(modelPath = modelFile.absolutePath, config = config).also {
            loadedModel = it
            Log.i(
                TAG,
                "ARABIC_LLM_LOADED bytes=${modelFile.length()} context=1024 threads=${config.threads} " +
                    "backend=llama.cpp cpu_only=true model=Arabic-50M"
            )
        }
    }

    override fun close() {
        val current = loadedModel
        loadedModel = null
        if (current != null) {
            runCatching { Llama.releaseModel(current) }
                .onFailure { Log.w(TAG, "ARABIC_LLM_RELEASE_FAILED", it) }
        }
    }

    companion object {
        private const val TAG = "EVE_ARABIC_50M_LLM"
        private const val MODEL_ASSET = "models/eve-arabic-50m-q4.gguf"
        private const val MIN_MODEL_BYTES = 20_000_000L
        private const val MAX_MODEL_BYTES = 50_000_000L
    }
}
