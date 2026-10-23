package com.nova.robot

import org.junit.Assert.*
import org.junit.Test

class NovaNotificationCommandParserTest {
    @Test fun parsesDailyEnableDisableAndTestCommands() {
        assertTrue(NovaNotificationCommandParser.parse("فعّلي إشعارات نوفا") is NovaNotificationCommand.EnableDaily)
        assertTrue(NovaNotificationCommandParser.parse("أوقفي إشعارات نوفا") is NovaNotificationCommand.DisableDaily)
        assertTrue(NovaNotificationCommandParser.parse("أرسلي إشعارًا تجريبيًا") is NovaNotificationCommand.Test)
    }

    @Test fun parsesArabicAndWesternDigitReminderCommands() {
        val western = NovaNotificationCommandParser.parse("ذكريني بعد 10 دقائق بشرب الماء") as NovaNotificationCommand.Reminder
        val arabic = NovaNotificationCommandParser.parse("ذكّريني بعد ٢ ساعة أن أراجع الدرس") as NovaNotificationCommand.Reminder
        assertEquals(600_000L, western.delayMillis)
        assertEquals("شرب الماء", western.message)
        assertEquals(7_200_000L, arabic.delayMillis)
        assertEquals("اراجع الدرس", arabic.message)
    }

    @Test fun rejectsEmptyTooShortAndTooLongReminders() {
        assertNull(NovaNotificationCommandParser.parse("ذكريني بعد 0 دقيقة بعمل"))
        assertNull(NovaNotificationCommandParser.parse("ذكريني بعد 31 يوم بشيء"))
        assertNull(NovaNotificationCommandParser.parse("ذكريني بعد 5 دقائق ب"))
        assertNull(NovaNotificationCommandParser.parse("رسالة عادية"))
    }
}
