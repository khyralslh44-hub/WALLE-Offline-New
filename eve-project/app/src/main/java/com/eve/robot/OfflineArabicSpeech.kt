package com.eve.robot

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import java.util.concurrent.Executors

/**
 * Fully local Arabic speech-to-text using the bundled Vosk Arabic model.
 *
 * This class does not synthesize words or send audio to a cloud service. It only emits
 * locally recognized text so MainActivity can map it to offline robot actions.
 */
class OfflineArabicSpeech(
    context: Context,
    private val onTranscript: (String) -> Unit,
    private val onStatus: (String) -> Unit
) : RecognitionListener {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recoveryExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    @Volatile private var preparing = false
    @Volatile private var speechService: SpeechService? = null
    @Volatile private var recognizer: Recognizer? = null
    @Volatile private var model: Model? = null

    private val retryListening = Runnable {
        if (!closed) {
            recoveryExecutor.execute { recoverAudioInput() }
        }
    }

    fun start() {
        if (closed || preparing || model != null) return
        preparing = true
        report("OFFLINE_SPEECH_MODEL_LOADING language=ar model=bundled_zip")
        try {
            recoveryExecutor.execute {
                try {
                    val modelDirectory = unpackBundledModelArchive()
                    val loadedModel = Model(modelDirectory.absolutePath)
                    mainHandler.post {
                        preparing = false
                        if (closed) {
                            try { loadedModel.close() } catch (_: Exception) { }
                        } else {
                            model = loadedModel
                            startRecognition()
                        }
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Bundled Arabic model archive could not be unpacked/loaded.", error)
                    mainHandler.post {
                        preparing = false
                        if (!closed) report("OFFLINE_SPEECH_FAILED reason=model_unpack")
                    }
                }
            }
        } catch (error: Exception) {
            preparing = false
            Log.e(TAG, "Could not schedule bundled Arabic model unpack.", error)
            report("OFFLINE_SPEECH_FAILED reason=model_start")
        }
    }

    /**
     * Extract the bundled model ZIP once. Extraction happens on the speech worker thread,
     * never the UI thread, and path validation prevents archive entries escaping private storage.
     * Existing model directories from previous installs are reused when their core files are intact.
     */
    private fun unpackBundledModelArchive(): File {
        val target = File(appContext.filesDir, MODEL_STORAGE_DIRECTORY)
        findModelRoot(target)?.let { return it }

        val staging = File(appContext.filesDir, MODEL_STORAGE_DIRECTORY + ".extracting")
        if (staging.exists() && !staging.deleteRecursively()) {
            throw IOException("Could not remove incomplete Arabic model extraction.")
        }
        if (!staging.mkdirs()) throw IOException("Could not create Arabic model staging directory.")

        try {
            appContext.assets.open(MODEL_ARCHIVE_ASSET).use { raw ->
                ZipInputStream(BufferedInputStream(raw, ZIP_BUFFER_SIZE)).use { zip ->
                    val buffer = ByteArray(ZIP_BUFFER_SIZE)
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val output = File(staging, entry.name)
                        val canonicalRoot = staging.canonicalPath + File.separator
                        val canonicalOutput = output.canonicalPath
                        if (!canonicalOutput.startsWith(canonicalRoot)) {
                            throw IOException("Rejected unsafe ZIP entry path.")
                        }

                        if (entry.isDirectory) {
                            if (!output.exists() && !output.mkdirs()) {
                                throw IOException("Could not create model directory.")
                            }
                        } else {
                            val parent = output.parentFile
                            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                                throw IOException("Could not create model parent directory.")
                            }
                            FileOutputStream(output).use { sink ->
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    if (count > 0) sink.write(buffer, 0, count)
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }

        val stagedRoot = findModelRoot(staging)
        if (stagedRoot == null) {
            staging.deleteRecursively()
            throw IOException("Extracted archive did not contain a complete Vosk model.")
        }
        val relativeRoot = stagedRoot.relativeTo(staging).path

        if (target.exists() && !target.deleteRecursively()) {
            staging.deleteRecursively()
            throw IOException("Could not replace the old Arabic model directory.")
        }
        if (!staging.renameTo(target)) {
            try {
                staging.copyRecursively(target, overwrite = true)
            } catch (error: Exception) {
                staging.deleteRecursively()
                throw IOException("Could not move the extracted Arabic model into private storage.", error)
            }
            staging.deleteRecursively()
        }

        val modelRoot = if (relativeRoot.isBlank() || relativeRoot == ".") target
            else File(target, relativeRoot)
        if (!isValidModelRoot(modelRoot)) {
            throw IOException("The extracted Arabic Vosk model failed integrity checks.")
        }
        Log.i(TAG, "OFFLINE_SPEECH_MODEL_EXTRACTED path=" + modelRoot.absolutePath)
        return modelRoot
    }

    private fun findModelRoot(base: File): File? {
        if (!base.isDirectory) return null
        if (isValidModelRoot(base)) return base
        return base.walkTopDown().maxDepth(5).firstOrNull { isValidModelRoot(it) }
    }

    private fun isValidModelRoot(directory: File): Boolean =
        directory.isDirectory &&
            File(directory, "conf/model.conf").isFile &&
            File(directory, "am/final.mdl").isFile &&
            File(directory, "graph/HCLG.fst").isFile

    @Synchronized
    private fun startRecognition() {
        if (closed || speechService != null) return
        val loadedModel = model ?: return
        try {
            val localRecognizer = Recognizer(loadedModel, SAMPLE_RATE_HZ)
            val localService = SpeechService(localRecognizer, SAMPLE_RATE_HZ)
            recognizer = localRecognizer
            speechService = localService
            if (!localService.startListening(this)) {
                speechService = null
                recognizer = null
                try { localService.shutdown() } catch (_: Exception) { }
                try { localRecognizer.close() } catch (_: Exception) { }
                throw IOException("Vosk microphone listener was already running.")
            }
            report("OFFLINE_SPEECH_LISTENING language=ar source=bundled_vosk")
        } catch (error: Exception) {
            Log.e(TAG, "Vosk could not start the local microphone recognizer.", error)
            report("OFFLINE_SPEECH_FAILED reason=audio_start")
            mainHandler.removeCallbacks(retryListening)
            mainHandler.postDelayed(retryListening, RETRY_DELAY_MS)
        }
    }

    private fun recoverAudioInput() {
        if (closed) return
        releaseAudioObjects(closeModel = false)
        if (!closed && model != null) startRecognition()
    }

    private fun releaseAudioObjects(closeModel: Boolean) {
        val service = speechService
        val localRecognizer = recognizer
        speechService = null
        recognizer = null
        if (service != null) {
            try { service.stop() } catch (error: Exception) {
                Log.w(TAG, "Stopping Vosk audio service failed.", error)
            }
            try { service.shutdown() } catch (error: Exception) {
                Log.w(TAG, "Releasing Vosk audio recorder failed.", error)
            }
        }
        try { localRecognizer?.close() } catch (error: Exception) {
            Log.w(TAG, "Closing Vosk recognizer failed.", error)
        }
        if (closeModel) {
            val loadedModel = model
            model = null
            try { loadedModel?.close() } catch (error: Exception) {
                Log.w(TAG, "Closing Vosk model failed.", error)
            }
        }
    }

    override fun onPartialResult(hypothesis: String) {
        // Partial transcripts are intentionally not executed as commands to avoid reacting
        // to half-spoken phrases. Execute only a complete utterance after a pause.
    }

    override fun onResult(hypothesis: String) {
        deliverCompleteUtterance(hypothesis)
    }

    override fun onFinalResult(hypothesis: String) {
        deliverCompleteUtterance(hypothesis)
    }

    override fun onError(exception: Exception) {
        if (closed) return
        Log.e(TAG, "Offline Arabic microphone recognition failed; retrying locally.", exception)
        report("OFFLINE_SPEECH_AUDIO_ERROR retrying=true")
        mainHandler.removeCallbacks(retryListening)
        mainHandler.postDelayed(retryListening, RETRY_DELAY_MS)
    }

    override fun onTimeout() {
        // Continuous microphone mode does not use a speech timeout.
    }

    private fun deliverCompleteUtterance(hypothesis: String) {
        if (closed) return
        val text = try {
            JSONObject(hypothesis).optString("text", "").trim()
        } catch (_: Exception) {
            ""
        }
        if (text.isBlank()) return
        Log.i(TAG, "RECOGNIZED_OFFLINE text=$text")
        mainHandler.post {
            if (!closed) onTranscript(text)
        }
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        mainHandler.post {
            if (!closed) onStatus(message)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        mainHandler.removeCallbacks(retryListening)
        try {
            recoveryExecutor.execute { releaseAudioObjects(closeModel = true) }
            recoveryExecutor.shutdown()
        } catch (error: Exception) {
            Log.w(TAG, "Could not schedule offline speech cleanup.", error)
        }
    }

    companion object {
        private const val TAG = "EVE_AUDIO"
        private const val SAMPLE_RATE_HZ = 16_000.0f
        private const val RETRY_DELAY_MS = 1_500L
        private const val MODEL_ARCHIVE_ASSET = "vosk-model-ar-mgb2-0.4.zip"
        private const val MODEL_STORAGE_DIRECTORY = "eve-offline-arabic-model"
        private const val ZIP_BUFFER_SIZE = 64 * 1024
    }
}
