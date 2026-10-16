package com.eve.robot

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that the production scheduler posts a locally generated, context-aware notification.
 * API 26-32 avoids Android 13+'s runtime notification permission prompt in this integration test.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26, maxSdkVersion = 32)
class EveGeneratedNotificationDeliveryTest {
    @Test
    fun postsGeneratedMessageFromRecentHighConfidenceObservation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val now = System.currentTimeMillis()
        val relationship = context.getSharedPreferences("eve_relationship", Context.MODE_PRIVATE)
        val games = context.getSharedPreferences("eve_games", Context.MODE_PRIVATE)
        val notifications = context.getSystemService(NotificationManager::class.java)

        NotificationManagerCompat.from(context).cancel(7303)
        relationship.edit().clear().commit()
        games.edit().clear().commit()

        relationship.edit()
            .putLong("lastForegroundAt", now - 90L * 60L * 1000L)
            .putLong("lastBackgroundAt", now - 60L * 60L * 1000L)
            .putInt("sentCount", 0)
            .putString("lastMood", "SLEEPY")
            .putString("lastMoodReason", "contextual idle")
            .putString("lastObservationLabel", "cat")
            .putFloat("lastObservationConfidence", 0.96f)
            .putInt("lastObservationCount", 4)
            .putLong("lastObservationAt", now - 4L * 60L * 1000L)
            .commit()

        try {
            EveNotificationScheduler.ensureChannel(context)
            EveNotificationScheduler.deliver(context)

            val posted = notifications.activeNotifications
                .firstOrNull { it.id == 7303 }
                ?.notification
            assertNotNull("EVE should post a notification from its local context.", posted)
            val notification = requireNotNull(posted)
            val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

            assertTrue("Generated notification title should not be blank.", title.isNotBlank())
            assertTrue("Recent observation should influence the generated body; body=$body", body.contains("قطة"))
            assertTrue("Generated message should include a follow-up reflection.", body.length > 45)

            val marker = "GENERATED_NOTIFICATION_PASS source=recent_visual_observation title=$title"
            Log.i("EVE_TEST", marker)
            System.out.println(marker)
        } finally {
            NotificationManagerCompat.from(context).cancel(7303)
            EveNotificationScheduler.onForeground(context)
            relationship.edit().clear().commit()
            games.edit().clear().commit()
        }
    }
}
