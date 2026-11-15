package com.eve.robot

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.exp
import kotlin.random.Random

/** EVE's compact GRU student, distilled from an open-weight Arabic-capable teacher at build time. */
internal class EvePrivateStudentLanguageModel(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val modelFile = File(appContext.filesDir, MODEL_FILE_NAME)
    private var interpreter: Interpreter? = null

    val isInstalled: Boolean
        get() = (modelFile.isFile && modelFile.length() >= MIN_VALID_BYTES) || bundledAssetAvailable()

    /** Copy the model bundled in the APK into app-private storage; never uses the network. */
    fun installBundledAndLoad(onProgress: (Int) -> Unit) {
        onProgress(0)
        installBundledAsset()
        onProgress(100)
        load()
    }

    fun load() {
        if (interpreter != null) return
        installBundledAsset()
        check(modelFile.isFile && modelFile.length() >= MIN_VALID_BYTES) {
            "أوزان نموذج إيف غير موجودة داخل هذا الإصدار من التطبيق."
        }
        val candidate = Interpreter(modelFile, Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        })
        check(candidate.inputTensorCount == 3 && candidate.outputTensorCount == 3) {
            "بنية نموذج إيف لا تطابق واجهة GRU المتوقعة."
        }
        interpreter = candidate
        Log.i(TAG, "EVE_PRIVATE_STUDENT_READY architecture=GRU-256-2L tokenizer=utf8-byte offline=true")
    }

    fun generate(prompt: String): String {
        val active = checkNotNull(interpreter) { "نموذج إيف الخاص لم يُحمّل بعد." }
        val promptBytes = prompt.toByteArray(Charsets.UTF_8).takeLast(MAX_CONTEXT_BYTES)
        var state1 = Array(1) { FloatArray(HIDDEN_SIZE) }
        var state2 = Array(1) { FloatArray(HIDDEN_SIZE) }
        val token = Array(1) { IntArray(1) }
        val logits = Array(1) { FloatArray(VOCAB_SIZE) }
        val nextState1 = Array(1) { FloatArray(HIDDEN_SIZE) }
        val nextState2 = Array(1) { FloatArray(HIDDEN_SIZE) }
        val outputs = hashMapOf<Int, Any>(0 to logits, 1 to nextState1, 2 to nextState2)

        token[0][0] = BOS
        active.runForMultipleInputsOutputs(arrayOf(token, state1, state2), outputs)
        state1 = nextState1.copyOfState()
        state2 = nextState2.copyOfState()
        for (byte in promptBytes) {
            token[0][0] = (byte.toInt() and 0xff) + 1
            active.runForMultipleInputsOutputs(arrayOf(token, state1, state2), outputs)
            state1 = nextState1.copyOfState()
            state2 = nextState2.copyOfState()
        }

        val generated = ByteArrayOutputStream()
        var next = sample(logits[0])
        for (step in 0 until MAX_NEW_TOKENS) {
            if (next == EOS || next == 0 || next !in 1..256) break
            generated.write(next - 1)
            token[0][0] = next
            active.runForMultipleInputsOutputs(arrayOf(token, state1, state2), outputs)
            state1 = nextState1.copyOfState()
            state2 = nextState2.copyOfState()
            next = sample(logits[0])
        }
        return generated.toByteArray().toString(Charsets.UTF_8)
            .replace("\u0000", "").trim().take(MAX_REPLY_CHARS)
    }

    private fun sample(values: FloatArray): Int {
        val candidates = values.indices.sortedByDescending { values[it] }.take(TOP_K)
        val max = candidates.maxOf { values[it] }
        val weights = candidates.map { exp(((values[it] - max) / TEMPERATURE).toDouble()) }
        val total = weights.sum()
        var draw = Random.nextDouble() * total
        for (i in candidates.indices) {
            draw -= weights[i]
            if (draw <= 0.0) return candidates[i]
        }
        return candidates.first()
    }

    private fun bundledAssetAvailable(): Boolean = try {
        appContext.assets.open(MODEL_FILE_NAME).use { }
        true
    } catch (_: Exception) {
        false
    }

    private fun installBundledAsset() {
        if (modelFile.isFile && modelFile.length() >= MIN_VALID_BYTES) return
        check(bundledAssetAvailable()) {
            "أوزان نموذج إيف غير مضمّنة داخل هذا الإصدار من التطبيق."
        }
        val temporary = File(appContext.filesDir, "$MODEL_FILE_NAME.part")
        temporary.delete()
        try {
            appContext.assets.open(MODEL_FILE_NAME).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            check(temporary.length() >= MIN_VALID_BYTES) {
                "ملف أوزان إيف المضمّن غير مكتمل."
            }
            if (modelFile.exists()) modelFile.delete()
            check(temporary.renameTo(modelFile)) {
                "تعذر تجهيز أوزان إيف المضمّنة في مساحة التطبيق."
            }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }

    private fun Array<FloatArray>.copyOfState(): Array<FloatArray> =
        Array(size) { this[it].copyOf() }

    companion object {
        private const val TAG = "EVE_PRIVATE_STUDENT"
        private const val MODEL_FILE_NAME = "eve_private_student.tflite"
        private const val MIN_VALID_BYTES = 100_000L
        private const val HIDDEN_SIZE = 256
        private const val VOCAB_SIZE = 259
        private const val BOS = 257
        private const val EOS = 258
        private const val MAX_CONTEXT_BYTES = 360
        private const val MAX_NEW_TOKENS = 420
        private const val MAX_REPLY_CHARS = 900
        private const val TOP_K = 8
        private const val TEMPERATURE = 0.72f
    }
}
