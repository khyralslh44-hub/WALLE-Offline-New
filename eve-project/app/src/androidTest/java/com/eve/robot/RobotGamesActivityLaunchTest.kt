package com.eve.robot

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Confirms the game activity is registered, reachable, and can open each offline game screen. */
@RunWith(AndroidJUnit4::class)
class RobotGamesActivityLaunchTest {
    private fun descendants(root: View): List<View> {
        val result = ArrayList<View>()
        result.add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) result.addAll(descendants(root.getChildAt(index)))
        }
        return result
    }

    private fun openButton(activity: RobotGamesActivity, phrase: String): Button? =
        descendants(activity.window.decorView).filterIsInstance<Button>()
            .firstOrNull { it.text.toString().contains(phrase, ignoreCase = true) }

    private fun visibleText(activity: RobotGamesActivity): String =
        descendants(activity.window.decorView).filterIsInstance<TextView>()
            .joinToString(" ") { it.text?.toString().orEmpty() }

    @Test
    fun animatedEveFaceAppearsInGameRoomAndReactsWhenGameStarts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(RobotGamesActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val face = descendants(activity.window.decorView)
                    .filterIsInstance<RobotFaceView>()
                    .firstOrNull()
                assertNotNull("Game room should render EVE's animated face.", face)
                assertEquals("EVE_CYAN_EXPRESSION_FACE", requireNotNull(face).contentDescription)

                requireNotNull(openButton(activity, "إكس-أو")).performClick()

                val moodField = RobotFaceView::class.java.getDeclaredField("mood").apply {
                    isAccessible = true
                }
                assertEquals(
                    "EVE should react with a playful pose when a game starts.",
                    EveMood.PLAYFUL,
                    moodField.get(requireNotNull(face))
                )
                assertTrue(
                    "The same EVE face should remain attached during gameplay.",
                    descendants(activity.window.decorView).contains(face)
                )
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun allSixOfflineGameScreensAreReachableFromTheMenu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(RobotGamesActivity::class.java)
        try {
            val games = listOf(
                "الشطرنج" to "الشطرنج",
                "إكس-أو" to "إكس-أو",
                "حجر" to "حجر • ورقة • مقص",
                "إصابة الهدف" to "إصابة الهدف",
                "ذاكرة الأنماط" to "ذاكرة الأنماط",
                "اختبار سرعة الاستجابة" to "تحدي سرعة الاستجابة"
            )
            for ((menuPhrase, screenPhrase) in games) {
                scenario.onActivity { activity ->
                    assertTrue(
                        "Game menu is missing button: $menuPhrase",
                        openButton(activity, menuPhrase) != null
                    )
                    assertTrue(
                        "Game screen is missing before selection: $menuPhrase",
                        visibleText(activity).contains("اختر لعبتنا")
                    )
                    requireNotNull(openButton(activity, menuPhrase)).performClick()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertTrue(
                        "Could not open game screen for '$menuPhrase'. Visible: ${visibleText(activity)}",
                        visibleText(activity).contains(screenPhrase, ignoreCase = true)
                    )
                    val back = openButton(activity, "قائمة الألعاب")
                    assertNotNull("No way back to menu from '$screenPhrase'", back)
                    requireNotNull(back).performClick()
                }
                instrumentation.waitForIdleSync()
            }
        } finally {
            scenario.close()
        }
    }
}
