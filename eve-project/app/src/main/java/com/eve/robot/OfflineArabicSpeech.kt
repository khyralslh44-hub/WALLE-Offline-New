package com.eve.robot

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Compact, fully offline Arabic speech recognition for EVE.
 *
 * The 27M-parameter Moonshine Tiny Arabic student model is installed once into private app storage.
 * Its int8 ONNX files are checksum-pinned; after first verification, Arabic inference is offline.
 * Short microphone segments are gated with a simple energy VAD.
 */
class OfflineArabicSpeech(
    context: Context,
    private val onTranscript: (String) -> Unit,
    private val onStatus: (String) -> Unit
) : Closeable {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inferenceExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var closed = false
    @Volatile private var started = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null
    private var recognizer: OfflineRecognizer? = null
    private var lastTranscript = ""
    private var lastTranscriptAt = 0L

    private data class ModelFiles(
        val encoder: File,
        val mergedDecoder: File,
        val tokens: File
    )

    fun start() {
        if (closed || started) return
        started = true
        report("OFFLINE_SPEECH_MODEL_LOADING language=ar model=moonshine_tiny_ar")
        try {
            inferenceExecutor.execute {
                try {
                    val modelFiles = ensureModelFiles()
                    if (closed) return@execute
                    val localRecognizer = createRecognizer(modelFiles)
                    if (closed) {
                        localRecognizer.release()
                        return@execute
                    }
                    recognizer = localRecognizer
                    startMicrophone()
                } catch (error: Exception) {
                    Log.e(TAG, "Could not prepare the compact offline Arabic speech model.", error)
                    report(
                        if (error is IOException) "OFFLINE_SPEECH_MODEL_NEEDS_NETWORK_ONCE"
                        else "OFFLINE_SPEECH_FAILED reason=model_init"
                    )
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not schedule local Arabic recognizer initialization.", error)
            report("OFFLINE_SPEECH_FAILED reason=model_start")
        }
    }

    private fun createRecognizer(files: ModelFiles): OfflineRecognizer {
        val moonshine = OfflineMoonshineModelConfig().apply {
            encoder = files.encoder.absolutePath
            mergedDecoder = files.mergedDecoder.absolutePath
        }
        val modelConfig = OfflineModelConfig().apply {
            this.moonshine = moonshine
            tokens = files.tokens.absolutePath
            numThreads = 2
            provider = "cpu"
            // Moonshine v2 tokens are byte-level pieces decoded by Sherpa's BinTokenizer format.
            // Leave modelingUnit unset; CJK character segmentation is not appropriate here.
        }
        val config = OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = FEATURE_DIM
            )
            this.modelConfig = modelConfig
            decodingMethod = "greedy_search"
        }
        return OfflineRecognizer(assetManager = null, config = config)
    }

    /**
     * Keep large speech weights out of the installer package. Each file is downloaded
     * only when absent or invalid, verified by SHA-256, then retained in app-private storage.
     * Once installed, EVE never needs a network connection for Arabic transcription.
     */
    private fun ensureModelFiles(): ModelFiles {
        val directory = File(appContext.filesDir, "eve_models/moonshine_tiny_ar")
        check(directory.exists() || directory.mkdirs()) { "Cannot create private Arabic model folder." }

        val encoder = File(directory, MODEL_ENCODER_ASSET)
        val decoder = File(directory, MODEL_DECODER_ASSET)
        val tokens = File(directory, MODEL_TOKENS_ASSET)

        downloadVerifiedModel(ENCODER_URL, encoder, ENCODER_SHA256)
        downloadVerifiedModel(DECODER_URL, decoder, DECODER_SHA256)
        installBundledTokens(tokens)

        return ModelFiles(encoder, decoder, tokens)
    }

    private fun downloadVerifiedModel(url: String, target: File, expectedSha256: String) {
        if (target.isFile && sha256(target) == expectedSha256) {
            Log.i(TAG, "MODEL_CACHE_HIT file=" + target.name + " bytes=" + target.length())
            return
        }

        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".download")
        temporary.delete()
        report("OFFLINE_SPEECH_MODEL_DOWNLOADING file=" + target.name)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
            readTimeout = DOWNLOAD_READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "EVE-Android/0.5")
        }

        try {
            connection.connect()
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("Model server returned HTTP " + status + " for " + target.name)
            }
            val expectedLength = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            var lastProgressBucket = -1L
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        if (closed) throw IOException("Speech model download cancelled.")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copied += count
                        if (expectedLength > 0L) {
                            val bucket = (copied * 4L / expectedLength).coerceAtMost(4L)
                            if (bucket > lastProgressBucket) {
                                lastProgressBucket = bucket
                                report(
                                    "OFFLINE_SPEECH_MODEL_DOWNLOAD_PROGRESS file=" + target.name +
                                        " percent=" + (bucket * 25L)
                                )
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
            if (copied <= 0L || actualSha != expectedSha256) {
                throw IOException("SHA-256 verification failed for " + target.name + ": " + actualSha)
            }
            if (expectedLength > 0L && copied != expectedLength) {
                throw IOException(
                    "Incomplete model download for " + target.name + ": " +
                        copied + " of " + expectedLength + " bytes"
                )
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            report("OFFLINE_SPEECH_MODEL_VERIFIED file=" + target.name + " bytes=" + copied)
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private fun installBundledTokens(target: File) {
        if (target.isFile && sha256(target) == TOKENS_SHA256) return
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.delete()
        appContext.assets.open(MODEL_TOKENS_ASSET).use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        if (sha256(temporary) != TOKENS_SHA256) {
            temporary.delete()
            throw IOException("Bundled Arabic tokenizer failed SHA-256 verification.")
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun startMicrophone() {
        if (closed) return
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBufferBytes > 0) { "Android could not calculate microphone buffer size: $minBufferBytes" }

        val activeRecorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferBytes, CHUNK_SAMPLES * 2 * 4)
        )
        check(activeRecorder.state == AudioRecord.STATE_INITIALIZED) {
            "Android did not initialize the microphone recorder."
        }
        try {
            activeRecorder.startRecording()
        } catch (error: Exception) {
            activeRecorder.release()
            throw error
        }
        if (closed) {
            try { activeRecorder.stop() } catch (_: Exception) { }
            activeRecorder.release()
            return
        }
        recorder = activeRecorder
        report("OFFLINE_SPEECH_LISTENING language=ar model=moonshine_tiny_ar runtime=sherpa_onnx offline=true")

        val thread = Thread({ captureLoop(activeRecorder) }, "EVE-Arabic-Microphone")
        thread.isDaemon = true
        captureThread = thread
        thread.start()
    }

    private fun captureLoop(activeRecorder: AudioRecord) {
        val input = ShortArray(CHUNK_SAMPLES)
        val preRoll = ArrayDeque<FloatArray>()
        val segment = ArrayList<Float>(SAMPLE_RATE * 4)
        var speechStarted = false
        var consecutiveVoiceChunks = 0
        var silentSamples = 0
        var voicedSamples = 0

        try {
            while (!closed && recorder === activeRecorder) {
                val count = activeRecorder.read(input, 0, input.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) {
                    if (!closed) Log.w(TAG, "Microphone read returned $count; local recognition will stop.")
                    break
                }

                val chunk = FloatArray(count)
                var energy = 0.0
                for (i in 0 until count) {
                    val sample = input[i] / 32768.0f
                    chunk[i] = sample
                    energy += sample * sample
                }
                val rms = sqrt(energy / count)
                val hasVoice = rms >= RMS_VOICE_THRESHOLD

                if (!speechStarted) {
                    preRoll.addLast(chunk)
                    while (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()
                    consecutiveVoiceChunks = if (hasVoice) consecutiveVoiceChunks + 1 else 0
                    if (consecutiveVoiceChunks >= REQUIRED_VOICE_CHUNKS) {
                        speechStarted = true
                        voicedSamples = 0
                        silentSamples = 0
                        segment.clear()
                        for (saved in preRoll) {
                            segment.addAll(saved.toList())
                            if (rmsOf(saved) >= RMS_VOICE_THRESHOLD) voicedSamples += saved.size
                        }
                        preRoll.clear()
                    }
                } else {
                    segment.addAll(chunk.toList())
                    if (hasVoice) {
                        voicedSamples += count
                        silentSamples = 0
                    } else {
                        silentSamples += count
                    }

                    val timedOut = segment.size >= MAX_SEGMENT_SAMPLES
                    val finishedBySilence = silentSamples >= END_SILENCE_SAMPLES
                    if (timedOut || finishedBySilence) {
                        submitSegment(segment, voicedSamples)
                        segment.clear()
                        preRoll.clear()
                        speechStarted = false
                        consecutiveVoiceChunks = 0
                        silentSamples = 0
                        voicedSamples = 0
                    }
                }
            }
        } catch (error: Exception) {
            if (!closed) {
                Log.e(TAG, "Compact Arabic microphone capture failed.", error)
                report("OFFLINE_SPEECH_AUDIO_ERROR retrying=false")
            }
        } finally {
            try { activeRecorder.stop() } catch (_: Exception) { }
            try { activeRecorder.release() } catch (_: Exception) { }
            if (recorder === activeRecorder) recorder = null
        }
    }

    private fun rmsOf(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var energy = 0.0
        for (sample in samples) energy += sample * sample
        return sqrt(energy / samples.size)
    }

    private fun submitSegment(samples: List<Float>, voicedCount: Int) {
        if (closed || voicedCount < MIN_VOICED_SAMPLES || samples.isEmpty()) return
        val ownedSamples = FloatArray(samples.size) { samples[it] }
        try {
            inferenceExecutor.execute {
                if (closed) return@execute
                try {
                    val active = recognizer ?: return@execute
                    val stream = active.createStream()
                    val transcript = try {
                        stream.acceptWaveform(ownedSamples, SAMPLE_RATE)
                        active.decode(stream)
                        active.getResult(stream).text.trim()
                    } finally {
                        stream.release()
                    }
                    if (transcript.isBlank()) return@execute
                    val now = System.currentTimeMillis()
                    if (transcript == lastTranscript && now - lastTranscriptAt < DUPLICATE_WINDOW_MS) {
                        return@execute
                    }
                    lastTranscript = transcript
                    lastTranscriptAt = now
                    Log.i(TAG, "RECOGNIZED_OFFLINE model=moonshine_tiny_ar text=$transcript")
                    mainHandler.post {
                        if (!closed) onTranscript(transcript)
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Local Moonshine Arabic inference failed for one audio segment.", error)
                    report("OFFLINE_SPEECH_FAILED reason=inference")
                }
            }
        } catch (error: Exception) {
            if (!closed) Log.e(TAG, "Could not queue local Arabic inference.", error)
        }
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        mainHandler.post {
            if (!closed) onStatus(message)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val activeRecorder = recorder
        recorder = null
        if (activeRecorder != null) {
            try { activeRecorder.stop() } catch (_: Exception) { }
        }
        captureThread?.interrupt()
        captureThread = null
        try {
            inferenceExecutor.execute {
                try { recognizer?.release() } catch (error: Exception) {
                    Log.w(TAG, "Releasing compact Arabic recognizer failed.", error)
                }
                recognizer = null
            }
            inferenceExecutor.shutdown()
        } catch (error: Exception) {
            Log.w(TAG, "Could not schedule Arabic recognizer cleanup.", error)
        }
    }

    companion object {
        private const val TAG = "EVE_AUDIO"
        private const val SAMPLE_RATE = 16_000
        private const val FEATURE_DIM = 80
        private const val CHUNK_SAMPLES = 320 // 20 ms at 16 kHz
        private const val PRE_ROLL_CHUNKS = 10 // 200 ms before voice onset
        private const val REQUIRED_VOICE_CHUNKS = 2
        private const val RMS_VOICE_THRESHOLD = 0.012
        private const val END_SILENCE_SAMPLES = SAMPLE_RATE * 2 / 5 // 400 ms
        private const val MIN_VOICED_SAMPLES = SAMPLE_RATE * 35 / 100
        private const val MAX_SEGMENT_SAMPLES = SAMPLE_RATE * 8
        private const val DUPLICATE_WINDOW_MS = 1_800L
        private const val MODEL_ENCODER_ASSET = "eve_moonshine_ar_encoder_int8.onnx"
        private const val MODEL_DECODER_ASSET = "eve_moonshine_ar_decoder_int8.onnx"
        private const val MODEL_TOKENS_ASSET = "eve_moonshine_ar_tokens.txt"
        private const val ENCODER_URL =
            "https://huggingface.co/onnx-community/moonshine-tiny-ar-ONNX/resolve/28d27f3/onnx/encoder_model_int8.onnx"
        private const val DECODER_URL =
            "https://huggingface.co/onnx-community/moonshine-tiny-ar-ONNX/resolve/28d27f3/onnx/decoder_model_merged_int8.onnx"
        private const val ENCODER_SHA256 =
            "189c63a96dbedcbce32987ccb2ef52367b5392744fdc9b05f3d5442b0db4e85f"
        private const val DECODER_SHA256 =
            "c3d7481beb40f0880155195a12c619e035fd67249b8d0315d90f7f32dca4d380"
        private const val TOKENS_SHA256 =
            "5e5890351df3fa5c6e87471fa37b888e2634cc2194f3885e8e651eb52de26678"
        private const val DOWNLOAD_BUFFER_BYTES = 128 * 1024
        private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 20_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 90_000
    }
}
