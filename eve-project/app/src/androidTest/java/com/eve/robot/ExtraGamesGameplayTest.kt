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
    fun cameraRpsModeLoadsTheOfflineGestureModelAndShowsEveReactionFace() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = android.content.Intent(
            instrumentation.targetContext,
            CameraShotActivity::class.java
        ).putExtra("eve_camera_game", "rps")
        val scenario = ActivityScenario.launch<CameraShotActivity>(intent)
        try {
            var modelReady = false
            val deadline = SystemClock.uptimeMillis() + 10_000L
            while (SystemClock.uptimeMillis() < deadline && !modelReady) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    modelReady = activity.gestureModelReadyForTest
                }
                if (!modelReady) SystemClock.sleep(120L)
            }
            scenario.onActivity { activity ->
                assertTrue(
                    "Camera RPS should load its on-device hand-gesture model without internet.",
                    modelReady
                )
                assertTrue(
                    "Camera RPS should explain the three gestures.",
                    activity.cameraMessage.text.toString().contains("قبضة") ||
                        activity.cameraMessage.text.toString().contains("جاهزة للرصد")
                )
                assertNotNull(
                    "Camera RPS must show EVE's face for live play reactions.",
                    descendants(activity.window.decorView).filterIsInstance<RobotFaceView>().firstOrNull()
                )
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun bothEmoInspiredShootingModesAreReachableFromEveGameMenu() {
        val scenario = ActivityScenario.launch(RobotGamesActivity::class.java)
        try {
            scenario.onActivity { activity ->
                assertNotNull(
                    "Moving-target Paint Shot mode must be listed in the game menu.",
                    buttonContaining(activity, "Paint Shot")
                )
                assertNotNull(
                    "Camera augmented-reality shooting mode must be listed in the game menu.",
                    buttonContaining(activity, "مثل EMO")
                )
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun paintShotRunsTenRoundsAndStoresItsResultLocally() {
        val scenario = ActivityScenario.launch(RobotGamesActivity::class.java)
        try {
            scenario.onActivity { activity ->
                requireNotNull(buttonContaining(activity, "Paint Shot")).performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            val arenaField = RobotGamesActivity::class.java.getDeclaredField("paintShotArena").apply {
                isAccessible = true
            }
            val roundField = RobotGamesActivity::class.java.getDeclaredField("paintShotRound").apply {
                isAccessible = true
            }
            val scoreField = RobotGamesActivity::class.java.getDeclaredField("paintShotScore").apply {
                isAccessible = true
            }

            repeat(10) { round ->
                scenario.onActivity { activity ->
                    val arena = requireNotNull(arenaField.get(activity) as? PaintShotArenaView) {
                        "Paint Shot arena missing at round " + (round + 1)
                    }
                    assertTrue("Paint Shot arena must be laid out.", arena.width > 0 && arena.height > 0)

                    // Tap the moving target through the actual view touch path, rather than
                    // searching for obsolete target/launch buttons from an older game design.
                    val target = arena.targetCenterForTest
                    val downTime = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(
                        downTime, downTime, MotionEvent.ACTION_DOWN, target.first, target.second, 0
                    )
                    val up = MotionEvent.obtain(
                        downTime, downTime + 18L, MotionEvent.ACTION_UP, target.first, target.second, 0
                    )
                    try {
                        arena.dispatchTouchEvent(down)
                        arena.dispatchTouchEvent(up)
                    } finally {
                        down.recycle()
                        up.recycle()
                    }
                    assertEquals(
                        "Paint Shot should count each real touch as one shot.",
                        round + 1,
                        roundField.getInt(activity)
                    )
                }
            }

            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(
                    "Paint Shot should show the completed ten-shot result: " + visibleText(activity),
                    visibleText(activity).contains("انتهى التحدّي")
                )
                assertEquals("The centered target taps should register hits.", 10, scoreField.getInt(activity))
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

                fun tapAt(x: Float, y: Float) {
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

                val target = overlay.targetCenterForTest
                tapAt(target.first, target.second)
            }

            // A single tap is delayed briefly so the overlay can distinguish it from a double tap.
            SystemClock.sleep(430L)
            scenario.onActivity { activity ->
                assertEquals(
                    "A tap aimed at the moving target should create one projectile.",
                    1,
                    activity.shotOverlay.shotCountForTest
                )
                assertEquals(
                    "A target-centered shot should score as a hit.",
                    1,
                    activity.shotOverlay.shotHitsForTest
                )
                assertTrue(activity.cameraMessage.text.toString().contains("1/1 إصابة"))

                val overlay = activity.shotOverlay
                val x = overlay.width * 0.05f
                val y = overlay.height * 0.90f
                val downTime = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(downTime, downTime + 25L, MotionEvent.ACTION_UP, x, y, 0)
                try {
                    overlay.dispatchTouchEvent(down)
                    overlay.dispatchTouchEvent(up)
                } finally {
                    down.recycle()
                    up.recycle()
                }
            }
            SystemClock.sleep(430L)
            scenario.onActivity { activity ->
                assertEquals(2, activity.shotOverlay.shotCountForTest)
                assertEquals("The corner shot must be scored as a miss.", 1, activity.shotOverlay.shotMissesForTest)
                assertTrue(activity.cameraMessage.text.toString().contains("1/2 إصابة"))
            }
        } finally {
            scenario.close()
        }
    }
}
