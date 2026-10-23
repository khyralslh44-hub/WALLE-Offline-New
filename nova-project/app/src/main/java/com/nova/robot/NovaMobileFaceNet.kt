package com.nova.robot

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Local MobileFaceNet embedding adapter. It uses the bundled float TFLite model when available.
 * Input normalization follows the common MobileFaceNet [-1, 1] convention; because model exports
 * can differ, identity matching remains experimental and must be calibrated with held-out people.
 */
internal class NovaMobileFaceNet(context: Context) : AutoCloseable {
    private val interpreter: Interpreter? = runCatching {
        val bytes = context.applicationContext.assets.open("mobile_facenet.tflite").use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.rewind()
        Interpreter(buffer, Interpreter.Options().setNumThreads(2))
    }.onFailure { Log.w(TAG, "MobileFaceNet asset unavailable; identity memory will use prototype fallback.", it) }
        .getOrNull()

    val isAvailable: Boolean get() = interpreter != null

    @Synchronized
    fun extract(face: Bitmap): FloatArray? {
        val model = interpreter ?: return null
        if (face.isRecycled) return null
        return runCatching {
            val inputShape = model.getInputTensor(0).shape()
            require(inputShape.size == 4 && inputShape[0] == 1) { "Unsupported MobileFaceNet input shape." }
            require(inputShape[3] == 3) { "Expected NHWC RGB MobileFaceNet input." }
            val height = inputShape[1]
            val width = inputShape[2]
            require(width > 0 && height > 0)
            val scaled = Bitmap.createScaledBitmap(face, width, height, true)
            try {
                val pixels = IntArray(width * height)
                scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                val inputTensor = model.getInputTensor(0)
                val input: ByteBuffer = when (inputTensor.dataType()) {
                    DataType.FLOAT32 -> {
                        val buffer = ByteBuffer.allocateDirect(width * height * 3 * 4).order(ByteOrder.nativeOrder())
                        for (i in pixels.indices) {
                            val p = pixels[i]
                            val r = ((p shr 16) and 0xff) / 127.5f - 1f
                            val g = ((p shr 8) and 0xff) / 127.5f - 1f
                            val b = (p and 0xff) / 127.5f - 1f
                            buffer.putFloat(r); buffer.putFloat(g); buffer.putFloat(b)
                        }
                        buffer.rewind()
                        buffer
                    }
                    DataType.UINT8 -> {
                        val buffer = ByteBuffer.allocateDirect(width * height * 3).order(ByteOrder.nativeOrder())
                        for (p in pixels) {
                            buffer.put(((p shr 16) and 0xff).toByte())
                            buffer.put(((p shr 8) and 0xff).toByte())
                            buffer.put((p and 0xff).toByte())
                        }
                        buffer.rewind()
                        buffer
                    }
                    else -> error("Unsupported MobileFaceNet input data type: ${inputTensor.dataType()}")
                }
                val outputTensor = model.getOutputTensor(0)
                require(outputTensor.dataType() == DataType.FLOAT32) {
                    "Expected float MobileFaceNet embeddings, got ${outputTensor.dataType()}."
                }
                val outSize = outputTensor.shape().fold(1) { acc, dim -> acc * dim }
                require(outSize in 16..4096) { "Unexpected MobileFaceNet output shape." }
                val output = ByteBuffer.allocateDirect(outSize * 4).order(ByteOrder.nativeOrder())
                model.run(input, output)
                output.rewind()
                val raw = FloatArray(outSize) { output.float }
                var normSquared = 0.0
                for (v in raw) normSquared += v.toDouble() * v
                val norm = sqrt(normSquared).toFloat()
                if (!norm.isFinite() || norm < 1e-8f) return null
                FloatArray(raw.size) { raw[it] / norm }
            } finally {
                if (scaled !== face) scaled.recycle()
            }
        }.onFailure { Log.w(TAG, "MobileFaceNet inference failed; using cautious fallback.", it) }.getOrNull()
    }

    @Synchronized
    override fun close() {
        runCatching { interpreter?.close() }
    }

    private companion object { const val TAG = "NOVA_FACE_EMBED" }
}
