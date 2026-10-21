package com.eve.robot

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbodiedGamesActivityTest {
    private fun descendants(root: View): List<View> {
        val all = arrayListOf(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) all.addAll(descendants(root.getChildAt(i)))
        }
        return all
    }

    @Test
    fun physicalGamesAreLockedWhenTheBodyIsNotReady() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EveEmbodiedGameGate.clear(context)
        val scenario = ActivityScenario.launch(EmbodiedGamesActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView)
                val visibleText = views.filterIsInstance<TextView>()
                    .joinToString(" ") { it.text?.toString().orEmpty() }
                val buttonLabels = views.filterIsInstance<Button>().map { it.text.toString() }
                assertTrue(visibleText.contains("الجسد غير جاهز"))
                assertFalse(buttonLabels.any { it.contains("بدء الغميضة") })
                assertFalse(buttonLabels.any { it.contains("بدء تحدّي الظهور") })
            }
        } finally {
            scenario.close()
            EveEmbodiedGameGate.clear(context)
        }
    }
}
