package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class EveCalendarKnowledgeTest {
    @Test fun seasonsAreOppositeAcrossHemispheres() {
        assertEquals(EveSeason.SPRING, EveCalendarKnowledge.seasonForMonth(4, EveHemisphere.NORTHERN))
        assertEquals(EveSeason.AUTUMN, EveCalendarKnowledge.seasonForMonth(4, EveHemisphere.SOUTHERN))
        assertEquals(EveSeason.SUMMER, EveCalendarKnowledge.seasonForMonth(7, EveHemisphere.NORTHERN))
        assertEquals(EveSeason.WINTER, EveCalendarKnowledge.seasonForMonth(7, EveHemisphere.SOUTHERN))
        assertEquals(EveSeason.UNKNOWN, EveCalendarKnowledge.seasonForMonth(13, EveHemisphere.NORTHERN))
    }

    @Test fun tabularHijriDateRecognizesEidDaysApproximately() {
        val fitr = EveCalendarKnowledge.hijriDateFromGregorian(2026, 3, 20)
        val adha = EveCalendarKnowledge.hijriDateFromGregorian(2026, 5, 27)
        assertEquals(EveHijriDate(1447, 10, 1), fitr)
        assertEquals(EveHijriDate(1447, 12, 10), adha)
    }

    @Test fun eidAnswersMentionMoonSightingUncertainty() {
        val fitr = EveCalendarKnowledge.holidayAnswer(
            "متى عيد الفطر",
            localNoon(2026, Calendar.OCTOBER, 21)
        )
        val adha = EveCalendarKnowledge.holidayAnswer(
            "متى عيد الأضحى",
            localNoon(2026, Calendar.OCTOBER, 21)
        )
        assertNotNull(fitr)
        assertNotNull(adha)
        assertTrue(fitr!!.contains("تقريبي"))
        assertTrue(fitr.contains("رؤية الهلال"))
        assertTrue(adha!!.contains("تقريبي"))
        assertTrue(adha.contains("رؤية الهلال"))
    }

    @Test fun dateSummaryContainsCalendarAndLocalDateContext() {
        val summary = EveCalendarKnowledge.todaySummary(
            localNoon(2026, Calendar.OCTOBER, 21),
            EveHemisphere.NORTHERN,
            Locale.forLanguageTag("ar-NL"),
            TimeZone.getTimeZone("Europe/Amsterdam")
        )
        assertTrue(summary.contains("تاريخ الهاتف المحلي"))
        assertTrue(summary.contains("الخريف"))
        assertTrue(summary.contains("التاريخ الهجري"))
        assertTrue(summary.contains("عيد الفطر القادم"))
        assertTrue(summary.contains("عيد الأضحى القادم"))
    }

    @Test fun seasonAnswerDoesNotInferWeatherFromDate() {
        val answer = EveCalendarKnowledge.seasonAnswer(
            localNoon(2026, Calendar.JULY, 10),
            EveHemisphere.NORTHERN,
            Locale.forLanguageTag("ar-NL"),
            TimeZone.getTimeZone("Europe/Amsterdam")
        )
        assertTrue(answer.contains("الصيف"))
        assertTrue(answer.contains("ليس تأكيدًا من الطقس وحده"))
    }

    private fun localNoon(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("Europe/Amsterdam"), Locale.forLanguageTag("ar-NL")).apply {
            clear()
            set(year, month, day, 12, 0, 0)
        }.timeInMillis
}
