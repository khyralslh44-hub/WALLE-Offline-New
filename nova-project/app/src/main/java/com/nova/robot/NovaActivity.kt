package com.nova.robot

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Calendar

class NovaActivity : Activity() {
    private lateinit var face: NovaFaceView
    private lateinit var message: TextView
    private lateinit var status: TextView
    private lateinit var brain: NovaBrain
    private lateinit var store: SharedPreferencesNovaMemory
    private val main = Handler(Looper.getMainLooper())
    private var lastInteractionElapsed = 0L
    private var lastTapElapsed = 0L
    private var pressDownX = 0f
    private var pressDownY = 0f
    private var resumed = false
    private var tone: ToneGenerator? = null

    private val idleCheck = object : Runnable {
        override fun run() {
            if (!resumed || isFinishing || isDestroyed) return
            val nowElapsed = SystemClock.elapsedRealtime()
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val event = brain.idleEvent(
                nowElapsedMs = nowElapsed,
                lastInteractionElapsedMs = lastInteractionElapsed,
                nowWallMs = System.currentTimeMillis(),
                localHour = hour,
                isVisible = hasWindowFocus()
            )
            if (event != null) showEvent(event, playSound = true)
            main.postDelayed(this, 15_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        store = SharedPreferencesNovaMemory(getSharedPreferences("nova_local_memory", MODE_PRIVATE))
        brain = NovaBrain(store)
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 18) }.getOrNull()
        lastInteractionElapsed = SystemClock.elapsedRealtime()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(4, 8, 21)) }
        face = NovaFaceView(this)
        root.addView(face, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        status = TextView(this).apply {
            text = "N O V A   •   O F F L I N E"
            textSize = 10f
            letterSpacing = 0.10f
            setTextColor(0xFF94C8FF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = pillBackground(0xCC09162B.toInt(), 0x664B8AE8)
        }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(20) })

        message = TextView(this).apply {
            text = "أنا نوفا. فضولي وهادئ، وأفضّل التحقّق قبل الاستنتاج."
            textSize = 16f
            setTextColor(0xFFE5F6FF.toInt())
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            maxLines = 4
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = pillBackground(0xE9081225.toInt(), 0x884F8DDF)
        }
        root.addView(message, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            setMargins(dp(24), 0, dp(24), dp(76))
        })

        val hint = TextView(this).apply {
            text = "لمسة: تفاعل    •    لمستان: مرح    •    ضغط مطوّل: سؤال جديد"
            textSize = 10f
            setTextColor(0xFF7798BD.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        root.addView(hint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))

        face.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressDownX = event.x
                    pressDownY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.hypot(
                        (event.x - pressDownX).toDouble(),
                        (event.y - pressDownY).toDouble()
                    ) > dp(18)
                    if (!moved) {
                        val now = SystemClock.elapsedRealtime()
                        lastInteractionElapsed = now
                        val longPress = event.eventTime - event.downTime >= 650L
                        when {
                            longPress -> {
                                lastTapElapsed = 0L
                                showEvent(brain.thinkNow(), playSound = true)
                            }
                            lastTapElapsed > 0L && now - lastTapElapsed <= 340L -> {
                                lastTapElapsed = 0L
                                showEvent(brain.onDoubleTap(), playSound = true)
                            }
                            else -> {
                                lastTapElapsed = now
                                showEvent(brain.onTap(), playSound = true)
                            }
                        }
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, 15_000L)
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(idleCheck)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        tone?.release()
        tone = null
        super.onDestroy()
    }

    private fun showEvent(event: NovaEvent, playSound: Boolean) {
        face.setMood(event.mood)
        message.text = event.messageArabic
        status.text = when (event.mood) {
            NovaMood.CURIOUS -> "N O V A   •   C U R I O U S"
            NovaMood.HAPPY, NovaMood.PLAYFUL -> "N O V A   •   P L A Y F U L"
            NovaMood.THINKING -> "N O V A   •   T H I N K I N G"
            NovaMood.SLEEPY -> "N O V A   •   Q U I E T"
            NovaMood.SURPRISED -> "N O V A   •   S U R P R I S E"
            NovaMood.FRUSTRATED -> "N O V A   •   R E S E T T I N G"
            NovaMood.CALM -> "N O V A   •   O F F L I N E"
        }
        if (event.hapticMillis > 0L) face.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (playSound) {
            val toneType = when (event.mood) {
                NovaMood.THINKING -> ToneGenerator.TONE_PROP_ACK
                NovaMood.HAPPY, NovaMood.PLAYFUL -> ToneGenerator.TONE_PROP_BEEP2
                NovaMood.CURIOUS -> ToneGenerator.TONE_PROP_BEEP
                else -> ToneGenerator.TONE_PROP_ACK
            }
            tone?.startTone(toneType, if (event.isQuestion) 130 else 70)
        }
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, 15_000L)
    }

    private fun pillBackground(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(26).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private class SharedPreferencesNovaMemory(
    private val preferences: android.content.SharedPreferences
) : NovaMemory {
    override fun getInt(key: String, defaultValue: Int): Int = preferences.getInt(key, defaultValue)
    override fun getLong(key: String, defaultValue: Long): Long = preferences.getLong(key, defaultValue)
    override fun putInt(key: String, value: Int) { preferences.edit().putInt(key, value).apply() }
    override fun putLong(key: String, value: Long) { preferences.edit().putLong(key, value).apply() }
}
