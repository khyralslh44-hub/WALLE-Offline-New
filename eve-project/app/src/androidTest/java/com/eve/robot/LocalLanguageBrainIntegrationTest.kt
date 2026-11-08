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
        val brain = LocalLanguageBrain(
            context,
            statusListener = { state, percent, message ->
                states.set(state)
                Log.i(TAG, "TINY_BRAIN_STATE state=$state percent=$percent message=$message")
            },
            // Do not block Android's main looper while this instrumentation test waits on a latch.
            callbackDispatcher = { callback -> callback() }
        )

        try {
            brain.start()
            assertTrue("Tiny model should be ready without downloading weights.", brain.isReady)
            assertEquals(LocalLlmState.READY, brain.currentState)

            val greeting = awaitAnswer(brain, "أهلاً يا إيف")
            assertTrue("Arabic greeting returned no text.", greeting.replyArabic.isNotBlank())
            assertTrue("Greeting intent was not recognized.", greeting.confidence >= 0.44f)
            assertEquals("greeting", EveTinyArabicBrain.classify("أهلاً يا إيف").intent)

            val dialectCases = listOf(
                "شلونك يا إيف" to "greeting",
                "وش تشوفين؟" to "vision",
                "وين الكاميرا؟" to "camera",
                "شن حالك يا إيف؟" to "wellbeing",
                "شن تشوفي قدامك؟" to "vision",
                "علاش الكاميرا مش شغالة؟" to "camera",
                "نبي نلعب شطرنج" to "play",
                "يعطيك الصحة يا إيف" to "thanks",
                "مرحبتين يا إيف" to "greeting",
                "شن حوالك؟" to "wellbeing",
                "شن اللي قدامك؟" to "vision",
                "علاش ما تشتغلش الكاميرا؟" to "camera",
                "تخدمي بلا نت؟" to "offline",
                "بارك الله فيك يا إيف" to "thanks",
                "ما قصرتيش يا إيف" to "thanks",
                "في حفظ الله يا إيف" to "farewell",
                "نشوفك غدوة يا إيف" to "farewell",
                "يلا بينا نلعبوا" to "play",
                "تعالي نلعبوا شوية" to "play",
                "فزعتك يا إيف" to "help",
                "نبيك في موضوع" to "help",
                "قداش عمرك؟" to "identity",
                "هلا وغلا" to "greeting",
                "يا مية هلا" to "greeting",
                "شن اخبار الدنيا معاك" to "wellbeing",
                "لاباس عليك" to "wellbeing",
                "شن تقدري تديري" to "capabilities",
                "تخدمي حتى لو النت مقطوع" to "offline",
                "شن هذا اللي قدامك" to "vision",
                "افتحيلي الكاميرا" to "camera",
                "شن مزاجك توا" to "mood",
                "فاكرة شن حكينا امس" to "memory",
                "تعرفيني من وجهي" to "people",
                "نحبك يا ايف" to "affection",
                "تعيشي يا ايف" to "thanks",
                "يلا سلام" to "farewell",
                "خلينا نلعبوا اكس او" to "play",
                "نبي نسألك على حاجة" to "help"
            )
            dialectCases.forEach { (phrase, expected) ->
                assertEquals(
                    "Dialect classification failed on Android for: $phrase",
                    expected,
                    EveTinyArabicBrain.classify(phrase).intent
                )
            }
            Log.i(TAG, "TINY_ARABIC_DIALECT_PASS cases=${dialectCases.size}")

            val userFeeling = awaitAnswer(
                brain,
                "أنا زهقان من الشطرنج",
                LocalLlmContext()
            )
            assertTrue(
                "The semantic composer should interpret a first-person boredom statement.",
                userFeeling.replyArabic.contains("مللت")
            )
            assertEquals("irritated", userFeeling.moodHint)

            val sceneAnswer = awaitAnswer(
                brain,
                "ماذا ترين؟",
                LocalLlmContext(
                    latestVisionLabel = "car",
                    visionConfidence = 0.92f,
                    visionObservationAgeMs = 250L,
                    latestSceneSummary = "سيارة قرب النافذة",
                    sceneObservationAgeMs = 300L
                )
            )
            assertTrue(
                "The reply should be grounded in the fresh visual scene.",
                sceneAnswer.replyArabic.contains("سيارة")
            )

            val followUp = awaitAnswer(
                brain,
                "ولماذا؟",
                LocalLlmContext(
                    recentDialogues = listOf(
                        "المستخدم: هل تعملين بدون إنترنت؟ | إيف: محرك الحوار يعمل محلياً على الهاتف."
                    )
                )
            )
            assertTrue(
                "A short follow-up should link to the preceding topic.",
                followUp.replyArabic.contains("الاتصال بالإنترنت")
            )
            Log.i(TAG, "TINY_ARABIC_SEMANTIC_COMPOSITION_PASS feelings=true scene=true followUp=true")

            // Warm up the tiny classifier once, then measure repeated local answer generation.
            // This is a smoke performance ceiling, not a claim about every phone's exact latency.
            val speedStart = System.nanoTime()
            repeat(100) {
                val generated = EveTinyArabicBrain.answerUser(
                    "كيف حالك اليوم؟",
                    LocalLlmContext(currentMood = "CURIOUS")
                )
                assertTrue("Repeated generation returned an empty reply.", generated.replyArabic.isNotBlank())
            }
            val generationMs = (System.nanoTime() - speedStart) / 1_000_000L
            assertTrue(
                "One hundred tiny-model responses exceeded the 15-second smoke ceiling: ${generationMs}ms",
                generationMs < 15_000L
            )
            Log.i(TAG, "TINY_ARABIC_BRAIN_SPEED_PASS iterations=100 elapsedMs=$generationMs")

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

    private fun awaitAnswer(
        brain: LocalLanguageBrain,
        prompt: String,
        context: LocalLlmContext = LocalLlmContext()
    ): LocalLlmResult {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<LocalLlmResult>?>(null)
        brain.answerUser(prompt, context) {
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
