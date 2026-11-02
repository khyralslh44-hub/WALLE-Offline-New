package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArabicCommandEarTest {
    @Test fun normalizesArabicOrthography() {
        assertEquals("هل انت هنا", ArabicCommandEar.normalize("هَلْ أَنْتَ هُنا؟"))
    }

    @Test fun recognizesOnlyExplicitWholePhrases() {
        assertEquals(ArabicCommandEar.Intent.LOOK, ArabicCommandEar.classify("انظري حولك")?.intent)
        assertNull(ArabicCommandEar.classify("أنا أريد أن أنظر حولي"))
        assertNull(ArabicCommandEar.classify("كلام عابر غير معروف"))
    }

    @Test fun recognizesQuietAndStopCommands() {
        assertEquals(ArabicCommandEar.Intent.QUIET, ArabicCommandEar.classify("لا تتكلمي")?.intent)
        assertEquals(ArabicCommandEar.Intent.STOP_EXPLORING, ArabicCommandEar.classify("أوقفي الاستكشاف")?.intent)
    }

    @Test fun recognizesGreetingAndPresence() {
        assertEquals(ArabicCommandEar.Intent.GREET, ArabicCommandEar.classify("صباح الخير")?.intent)
        assertEquals(ArabicCommandEar.Intent.PRESENCE, ArabicCommandEar.classify("هل أنت هنا؟")?.intent)
    }
}
