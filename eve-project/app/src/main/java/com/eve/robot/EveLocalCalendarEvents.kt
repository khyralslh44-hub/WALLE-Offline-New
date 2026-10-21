package com.eve.robot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Reads local birthdays only when the user explicitly asks and the relevant runtime permission exists. */
object EveLocalCalendarEvents {
    private data class Birthday(val title: String, val dateMillis: Long)

    fun answerBirthdayQuestion(context: Context, question: String, nowMillis: Long = System.currentTimeMillis()): String {
        val contactsAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val calendarAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val birthdays = mutableListOf<Birthday>()
        if (contactsAllowed) birthdays += fromContacts(context, nowMillis)
        if (calendarAllowed) birthdays += fromCalendar(context, nowMillis)
        val sorted = birthdays.distinctBy { normalize(it.title) to dateKey(it.dateMillis) }.sortedBy { it.dateMillis }
        if (sorted.isEmpty()) {
            return if (!contactsAllowed && !calendarAllowed) {
                "لم تمنح إذن قراءة جهات الاتصال أو التقويم، لذلك لم أفتح بيانات المناسبات."
            } else {
                "لم أجد عيد ميلاد قادمًا محفوظًا في جهات الاتصال أو أحداث التقويم المتاحة على الهاتف."
            }
        }
        val q = normalize(question)
        val named = sorted.filter { birthday ->
            val name = normalize(birthday.title).replace("birthday", "").replace("birth day", "").replace("عيد ميلاد", "").trim()
            name.length >= 2 && q.contains(name)
        }
        val today = dateKey(nowMillis)
        val chosen = when {
            named.isNotEmpty() -> named.take(5)
            sorted.any { dateKey(it.dateMillis) == today } -> sorted.filter { dateKey(it.dateMillis) == today }.take(6)
            else -> sorted.take(6)
        }
        val formatter = SimpleDateFormat("d MMMM", Locale.forLanguageTag("ar"))
        val descriptions = chosen.map { birthday ->
            val days = daysBetween(nowMillis, birthday.dateMillis)
            val relative = when (days) { 0 -> "اليوم"; 1 -> "غدًا"; else -> "بعد $${days} يومًا" }
            "عيد ميلاد $${birthday.title} $${relative} ($${formatter.format(Date(birthday.dateMillis))})"
        }
        return "من البيانات المحلية التي سمحت لي بقراءتها: $${descriptions.joinToString("؛ ")}. لم أرسل بيانات المناسبات خارج الهاتف."
    }

    private fun fromContacts(context: Context, nowMillis: Long): List<Birthday> {
        val result = mutableListOf<Birthday>()
        val projection = arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE)
        val selection = ContactsContract.Data.MIMETYPE + " = ? AND " + ContactsContract.Data.DATA2 + " = ?"
        val args = arrayOf(
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()
        )
        try {
            context.contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, args, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)
                val dateIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Event.START_DATE)
                if (nameIndex < 0 || dateIndex < 0) return@use
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)?.trim().orEmpty()
                    val monthDay = parseMonthDay(cursor.getString(dateIndex)?.trim().orEmpty()) ?: continue
                    if (name.isBlank()) continue
                    nextOccurrence(monthDay.first, monthDay.second, nowMillis)?.let { result += Birthday(name, it) }
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
        return result
    }

    private fun fromCalendar(context: Context, nowMillis: Long): List<Birthday> {
        val result = mutableListOf<Birthday>()
        val start = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val end = Calendar.getInstance().apply { timeInMillis = start.timeInMillis; add(Calendar.DAY_OF_YEAR, 370) }
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(start.timeInMillis.toString()).appendPath(end.timeInMillis.toString()).build()
        val projection = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN)
        try {
            context.contentResolver.query(uri, projection, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cursor ->
                val titleIndex = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIndex = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                if (titleIndex < 0 || beginIndex < 0) return@use
                while (cursor.moveToNext()) {
                    val title = cursor.getString(titleIndex)?.trim().orEmpty()
                    if (title.isBlank() || !isBirthdayTitle(title)) continue
                    val eventTime = cursor.getLong(beginIndex)
                    if (eventTime >= start.timeInMillis) result += Birthday(title, eventTime)
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
        return result
    }

    private fun isBirthdayTitle(title: String): Boolean {
        val value = normalize(title)
        return listOf("birthday", "birth day", "عيد ميلاد", "ميلاد", "anniversaire", "geburtstag").any(value::contains)
    }

    private fun parseMonthDay(value: String): Pair<Int, Int>? {
        val match = Regex("""^(?:\d{4}-|--)(\d{2})-(\d{2})$""").matchEntire(value.trim()) ?: return null
        val month = match.groupValues[1].toIntOrNull() ?: return null
        val day = match.groupValues[2].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        return month to day
    }

    private fun nextOccurrence(month: Int, day: Int, nowMillis: Long): Long? {
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val candidate = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.MONTH, month - 1); set(Calendar.DAY_OF_MONTH, 1); set(Calendar.YEAR, now.get(Calendar.YEAR))
            set(Calendar.DAY_OF_MONTH, day.coerceAtMost(getActualMaximum(Calendar.DAY_OF_MONTH)))
        }
        if (dateKey(candidate.timeInMillis) < dateKey(nowMillis)) {
            candidate.add(Calendar.YEAR, 1)
            candidate.set(Calendar.MONTH, month - 1); candidate.set(Calendar.DAY_OF_MONTH, 1)
            candidate.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(candidate.getActualMaximum(Calendar.DAY_OF_MONTH)))
        }
        val horizon = Calendar.getInstance().apply { timeInMillis = nowMillis; add(Calendar.DAY_OF_YEAR, 370) }
        return candidate.timeInMillis.takeIf { it <= horizon.timeInMillis }
    }

    private fun daysBetween(fromMillis: Long, toMillis: Long): Int {
        val from = Calendar.getInstance().apply { timeInMillis = fromMillis; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val to = Calendar.getInstance().apply { timeInMillis = toMillis; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return ((to.timeInMillis - from.timeInMillis) / 86_400_000L).toInt().coerceAtLeast(0)
    }

    private fun dateKey(millis: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(millis))
    private fun normalize(text: String) = text.lowercase(Locale.ROOT).replace(Regex("[ًٌٍَُِّْـ]"), "").replace(Regex("\\s+"), " ").trim()
}
