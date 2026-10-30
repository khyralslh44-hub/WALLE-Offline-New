package com.eve.robot

import ai.moonshine.voice.JNI
import ai.moonshine.voice.MicTranscriber
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Compact offline Arabic speech recognition using Moonshine Tiny Streaming (34M parameters).
 *
 * The model is bundled under assets/moonshine/ar, copied once to app-private storage, and loaded
 * through modelsFrom(). No first-run network download is required and no transcript is sent away.
 */
class OfflineArabicSpeech(
    context: Context,
    private val onTranscript: (String) -> Unit,
    private val onStatus: (String) -> Unit
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val permissionContext = context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "eve-moonshine-arabic").apply { isDaemon = true }
    }

    @Volatile private var closed = false
    @Volatile private var preparing = false
    @Volatile private var transcriber: MicTranscriber? = null

    fun start() {
        if (closed || preparing || transcriber?.isRunning == true) return
        preparing = true
        report("OFFLINE_SPEECH_MODEL_LOADING language=ar engine=moonshine-tiny-streaming")
        try {
            worker.execute {
                var candidate: MicTranscriber? = null
                try {
                    val modelDirectory = ensureBundledModelCopied()
                    candidate = MicTranscriber(permissionContext)
                        .language("ar")
                        .modelArch(JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING)
                        .modelsFrom(modelDirectory)
                        .onText { /* Partial phrases are not executed as commands. */ }
                        .onLine { line ->
                            val text = line.text?.trim().orEmpty()
                            if (text.isNotBlank() && !closed) {
                                Log.i(TAG, "RECOGNIZED_OFFLINE_ARABIC text=$text")
                                mainHandler.post { if (!closed) onTranscript(text) }
                            }
                        }
                        .onError { error ->
                            Log.e(TAG, "Moonshine Arabic streaming transcription error.", error)
                            report("OFFLINE_SPEECH_AUDIO_ERROR retrying=true")
                        }

                    // Both methods block; this worker is deliberately not the UI thread.
                    candidate.load()
                    if (closed) {
                        candidate.close()
                        candidate = null
                        return@execute
                    }
                    transcriber = candidate
                    candidate.start()
                    candidate = null
                    report("OFFLINE_SPEECH_LISTENING language=ar engine=moonshine-tiny-streaming")
                } catch (error: Exception) {
                    Log.e(TAG, "Bundled Moonshine Arabic model could not be loaded.", error)
                    try { candidate?.close() } catch (_: Exception) { }
                    if (!closed) report("OFFLINE_SPEECH_FAILED reason=moonshine_model_or_microphone")
                } finally {
                    preparing = false
                }
            }
        } catch (error: Exception) {
            preparing = false
            Log.e(TAG, "Could not schedule Moonshine model loading.", error)
            report("OFFLINE_SPEECH_FAILED reason=model_start")
        }
    }

    /** Copy all .ort and tokenizer assets once. All extraction is inside app-private storage. */
    @Synchronized
    private fun ensureBundledModelCopied(): File {
        val target = File(appContext.filesDir, MODEL_DIRECTORY)
        if (isCompleteModel(target)) return target

        val staging = File(appContext.filesDir, "${MODEL_DIRECTORY}.extracting")
        if (staging.exists() && !staging.deleteRecursively()) {
            throw IOException("Could not remove incomplete Moonshine model copy.")
        }
        if (!staging.mkdirs()) throw IOException("Could not create Moonshine model staging folder.")

        try {
            copyAssetTree(MODEL_ASSET_DIRECTORY, staging)
            if (!isCompleteModel(staging)) {
                throw IOException("Bundled Moonshine model is missing ORT weights or tokenizer.bin.")
            }
            if (target.exists() && !target.deleteRecursively()) {
                throw IOException("Could not replace stale Moonshine model files.")
            }
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }

        if (!isCompleteModel(target)) throw IOException("Moonshine model copy failed integrity checks.")
        Log.i(TAG, "MOONSHINE_ARABIC_MODEL_COPIED bytes=${directoryBytes(target)} files=${target.walkTopDown().count { it.isFile }}")
        return target
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isNotEmpty()) {
            if (!destination.exists() && !destination.mkdirs()) {
                throw IOException("Could not create model subdirectory: $assetPath")
            }
            children.forEach { child ->
                copyAssetTree("$assetPath/$child", File(destination, child))
            }
        } else {
            destination.parentFile?.let { if (!it.exists() && !it.mkdirs()) {
                throw IOException("Could not create asset parent folder.")
            } }
            appContext.assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
        }
    }

    private fun isCompleteModel(directory: File): Boolean {
        if (!directory.isDirectory) return false
        val files = directory.walkTopDown().filter { it.isFile }.toList()
        return files.any { it.extension == "ort" } &&
            files.any { it.name == "tokenizer.bin" } &&
            files.none { it.length() <= 0L }
    }

    private fun directoryBytes(directory: File): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun report(message: String) {
        Log.i(TAG, message)
        mainHandler.post { if (!closed) onStatus(message) }
    }

    fun stop() {
        try { transcriber?.stop() } catch (error: Exception) {
            Log.w(TAG, "Could not stop Moonshine microphone transcriber.", error)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            worker.execute {
                try { transcriber?.close() } catch (error: Exception) {
                    Log.w(TAG, "Could not close Moonshine transcriber.", error)
                }
                transcriber = null
            }
            worker.shutdown()
        } catch (error: Exception) {
            try { transcriber?.close() } catch (_: Exception) { }
            transcriber = null
            worker.shutdownNow()
        }
    }

    companion object {
        private const val TAG = "EVE_AUDIO"
        private const val MODEL_ASSET_DIRECTORY = "moonshine/ar"
        private const val MODEL_DIRECTORY = "eve-moonshine-arabic"
    }
}
