package com.eve.robot

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class EvePersonalHearingTest {
    @Test fun normalizesArabicText() {
        assertEquals("انا هنا", EvePersonalHearing.normalize("أَنَا هُنا؟"))
    }

    @Test fun onlyExplicitCorrectionPhrasesAreParsed() {
        assertEquals("افتح الباب", EvePersonalHearing.parseCorrection("لا، أقصد افتح الباب"))
        assertNull(EvePersonalHearing.parseCorrection("أنا أتكلم عن الباب"))
    }

    @Test fun extractsStableFeaturesFromAudio() {
        val sampleRate = 16_000
        val pcm = ShortArray(sampleRate / 2) { i ->
            (sin(2.0 * PI * 440.0 * i / sampleRate) * 12_000).toInt().toShort()
        }
        val features = EveMfcc.extract(pcm, sampleRate)
        assertTrue(features.size > 10)
        assertEquals(13, features.first().size)
        assertTrue(EveMfcc.dtwDistance(features, features) < 0.001f)
    }

    @Test fun rejectsEmptyAudio() {
        assertTrue(EveMfcc.extract(shortArrayOf(), 16_000).isEmpty())
    }
}
