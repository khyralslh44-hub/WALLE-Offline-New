package com.nova.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaEveSocialCuePolicyTest {
    @Test fun affectionAndSadnessHaveSupportiveLocalReactions() {
        assertEquals(NovaMood.LOVE, NovaEveSocialCuePolicy.match("أحبك يا نوفا")!!.mood)
        val sad = NovaEveSocialCuePolicy.match("أنا حزين اليوم")!!
        assertEquals(NovaMood.WARM, sad.mood)
        assertTrue(sad.messageArabic.contains("أستمع"))
    }

    @Test fun anotherAssistantMentionTriggersOnlyOnExplicitSwitchingLanguage() {
        assertEquals(NovaMood.JEALOUS,
            NovaEveSocialCuePolicy.match("سأتحدث مع جيميني بدلًا منك")!!.mood)
        assertNull(NovaEveSocialCuePolicy.match("ماذا تعرفين عن جيميني؟"))
    }

    @Test fun praiseInsultPlayAndDeterminationUseDistinctExpressions() {
        assertEquals(NovaMood.EMBARRASSED, NovaEveSocialCuePolicy.match("أنتِ ذكية")!!.mood)
        assertEquals(NovaMood.ANGRY, NovaEveSocialCuePolicy.match("أنت غبي")!!.mood)
        assertEquals(NovaMood.EXCITED, NovaEveSocialCuePolicy.match("هيا نلعب")!!.mood)
        assertEquals(NovaMood.DETERMINED, NovaEveSocialCuePolicy.match("لن أستسلم")!!.mood)
    }

    @Test fun ordinaryUnknownTextIsLeftForTheGeneralOfflineMind() {
        assertNull(NovaEveSocialCuePolicy.match("ما هي عاصمة إيطاليا؟"))
    }
}
