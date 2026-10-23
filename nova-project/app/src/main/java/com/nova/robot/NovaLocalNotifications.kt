package com.nova.robot

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** All notification text and scheduling are local. No network service is used. */
internal object NovaLocalNotifications {
    const val CHANNEL_ID = "nova_local_reminders"
    const val ACTION_DAILY = "com.nova.robot.action.DAILY_NOTIFICATION"
    const val ACTION_REMINDER = "com.nova.robot.action.ONE_TIME_REMINDER"
    const val ACTION_AUTONOMOUS = "com.nova.robot.action.AUTONOMOUS_NOTIFICATION_CHECK"
    private const val PREFS = "nova_local_notifications"
    private const val DAILY_REQUEST = 19001
    private const val DAILY_ID = 19001
    private const val AUTONOMOUS_REQUEST = 19004
    private const val AUTONOMOUS_ID = 19004
    internal const val EXTRA_MESSAGE_KEY = "nova_notification_message"
    internal const val EXTRA_ID_KEY = "nova_notification_id"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "إشعارات نوفا والتذكيرات", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "تذكيرات محلية يطلبها المستخدم ورسائل نوفا اليومية." })
        }
    }

    fun show(context: Context, title: String, body: String, notificationId: Int): Boolean {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!manager.areNotificationsEnabled()) return false
            val openIntent = Intent(context, NovaActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val contentIntent = PendingIntent.getActivity(
                context, 19000, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build()
            manager.notify(notificationId, notification)
            true
        }.getOrDefault(false)
    }

    fun showTest(context: Context): Boolean =
        show(context, "إشعار تجريبي من نوفا", "نجح إرسال إشعار محلي. أنا هنا عندما تحتاجني.", 19002)

    fun enableDaily(context: Context, hour: Int = 19, minute: Int = 0) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("autonomous_enabled", false)
            .putBoolean("daily_enabled", true)
            .putInt("daily_hour", hour.coerceIn(0, 23))
            .putInt("daily_minute", minute.coerceIn(0, 59))
            .apply()
        alarm(context).cancel(autonomousPendingIntent(context))
        scheduleNextDaily(context)
    }

    fun disableDaily(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("daily_enabled", false).apply()
        alarm(context).cancel(dailyPendingIntent(context))
    }

    fun enableAutonomous(context: Context) {
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("daily_enabled", false).putBoolean("autonomous_enabled", true)
            .putLong("last_interaction_wall", now).putLong("last_autonomous_notification_wall", 0L)
            .putInt("autonomous_message_index", 0).apply()
        alarm(context).cancel(dailyPendingIntent(context))
        scheduleAutonomousCheck(context, now + NovaAutonomousNotificationPolicy.MIN_IDLE_MILLIS)
    }
    fun disableAutonomous(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("autonomous_enabled", false).apply()
        alarm(context).cancel(autonomousPendingIntent(context))
    }
    fun autonomousEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("autonomous_enabled", false)
    fun markInteraction(context: Context, nowWallMillis: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong("last_interaction_wall", nowWallMillis).apply()
        if (prefs.getBoolean("autonomous_enabled", false)) {
            val lastSent = prefs.getLong("last_autonomous_notification_wall", 0L)
            val idleTarget = nowWallMillis + NovaAutonomousNotificationPolicy.MIN_IDLE_MILLIS
            val cooldownTarget = if (lastSent > 0L) lastSent + NovaAutonomousNotificationPolicy.MIN_GAP_MILLIS else idleTarget
            scheduleAutonomousCheck(context, maxOf(idleTarget, cooldownTarget))
        }
    }
    fun restoreAutonomousIfEnabled(context: Context) {
        if (!autonomousEnabled(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastInteraction = prefs.getLong("last_interaction_wall", System.currentTimeMillis())
        val lastSent = prefs.getLong("last_autonomous_notification_wall", 0L)
        val idleTarget = lastInteraction + NovaAutonomousNotificationPolicy.MIN_IDLE_MILLIS
        val cooldownTarget = if (lastSent > 0L) lastSent + NovaAutonomousNotificationPolicy.MIN_GAP_MILLIS else idleTarget
        scheduleAutonomousCheck(context, maxOf(System.currentTimeMillis() + 60_000L, idleTarget, cooldownTarget))
    }
    private fun scheduleAutonomousCheck(context: Context, at: Long) {
        if (!autonomousEnabled(context)) return
        alarm(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(System.currentTimeMillis() + 60_000L, at), autonomousPendingIntent(context))
    }
    internal fun handleAutonomousCheck(context: Context) {
        if (!autonomousEnabled(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val lastInteraction = prefs.getLong("last_interaction_wall", now)
        val lastSent = prefs.getLong("last_autonomous_notification_wall", 0L)
        if (hour < 10 || hour > 20) {
            val next = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 5); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
            scheduleAutonomousCheck(context, next); return
        }
        if (!NovaAutonomousNotificationPolicy.shouldNotify(now, lastInteraction, lastSent, hour)) {
            val idleTarget = if (lastInteraction > 0L) lastInteraction + NovaAutonomousNotificationPolicy.MIN_IDLE_MILLIS else now + NovaAutonomousNotificationPolicy.MIN_IDLE_MILLIS
            val cooldownTarget = if (lastSent > 0L) lastSent + NovaAutonomousNotificationPolicy.MIN_GAP_MILLIS else idleTarget
            scheduleAutonomousCheck(context, maxOf(now + 30L * 60 * 1000, idleTarget, cooldownTarget)); return
        }
        val messages = listOf("مرّ وقت منذ آخر تفاعلنا. هل ترغب في جولة شطرنج أو إكس أو؟", "مرحبًا من نوفا! إن كان لديك وقت، يمكننا اللعب أو التحدث قليلًا.", "فكرت أن أقترح تحديًا صغيرًا اليوم: هل نلعب جولة؟", "أنا مستعدة لجولة جديدة متى ناسَبك الوقت. لا داعي للرد الآن.")
        val index = prefs.getInt("autonomous_message_index", 0).coerceAtLeast(0) % messages.size
        if (show(context, "رسالة من نوفا", messages[index], AUTONOMOUS_ID)) {
            prefs.edit().putLong("last_autonomous_notification_wall", now).putInt("autonomous_message_index", (index + 1) % messages.size).apply()
            scheduleAutonomousCheck(context, now + 24L * 60 * 60 * 1000)
        } else scheduleAutonomousCheck(context, now + 4L * 60 * 60 * 1000)
    }
    private fun autonomousPendingIntent(context: Context) = PendingIntent.getBroadcast(context, AUTONOMOUS_REQUEST, Intent(context, NovaNotificationReceiver::class.java).setAction(ACTION_AUTONOMOUS), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun dailyEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("daily_enabled", false)

    fun restoreDailyIfEnabled(context: Context) {
        if (dailyEnabled(context)) scheduleNextDaily(context)
    }

    fun scheduleNextDaily(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("daily_enabled", false)) return
        val hour = prefs.getInt("daily_hour", 19).coerceIn(0, 23)
        val minute = prefs.getInt("daily_minute", 0).coerceIn(0, 59)
        val now = System.currentTimeMillis()
        val next = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        alarm(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, dailyPendingIntent(context))
    }

    fun scheduleReminder(context: Context, delayMillis: Long, message: String): Boolean {
        if (delayMillis !in 60_000L..(30L * 24 * 60 * 60 * 1000)) return false
        val clean = message.trim().replace(Regex("\\s+"), " ").take(220)
        if (clean.length < 2) return false
        val requestCode = 20_000 + ((System.currentTimeMillis() xor clean.hashCode().toLong()).and(0x3fffffff).toInt())
        val intent = Intent(context, NovaNotificationReceiver::class.java)
            .setAction("$ACTION_REMINDER.$requestCode")
            .putExtra(EXTRA_MESSAGE_KEY, clean)
            .putExtra(EXTRA_ID_KEY, requestCode)
        val pending = PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMillis, pending)
        return true
    }

    internal fun postDaily(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("daily_enabled", false)) return
        val messages = listOf(
            "مساء الخير! هل نلعب الشطرنج اليوم؟",
            "أنا نوفا. هل تود جولة إكس أو؟",
            "تذكير لطيف: خذ استراحة قصيرة واشرب الماء.",
            "أنا هنا عندما تحب أن نتحدث أو نجرب تحديًا."
        )
        val index = prefs.getInt("daily_message_index", 0).coerceAtLeast(0)
        show(context, "رسالة من نوفا", messages[index % messages.size], DAILY_ID)
        prefs.edit().putInt("daily_message_index", (index + 1) % messages.size).apply()
        scheduleNextDaily(context)
    }

    private fun alarm(context: Context) = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun dailyPendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, DAILY_REQUEST,
        Intent(context, NovaNotificationReceiver::class.java).setAction(ACTION_DAILY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

internal class NovaNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when {
            action == Intent.ACTION_BOOT_COMPLETED -> { NovaLocalNotifications.restoreDailyIfEnabled(context); NovaLocalNotifications.restoreAutonomousIfEnabled(context) }
            action == NovaLocalNotifications.ACTION_DAILY -> NovaLocalNotifications.postDaily(context)
            action == NovaLocalNotifications.ACTION_AUTONOMOUS -> NovaLocalNotifications.handleAutonomousCheck(context)
            action.startsWith(NovaLocalNotifications.ACTION_REMINDER) -> {
                val message = intent.getStringExtra(NovaLocalNotifications.EXTRA_MESSAGE_KEY) ?: return
                val id = intent.getIntExtra(NovaLocalNotifications.EXTRA_ID_KEY, 19003)
                NovaLocalNotifications.show(context, "تذكير من نوفا", message, id)
            }
        }
    }
}
