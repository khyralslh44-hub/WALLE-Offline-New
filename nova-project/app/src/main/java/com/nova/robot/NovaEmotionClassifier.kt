package com.nova.robot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * Local facial-expression classifier.
 *
 * The MIT-licensed simple_classifier.tflite from CodeArtisanRiz/FaceEmotion is pinned by the
 * workflow. It expects a face crop resized to 48x48 grayscale, normalized to [0,1].
 * The output order is: angry, disgust, fear, happy, neutral, sad, surprise.
 * Output is phrased as a possible facial expression, not certain knowledge of inner feelings.
 */
internal class NovaEmotionClassifier(context: Context) : AutoCloseable {
    private val interpreter: Interpreter
    private val inputBuffer = ByteBuffer.allocateDirect(SIDE * SIDE * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val outputBuffer = ByteBuffer.allocateDirect(CLASS_COUNT * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())

    init {
        val modelBytes = context.applicationContext.assets.open(MODEL_ASSET).use { it.readBytes() }
        interpreter = Interpreter(modelBytes, Interpreter.Options().setNumThreads(2))
        interpreter.allocateTensors()
        val inputTensor = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)
        require(inputTensor.dataType() == DataType.FLOAT32 &&
            inputTensor.shape().contentEquals(intArrayOf(1, SIDE, SIDE, 1))) {
            "Emotion model input must be float32 [1,48,48,1]."
        }
        require(outputTensor.dataType() == DataType.FLOAT32 &&
            outputTensor.shape().contentEquals(intArrayOf(1, CLASS_COUNT))) {
            "Emotion model output must be float32 [1,7]."
        }
    }

    @Synchronized
    fun classify(faceCrop: Bitmap): String {
        if (faceCrop.isRecycled || faceCrop.width < 16 || faceCrop.height < 16) {
            return NovaEmotionEvidence.uncertainNote()
        }
        val resized = Bitmap.createScaledBitmap(faceCrop, SIDE, SIDE, true)
        try {
            inputBuffer.clear()
            for (y in 0 until SIDE) {
                for (x in 0 until SIDE) {
                    val pixel = resized.getPixel(x, y)
                    val gray = (Color.red(pixel) * 299 +
                        Color.green(pixel) * 587 +
                        Color.blue(pixel) * 114) / 1000
                    inputBuffer.putFloat(gray / 255f)
                }
            }
            inputBuffer.rewind()
            outputBuffer.clear()
            interpreter.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()
            val scores = FloatArray(CLASS_COUNT) { outputBuffer.float }
            return NovaEmotionEvidence.describe(scores)
        } finally {
            if (resized !== faceCrop) resized.recycle()
        }
    }

    @Synchronized
    override fun close() {
        interpreter.close()
    }

    private companion object {
        const val MODEL_ASSET = "simple_classifier.tflite"
        const val SIDE = 48
        const val CLASS_COUNT = 7
    }
}

/** Conservative formatter/calibrator helper for seven FER expression categories. */
internal object NovaEmotionEvidence {
    private val expressionNames = listOf(
        "الغضب", "الاشمئزاز", "الخوف", "الفرح",
        "الحياد", "الحزن", "الدهشة"
    )

    fun describe(rawScores: FloatArray): String {
        if (rawScores.size != expressionNames.size || rawScores.any { !it.isFinite() }) {
            return uncertainNote()
        }
        val probabilities = toProbabilities(rawScores)
        val ranked = probabilities.indices.sortedByDescending { probabilities[it] }
        val bestIndex = ranked.firstOrNull() ?: return uncertainNote()
        val best = probabilities[bestIndex]
        val second = ranked.getOrNull(1)?.let { probabilities[it] } ?: 0f
        if (best < MIN_CONFIDENCE || best - second < MIN_MARGIN) return uncertainNote()
        val percent = (best.coerceIn(0f, 1f) * 100).toInt()
        return "أظن أن تعبير الوجه أقرب إلى «${expressionNames[bestIndex]}» (تقدير بصري يقارب $percent٪). " +
            "لا أستطيع معرفة الشعور الداخلي من تعبير الوجه وحده."
    }

    fun uncertainNote(): String =
        "ألاحظ تعبيرًا على الوجه، لكنني لا أستطيع تسميته بثقة كافية بعد؛ سأواصل الملاحظة دون تخمين."

    private fun toProbabilities(scores: FloatArray): FloatArray {
        val sum = scores.sum()
        val alreadyProbabilities = scores.all { it in 0f..1f } && sum in 0.97f..1.03f
        if (alreadyProbabilities) return FloatArray(scores.size) { scores[it] / sum }

        val maxScore = scores.maxOrNull() ?: return FloatArray(scores.size)
        val exponents = FloatArray(scores.size) {
            exp((scores[it] - maxScore).coerceIn(-80f, 0f))
        }
        val total = exponents.sum()
        if (!total.isFinite() || total <= 0f) return FloatArray(scores.size)
        return FloatArray(scores.size) { exponents[it] / total }
    }

    private const val MIN_CONFIDENCE = 0.40f
    private const val MIN_MARGIN = 0.08f
}
