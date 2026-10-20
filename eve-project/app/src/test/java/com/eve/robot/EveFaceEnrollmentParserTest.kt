package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EveFaceEnrollmentParserTest {
    @Test fun explicitMotherFaceRequestPreservesDisplayedName() {
        val request = EveFaceEnrollmentParser.parse("احفظ وجه أمي اسمها فاطمة")
        assertEquals("mother", request?.person?.key)
        assertEquals("فاطمة", request?.person?.name)
        assertEquals("أمي اسمها فاطمة", request?.spokenIntroduction)
    }

    @Test fun explicitFriendFaceRequestIsDistinctFromOrdinaryNameMemory() {
        val request = EveFaceEnrollmentParser.parse("تذكري وجه صديقي اسمه أحمد")
        assertEquals("friend", request?.person?.key)
        assertEquals("أحمد", request?.person?.name)
    }

    @Test fun ordinaryIntroductionDoesNotAutomaticallyEnrollBiometrics() {
        assertNull(EveFaceEnrollmentParser.parse("هذا صديقي اسمه أحمد"))
        assertNull(EveFaceEnrollmentParser.parse("أمي اسمها فاطمة"))
    }

    @Test fun rejectFaceEnrollmentWhenNoRelationshipAndNameCanBeParsed() {
        assertNull(EveFaceEnrollmentParser.parse("احفظ وجه هذا الشخص"))
        assertNull(EveFaceEnrollmentParser.parse("تذكر وجه أمي اسمها"))
    }
}
