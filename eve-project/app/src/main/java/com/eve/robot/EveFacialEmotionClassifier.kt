package com.eve.robot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline FER2013 facial-expression classifier backed by a bundled TensorFlow Lite CNN.
 *
 * Output describes visual-expression cues, not a guaranteed reading of a person's inner feelings.
 * The model is a pre-trained six-class model; EveMemory's preference adaptation is separate
 * from model-weight training.
 */
data class EveEmotionPrediction(
    val label: String,
    val confidence: Float
)

class EveFacialEmotionClassifier(context: Context) : Closeable {
    companion object {
        val LABELS = listOf("happy", "sad", "angry", "neutral", "surprised", "fearful")
        private const val MODEL_ASSET = "eve_emotion_model.tflite"
        private const val INPUT_SIZE = 48
    }

    private val interpreter: Interpreter? = loadInterpreter(context.applicationContext)
    val isReady: Boolean get() = interpreter != null

    private fun loadInterpreter(context: Context): Interpreter? = try {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        require(bytes.isNotEmpty()) { "Bundled emotion model is empty." }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.rewind()
        val options = Interpreter.Options()
            .setNumThreads(2)
            .setUseXNNPACK(true)
        Interpreter(buffer, options).also { candidate ->
            val input = candidate.getInputTensor(0)
            require(input.shape().contentEquals(intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 1))) {
                "Unexpected FER input shape: ${input.shape().contentToString()}"
            }
            require(input.dataType() == DataType.FLOAT32) {
                "Unexpected FER input type: ${input.dataType()}"
            }
            val output = candidate.getOutputTensor(0)
            require(output.shape().contentEquals(intArrayOf(1, LABELS.size))) {
                "Unexpected FER output shape: ${output.shape().contentToString()}"
            }
            require(output.dataType() == DataType.FLOAT32) {
                "Unexpected FER output type: ${output.dataType()}"
            }
            Log.i("EVE_FER", "Bundled offline FER model loaded; input=48x48 grayscale; classes=${LABELS.size}")
        }
    } catch (error: Exception) {
        Log.e("EVE_FER", "Offline FER model could not load; use ML Kit cues as fallback.", error)
        null
    }

    /**
     * Takes a face-only crop. The caller owns the bitmap and remains responsible for recycling it.
     * Pixels are resized to 48x48 grayscale and scaled to [0, 1], matching the model's training path.
     */
    @Synchronized
    fun predict(faceCrop: Bitmap): EveEmotionPrediction? {
        val model = interpreter ?: return null
        if (faceCrop.width < 12 || faceCrop.height < 12) return null
        return try {
            val resized = if (faceCrop.width == INPUT_SIZE && faceCrop.height == INPUT_SIZE) {
                faceCrop
            } else {
                Bitmap.createScaledBitmap(faceCrop, INPUT_SIZE, INPUT_SIZE, true)
            }
            try {
                // Direct native-order buffers avoid allocating over 2,300 nested Kotlin arrays
                // for each video frame. XNNPACK is enabled above for optimized CPU convolution.
                val input = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 4)
                    .order(ByteOrder.nativeOrder())
                for (y in 0 until INPUT_SIZE) {
                    for (x in 0 until INPUT_SIZE) {
                        val pixel = resized.getPixel(x, y)
                        val gray = (
                            0.299f * Color.red(pixel) +
                                0.587f * Color.green(pixel) +
                                0.114f * Color.blue(pixel)
                            ) / 255f
                        input.putFloat(gray)
                    }
                }
                input.rewind()
                val output = ByteBuffer.allocateDirect(LABELS.size * 4)
                    .order(ByteOrder.nativeOrder())
                model.run(input, output)
                output.rewind()
                val scores = FloatArray(LABELS.size) { output.float }
                if (scores.any { !it.isFinite() }) return null
                val bestIndex = scores.indices.maxByOrNull { scores[it] } ?: return null
                EveEmotionPrediction(
                    label = LABELS[bestIndex],
                    confidence = scores[bestIndex].coerceIn(0f, 1f)
                )
            } finally {
                if (resized !== faceCrop) resized.recycle()
            }
        } catch (error: Exception) {
            Log.w("EVE_FER", "Local facial-expression inference failed.", error)
            null
        }
    }

    override fun close() {
        try {
            interpreter?.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Temporal majority filter for model predictions. One unusual frame (blink, blur or movement)
 * must not change the face or voice; two agreeing predictions from the last three are required.
 */
class EveEmotionSmoother(
    private val windowSize: Int = 3,
    private val requiredVotes: Int = 2,
    private val minConfidence: Float = 0.62f
) {
    private val window = ArrayList<EveEmotionPrediction>()

    init {
        require(windowSize >= 2)
        require(requiredVotes in 2..windowSize)
        require(minConfidence.isFinite() && minConfidence in 0f..1f)
    }

    @Synchronized
    fun add(prediction: EveEmotionPrediction?): EveEmotionPrediction? {
        if (prediction == null ||
            prediction.label !in EveFacialEmotionClassifier.LABELS ||
            !prediction.confidence.isFinite() ||
            prediction.confidence < minConfidence
        ) {
            window.clear()
            return null
        }
        window += prediction.copy(confidence = prediction.confidence.coerceIn(0f, 1f))
        while (window.size > windowSize) window.removeAt(0)
        val winner = window.groupingBy { it.label }.eachCount()
            .maxByOrNull { it.value }
            ?.takeIf { it.value >= requiredVotes }
            ?.key ?: return null
        val agreeing = window.filter { it.label == winner }
        val meanConfidence = agreeing.map { it.confidence }.average().toFloat()
        return EveEmotionPrediction(winner, meanConfidence)
    }

    @Synchronized
    fun reset() = window.clear()

    @Synchronized
    fun sampleCount(): Int = window.size
}
