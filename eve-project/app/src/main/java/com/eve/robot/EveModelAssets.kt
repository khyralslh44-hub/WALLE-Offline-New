package com.eve.robot

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads optionally ZIP-compressed model assets into direct buffers for MediaPipe/TFLite.
 * Using a buffer lets Android compress model assets inside the APK while inference remains local.
 */
internal object EveModelAssets {
    fun loadDirectBuffer(context: Context, assetName: String): ByteBuffer {
        val bytes = context.applicationContext.assets.open(assetName).use { input ->
            input.readBytes()
        }
        require(bytes.isNotEmpty()) { "Model asset is empty: $assetName" }
        return ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(bytes)
                rewind()
            }
    }
}
