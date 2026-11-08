package com.eve.robot

import ai.moonshine.voice.JNI
import ai.moonshine.voice.MicTranscriber
import ai.moonshine.voice.ModelCache
import ai.moonshine.voice.ModelSpec
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Verifies the exact MicTranscriber.modelsFrom(root).load() path used by EVE,
 * then exercises the streaming decoder with synthetic PCM. This is a model/API
 * integration test, not a natural Arabic speech-accuracy benchmark.
 */
@RunWith(AndroidJUnit4::class)
class MoonshineArabicModelIntegrationTest {

    @Test
    fun bundledArabicTinyStreamingModelLoadsAndRunsLocally() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelRoot = File(context.filesDir, "moonshine-arabic-instrumentation")
        val cacheKey = ModelCache.key(
            ModelSpec.stt("ar", JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING, false)
        )
        val modelDir = File(modelRoot, cacheKey)
        if (modelRoot.exists()) modelRoot.deleteRecursively()
        assertTrue("Could not create model cache directory.", modelDir.mkdirs())
        copyAssetTree(context.assets, "moonshine/ar", modelDir)

        val files = modelDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue("Moonshine ORT model assets are missing.", files.any { it.extension == "ort" })
        assertTrue("Moonshine tokenizer.bin is missing.", files.any { it.name == "tokenizer.bin" })
        assertTrue("Moonshine model contains an empty file.", files.none { it.length() <= 0L })

        val mic = MicTranscriber(context)
            .language("ar")
            .modelArch(JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING)
            .options(TranscriberOption("max_tokens_per_second", "13.0"))
            .modelsFrom(modelRoot)

        try {
            // MicTranscriber.load() resolves ModelCache.key(spec) beneath modelRoot.
            // Since every required file is already there, AssetDownloader must not make
            // a network request. CI has network access disabled for the instrumentation run.
            mic.load()
            assertTrue("MicTranscriber did not load the bundled Arabic model.", mic.isLoaded)
        } finally {
            mic.close()
        }

        val transcriber = Transcriber(listOf(TranscriberOption("max_tokens_per_second", "13.0")))
        try {
            transcriber.loadFromFiles(
                modelDir.absolutePath,
                JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING
            )
            assertTrue("Arabic Tiny Streaming transcriber did not load.", transcriber.isLoaded)

            // Exercise the streaming decoder with synthetic PCM, not a speech recording.
            transcriber.start()
            val pcm = FloatArray(8_000) { index ->
                (0.08 * kotlin.math.sin(2.0 * Math.PI * 440.0 * index / 16_000.0)).toFloat()
            }
            transcriber.addAudio(pcm, 16_000)
            transcriber.stop()

            val modelBytes = files.sumOf { it.length() }
            Log.i(
                TAG,
                "MOONSHINE_ARABIC_ASR_PASS modelBytes=$modelBytes files=${files.size} " +
                    "cacheKey=$cacheKey localMicLoad=true streamingInference=true syntheticAudio=true"
            )
        } finally {
            transcriber.close()
            modelRoot.deleteRecursively()
        }
    }

    private fun copyAssetTree(
        assets: android.content.res.AssetManager,
        assetPath: String,
        destination: File
    ) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isNotEmpty()) {
            if (!destination.exists()) assertTrue("Could not create $destination", destination.mkdirs())
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
