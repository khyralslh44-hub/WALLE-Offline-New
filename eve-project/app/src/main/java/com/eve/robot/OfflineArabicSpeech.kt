package com.eve.robot

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * The 27M-parameter Moonshine Tiny Arabic student model is bundled in int8 ONNX form.
 * Sherpa-ONNX runs it locally. Short microphone segments are gated with a simple energy VAD
 * so silence is not sent to the sequence-to-sequence decoder (which can hallucinate on silence).
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

    fun start() {
        if (closed || started) return
        started = true
        report("OFFLINE_SPEECH_MODEL_LOADING language=ar model=moonshine_tiny_ar")
        try {
            inferenceExecutor.execute {
                try {
                    val localRecognizer = createRecognizer()
                    if (closed) {
                        localRecognizer.release()
                        return@execute
                    }
                    recognizer = localRecognizer
                    startMicrophone()
                } catch (error: Exception) {
                    Log.e(TAG, "Could not initialize the bundled compact Arabic speech model.", error)
                    report("OFFLINE_SPEECH_FAILED reason=model_init")
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not schedule local Arabic recognizer initialization.", error)
            report("OFFLINE_SPEECH_FAILED reason=model_start")
        }
    }

    private fun createRecognizer(): OfflineRecognizer {
        val moonshine = OfflineMoonshineModelConfig().apply {
            encoder = MODEL_ENCODER_ASSET
            mergedDecoder = MODEL_DECODER_ASSET
        }
        val modelConfig = OfflineModelConfig().apply {
            this.moonshine = moonshine
            tokens = MODEL_TOKENS_ASSET
            numThreads = 2
            provider = "cpu"
            modelingUnit = "cjkchar"
        }
        val config = OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = FEATURE_DIM
            )
            this.modelConfig = modelConfig
            decodingMethod = "greedy_search"
        }
        return OfflineRecognizer(assetManager = appContext.assets, config = config)
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
    }
}
