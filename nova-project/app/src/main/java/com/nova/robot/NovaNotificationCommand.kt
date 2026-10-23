package com.nova.robot

import java.util.Locale

internal sealed class NovaNotificationCommand {
    object EnableDaily : NovaNotificationCommand()
    object DisableDaily : NovaNotificationCommand()
    object EnableAutonomous : NovaNotificationCommand()
    object DisableAutonomous : NovaNotificationCommand()
    object Test : NovaNotificationCommand()
    data class Reminder(val delayMillis: Long, val spokenDelay: String, val message: String) : NovaNotificationCommand()
}

/** Parses explicit Arabic notification requests; it does not schedule or notify by itself. */
internal object NovaNotificationCommandParser {
    fun parse(rawText: String): NovaNotificationCommand? {
        val normalized = normalize(rawText).trim().trimEnd('.', '!', '?', '؟')
        when (normalized) {
            "فعلي اشعارات نوفا", "شغلي اشعارات نوفا", "فعلي الاشعارات", "شغلي الاشعارات",
            "فعلي التذكيرات", "شغلي التذكيرات" -> return NovaNotificationCommand.EnableDaily
            "اوقفي اشعارات نوفا", "عطلي اشعارات نوفا", "اوقفي الاشعارات", "عطلي الاشعارات",
            "اوقفي التذكيرات" -> return NovaNotificationCommand.DisableDaily
            "فعلي اشعارات نوفا التلقائية", "فعلي الرسائل التلقائية", "اجعلي نوفا تختار وقت الاشعار" -> return NovaNotificationCommand.EnableAutonomous
            "اوقفي اشعارات نوفا التلقائية", "اوقفي الرسائل التلقائية", "عطلي الاشعارات التلقائية" -> return NovaNotificationCommand.DisableAutonomous
            "اختبري اشعارات نوفا", "اختبري الاشعارات", "ارسلي اشعارا تجريبيا",
            "ارسلي اشعار تجريبي", "ارسل اشعارا تجريبيا" -> return NovaNotificationCommand.Test
        }

        val match = REMINDER.matchEntire(normalized) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        if (amount <= 0L) return null
        val unit = match.groupValues[2]
        val multiplier = when (unit) {
            "دقيقة", "دقيقه", "دقيقتين", "دقائق" -> 60_000L
            "ساعة", "ساعه", "ساعتين", "ساعات" -> 3_600_000L
            else -> return null
        }
        val delay = runCatching { Math.multiplyExact(amount, multiplier) }.getOrNull() ?: return null
        if (delay !in 60_000L..(30L * 24 * 60 * 60 * 1000)) return null
        val message = match.groupValues[3].trim().take(220)
        if (message.length < 2) return null
        val duration = when {
            multiplier == 60_000L && amount == 1L -> "دقيقة"
            multiplier == 60_000L -> "$amount دقائق"
            amount == 1L -> "ساعة"
            else -> "$amount ساعات"
        }
        return NovaNotificationCommand.Reminder(delay, duration, message)
    }

    private val REMINDER = Regex(
        "^(?:ذكريني|ذكرني) بعد ([0-9]+) (دقيقة|دقيقه|دقيقتين|دقائق|ساعة|ساعه|ساعتين|ساعات) (?:ب|ان)\\s*(.+)$"
    )

    private fun normalize(raw: String): String = raw.trim().lowercase(Locale.ROOT)
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي')
        .replace('٠', '0').replace('١', '1').replace('٢', '2').replace('٣', '3')
        .replace('٤', '4').replace('٥', '5').replace('٦', '6').replace('٧', '7')
        .replace('٨', '8').replace('٩', '9').replace('ـ', ' ')
        .replace(Regex("\\s+"), " ")
}
