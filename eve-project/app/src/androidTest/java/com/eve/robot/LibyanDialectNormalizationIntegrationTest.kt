package com.eve.robot

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression checks for everyday Libyan expressions after conservative normalization.
 * These tests validate text handling and intent anchors, not human-level understanding.
 */
@RunWith(AndroidJUnit4::class)
class LibyanDialectNormalizationIntegrationTest {
    @Test
    fun commonLibyanWordsNormalizeToStableArabicForms() {
        val cases = listOf(
            "تقدري تديري هذا غدوة" to "تستطيعين تفعلين هذا غدا",
            "مانبيش" to "لا اريد",
            "وقتاش" to "متى",
            "نحكوا" to "نتحدث",
            "قوليلي" to "اخبريني",
            "وريني" to "اريني",
            "امبارح" to "امس",
            "هني" to "هنا",
            "غادي" to "هناك",
            "موش" to "ليس"
        )

        cases.forEach { (spoken, expected) ->
            assertEquals(
                "Unexpected normalization for Libyan phrase: $spoken",
                expected,
                EveArabicTextUnderstanding.normalize(spoken)
            )
        }
    }

    @Test
    fun LibyanQuestionWordsRetainExistingIntentAnchors() {
        assertEquals("wellbeing", EveTinyArabicBrain.classify("شنية اخبارك؟").intent)
        assertEquals("mood", EveTinyArabicBrain.classify("شنية مزاجك").intent)
        assertEquals("identity", EveTinyArabicBrain.classify("شن اسمك").intent)
        assertEquals("wellbeing", EveTinyArabicBrain.classify("شن حالك؟").intent)
    }
}
