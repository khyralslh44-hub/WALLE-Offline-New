package com.eve.robot

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises EVE's bespoke Arabic model and its priority queue on an Android runtime. */
@RunWith(AndroidJUnit4::class)
class LocalLanguageBrainIntegrationTest {
    @Test
    fun tinyArabicBrainAnswersLocallyAndDoesNotDropUserQuestions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val states = AtomicReference<LocalLlmState?>(null)
        val brain = LocalLanguageBrain(context) { state, percent, message ->
            states.set(state)
            Log.i(TAG, "TINY_BRAIN_STATE state=$state percent=$percent message=$message")
        }

        try {
            brain.start()
            assertTrue("Tiny model should be ready without downloading weights.", brain.isReady)
            assertEquals(LocalLlmState.READY, brain.currentState)

            val greeting = awaitAnswer(brain, "أهلاً يا إيف")
            assertTrue("Arabic greeting returned no text.", greeting.replyArabic.isNotBlank())
            assertTrue("Greeting intent was not recognized.", greeting.confidence >= 0.44f)
            assertEquals("greeting", EveTinyArabicBrain.classify("أهلاً يا إيف").intent)

            // Queue a silent reflection followed immediately by a direct user question.
            val latch = CountDownLatch(2)
            val reflection = AtomicReference<Result<LocalLlmResult>?>(null)
            val userReply = AtomicReference<Result<LocalLlmResult>?>(null)

            brain.reflectOnObservation(
                LocalLlmContext(
                    latestVisionLabel = "cat",
                    visionConfidence = 0.93f,
                    visionObservationAgeMs = 0L,
                    sightingCount = 2
                )
            ) {
                reflection.set(it)
                latch.countDown()
            }
            brain.answerUser("هل تعملين بدون انترنت؟", LocalLlmContext()) {
                userReply.set(it)
                latch.countDown()
            }

            assertTrue(
                "The direct user question was lost while reflection was running.",
                latch.await(30L, TimeUnit.SECONDS)
            )
            assertNotNull("Reflection callback did not arrive.", reflection.get())
            assertNotNull("User reply callback did not arrive.", userReply.get())
            assertTrue("Reflection failed.", reflection.get()!!.isSuccess)
            assertTrue("User reply failed.", userReply.get()!!.isSuccess)
            val answer = userReply.get()!!.getOrThrow()
            assertTrue(
                "Offline answer should describe local operation.",
                listOf("دون اتصال", "محلي", "على الجهاز", "على الهاتف").any(answer.replyArabic::contains)
            )

            val unknown = awaitAnswer(brain, "لماذا تمطر على المريخ اليوم؟")
            assertTrue("Unknown question must be handled honestly.", unknown.replyArabic.contains("لم أفهم"))
            assertEquals(null, EveTinyArabicBrain.classify("لماذا تمطر على المريخ اليوم؟").intent)

            assertFalse("Tiny model must not report the old model as downloading.", brain.currentState == LocalLlmState.DOWNLOADING)
            Log.i(
                TAG,
                "TINY_ARABIC_BRAIN_ANDROID_PASS engine=hashed_char_ngram queue=reflection_then_user " +
                    "offline=true greetingChars=${greeting.replyArabic.length} " +
                    "answerChars=${answer.replyArabic.length}"
            )
        } finally {
            brain.close()
        }
    }

    private fun awaitAnswer(brain: LocalLanguageBrain, prompt: String): LocalLlmResult {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<LocalLlmResult>?>(null)
        brain.answerUser(prompt, LocalLlmContext()) {
            result.set(it)
            latch.countDown()
        }
        assertTrue("Timed out waiting for local Arabic answer.", latch.await(30L, TimeUnit.SECONDS))
        val outcome = result.get()
        assertNotNull("No inference result container was delivered.", outcome)
        return outcome!!.getOrElse { throw AssertionError("Tiny Arabic inference failed.", it) }
    }

    companion object {
        private const val TAG = "EVE_TINY_BRAIN_TEST"
    }
}
