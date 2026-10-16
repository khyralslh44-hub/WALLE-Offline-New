package com.eve.robot

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
