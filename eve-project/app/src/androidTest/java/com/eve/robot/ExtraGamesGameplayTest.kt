package com.eve.robot

import android.Manifest
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Covers the two newer offline game modes that were not in the original smoke coverage. */
@RunWith(AndroidJUnit4::class)
class ExtraGamesGameplayTest {
    @get:Rule
    val cameraPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private fun descendants(root: View): List<View> {
        val result = ArrayList<View>()
        result.add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                result.addAll(descendants(root.getChildAt(index)))
            }
        }
        return result
    }

    private fun buttonContaining(activity: RobotGamesActivity, phrase: String): Button? =
        descendants(activity.window.decorView).filterIsInstance<Button>()
            .firstOrNull { it.text.toString().contains(phrase, ignoreCase = true) }

    private fun visibleText(activity: RobotGamesActivity): String =
        descendants(activity.window.decorView).filterIsInstance<TextView>()
            .joinToString(" ") { it.text?.toString().orEmpty() }

    @Test
    fun paintShotRunsTenRoundsAndStoresItsResultLocally() {
        val scenario = ActivityScenario.launch(RobotGamesActivity::class.java)
        try {
            scenario.onActivity { activity ->
                requireNotNull(buttonContaining(activity, "Paint Shot")).performClick()

                val roundField = RobotGamesActivity::class.java.getDeclaredField("paintShotRound").apply {
                    isAccessible = true
                }
                repeat(10) { round ->
                    val fire = buttonContaining(activity, "أطلق طلقة طلاء")
                    assertNotNull("Paint Shot launch/fire control missing at round ${round + 1}.", fire)
                    requireNotNull(fire).performClick()
                    assertEquals(
                        "Paint Shot should count each shot immediately.",
                        round + 1,
                        roundField.getInt(activity)
                    )
                }

                assertTrue(
                    "Paint Shot should show a completed 10-shot result: ${visibleText(activity)}",
                    visibleText(activity).contains("انتهت الجولة")
                )
                assertTrue(
                    "Paint Shot should persist an EVE game rating locally.",
                    activity.getSharedPreferences("eve_games", android.content.Context.MODE_PRIVATE)
                        .contains("rating:paint_shot")
                )
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun cameraShotModeStartsPreviewAndDrawsOneOfflineVirtualShot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(CameraShotActivity::class.java)
        try {
            instrumentation.waitForIdleSync()
            var cameraReady = false
            val cameraDeadline = SystemClock.uptimeMillis() + 7_000L
            while (SystemClock.uptimeMillis() < cameraDeadline && !cameraReady) {
                scenario.onActivity { activity ->
                    cameraReady = activity.cameraMessage.text.toString().contains("الكاميرا الخلفية") ||
                        activity.cameraMessage.text.toString().contains("الكاميرا الأمامية")
                }
                if (!cameraReady) SystemClock.sleep(100L)
            }

            scenario.onActivity { activity ->
                assertTrue(
                    "Camera Shot should bind an on-device camera preview: ${activity.cameraMessage.text}",
                    cameraReady
                )
                val overlay = activity.shotOverlay
                assertTrue("Camera shot overlay should be laid out.", overlay.width > 0 && overlay.height > 0)

                val x = overlay.width * 0.45f
                val y = overlay.height * 0.60f
                val downTime = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(
                    downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0
                )
                val up = MotionEvent.obtain(
                    downTime, downTime + 25L, MotionEvent.ACTION_UP, x, y, 0
                )
                try {
                    overlay.dispatchTouchEvent(down)
                    overlay.dispatchTouchEvent(up)
                } finally {
                    down.recycle()
                    up.recycle()
                }
            }

            // A single tap is delayed briefly so the overlay can distinguish it from a double tap.
            SystemClock.sleep(430L)
            scenario.onActivity { activity ->
                assertEquals(
                    "One tap should produce one locally rendered virtual projectile.",
                    1,
                    activity.shotOverlay.shotCountForTest
                )
            }
        } finally {
            scenario.close()
        }
    }
}
