package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvePeopleMemoryTest {
    @Test fun parsesSelfIntroductionAndKeepsArabicSpelling() {
        val person = EvePersonUtteranceParser.parseIntroduction("أنا اسمي محمد")
        assertEquals("user", person?.key)
        assertEquals("محمد", person?.name)
    }

    @Test fun parsesMotherIntroductionAndPreservesTaaMarbuta() {
        val person = EvePersonUtteranceParser.parseIntroduction("أمي اسمها فاطمة")
        assertEquals("mother", person?.key)
        assertEquals("الأم", person?.relationArabic)
        assertEquals("فاطمة", person?.name)
    }

    @Test fun parsesIntroducedFriend() {
        val person = EvePersonUtteranceParser.parseIntroduction("هذا صديقي اسمه أحمد")
        assertEquals("friend", person?.key)
        assertEquals("أحمد", person?.name)
    }

    @Test fun parsesIntroducedFemaleFriend() {
        val person = EvePersonUtteranceParser.parseIntroduction("صديقتي اسمها سارة")
        assertEquals("friend_female", person?.key)
        assertEquals("سارة", person?.name)
    }

    @Test fun rejectsEmptyOrOverlongNames() {
        assertNull(EvePersonUtteranceParser.parseIntroduction("أمي اسمها"))
        assertNull(EvePersonUtteranceParser.parseIntroduction("صديقي اسمه هذا"))
        assertNull(EvePersonUtteranceParser.parseIntroduction("أبي اسمه محمد علي حسن إبراهيم"))
    }

    @Test fun rejectsOrdinaryConversationThatIsNotAnIntroduction() {
        assertNull(EvePersonUtteranceParser.parseIntroduction("كيف حال أمي اليوم"))
        assertNull(EvePersonUtteranceParser.parseIntroduction("أحب أصدقائي"))
    }
}
