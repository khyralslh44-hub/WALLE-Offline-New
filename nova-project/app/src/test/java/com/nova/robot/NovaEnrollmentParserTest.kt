package com.nova.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovaEnrollmentParserTest {
    @Test
    fun parsesMotherAndFatherIntroductions() {
        assertEquals(
            NovaEnrollmentRequest("الأم", "فاطمة"),
            NovaEnrollmentParser.parse("احفظ وجه أمي اسمها فاطمة")
        )
        assertEquals(
            NovaEnrollmentRequest("الأب", "أحمد"),
            NovaEnrollmentParser.parse("احفظ وجه أبي اسمه أحمد")
        )
    }

    @Test
    fun parsesFriendsAndGrandparents() {
        assertEquals(
            NovaEnrollmentRequest("الصديق", "سامر"),
            NovaEnrollmentParser.parse("احفظ وجه صديقي اسمه سامر")
        )
        assertEquals(
            NovaEnrollmentRequest("الصديقة", "ليلى"),
            NovaEnrollmentParser.parse("احفظي وجه صديقتي اسمها ليلى")
        )
        assertEquals(
            NovaEnrollmentRequest("الجدة", "مريم"),
            NovaEnrollmentParser.parse("تذكري وجه جدتي اسمها مريم")
        )
    }

    @Test
    fun refusesUnrecognizedOrIncompleteIntroductions() {
        assertNull(NovaEnrollmentParser.parse("احفظ وجه أمي"))
        assertNull(NovaEnrollmentParser.parse("احفظ وجه أمي اسمها"))
        assertNull(NovaEnrollmentParser.parse("احفظ هذا الوجه"))
        assertNull(NovaEnrollmentParser.parse("احفظ وجه صديقي اسمه أحمد بن محمد علي"))
    }
}
