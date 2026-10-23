package com.nova.robot

import android.Manifest
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone, offline-first Nova. The camera is opt-in and is stopped when the activity leaves
 * the foreground. No microphone, network, background service, or raw image storage is used.
 * Vision runs on small 320x240 frames with a throttle so the phone is not continuously stressed.
 */
class NovaActivity : ComponentActivity() {
    private lateinit var face: NovaFaceView
    private lateinit var message: TextView
    private lateinit var status: TextView
    private lateinit var brain: NovaBrain
    private lateinit var store: SharedPreferencesNovaMemory
    private lateinit var localPreferences: SharedPreferences
    private lateinit var affect: NovaAffectEngine
    private val main = Handler(Looper.getMainLooper())
    private var lastInteractionElapsed = 0L
    private var lastTapElapsed = 0L
    private var pressDownX = 0f
    private var pressDownY = 0f
    private var resumed = false
    private var visionEnabled = false
    private var lastVisionSummary = ""
    private var lastVisionFrameAt = 0L
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private val visionInFlight = AtomicBoolean(false)
    private val visionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var localFaceDetector: FaceDetector? = null
    private var localImageLabeler: ImageLabeler? = null
    private var tone: ToneGenerator? = null

    private val cameraPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                enableVision()
            } else {
                message.text = "لم تسمح بالكاميرا؛ لن أرى المشهد. ما زلت أعمل بالتفاعل والذاكرة المحلية."
                status.text = "N O V A • CAMERA OFF"
                face.setMood(NovaMood.CALM)
            }
        }

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
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        localPreferences = getSharedPreferences("nova_local_memory", MODE_PRIVATE)
        store = SharedPreferencesNovaMemory(localPreferences)
        brain = NovaBrain(store)
        affect = NovaAffectEngine()
        localPreferences.getString(KEY_AFFECT_SNAPSHOT, null)?.let { affect.restore(it) }
        affect.observe(NovaAffectEvent.USER_RETURNED, System.currentTimeMillis())
        persistAffect()
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 18) }.getOrNull()
        lastInteractionElapsed = SystemClock.elapsedRealtime()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(4, 8, 21)) }
        face = NovaFaceView(this)
        root.addView(face, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        status = TextView(this).apply {
            text = "N O V A • STARTING VISION"
            textSize = 10f
            letterSpacing = 0.10f
            setTextColor(0xFF94C8FF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            contentDescription = "حالة الرؤية المحلية لنوفا"
            isClickable = false
            isFocusable = false
        }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(20) })

        message = TextView(this).apply {
            text = "أنا نوفا. فضولي وهادئ، وأفضّل التحقّق قبل الاستنتاج. أستخدم الكاميرا محليًا بعد إذنك، ولا أحفظ الصور."
            textSize = 16f
            setTextColor(0xFFE5F6FF.toInt())
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            maxLines = 5
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = pillBackground(0xE9081225.toInt(), 0x884F8DDF.toInt())
        }
        root.addView(message, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            setMargins(dp(24), 0, dp(24), dp(76))
        })

        val hint = TextView(this).apply {
            text = "لمسة: تفاعل • لمستان: مرح • ضغط مطوّل: سؤال جديد"
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            enableVision()
        } else {
            cameraPermissionRequest.launch(Manifest.permission.CAMERA)
        }
    }

    private fun toggleVision() {
        lastInteractionElapsed = SystemClock.elapsedRealtime()
        if (visionEnabled) {
            visionEnabled = false
            stopVisionCamera()
            lastVisionSummary = ""
            status.text = "N O V A • TAP TO LOOK"
            message.text = "أوقفت الكاميرا. لا أحفظ الصور، ويمكنك تشغيل الرؤية وقتما تريد."
            face.setMood(NovaMood.CALM)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            enableVision()
        } else {
            cameraPermissionRequest.launch(Manifest.permission.CAMERA)
        }
    }

    private fun enableVision() {
        visionEnabled = true
        lastVisionSummary = ""
        lastVisionFrameAt = 0L
        status.text = "N O V A • STARTING VISION"
        message.text = "أشغّل الكاميرا الخلفية محليًا. التحليل منخفض الدقة ولا تُحفَظ الصور."
        face.setMood(NovaMood.CURIOUS)
        startVisionCamera()
    }

    private fun startVisionCamera() {
        if (!visionEnabled || !resumed) return
        val existing = cameraProvider
        if (existing != null) {
            bindLowLoadAnalyzer(existing)
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                if (visionEnabled && resumed) bindLowLoadAnalyzer(cameraProvider!!)
            } catch (error: Exception) {
                Log.w("NOVA_VISION", "Could not open the local camera.", error)
                visionEnabled = false
                status.text = "N O V A • CAMERA UNAVAILABLE"
                message.text = "لم أستطع فتح الكاميرا. لم أستنتج شيئًا عن المشهد."
                face.setMood(NovaMood.CALM)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindLowLoadAnalyzer(provider: ProcessCameraProvider) {
        if (!visionEnabled || !resumed) return
        try {
            analysisUseCase?.let { runCatching { provider.unbind(it) } }
            val useCase = ImageAnalysis.Builder()
                .setTargetResolution(Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            useCase.setAnalyzer(visionExecutor) { proxy -> analyzeOneFrame(proxy) }
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, useCase)
            analysisUseCase = useCase
            status.text = "N O V A • SEEING • TAP TO STOP"
            Log.i("NOVA_VISION", "Camera started: back camera, 320x240, throttled local inference.")
        } catch (error: Exception) {
            Log.w("NOVA_VISION", "Binding the camera analyzer failed.", error)
            visionEnabled = false
            stopVisionCamera()
            status.text = "N O V A • CAMERA UNAVAILABLE"
            message.text = "لم أستطع تشغيل الرؤية على هذا الجهاز؛ بقيت بقية نوفا مستقلة عن الإنترنت."
            face.setMood(NovaMood.CALM)
        }
    }

    private fun analyzeOneFrame(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!visionEnabled || !resumed || now - lastVisionFrameAt < MIN_FRAME_INTERVAL_MS ||
            !visionInFlight.compareAndSet(false, true)
        ) {
            proxy.close()
            return
        }
        lastVisionFrameAt = now
        val mediaImage = proxy.image
        if (mediaImage == null) {
            visionInFlight.set(false)
            proxy.close()
            return
        }
        try {
            val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
            val faceTask = faceDetector().process(input)
            val labelTask = imageLabeler().process(input)
            Tasks.whenAllComplete(listOf(faceTask, labelTask)).addOnCompleteListener(visionExecutor) {
                try {
                    val hasFace = faceTask.isSuccessful && !faceTask.result.isNullOrEmpty()
                    val labels = if (labelTask.isSuccessful) {
                        labelTask.result.orEmpty().map { NovaLabelEvidence(it.text, it.confidence) }
                    } else {
                        emptyList()
                    }
                    val summary = NovaVisionEvidence.summarize(hasFace, labels)
                    runOnUiThread {
                        if (visionEnabled && resumed && !isFinishing && !isDestroyed) {
                            applyVisionSummary(summary, hasFace)
                        }
                    }
                } catch (error: Exception) {
                    Log.w("NOVA_VISION", "Local vision parsing failed; keeping uncertainty.", error)
                } finally {
                    visionInFlight.set(false)
                    runCatching { proxy.close() }
                }
            }
        } catch (error: Exception) {
            visionInFlight.set(false)
            runCatching { proxy.close() }
            Log.w("NOVA_VISION", "Local inference could not start.", error)
        }
    }

    private fun applyVisionSummary(summary: String, hasFace: Boolean) {
        if (summary == lastVisionSummary) return
        lastVisionSummary = summary
        val affectMood = recordAffect(if (hasFace) NovaAffectEvent.NOVEL_OBSERVATION else NovaAffectEvent.UNCERTAIN_OBSERVATION)
        message.text = summary
        face.setMood(when (affectMood) {
            NovaAffectMood.ALERT -> NovaMood.ALERT
            NovaAffectMood.CAUTIOUS -> NovaMood.CAUTIOUS
            NovaAffectMood.WARM -> NovaMood.WARM
            NovaAffectMood.PLAYFUL -> NovaMood.PLAYFUL
            NovaAffectMood.FRUSTRATED -> NovaMood.FRUSTRATED
            NovaAffectMood.TIRED -> NovaMood.TIRED
            NovaAffectMood.CURIOUS -> NovaMood.CURIOUS
            NovaAffectMood.CALM -> if (hasFace) NovaMood.CURIOUS else NovaMood.THINKING
        })
        status.text = "N O V A • SEEING • TAP TO STOP"
        Log.i("NOVA_VISION", "LOCAL_FRAME_SUMMARY face=$hasFace; imagesStored=false; network=false")
    }

    private fun faceDetector(): FaceDetector {
        if (localFaceDetector == null) {
            localFaceDetector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .build()
            )
        }
        return localFaceDetector!!
    }

    private fun imageLabeler(): ImageLabeler {
        if (localImageLabeler == null) {
            localImageLabeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        }
        return localImageLabeler!!
    }

    private fun stopVisionCamera() {
        val provider = cameraProvider
        val useCase = analysisUseCase
        if (provider != null && useCase != null) {
            runCatching { provider.unbind(useCase) }
        }
        analysisUseCase = null
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, 15_000L)
        if (visionEnabled) startVisionCamera()
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(idleCheck)
        stopVisionCamera()
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopVisionCamera()
        tone?.release()
        tone = null
        localFaceDetector?.close()
        localFaceDetector = null
        localImageLabeler?.close()
        localImageLabeler = null
        visionExecutor.shutdown()
        super.onDestroy()
    }

    private fun showEvent(event: NovaEvent, playSound: Boolean) {
        val affectSignal = when (event.mood) {
            NovaMood.HAPPY, NovaMood.PLAYFUL, NovaMood.WARM -> NovaAffectEvent.FRIENDLY_INTERACTION
            NovaMood.FRUSTRATED -> NovaAffectEvent.USER_CORRECTION
            NovaMood.THINKING, NovaMood.CURIOUS -> NovaAffectEvent.UNCERTAIN_OBSERVATION
            else -> NovaAffectEvent.FRIENDLY_INTERACTION
        }
        val affectMood = recordAffect(affectSignal)
        val visibleMood = when {
            affectMood == NovaAffectMood.ALERT -> NovaMood.ALERT
            affectMood == NovaAffectMood.CAUTIOUS -> NovaMood.CAUTIOUS
            affectMood == NovaAffectMood.WARM && event.mood == NovaMood.CALM -> NovaMood.WARM
            affectMood == NovaAffectMood.TIRED && event.mood == NovaMood.CALM -> NovaMood.TIRED
            else -> event.mood
        }
        face.setMood(visibleMood)
        message.text = event.messageArabic
        status.text = if (visionEnabled) {
            "N O V A • SEEING • TAP TO STOP"
        } else when (visibleMood) {
            NovaMood.CURIOUS -> "N O V A • C U R I O U S"
            NovaMood.HAPPY, NovaMood.PLAYFUL -> "N O V A • P L A Y F U L"
            NovaMood.THINKING -> "N O V A • T H I N K I N G"
            NovaMood.SLEEPY -> "N O V A • Q U I E T"
            NovaMood.SURPRISED -> "N O V A • S U R P R I S E"
            NovaMood.FRUSTRATED -> "N O V A • R E S E T T I N G"
            NovaMood.WARM -> "N O V A • W A R M"
            NovaMood.ALERT -> "N O V A • A L E R T"
            NovaMood.CAUTIOUS -> "N O V A • C A U T I O U S"
            NovaMood.TIRED -> "N O V A • R E S T I N G"
            NovaMood.CALM -> "N O V A • TAP TO LOOK"
        }
        if (event.hapticMillis > 0L) face.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (playSound) {
            val toneType = when (visibleMood) {
                NovaMood.THINKING -> ToneGenerator.TONE_PROP_ACK
                NovaMood.HAPPY, NovaMood.PLAYFUL -> ToneGenerator.TONE_PROP_BEEP2
                NovaMood.CURIOUS, NovaMood.ALERT -> ToneGenerator.TONE_PROP_BEEP
                else -> ToneGenerator.TONE_PROP_ACK
            }
            tone?.startTone(toneType, if (event.isQuestion) 130 else 70)
        }
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, 15_000L)
    }

    private fun recordAffect(event: NovaAffectEvent): NovaAffectMood {
        val now = System.currentTimeMillis()
        affect.observe(event, now)
        persistAffect()
        return affect.mood(now)
    }

    private fun persistAffect() {
        localPreferences.edit().putString(KEY_AFFECT_SNAPSHOT, affect.snapshot(System.currentTimeMillis())).apply()
    }

    private fun pillBackground(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(26).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MIN_FRAME_INTERVAL_MS = 1_800L
        private const val KEY_AFFECT_SNAPSHOT = "nova_affect_snapshot_v1"
    }
}

private class SharedPreferencesNovaMemory(
    private val preferences: android.content.SharedPreferences
) : NovaMemory {
    override fun getInt(key: String, defaultValue: Int): Int = preferences.getInt(key, defaultValue)
    override fun getLong(key: String, defaultValue: Long): Long = preferences.getLong(key, defaultValue)
    override fun putInt(key: String, value: Int) { preferences.edit().putInt(key, value).apply() }
    override fun putLong(key: String, value: Long) { preferences.edit().putLong(key, value).apply() }
}
