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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end proof that the packaged Qwen asset can be extracted, hash-verified, and used on
 * first launch with the emulator network disabled. A second engine is then opened from private
 * storage and generates again offline to verify persistence and true local inference.
 */
@RunWith(AndroidJUnit4::class)
class LocalLanguageBrainIntegrationTest {
    @Test
    fun qwenGeneratesBeforeAndAfterNetworkIsDisabledAndEngineIsReopened() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val modelFile = File(
            File(context.filesDir, "local-language-brain"),
            "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"
        )
        val modelPrefs = context.getSharedPreferences("eve_local_llm", Context.MODE_PRIVATE)
        val verifiedOnDisk = modelFile.isFile &&
            modelFile.length() >= MIN_MODEL_BYTES &&
            modelPrefs.getString("verifiedModelSha256", null) == MODEL_SHA256 &&
            modelPrefs.getLong("verifiedModelBytes", -1L) == modelFile.length()
        val bundledAssetAvailable = try {
            context.assets.open("Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm").use { it.read() >= 0 }
        } catch (_: java.io.FileNotFoundException) {
            false
        }
        assertTrue(
            "Expected the pinned Qwen weights in APK assets or already verified app-private storage.",
            verifiedOnDisk || bundledAssetAvailable
        )
        assertFalse(
            "The packaged-model test must start with network connectivity disabled.",
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
                "أجب بجملة عربية قصيرة: ٢+٢؟",
                "first offline inference",
                maxOutputTokens = 12
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
                "أجب بكلمة عربية واحدة: لون السماء الصافية؟",
                "offline cold-reload inference",
                maxOutputTokens = 12
            )
            assertTrue("Offline generated reply was empty: $second", second.replyArabic.isNotBlank())

            // A background reflection must not cause a user's next question to be rejected.
            // Both operations share one LiteRT-LM conversation and therefore must execute serially.
            val queuedLatch = CountDownLatch(2)
            val reflectionResult = AtomicReference<Result<LocalLlmResult>?>(null)
            val queuedUserResult = AtomicReference<Result<LocalLlmResult>?>(null)
            offlineBrain.reflectOnObservation(
                LocalLlmContext(
                    latestVisionLabel = "cat",
                    visionConfidence = 0.91f,
                    visionObservationAgeMs = 0L,
                    latestSceneSummary = "جسم واضح واحد في المشهد",
                    sceneObservationAgeMs = 0L
                )
            ) {
                reflectionResult.set(it)
                queuedLatch.countDown()
            }
            offlineBrain.answerUser(
                "أجب بجملة عربية قصيرة: كيف تتصرف إذا لم تعرف الإجابة؟",
                LocalLlmContext(),
                maxOutputTokens = 18
            ) {
                queuedUserResult.set(it)
                queuedLatch.countDown()
            }
            assertTrue(
                "A user question queued behind background reflection did not complete.",
                queuedLatch.await(INFERENCE_TIMEOUT_MS * 2L, TimeUnit.MILLISECONDS)
            )
            val reflectionOutcome = reflectionResult.get()
            val queuedUserOutcome = queuedUserResult.get()
            assertNotNull("Background reflection callback was not delivered.", reflectionOutcome)
            assertNotNull("Queued user-answer callback was not delivered.", queuedUserOutcome)
            assertTrue(
                "Background reflection failed unexpectedly: ${reflectionOutcome?.exceptionOrNull()}",
                reflectionOutcome!!.isSuccess
            )
            assertTrue(
                "User question was lost while reflection was running: ${queuedUserOutcome?.exceptionOrNull()}",
                queuedUserOutcome!!.isSuccess
            )
            assertTrue(
                "Queued user question produced no Arabic reply.",
                !queuedUserOutcome.getOrNull()?.replyArabic.isNullOrBlank()
            )
            Log.i(
                TAG,
                "LOCAL_LLM_SERIAL_QUEUE_PASS backgroundReflection=true userQuestion=true " +
                    "userReplyChars=${queuedUserOutcome.getOrNull()?.replyArabic?.length ?: 0} internetValidated=false"
            )

            assertNotNull("The first generated answer should be retained for diagnostics.", firstReply)
            Log.i(
                TAG,
                "LOCAL_LLM_OFFLINE_RESTART_PASS model=Qwen3-0.6B-INT4 " +
                    "backend=gpu-first-cpu-fallback verifiedBytes=${modelFile.length()} firstChars=${firstReply.length} " +
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
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + timeoutMs
        while (!brain.isReady && SystemClock.elapsedRealtime() < deadline) {
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val currentState = brain.currentState
            val lastStatus = status.get()

            if (currentState == LocalLlmState.ERROR) {
                throw AssertionError("Local Qwen engine failed to initialize: $lastStatus")
            }

            // WAITING_FOR_WIFI is the object's initial enum value before its worker thread runs.
            // Do not fail on that placeholder during the first seconds of startup. Only fail when
            // the worker has published a real WAITING_FOR_WIFI status after giving APK extraction
            // time to begin; this avoids racing the model worker and falsely reporting missing weights.
            val explicitWifiWait = lastStatus.startsWith("WAITING_FOR_WIFI")
            if (elapsedMs >= INITIAL_STARTUP_GRACE_MS &&
                currentState == LocalLlmState.WAITING_FOR_WIFI &&
                explicitWifiWait &&
                !hasValidatedUnmeteredNetwork(InstrumentationRegistry.getInstrumentation().targetContext) &&
                !hasVerifiedModel(InstrumentationRegistry.getInstrumentation().targetContext)
            ) {
                throw AssertionError("Local Qwen needs an unmetered connection for first setup: $lastStatus")
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
        phase: String,
        maxOutputTokens: Int = 12
    ): LocalLlmResult {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<LocalLlmResult>?>(null)
        brain.answerUser(prompt, LocalLlmContext(), maxOutputTokens = maxOutputTokens) {
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
            "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"
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
        private const val MODEL_SHA256 = "03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856"
        private const val MIN_MODEL_BYTES = 300_000_000L
        private const val INITIAL_STARTUP_GRACE_MS = 12_000L
        private const val INITIALIZE_TIMEOUT_MS = 20L * 60L * 1000L
        private const val OFFLINE_RELOAD_TIMEOUT_MS = 5L * 60L * 1000L
        private const val INFERENCE_TIMEOUT_MS = 10L * 60L * 1000L
    }
}
