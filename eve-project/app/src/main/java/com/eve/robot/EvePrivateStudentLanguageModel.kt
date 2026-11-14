package com.eve.robot

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.exp
import kotlin.random.Random

/** EVE's randomly initialized, EVE-trained byte-level GRU student; no pretrained LLM weights. */
internal class EvePrivateStudentLanguageModel(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val modelFile = File(appContext.filesDir, MODEL_FILE_NAME)
    private var interpreter: Interpreter? = null

    val isInstalled: Boolean
        get() = modelFile.isFile && modelFile.length() >= MIN_VALID_BYTES

    fun downloadAndLoad(onProgress: (Int) -> Unit) {
        if (!isInstalled) download(onProgress)
        load()
    }

    fun load() {
        if (interpreter != null) return
        check(isInstalled) { "نموذج إيف الخاص غير مثبت أو أن تنزيله غير مكتمل." }
        val candidate = Interpreter(modelFile, Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        })
        check(candidate.inputTensorCount == 2 && candidate.outputTensorCount == 2) {
            "بنية نموذج إيف لا تطابق واجهة GRU المتوقعة."
        }
        interpreter = candidate
        Log.i(TAG, "EVE_PRIVATE_STUDENT_READY architecture=GRU-192 tokenizer=utf8-byte offline=true")
    }

    fun generate(prompt: String): String {
        val active = checkNotNull(interpreter) { "نموذج إيف الخاص لم يُحمّل بعد." }
        val promptBytes = prompt.toByteArray(Charsets.UTF_8).takeLast(MAX_CONTEXT_BYTES)
        var state = Array(1) { FloatArray(HIDDEN_SIZE) }
        val token = Array(1) { IntArray(1) }
        val logits = Array(1) { FloatArray(VOCAB_SIZE) }
        val nextState = Array(1) { FloatArray(HIDDEN_SIZE) }
        val outputs = hashMapOf<Int, Any>(0 to logits, 1 to nextState)

        token[0][0] = BOS
        active.runForMultipleInputsOutputs(arrayOf(token, state), outputs)
        state = nextState.copyOfState()
        for (byte in promptBytes) {
            token[0][0] = (byte.toInt() and 0xff) + 1
            active.runForMultipleInputsOutputs(arrayOf(token, state), outputs)
            state = nextState.copyOfState()
        }

        val generated = ByteArrayOutputStream()
        var next = sample(logits[0])
        for (step in 0 until MAX_NEW_TOKENS) {
            if (next == EOS || next == 0 || next !in 1..256) break
            generated.write(next - 1)
            token[0][0] = next
            active.runForMultipleInputsOutputs(arrayOf(token, state), outputs)
            state = nextState.copyOfState()
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

    private fun download(onProgress: (Int) -> Unit) {
        val temporary = File(appContext.filesDir, "\${MODEL_FILE_NAME}.part")
        temporary.delete()
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 90_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "EVE-Android/1.0")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("تعذر تنزيل نموذج إيف: HTTP \${connection.responseCode}")
            }
            val expected = connection.contentLengthLong
            var copied = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val percent = if (expected > 0) (copied * 100L / expected).toInt().coerceIn(0, 99) else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            check(copied >= MIN_VALID_BYTES && (expected <= 0 || copied == expected)) {
                "ملف نموذج إيف غير مكتمل؛ أعد المحاولة."
            }
            if (modelFile.exists()) modelFile.delete()
            check(temporary.renameTo(modelFile)) { "تعذر حفظ نموذج إيف في مساحة التطبيق." }
            onProgress(100)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
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
        private const val MODEL_URL =
            "https://github.com/khyralslh44-hub/WALLE-Offline-New/releases/download/eve-private-student/eve_private_student.tflite"
        private const val MIN_VALID_BYTES = 100_000L
        private const val HIDDEN_SIZE = 192
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
