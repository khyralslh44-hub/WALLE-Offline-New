package com.eve.robot

import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real UI interactions for the target and pattern-memory games. */
@RunWith(AndroidJUnit4::class)
class RobotGamesGameplayTest {
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

    private fun button(activity: RobotGamesActivity, exactText: String): Button? =
        descendants(activity.window.decorView).filterIsInstance<Button>()
            .firstOrNull { it.text.toString() == exactText }

    private fun buttonContaining(activity: RobotGamesActivity, phrase: String): Button? =
        descendants(activity.window.decorView).filterIsInstance<Button>()
            .firstOrNull { it.text.toString().contains(phrase, ignoreCase = true) }

    private fun visibleText(activity: RobotGamesActivity): String =
        descendants(activity.window.decorView).filterIsInstance<TextView>()
            .joinToString(" ") { it.text?.toString().orEmpty() }

    @Test
    fun paintShotArenaFiresAtMovingTargetAndScoresOnlyAccurateShots() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(RobotGamesActivity::class.java)
        try {
            scenario.onActivity { activity ->
                requireNotNull(buttonContaining(activity, "Paint Shot")).performClick()
            }
            instrumentation.waitForIdleSync()

            scenario.onActivity { activity ->
                val arena = descendants(activity.window.decorView)
                    .filterIsInstance<PaintShotArenaView>()
                    .firstOrNull()
                assertNotNull("Paint Shot must show a real animated shooting arena.", arena)
                requireNotNull(arena)
                assertTrue("The shooting arena needs a usable touch area.", arena.width > 0 && arena.height > 0)

                fun tapAt(x: Float, y: Float) {
                    val downTime = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(
                        downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0
                    )
                    val up = MotionEvent.obtain(
                        downTime, downTime + 40L, MotionEvent.ACTION_UP, x, y, 0
                    )
                    try {
                        arena.dispatchTouchEvent(down)
                        arena.dispatchTouchEvent(up)
                    } finally {
                        down.recycle()
                        up.recycle()
                    }
                }

                // A corner shot is well away from the moving target and must miss.
                tapAt(arena.width * 0.04f, arena.height * 0.90f)
                assertEquals("One touch should fire one projectile.", 1, arena.shotsFiredForTest)
                assertEquals("A distant shot must not be awarded as a hit.", 0, arena.shotsHitForTest)

                // Shoot at the current target position; the same production scoring path is used.
                val target = arena.targetCenterForTest
                tapAt(target.first, target.second)
                assertEquals("Two touches should create two shots.", 2, arena.shotsFiredForTest)
                assertEquals("Touching the visible moving target should score a hit.", 1, arena.shotsHitForTest)
                assertTrue(
                    "The game screen should update its progress after a hit: ${visibleText(activity)}",
                    visibleText(activity).contains("الإصابات: 1")
                )
                assertTrue("The game should count the shots out of ten.", visibleText(activity).contains("2 / 10"))
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun movingTargetScoresTheVisibleCellAndMovesAfterAHit() {
        val scenario = ActivityScenario.launch(RobotGamesActivity::class.java)
        try {
            scenario.onActivity { activity ->
                requireNotNull(buttonContaining(activity, "إصابة الهدف")).performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                val targetField = RobotGamesActivity::class.java.getDeclaredField("targetCell").apply {
                    isAccessible = true
                }
                val previousCell = targetField.getInt(activity)
                assertNotNull("The target glyph must be visible as a real button.", button(activity, "●"))
                requireNotNull(button(activity, "●")).performClick()

                assertTrue(
                    "A successful target tap should be counted immediately: ${visibleText(activity)}",
                    visibleText(activity).contains("الإصابات: 1 / 1")
                )
                val nextCell = targetField.getInt(activity)
                assertNotEquals("After a hit, the target must move to a different cell.", previousCell, nextCell)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun patternMemoryShowsTheSequenceAndAcceptsTheCorrectFirstColor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(RobotGamesActivity::class.java)
        try {
            scenario.onActivity { activity ->
                requireNotNull(buttonContaining(activity, "ذاكرة الأنماط")).performClick()
            }
            // Emulator frame timing varies, so wait for the actual input state instead of
            // assuming the first cue always finishes within one fixed sleep.
            var memoryScreenText = ""
            val inputDeadline = SystemClock.uptimeMillis() + 5_000L
            while (SystemClock.uptimeMillis() < inputDeadline && !memoryScreenText.contains("حان دورك")) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    memoryScreenText = visibleText(activity)
                }
                if (!memoryScreenText.contains("حان دورك")) SystemClock.sleep(100L)
            }

            scenario.onActivity { activity ->
                assertTrue(
                    "The memory game must tell the player when input begins: ${visibleText(activity)}",
                    visibleText(activity).contains("حان دورك")
                )
                val sequenceField = RobotGamesActivity::class.java.getDeclaredField("sequence").apply {
                    isAccessible = true
                }
                @Suppress("UNCHECKED_CAST")
                val sequence = sequenceField.get(activity) as List<Int>
                assertEquals("A fresh memory round starts with one color.", 1, sequence.size)

                val colorNames = listOf("أزرق", "أخضر", "برتقالي", "بنفسجي")
                val correctColor = colorNames[sequence.first()]
                requireNotNull(button(activity, correctColor)).performClick()

                assertTrue(
                    "The correct color should be accepted and the pattern extended: ${visibleText(activity)}",
                    visibleText(activity).contains("صحيح!")
                )
                assertEquals("A successful input should extend the next pattern by one color.", 2, sequence.size)
            }
        } finally {
            scenario.close()
        }
    }
}
