package com.eve.robot

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One local microphone stream feeds two layers:
 * 1) the existing general Arabic transcript fallback;
 * 2) EVE's own MFCC+DTW learner, which enrolls and recognizes user-taught phrases.
 *
 * The PCM frames are captured once and shared locally. No audio is uploaded.
 */
class OfflineArabicSpeech(
    context: Context,
    private val onTranscript: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onAudioChunk: (ShortArray) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recoveryExecutor = Executors.newSingleThreadExecutor()
    private val audioExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    @Volatile private var preparing = false
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var recognizer: Recognizer? = null
    @Volatile private var model: Model? = null
    @Volatile private var audioLoopDone: CountDownLatch = CountDownLatch(0)

    private val retryListening = Runnable {
        if (!closed) recoveryExecutor.execute { recoverAudioInput() }
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

    private fun unpackBundledModelArchive(): File {
        val target = File(appContext.filesDir, MODEL_STORAGE_DIRECTORY)
        findModelRoot(target)?.let { return it }
        val staging = File(appContext.filesDir, MODEL_STORAGE_DIRECTORY + ".extracting")
        if (staging.exists() && !staging.deleteRecursively()) throw IOException("Could not remove incomplete Arabic model extraction.")
        if (!staging.mkdirs()) throw IOException("Could not create Arabic model staging directory.")
        try {
            appContext.assets.open(MODEL_ARCHIVE_ASSET).use { raw ->
                java.util.zip.ZipInputStream(BufferedInputStream(raw, ZIP_BUFFER_SIZE)).use { zip ->
                    val buffer = ByteArray(ZIP_BUFFER_SIZE)
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val output = File(staging, entry.name)
                        val canonicalRoot = staging.canonicalPath + File.separator
                        if (!output.canonicalPath.startsWith(canonicalRoot)) throw IOException("Rejected unsafe ZIP entry path.")
                        if (entry.isDirectory) {
                            if (!output.exists() && !output.mkdirs()) throw IOException("Could not create model directory.")
                        } else {
                            val parent = output.parentFile
                            if (parent != null && !parent.exists() && !parent.mkdirs()) throw IOException("Could not create model parent directory.")
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
        val stagedRoot = findModelRoot(staging) ?: run {
            staging.deleteRecursively()
            throw IOException("Extracted archive did not contain a complete Vosk model.")
        }
        val relativeRoot = stagedRoot.relativeTo(staging).path
        if (target.exists() && !target.deleteRecursively()) {
            staging.deleteRecursively()
            throw IOException("Could not replace old Arabic model directory.")
        }
        if (!staging.renameTo(target)) {
            staging.copyRecursively(target, overwrite = true)
            staging.deleteRecursively()
        }
        val modelRoot = if (relativeRoot.isBlank() || relativeRoot == ".") target else File(target, relativeRoot)
        if (!isValidModelRoot(modelRoot)) throw IOException("Extracted Arabic model failed integrity checks.")
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
        if (closed || audioRecord != null) return
        val loadedModel = model ?: return
        try {
            val localRecognizer = Recognizer(loadedModel, SAMPLE_RATE_HZ.toFloat())
            val minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBytes <= 0) throw IOException("Android returned invalid microphone buffer size: $minBytes")
            val bufferBytes = maxOf(minBytes * 2, FRAME_BYTES * 4)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                localRecognizer.close()
                throw IOException("Android microphone recorder did not initialize.")
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                localRecognizer.close()
                throw IOException("Android microphone did not enter recording state.")
            }
            recognizer = localRecognizer
            audioRecord = record
            val done = CountDownLatch(1)
            audioLoopDone = done
            audioExecutor.execute { readMicrophoneLoop(record, localRecognizer, done) }
            report("OFFLINE_SPEECH_LISTENING language=ar source=local_pcm_dual_ear")
        } catch (error: Exception) {
            Log.e(TAG, "Could not start the local PCM microphone.", error)
            report("OFFLINE_SPEECH_FAILED reason=audio_start")
            scheduleRetry()
        }
    }

    private fun readMicrophoneLoop(record: AudioRecord, localRecognizer: Recognizer, done: CountDownLatch) {
        val bytes = ByteArray(FRAME_BYTES)
        try {
            while (!closed && audioRecord === record) {
                val count = record.read(bytes, 0, bytes.size, AudioRecord.READ_BLOCKING)
                if (count == AudioRecord.ERROR_DEAD_OBJECT || count < 0) {
                    throw IOException("AudioRecord read failed: $count")
                }
                if (count < 2) continue
                val evenCount = count - (count % 2)
                val pcm = ShortArray(evenCount / 2)
                for (i in pcm.indices) {
                    val low = bytes[i * 2].toInt() and 0xff
                    val high = bytes[i * 2 + 1].toInt()
                    pcm[i] = (low or (high shl 8)).toShort()
                }
                try { onAudioChunk(pcm) } catch (error: Exception) {
                    Log.w(TAG, "EVE personal hearing frame callback failed.", error)
                }
                if (localRecognizer.acceptWaveForm(bytes, evenCount)) {
                    deliverCompleteUtterance(localRecognizer.result)
                }
            }
            if (!closed && audioRecord === record) deliverCompleteUtterance(localRecognizer.finalResult)
        } catch (error: Exception) {
            if (!closed) {
                Log.e(TAG, "Local microphone loop failed.", error)
                report("OFFLINE_SPEECH_AUDIO_ERROR retrying=true")
                scheduleRetry()
            }
        } finally {
            done.countDown()
        }
    }

    private fun scheduleRetry() {
        mainHandler.removeCallbacks(retryListening)
        mainHandler.postDelayed(retryListening, RETRY_DELAY_MS)
    }

    private fun recoverAudioInput() {
        if (closed) return
        releaseAudioObjects(closeModel = false)
        if (!closed && model != null) startRecognition()
    }

    private fun releaseAudioObjects(closeModel: Boolean) {
        val record = audioRecord
        audioRecord = null
        if (record != null) {
            try { record.stop() } catch (error: Exception) {
                Log.w(TAG, "Stopping AudioRecord failed.", error)
            }
            try { audioLoopDone.await(1200L, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            try { record.release() } catch (error: Exception) {
                Log.w(TAG, "Releasing AudioRecord failed.", error)
            }
        }
        val localRecognizer = recognizer
        recognizer = null
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

    private fun deliverCompleteUtterance(hypothesis: String) {
        if (closed) return
        val text = try { JSONObject(hypothesis).optString("text", "").trim() } catch (_: Exception) { "" }
        if (text.isBlank()) return
        Log.i(TAG, "RECOGNIZED_OFFLINE text=$text")
        mainHandler.post { if (!closed) onTranscript(text) }
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        mainHandler.post { if (!closed) onStatus(message) }
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
        audioExecutor.shutdown()
    }

    companion object {
        private const val TAG = "EVE_AUDIO"
        private const val SAMPLE_RATE_HZ = 16_000
        private const val RETRY_DELAY_MS = 1_500L
        private const val MODEL_ARCHIVE_ASSET = "vosk-model-ar-mgb2-0.4.zip"
        private const val MODEL_STORAGE_DIRECTORY = "eve-offline-arabic-model"
        private const val ZIP_BUFFER_SIZE = 64 * 1024
        private const val FRAME_BYTES = 3_200 // 100 ms at 16 kHz, mono, PCM16
    }
}
