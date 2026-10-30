package com.eve.robot

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** Loads and invokes the real bundled Arabic streaming model with all radios disabled by CI. */
@RunWith(AndroidJUnit4::class)
class MoonshineArabicModelIntegrationTest {

    @Test
    fun bundledArabicTinyStreamingModelLoadsAndRunsLocally() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = File(context.filesDir, "moonshine-arabic-instrumentation")
        if (modelDir.exists()) modelDir.deleteRecursively()
        copyAssetTree(context.assets, "moonshine/ar", modelDir)

        val files = modelDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue("Moonshine ORT model assets are missing.", files.any { it.extension == "ort" })
        assertTrue("Moonshine tokenizer.bin is missing.", files.any { it.name == "tokenizer.bin" })

        val transcriber = Transcriber()
        try {
            transcriber.loadFromFiles(
                modelDir.absolutePath,
                JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING
            )
            assertTrue("Arabic Tiny Streaming transcriber did not load.", transcriber.isLoaded)

            // A short silent PCM buffer exercises the native inference entrypoint as well as
            // model loading, without microphone permission or a remote speech service.
            val silence = FloatArray(16_000)
            val result = transcriber.transcribeWithoutStreaming(silence, 16_000)
            assertNotNull("Arabic local inference returned no transcript object.", result)

            val modelBytes = files.sumOf { it.length() }
            Log.i(TAG, "MOONSHINE_ARABIC_ASR_PASS modelBytes=$modelBytes files=${files.size} offline=true")
        } finally {
            transcriber.close()
            modelDir.deleteRecursively()
        }
    }

    private fun copyAssetTree(
        assets: android.content.res.AssetManager,
        assetPath: String,
        destination: File
    ) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isNotEmpty()) {
            if (!destination.exists()) destination.mkdirs()
            children.forEach { child ->
                copyAssetTree(assets, "$assetPath/$child", File(destination, child))
            }
        } else {
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
        }
    }

    companion object {
        private const val TAG = "EVE_MOONSHINE_TEST"
    }
}
