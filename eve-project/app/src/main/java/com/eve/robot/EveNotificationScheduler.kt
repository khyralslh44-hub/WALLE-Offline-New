package com.eve.robot

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlin.math.abs

/**
 * Offline reminder layer. Messages are composed locally from time, object-label counts,
 * and game history. It does not contact a server or imply human-like awareness.
 */
object EveNotificationScheduler {
    const val ACTION_REMINDER = "com.eve.robot.ACTION_CURIOSITY_REMINDER"
    const val ACTION_SNOOZE = "com.eve.robot.ACTION_SNOOZE_REMINDER"
    const val CHANNEL_ID = "eve_companion"
    private const val PREFS = "eve_relationship"
    private const val REQUEST_REMINDER = 7301
    private const val REQUEST_OPEN_GAMES = 7302
    private const val NOTIFICATION_ID = 7303
    private const val QUIET_DELAY_MS = 45L * 60L * 1000L
    private const val REPEAT_DELAY_MS = 6L * 60L * 60L * 1000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "رسائل إيف", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "رسائل فضول وتحديات محلية من الروبوت"
                setShowBadge(true)
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun reminderPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, EveNotificationReceiver::class.java).setAction(ACTION_REMINDER)
        return PendingIntent.getBroadcast(
            context, REQUEST_REMINDER, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun schedule(context: Context, delayMs: Long) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(60_000L)
        try {
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, reminderPendingIntent(context))
        } catch (error: SecurityException) {
            Log.w("EVE_NOTIFY", "Could not schedule local reminder.", error)
        }
    }

    private fun cancel(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try { alarm.cancel(reminderPendingIntent(context)) } catch (_: Exception) { }
    }

    fun onForeground(context: Context) {
        prefs(context).edit().putLong("lastForegroundAt", System.currentTimeMillis()).apply()
        cancel(context)
    }

    fun onBackground(context: Context) {
        prefs(context).edit().putLong("lastBackgroundAt", System.currentTimeMillis()).apply()
        schedule(context, QUIET_DELAY_MS)
    }

    fun onBoot(context: Context) {
        ensureChannel(context)
        val p = prefs(context)
        if (p.getLong("lastBackgroundAt", 0L) > p.getLong("lastForegroundAt", 0L)) {
            schedule(context, 5L * 60L * 1000L)
        }
    }

    fun recordGameResult(context: Context, game: String, result: String) {
        val key = game.trim().lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(24).ifBlank { "game" }
        val p = context.getSharedPreferences("eve_games", Context.MODE_PRIVATE)
        p.edit()
            .putInt("games:" + key + ":played", p.getInt("games:" + key + ":played", 0) + 1)
            .putInt("games:" + key + ":" + result, p.getInt("games:" + key + ":" + result, 0) + 1)
            .putLong("games:lastAt", System.currentTimeMillis())
            .putString("games:lastName", key)
            .putString("games:lastResult", result)
            .apply()
    }

    fun deliver(context: Context) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val backgroundAt = p.getLong("lastBackgroundAt", 0L)
        val foregroundAt = p.getLong("lastForegroundAt", 0L)
        if (backgroundAt <= 0L || foregroundAt >= backgroundAt) {
            cancel(context)
            return
        }
        val quietFor = now - backgroundAt
        if (quietFor < QUIET_DELAY_MS - 2_000L) {
            schedule(context, QUIET_DELAY_MS - quietFor)
            return
        }

        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i("EVE_NOTIFY", "Notification permission not granted; reminder skipped without prompting in background.")
            schedule(context, REPEAT_DELAY_MS)
            return
        }

        val (title, body) = composeMessage(context, now)
        val openGames = PendingIntent.getActivity(
            context, REQUEST_OPEN_GAMES,
            Intent(context, RobotGamesActivity::class.java)
                .putExtra("fromNotification", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snooze = PendingIntent.getBroadcast(
            context, REQUEST_REMINDER + 1,
            Intent(context, EveNotificationReceiver::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_eve_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openGames)
            .addAction(0, "العب معي", openGames)
            .addAction(0, "لاحقًا", snooze)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            p.edit().putInt("sentCount", p.getInt("sentCount", 0) + 1).putLong("lastSentAt", now).apply()
            Log.i("EVE_NOTIFY", "Reminder posted locally; quietForMs=" + quietFor + ".")
        } catch (error: SecurityException) {
            Log.w("EVE_NOTIFY", "System denied notification delivery.", error)
        }
        schedule(context, REPEAT_DELAY_MS)
    }

    private fun composeMessage(context: Context, now: Long): Pair<String, String> {
        val relationship = prefs(context)
        val sent = relationship.getInt("sentCount", 0)
        val localMemory = context.getSharedPreferences("eve_local_memory", Context.MODE_PRIVATE)
        val objectCount = localMemory.all.entries
            .filter { it.key.startsWith("count:") && it.value is Int }
            .map { it.key.removePrefix("count:") to (it.value as Int) }
            .filter { it.first.isNotBlank() && it.second > 0 }
            .maxByOrNull { it.second }

        val games = context.getSharedPreferences("eve_games", Context.MODE_PRIVATE)
        val lastGameAt = games.getLong("games:lastAt", 0L)
        val lastGameName = games.getString("games:lastName", null)
        val lastResult = games.getString("games:lastResult", null)
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        val salt = abs((now / (60L * 60L * 1000L)).toInt() + sent * 7 + (objectCount?.second ?: 0))
        val openings = listOf(
            "علقت في بالي فكرة",
            "تكوّنت عندي فرضية صغيرة",
            "لاحظت نمطًا وأريد أن أختبره معك",
            "أشعر أن لدي سؤالًا يستحق التجربة",
            "وصلت إلى تحدٍ جديد"
        )
        val body = when {
            lastGameAt > 0L && now - lastGameAt < 3L * 24L * 60L * 60L * 1000L &&
                lastGameName != null && lastResult == "player" ->
                "أتذكّر نتيجتنا الأخيرة في " + gameArabic(lastGameName) + ". " +
                    listOf("أريد إعادة المباراة بخطة مختلفة.", "هذه المرة لن أكرر الخطأ نفسه.", "هل تستطيع الفوز مرتين متتاليتين؟")[salt % 3] +
                    " افتحني لجولة جديدة."
            lastGameAt > 0L && now - lastGameAt < 3L * 24L * 60L * 60L * 1000L &&
                lastGameName != null && lastResult == "robot" ->
                "آخر مرة في " + gameArabic(lastGameName) + " كانت النتيجة لصالحي. " +
                    listOf("هل تغيّر خطتك هذه المرة؟", "أريد أن أرى إن كنت قد اكتشفت أسلوبي.", "تحدٍّ جديد؟")[salt % 3] +
                    " اضغط «العب معي» إن رغبت."
            objectCount != null ->
                openings[salt % openings.size] + ": رأيت " + objectCount.first + " " +
                    (if (objectCount.second == 1) "مرة واحدة" else "في " + objectCount.second + " ملاحظات") +
                    " في تجاربنا السابقة. " +
                    listOf(
                        "ما التفصيل الذي ينبغي أن ألاحظه في المرة القادمة؟",
                        "هل تخمّن ما الذي قد يميّزه عن شيء مشابه؟",
                        "هل تختبر دقّة ملاحظتي عندما تفتح الكاميرا مجددًا؟",
                        "ربما أحتاج إلى مقارنة جديدة بدل الاكتفاء بالاسم."
                    )[salt % 4]
            hour < 11 ->
                openings[salt % openings.size] + ": لو بدأنا اليوم بتحدٍّ صغير، أيهما أصعب برأيك—اكتشاف نمط أم توقّع حركة منافس؟"
            hour >= 21 ->
                "قبل أن أهدأ، أريد سؤالًا واحدًا: ما الشيء الذي يمكن أن أتعلمه من خسارة في لعبة بدل أن أكرر الحركة نفسها؟"
            else ->
                openings[salt % openings.size] + ": لو كان عليّ أن أختار بين الفضول والحذر في تجربة جديدة، ما الاختبار الذي تقترحه؟"
        }
        val title = when {
            lastResult == "player" -> "إيف تتحدّاك من جديد"
            lastResult == "robot" -> "إيف لديها سؤال لك"
            sent % 3 == 1 -> "فكرة جديدة خطرت لإيف"
            else -> "إيف ما زالت فضولية"
        }
        return title to body
    }

    private fun gameArabic(name: String): String = when (name) {
        "chess" -> "الشطرنج"
        "xo" -> "إكس-أو"
        "rps" -> "حجر ورقة مقص"
        "target" -> "التصويب"
        "memory" -> "اختبار الذاكرة"
        else -> "اللعبة"
    }
}

class EveNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> EveNotificationScheduler.onBoot(context)
            EveNotificationScheduler.ACTION_SNOOZE -> {
                val p = context.getSharedPreferences("eve_relationship", Context.MODE_PRIVATE)
                p.edit().putLong("lastBackgroundAt", System.currentTimeMillis() - 12L * 60L * 60L * 1000L).apply()
                val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                val pending = PendingIntent.getBroadcast(
                    context, 7301,
                    Intent(context, EveNotificationReceiver::class.java).setAction(EveNotificationScheduler.ACTION_REMINDER),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                try {
                    alarm?.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 12L * 60L * 60L * 1000L,
                        pending
                    )
                } catch (_: Exception) { }
            }
            EveNotificationScheduler.ACTION_REMINDER -> EveNotificationScheduler.deliver(context)
        }
    }
}
