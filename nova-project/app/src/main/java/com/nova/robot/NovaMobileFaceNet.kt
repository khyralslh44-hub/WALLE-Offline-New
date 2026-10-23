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
            val height: Int
            val width: Int
            val channels: Int
            if (inputShape[3] == 3) {
                height = inputShape[1]; width = inputShape[2]; channels = inputShape[3]
            } else if (inputShape[1] == 3) {
                channels = inputShape[1]; height = inputShape[2]; width = inputShape[3]
            } else error("Expected RGB MobileFaceNet input.")
            require(channels == 3 && width > 0 && height > 0)
            val scaled = Bitmap.createScaledBitmap(face, width, height, true)
            try {
                val pixels = IntArray(width * height)
                scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                val inputTensor = model.getInputTensor(0)
                val input: Any = when (inputTensor.dataType()) {
                    DataType.FLOAT32 -> {
                        val values = FloatArray(width * height * 3)
                        for (i in pixels.indices) {
                            val p = pixels[i]
                            val r = ((p shr 16) and 0xff) / 127.5f - 1f
                            val g = ((p shr 8) and 0xff) / 127.5f - 1f
                            val b = (p and 0xff) / 127.5f - 1f
                            if (inputShape[3] == 3) {
                                values[i * 3] = r; values[i * 3 + 1] = g; values[i * 3 + 2] = b
                            } else {
                                val plane = width * height
                                values[i] = r; values[plane + i] = g; values[2 * plane + i] = b
                            }
                        }
                        values
                    }
                    DataType.UINT8 -> {
                        val values = ByteArray(width * height * 3)
                        for (i in pixels.indices) {
                            val p = pixels[i]
                            values[i * 3] = ((p shr 16) and 0xff).toByte()
                            values[i * 3 + 1] = ((p shr 8) and 0xff).toByte()
                            values[i * 3 + 2] = (p and 0xff).toByte()
                        }
                        values
                    }
                    else -> error("Unsupported MobileFaceNet input data type: ${inputTensor.dataType()}")
                }
                val outputTensor = model.getOutputTensor(0)
                val outSize = outputTensor.shape().fold(1) { acc, dim -> acc * dim }
                require(outSize in 16..4096) { "Unexpected MobileFaceNet output shape." }
                val output: Any = when (outputTensor.dataType()) {
                    DataType.FLOAT32 -> Array(1) { FloatArray(outSize) }
                    DataType.UINT8 -> Array(1) { ByteArray(outSize) }
                    else -> error("Unsupported MobileFaceNet output type.")
                }
                model.run(arrayOf(input), output)
                val raw = when (output) {
                    is Array<*> -> (output[0] as FloatArray)
                    else -> (output as Array<*>).first() as FloatArray
                }
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
