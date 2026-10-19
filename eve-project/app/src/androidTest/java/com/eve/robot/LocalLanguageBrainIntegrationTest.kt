package com.eve.robot

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end proof for the generative language model (not the rule-based dialogue fallback).
 *
 * The dedicated workflow downloads the pinned LiteRT-LM model on the build runner, verifies its
 * SHA-256, then stages it in the app-private data directory with the same integrity metadata
 * used by LocalLanguageBrain. This avoids making the test result depend on slow emulator
 * downloads while still exercising the real native LiteRT-LM engine and model weights.
 *
 * This test requires the emulator network to be disabled before first inference. It runs one
 * generation, closes the engine, opens a fresh engine from the persisted local weights, and
 * runs a second generation while still offline. The ordinary vision suite skips this test if
 * no verified model has been staged; the dedicated workflow requires the success marker.
 */
@RunWith(AndroidJUnit4::class)
class LocalLanguageBrainIntegrationTest {
    @Test
    fun qwenGeneratesBeforeAndAfterNetworkIsDisabledAndEngineIsReopened() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val modelFile = File(
            File(context.filesDir, "local-language-brain"),
            "Qwen2_0.5B_Instruct.litertlm"
        )
        val modelPrefs = context.getSharedPreferences("eve_local_llm", Context.MODE_PRIVATE)
        val verifiedOnDisk = modelFile.isFile &&
            modelFile.length() >= MIN_MODEL_BYTES &&
            modelPrefs.getString("verifiedModelSha256", null) == MODEL_SHA256 &&
            modelPrefs.getLong("verifiedModelBytes", -1L) == modelFile.length()
        assumeTrue(
            "No verified Qwen model was staged; use the dedicated local-LLM verification workflow.",
            verifiedOnDisk
        )
        assertFalse(
            "The dedicated local-LLM test must start with network connectivity disabled.",
            hasValidatedNetwork(context)
        )

        val firstStatus = AtomicReference("startup")
        val firstBrain = LocalLanguageBrain(context) { state, percent, message ->
            firstStatus.set("$state $percent% $message")
            Log.i(TAG, "FIRST_ENGINE_STATE $state $percent% $message")
        }
        var firstReply = ""
        try {
            firstBrain.start()
            awaitReady(firstBrain, firstStatus, INITIALIZE_TIMEOUT_MS)
            val first = awaitReply(
                firstBrain,
                "أجب بالعربية بجملة قصيرة: كم يساوي اثنان زائد اثنين؟",
                "first offline inference"
            )
            firstReply = first.replyArabic
            assertTrue("First generated reply was empty: $first", first.replyArabic.isNotBlank())
            Log.i(TAG, "LOCAL_LLM_FIRST_INFERENCE_PASS chars=${first.replyArabic.length} internetValidated=${hasValidatedNetwork(context)}")
        } finally {
            firstBrain.close()
        }

        // Give LiteRT-LM time to release native tensors before constructing a fresh engine.
        SystemClock.sleep(8_000L)
        disableEmulatorNetwork(instrumentation)
        assertFalse(
            "Emulator still reports a validated Internet network after airplane-mode request.",
            hasValidatedNetwork(context)
        )

        val offlineStatus = AtomicReference("offline startup")
        val offlineBrain = LocalLanguageBrain(context) { state, percent, message ->
            offlineStatus.set("$state $percent% $message")
            Log.i(TAG, "OFFLINE_ENGINE_STATE $state $percent% $message")
        }
        try {
            offlineBrain.start()
            awaitReady(offlineBrain, offlineStatus, OFFLINE_RELOAD_TIMEOUT_MS)
            val second = awaitReply(
                offlineBrain,
                "أجب بالعربية بجملة قصيرة: اذكر لون السماء في يوم صافٍ، ولا تستخدم معلومات من الكاميرا.",
                "offline cold-reload inference"
            )
            assertTrue("Offline generated reply was empty: $second", second.replyArabic.isNotBlank())
            assertNotNull("The first generated answer should be retained for diagnostics.", firstReply)
            Log.i(
                TAG,
                "LOCAL_LLM_OFFLINE_RESTART_PASS model=Qwen2-0.5B-Instruct " +
                    "backend=CPU verifiedBytes=${modelFile.length()} firstChars=${firstReply.length} " +
                    "secondChars=${second.replyArabic.length} internetValidated=false"
            )
            val gameComment = awaitGameComment(offlineBrain)
            assertTrue("Offline Qwen game comment was empty: $gameComment", gameComment.replyArabic.isNotBlank())
            Log.i(
                TAG,
                "LOCAL_LLM_GAME_COMMENT_PASS source=local_qwen chars=${gameComment.replyArabic.length} " +
                    "mood=${gameComment.moodHint ?: "none"} intent=${gameComment.intentHint ?: "none"} internetValidated=false"
            )
        } finally {
            offlineBrain.close()
        }
    }

    private fun awaitGameComment(brain: LocalLanguageBrain): LocalLlmResult {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<LocalLlmResult>?>(null)
        brain.commentOnGame(
            gameName = "إكس-أو",
            outcome = "player",
            detail = "فاز اللاعب في المباراة الحالية؛ إيف خسرت هذه الجولة.",
            playerScore = 1,
            eveScore = 0,
            round = 9,
            difficulty = 2,
            recentResults = listOf("xo:draw", "chess:robot"),
            context = LocalLlmContext(currentMood = "PLAYFUL", moodReason = "game result")
        ) {
            result.set(it)
            latch.countDown()
        }
        assertTrue("Timed out waiting for an offline game comment.", latch.await(INFERENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        val outcome = result.get()
        assertNotNull("The game comment callback returned no result container.", outcome)
        return outcome!!.getOrElse { throw AssertionError("Offline game comment failed.", it) }
    }

    private fun awaitReady(
        brain: LocalLanguageBrain,
        status: AtomicReference<String>,
        timeoutMs: Long
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!brain.isReady && SystemClock.elapsedRealtime() < deadline) {
            if (brain.currentState == LocalLlmState.ERROR) {
                throw AssertionError("Local Qwen engine failed to initialize: ${status.get()}")
            }
            if (brain.currentState == LocalLlmState.WAITING_FOR_WIFI &&
                !hasValidatedUnmeteredNetwork(InstrumentationRegistry.getInstrumentation().targetContext) &&
                !hasVerifiedModel(InstrumentationRegistry.getInstrumentation().targetContext)
            ) {
                throw AssertionError("Local Qwen needs an unmetered connection for first setup: ${status.get()}")
            }
            SystemClock.sleep(1_000L)
        }
        assertTrue(
            "Local Qwen did not reach READY within ${timeoutMs / 1000}s; last state=${brain.currentState}, status=${status.get()}",
            brain.isReady
        )
    }

    private fun awaitReply(
        brain: LocalLanguageBrain,
        prompt: String,
        phase: String
    ): LocalLlmResult {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<LocalLlmResult>?>(null)
        brain.answerUser(prompt, LocalLlmContext()) {
            result.set(it)
            latch.countDown()
        }
        assertTrue("Timed out waiting for $phase.", latch.await(INFERENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        val outcome = result.get()
        assertNotNull("The $phase callback returned no result container.", outcome)
        return outcome!!.getOrElse { throw AssertionError("The $phase failed.", it) }
    }

    private fun hasVerifiedModel(context: Context): Boolean {
        val file = File(
            File(context.filesDir, "local-language-brain"),
            "Qwen2_0.5B_Instruct.litertlm"
        )
        val prefs = context.getSharedPreferences("eve_local_llm", Context.MODE_PRIVATE)
        return file.isFile &&
            file.length() >= MIN_MODEL_BYTES &&
            prefs.getString("verifiedModelSha256", null) == MODEL_SHA256 &&
            prefs.getLong("verifiedModelBytes", -1L) == file.length()
    }

    private fun hasValidatedNetwork(context: Context): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun hasValidatedUnmeteredNetwork(context: Context): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            !connectivity.isActiveNetworkMetered
    }

    private fun disableEmulatorNetwork(instrumentation: android.app.Instrumentation) {
        val command =
            "svc wifi disable; svc data disable; settings put global airplane_mode_on 1; " +
                "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true; sleep 3"
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        Log.i(TAG, "NETWORK_DISABLE_REQUESTED output=${output.take(400)}")
        val context = instrumentation.targetContext
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (hasValidatedNetwork(context) && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(1_000L)
        }
    }

    companion object {
        private const val TAG = "EVE_LLM_TEST"
        private const val MODEL_SHA256 = "0f01cc004b8eb62b92ba6be85ed05a248ba0d2f78af94c4949b313eccfb4c157"
        private const val MIN_MODEL_BYTES = 500_000_000L
        private const val INITIALIZE_TIMEOUT_MS = 20L * 60L * 1000L
        private const val OFFLINE_RELOAD_TIMEOUT_MS = 5L * 60L * 1000L
        private const val INFERENCE_TIMEOUT_MS = 6L * 60L * 1000L
    }
}
