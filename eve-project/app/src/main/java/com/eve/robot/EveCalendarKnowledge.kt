package com.eve.robot

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.floor

data class EveHijriDate(val year: Int, val month: Int, val day: Int)
enum class EveIslamicOccasion { EID_FITR, EID_ADHA }

/** On-device date knowledge. Tabular Hijri holiday dates are estimates, not official moon sightings. */
object EveCalendarKnowledge {
    fun seasonForMonth(month: Int, hemisphere: EveHemisphere): EveSeason {
        if (month !in 1..12 || hemisphere == EveHemisphere.UNKNOWN) return EveSeason.UNKNOWN
        val northern = when (month) {
            3, 4, 5 -> EveSeason.SPRING
            6, 7, 8 -> EveSeason.SUMMER
            9, 10, 11 -> EveSeason.AUTUMN
            else -> EveSeason.WINTER
        }
        if (hemisphere == EveHemisphere.NORTHERN) return northern
        return when (northern) {
            EveSeason.SPRING -> EveSeason.AUTUMN
            EveSeason.SUMMER -> EveSeason.WINTER
            EveSeason.AUTUMN -> EveSeason.SPRING
            EveSeason.WINTER -> EveSeason.SUMMER
            EveSeason.UNKNOWN -> EveSeason.UNKNOWN
        }
    }

    fun hijriDateFromGregorian(year: Int, month: Int, day: Int): EveHijriDate {
        require(month in 1..12 && day in 1..31) { "Invalid Gregorian date" }
        val jd = gregorianToJdn(year, month, day)
        var hy = floor((30.0 * (jd - 1948440) + 10646.0) / 10631.0).toInt().coerceAtLeast(1)
        var hm = (ceil((jd - (29.0 + islamicToJdn(hy, 1, 1))) / 29.5).toInt() + 1).coerceIn(1, 12)
        while (hm > 1 && jd < islamicToJdn(hy, hm, 1)) hm--
        while (hm < 12 && jd >= islamicToJdn(hy, hm + 1, 1)) hm++
        if (jd < islamicToJdn(hy, 1, 1)) { hy--; hm = 12 }
        return EveHijriDate(hy, hm, jd - islamicToJdn(hy, hm, 1) + 1)
    }

    fun todaySummary(
        nowMillis: Long,
        hemisphere: EveHemisphere,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val cal = Calendar.getInstance(timeZone, locale).apply { timeInMillis = nowMillis }
        val fmt = SimpleDateFormat("EEEE d MMMM yyyy", locale).apply { this.timeZone = timeZone }
        val hijri = hijriDateFromGregorian(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        val season = seasonForMonth(cal.get(Calendar.MONTH) + 1, hemisphere)
        val nextFitr = nextOccasion(nowMillis, EveIslamicOccasion.EID_FITR, locale, timeZone)
        val nextAdha = nextOccasion(nowMillis, EveIslamicOccasion.EID_ADHA, locale, timeZone)
        return buildList {
            add("تاريخ الهاتف المحلي: ${fmt.format(Date(nowMillis))}.")
            if (season != EveSeason.UNKNOWN) add("الفصل التقويمي: ${seasonArabic(season)}، حسب الشهر ونصف الكرة الأرضية.")
            add("التاريخ الهجري الحسابي التقريبي: ${hijri.day} ${hijriMonthArabic(hijri.month)} ${hijri.year} هـ.")
            occasionOnHijriDate(hijri)?.let { add("قد يوافق التاريخ حسابيًا ${it}؛ الموعد الرسمي قد يختلف يومًا حسب البلد ورؤية الهلال.") }
            add("عيد الفطر القادم حسابيًا: ${nextFitr}.")
            add("عيد الأضحى القادم حسابيًا: ${nextAdha}.")
        }.joinToString(" ")
    }

    fun holidayAnswer(question: String, nowMillis: Long, locale: Locale = Locale.getDefault(), timeZone: TimeZone = TimeZone.getDefault()): String? {
        val q = question.lowercase(Locale.ROOT)
        val occasion = when {
            listOf("عيد الفطر", "الفطر", "eid al fitr", "eid fitr").any(q::contains) -> EveIslamicOccasion.EID_FITR
            listOf("عيد الأضحى", "عيد الاضحى", "الأضحى", "الاضحى", "eid al adha", "eid al-adha").any(q::contains) -> EveIslamicOccasion.EID_ADHA
            else -> return null
        }
        val cal = Calendar.getInstance(timeZone, locale).apply { timeInMillis = nowMillis }
        val h = hijriDateFromGregorian(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        val name = if (occasion == EveIslamicOccasion.EID_FITR) "عيد الفطر" else "عيد الأضحى"
        if ((occasion == EveIslamicOccasion.EID_FITR && h.month == 10 && h.day in 1..3) ||
            (occasion == EveIslamicOccasion.EID_ADHA && h.month == 12 && h.day in 10..13)) {
            return "وفق الحساب الهجري التقريبي، نحن في أيام ${name}. قد يختلف التاريخ الرسمي يومًا حسب البلد ورؤية الهلال."
        }
        return "الموعد الحسابي القادم المتوقع لـ${name} هو ${nextOccasion(nowMillis, occasion, locale, timeZone)}. هذا تقدير دون إنترنت وليس إعلانًا رسميًا؛ قد يختلف يومًا حسب البلد ورؤية الهلال."
    }

    fun seasonAnswer(nowMillis: Long, hemisphere: EveHemisphere, locale: Locale = Locale.getDefault(), timeZone: TimeZone = TimeZone.getDefault()): String {
        val cal = Calendar.getInstance(timeZone, locale).apply { timeInMillis = nowMillis }
        val season = seasonForMonth(cal.get(Calendar.MONTH) + 1, hemisphere)
        val fmt = SimpleDateFormat("EEEE d MMMM yyyy", locale).apply { this.timeZone = timeZone }
        return if (season == EveSeason.UNKNOWN) "لم أحدد نصف الكرة الأرضية من إعدادات الهاتف بثقة كافية، لذلك لن أخمن الفصل."
        else "وفق تاريخ الهاتف اليوم ${fmt.format(Date(nowMillis))}، الفصل التقويمي هو ${seasonArabic(season)}. هذا استنتاج من التاريخ وليس تأكيدًا من الطقس وحده."
    }

    private fun nextOccasion(nowMillis: Long, occasion: EveIslamicOccasion, locale: Locale, timeZone: TimeZone): String {
        val cursor = Calendar.getInstance(timeZone, locale).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val fmt = SimpleDateFormat("EEEE d MMMM yyyy", locale).apply { this.timeZone = timeZone }
        repeat(370) {
            val h = hijriDateFromGregorian(cursor.get(Calendar.YEAR), cursor.get(Calendar.MONTH) + 1, cursor.get(Calendar.DAY_OF_MONTH))
            val match = if (occasion == EveIslamicOccasion.EID_FITR) h.month == 10 && h.day == 1 else h.month == 12 && h.day == 10
            if (match) return fmt.format(cursor.time) + " (تقريبي)"
            cursor.add(Calendar.DAY_OF_MONTH, 1)
        }
        return "غير محدد"
    }

    private fun occasionOnHijriDate(h: EveHijriDate): String? = when {
        h.month == 10 && h.day in 1..3 -> "عيد الفطر"
        h.month == 12 && h.day in 10..13 -> "عيد الأضحى"
        else -> null
    }
    private fun hijriMonthArabic(m: Int) = when (m) {
        1 -> "محرم"; 2 -> "صفر"; 3 -> "ربيع الأول"; 4 -> "ربيع الآخر"; 5 -> "جمادى الأولى"; 6 -> "جمادى الآخرة"
        7 -> "رجب"; 8 -> "شعبان"; 9 -> "رمضان"; 10 -> "شوال"; 11 -> "ذو القعدة"; 12 -> "ذو الحجة"
        else -> "شهر غير معروف"
    }
    private fun seasonArabic(s: EveSeason) = when (s) {
        EveSeason.SPRING -> "الربيع"; EveSeason.SUMMER -> "الصيف"; EveSeason.AUTUMN -> "الخريف"
        EveSeason.WINTER -> "الشتاء"; EveSeason.UNKNOWN -> "غير محدد"
    }
    private fun gregorianToJdn(year: Int, month: Int, day: Int): Int {
        val a = (14 - month) / 12
        val y = year + 4800 - a
        val m = month + 12 * a - 3
        return day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
    }
    private fun islamicToJdn(year: Int, month: Int, day: Int): Int =
        day + ceil(29.5 * (month - 1)).toInt() + (year - 1) * 354 + (3 + 11 * year) / 30 + 1948439
}
