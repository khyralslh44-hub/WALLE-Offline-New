package com.eve.robot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale

/**
 * EVE face experience: camera labeling and behavior operate locally while the activity is open.
 * No network permission is declared. The user can deny the camera; the animated face still works.
 */
class MainActivity : ComponentActivity() {
    private lateinit var face: RobotFaceView
    private lateinit var brain: RobotBrain
    private lateinit var memory: EveMemory
    private lateinit var voice: RobotVoice
    private lateinit var localLanguageBrain: LocalLanguageBrain
    private val lastLocalLlmVisionAt = AtomicLong(0L)
    @Volatile private var lastLocalLlmVisionLabel = ""
    private var offlineArabicSpeech: OfflineArabicSpeech? = null
    private var lastSpeechText = ""
    private var lastSpeechAt = 0L
    private lateinit var cameraStatus: TextView
    private lateinit var dialogueBubble: TextView
    private val hideDialogue = Runnable {
        if (::dialogueBubble.isInitialized) {
            dialogueBubble.animate().cancel()
            dialogueBubble.animate().alpha(0f).setDuration(220L).withEndAction {
                if (::dialogueBubble.isInitialized) dialogueBubble.visibility = View.GONE
            }.start()
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val emotionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Recognizer creation, inference and release all occur on this one worker.
    private val gestureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var handGestureRecognizer: GestureRecognizer? = null
    @Volatile private var handGestureReady = false
    private val handGestureInferenceRunning = AtomicBoolean(false)
    private val lastGestureDiagnosticAt = AtomicLong(0L)
    private val handGestureInterpreter = HandGestureInterpreter()
    private val lastHandReactionAt = mutableMapOf<String, Long>()
    private val emotionClassifierDelegate = lazy { EveFacialEmotionClassifier(applicationContext) }
    private val emotionClassifier by emotionClassifierDelegate
    private val emotionSmoother = EveEmotionSmoother(windowSize = 3, requiredVotes = 2, minConfidence = 0.62f)
    private val lastAnalysis = AtomicLong(0L)
    // Allow at most one FER inference in flight; never build a queue of stale camera frames.
    private val ferInferenceRunning = AtomicBoolean(false)
    private val faceCueSmoother = EveFaceCueSmoother(windowSize = 3, requiredVotes = 2)
    private val lastLabelAt = mutableMapOf<String, Long>()
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }
    private val faceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraStarted = false
    private enum class CameraLens { BACK, FRONT }
    private var activeCameraLens = CameraLens.FRONT
    private var lastTapUptime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    // Independent fallback detector: some Android 10 immersive-window combinations do not
    // deliver the second tap to the custom-drawn face view's OnTouchListener.
    private var dispatchLastTapUptime = 0L
    private var dispatchLastTapX = 0f
    private var dispatchLastTapY = 0f
    private var dispatchDownRawX = 0f
    private var dispatchDownRawY = 0f
    private var cameraSwitchGestureSerial = 0
    @Volatile private var lastFaceSeenAt = 0L
    private var lastFaceReactionAt = 0L
    private var lastInteractionAt = SystemClock.elapsedRealtime()
    private var idleCount = 0
    private var touchDownAt = 0L
    private var lastMeaningfulLabel: String? = null
    private var lastMeaningfulLabelAt = 0L
    private var lastMeaningfulConfidence = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val x = event.rawX
        val y = event.rawY
        val maxTravel = 72f * resources.displayMetrics.density

        if (action == MotionEvent.ACTION_DOWN) {
            dispatchDownRawX = x
            dispatchDownRawY = y
        }

        val duration = event.eventTime - event.downTime
        val travel = kotlin.math.hypot(
            (x - dispatchDownRawX).toDouble(),
            (y - dispatchDownRawY).toDouble()
        )
        val shortTap = action == MotionEvent.ACTION_UP &&
            duration in 0L..550L &&
            travel <= maxTravel
        val tapDistance = kotlin.math.hypot(
            (x - dispatchLastTapX).toDouble(),
            (y - dispatchLastTapY).toDouble()
        )
        val fallbackDoubleTap = shortTap &&
            dispatchLastTapUptime != 0L &&
            event.eventTime - dispatchLastTapUptime in 0L..1500L &&
            tapDistance <= maxTravel
        val gestureSerialBeforeDispatch = cameraSwitchGestureSerial
        val handled = super.dispatchTouchEvent(event)

        if (action == MotionEvent.ACTION_UP) {
            if (fallbackDoubleTap) {
                // Prefer the face listener when it saw the gesture. Only take over when that
                // listener did not switch cameras, preventing duplicate switches on newer APIs.
                if (cameraSwitchGestureSerial == gestureSerialBeforeDispatch) {
                    dispatchLastTapUptime = 0L
                    cameraSwitchGestureSerial++
                    Log.i("EVE", "CAMERA_SWITCH_GESTURE source=activity_dispatch_fallback")
                    switchCamera()
                } else {
                    dispatchLastTapUptime = 0L
                }
            } else if (shortTap) {
                dispatchLastTapUptime = event.eventTime
                dispatchLastTapX = x
                dispatchLastTapY = y
            } else {
                dispatchLastTapUptime = 0L
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            dispatchLastTapUptime = 0L
        }

        return handled || fallbackDoubleTap
    }

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            showCameraStatus("الكاميرا متوقفة • اضغط هنا للسماح", active = false)
            react(brain.cameraUnavailable("permission declined"), playSound = false)
            Log.i("EVE", "Camera permission declined; face remains active.")
        }
        // Request system permissions sequentially; Android can queue only one runtime dialog at a time.
        requestOfflineSpeechPermission()
    }

    private val audioPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startOfflineSpeechListening()
        else Log.i("EVE_AUDIO", "Microphone permission declined; offline listening disabled.")
        requestNotificationPermissionIfNeeded()
    }

    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        getSharedPreferences("eve_relationship", MODE_PRIVATE).edit()
            .putBoolean("notificationPermissionAsked", true)
            .apply()
        Log.i("EVE_NOTIFY", "notification permission granted=$granted")
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getSharedPreferences("eve_relationship", MODE_PRIVATE)
        if (prefs.getBoolean("notificationPermissionAsked", false)) return
        // Ask at most once from the foreground. Declining reminders never disables the face or games.
        prefs.edit().putBoolean("notificationPermissionAsked", true).apply()
        notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val idleLoop = object : Runnable {
        override fun run() {
            if (isFinishing || !::face.isInitialized) return
            idleCount++
            val inactive = SystemClock.elapsedRealtime() - lastInteractionAt
            if (inactive >= 5_000L) {
                val decision = brain.idle(inactive, idleCount)
                face.setExpression(decision, System.currentTimeMillis())
                Log.i("EVE", "AUTONOMOUS mood=${decision.mood} reason=${decision.reason} inactiveMs=$inactive")
            }
            main.postDelayed(this, 6000L)
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
        face = RobotFaceView(this)
        face.id = View.generateViewId()
        cameraStatus = TextView(this).apply {
            text = "جارٍ تجهيز الكاميرا..."
            textSize = 10.5f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(0xCC07151C.toInt())
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0x6637F5A1)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { handleCameraStatusTap() }
            // Hidden long-press opens the offline game room without adding a visible app button.
            setOnLongClickListener {
                startActivity(Intent(this@MainActivity, RobotGamesActivity::class.java))
                true
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        dialogueBubble = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(0xFFE6FFFF.toInt())
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            minWidth = dp(160)
            maxWidth = dp(340)
            maxLines = 3
            background = GradientDrawable().apply {
                setColor(0xE6071015.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0xCC00CFE0.toInt())
            }
            visibility = View.GONE
            alpha = 0f
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            elevation = dp(5).toFloat()
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.rgb(2, 7, 10))
            addView(face, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(cameraStatus, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(16)
                marginStart = dp(12)
                marginEnd = dp(12)
            })
            addView(dialogueBubble, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(42)
                marginStart = dp(18)
                marginEnd = dp(18)
            })
        }
        setContentView(root)
        memory = EveMemory(applicationContext)
        brain = RobotBrain(memory.learnedPersonality())
        voice = RobotVoice()
        localLanguageBrain = LocalLanguageBrain(applicationContext) { state, percent, message ->
            handleLocalLanguageStatus(state, percent, message)
        }
        localLanguageBrain.start()
        initializeHandGestureRecognizer()
        requestOfflineSpeechPermission()

        face.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownAt = SystemClock.uptimeMillis()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val releasedAt = SystemClock.uptimeMillis()
                    val longPress = releasedAt - touchDownAt >= 550L
                    val tapDistance = kotlin.math.hypot(
                        (event.x - lastTapX).toDouble(),
                        (event.y - lastTapY).toDouble()
                    )
                    val doubleTap = !longPress &&
                        lastTapUptime != 0L &&
                        releasedAt - lastTapUptime in 0L..1500L &&
                        tapDistance <= 72f * resources.displayMetrics.density

                    lastInteractionAt = SystemClock.elapsedRealtime()
                    if (doubleTap) {
                        // Keeps the face button-free: double-tap anywhere on the eyes to switch lens.
                        lastTapUptime = 0L
                        cameraSwitchGestureSerial++
                        Log.i("EVE", "CAMERA_SWITCH_GESTURE source=double_tap")
                        switchCamera()
                        true
                    } else {
                        lastTapUptime = if (longPress) 0L else releasedAt
                        lastTapX = event.x
                        lastTapY = event.y
                        val recentLabel = lastMeaningfulLabel
                        val age = lastInteractionAt - lastMeaningfulLabelAt
                        if (longPress && recentLabel != null && age in 0L..20_000L) {
                            val learned = memory.reinforce(recentLabel, reward = 1f)
                            Log.i(
                                "EVE",
                                "LEARNING positive feedback label=$recentLabel affinity=$learned " +
                                    "feedbackCount=${memory.feedbackCount(recentLabel)}"
                            )
                        }
                        val touchDecision = brain.touched(longPress, lastInteractionAt)
                        val learnedPersonality = when {
                            longPress -> memory.adaptPersonality("gentle_contact")
                            touchDecision.mood == EveMood.IRRITATED || touchDecision.mood == EveMood.ANGRY ->
                                memory.adaptPersonality("overstimulation")
                            else -> memory.learnedPersonality()
                        }
                        brain.updatePersonality(learnedPersonality)
                        Log.i(
                            "EVE_PERSONALITY",
                            "traits warmth=${learnedPersonality.warmth} " +
                                "curiosity=${learnedPersonality.curiosityBias} " +
                                "playfulness=${learnedPersonality.playfulness} patience=${learnedPersonality.patience}"
                        )
                        // A hold comforts/reinforces; it does not unexpectedly open a game.
                        react(touchDecision, longPress && touchDecision.playSound)
                        true
                    }
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }

        react(brain.awakenExperience(), playSound = false)
        face.startAnimation()
        main.postDelayed(idleLoop, 5200L)
        if (hasCameraPermission()) {
            startCamera()
            requestOfflineSpeechPermission()
        } else {
            getSharedPreferences("eve_relationship", MODE_PRIVATE).edit()
                .putBoolean("cameraPermissionRequested", true).apply()
            permissionRequest.launch(Manifest.permission.CAMERA)
        }
        // Camera setup can overwrite the same top chip; restore the language-brain status afterward.
        if (::localLanguageBrain.isInitialized) localLanguageBrain.refreshStatus()
    }


    private fun requestOfflineSpeechPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startOfflineSpeechListening()
            requestNotificationPermissionIfNeeded()
        } else {
            audioPermissionRequest.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // This recognizer and its Arabic model are bundled in the APK and never use Android/cloud STT.
    private fun startOfflineSpeechListening() {
        if (isFinishing || isDestroyed || offlineArabicSpeech != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        offlineArabicSpeech = OfflineArabicSpeech(
            context = applicationContext,
            onTranscript = { recognizedText ->
                if (!isFinishing && !isDestroyed) {
                    handleRecognizedSpeech(recognizedText, "offline_vosk")
                }
            },
            onStatus = { status ->
                Log.i("EVE_AUDIO", status)
            }
        )
        offlineArabicSpeech?.start()
    }

    private fun handleRecognizedSpeech(rawText: String, source: String) {
        val text = normalizeArabicSpeech(rawText)
        if (text.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        if (text == lastSpeechText && now - lastSpeechAt < 2800L) return
        lastSpeechText = text
        lastSpeechAt = now
        Log.i("EVE_AUDIO", "RECOGNIZED source=$source text=$text")

        // Respect a quiet request immediately: settle the eyes without answering with a tone.
        if (listOf(
                "توقفي", "اسكتي", "لا تتكلمي", "لا تتحدثي", "اصمتي",
                "stop talking", "be quiet"
            ).any(text::contains)
        ) {
            react(brain.observeFacialEmotion("neutral", 0.90f), playSound = false)
            Log.i("EVE_AUDIO", "SPEECH_RESPONSE_SUPPRESSED reason=quiet_command")
            return
        }

        // Direct questions get a short, honest local answer and a matching eye expression.
        // Unknown questions receive a clear limitation; EVE never fabricates a fact offline.
        val currentVisualAge = if (lastMeaningfulLabelAt > 0L) now - lastMeaningfulLabelAt else Long.MAX_VALUE
        val offlineAnswer = OfflineEveResponder.answer(
            rawQuestion = text,
            latestLabel = lastMeaningfulLabel,
            latestConfidence = lastMeaningfulConfidence,
            observationAgeMs = currentVisualAge
        )
        if (offlineAnswer != null) {
            val speechState = brain.respondToSpeech(text, addressedToEve = true)
            val answerDecision = speechState.copy(
                mood = offlineAnswer.mood,
                reason = "local answer: " + offlineAnswer.topic,
                intent = if (offlineAnswer.mood == EveMood.CURIOUS) EveIntent.EXPLORE else EveIntent.FOCUS
            )
            react(answerDecision, playSound = false)
            showDialogueText(offlineAnswer.text, durationMs = 7200L)
            voice.respondToSpeech(answerDecision.mood, text)
            Log.i("EVE_DIALOGUE", "ANSWER topic=" + offlineAnswer.topic + " mood=" + answerDecision.mood + " local=true")
            return
        }

        // Camera changes are actions; acknowledge them with a brief nonverbal character cue.
        if (listOf(
                "بدلي الكاميرا", "غيري الكاميرا", "بدل الكاميرا", "غير الكاميرا",
                "switch camera", "change camera", "flip camera"
            ).any(text::contains)
        ) {
            switchCamera()
            voice.respondToSpeech(EveMood.CURIOUS, text)
            return
        }

        val decision: EveDecision? = when {
            listOf(
                "مرحبا", "السلام عليكم", "اهلا", "اهلا وسهلا", "صباح الخير", "مساء الخير",
                "hello", "hi", "hey"
            ).any(text::contains) ->
                brain.observeHandGesture("wave", 0.96f)

            listOf(
                "سعيد", "سعيده", "فرحان", "فرحانه", "افرح", "فرح", "happy", "happiness"
            ).any(text::contains) ->
                brain.observeFacialEmotion("happy", 0.96f)

            listOf(
                "حزين", "حزينه", "زعلان", "زعلانه", "حزن", "sad", "sadness"
            ).any(text::contains) ->
                brain.observeFacialEmotion("sad", 0.90f)

            listOf(
                "غاضب", "غضبان", "معصب", "معصبه", "منزعج", "منزعجه", "غضب", "angry", "anger"
            ).any(text::contains) ->
                brain.observeFacialEmotion("angry", 0.90f)

            listOf(
                "خائف", "خايف", "خايفه", "خوف", "مرعوب", "مرعوبه", "scared", "fear"
            ).any(text::contains) ->
                brain.observeFacialEmotion("fearful", 0.90f)

            listOf(
                "مندهش", "مندهشه", "مفاجاه", "دهشه", "surprised", "surprise"
            ).any(text::contains) ->
                brain.observeFacialEmotion("surprised", 0.90f)

            listOf(
                "ماذا ترين", "ما ترين", "ماذا تشاهدين", "ماذا يوجد امامك",
                "ما الذي امامك", "صفي ما ترين", "وش تشوفين", "ايش تشوفين",
                "ما هذا", "وش هذا", "ايش هذا", "what is this", "what do you see",
                "describe what you see"
            ).any(text::contains) -> {
                val visualLabel = lastMeaningfulLabel
                if (visualLabel != null && now - lastMeaningfulLabelAt in 0L..12_000L) {
                    brain.observe(visualLabel, 0.90f, 1)
                } else {
                    brain.observe("unknown", 0.44f, 0)
                }
            }

            listOf(
                "انظري الي", "شوفي هنا", "التفتي الي", "hello eve", "hey eve",
                "look at me", "come here"
            ).any(text::contains) ->
                brain.observeHandGesture("wave", 0.92f)

            listOf(
                "استكشفي", "استكشفي المكان", "انظري حولك", "دوري حولك",
                "ابحثي حولك", "explore", "look around", "scan the room"
            ).any(text::contains) ->
                brain.cameraSwitched(activeCameraLens.name)

            listOf(
                "ما اسمك", "من انت", "من انتي", "من تكونين", "who are you", "what is your name"
            ).any(text::contains) ->
                brain.observeHandGesture("wave", 0.84f)

            listOf(
                "توقفي", "اسكتي", "لا تتكلمي", "لا تتحدثي", "اصمتي",
                "stop talking", "be quiet"
            ).any(text::contains) ->
                brain.observeFacialEmotion("neutral", 0.90f)

            else -> {
                // A recognized full sentence still deserves a nonverbal acknowledgement.
                // This is an attentive reaction, not a claim that EVE understood its meaning.
                val addressedToEve = listOf(
                    "ايف", "يا ايف", "ايفي", "يا روبوت", "eve", "hey eve", "hello eve"
                ).any(text::contains)
                val wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() }
                if (addressedToEve || wordCount >= 2) {
                    requestLocalLanguageReply(text, addressedToEve)
                    null
                } else {
                    Log.i("EVE_AUDIO", "UNMAPPED_SHORT_UTTERANCE text=$text")
                    null
                }
            }
        }

        if (decision != null) {
            // Every accepted utterance changes the face and receives one short, varied vocal cue.
            // The dedicated voice path applies its own cooldown and never speaks Arabic words.
            react(decision, playSound = false)
            voice.respondToSpeech(decision.mood, text)
        }
    }

    /** Normalize common Arabic orthography so local ASR output matches command phrases reliably. */
    private fun normalizeArabicSpeech(rawText: String): String {
        return rawText.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun handleCameraStatusTap() {
        if (hasCameraPermission()) {
            if (!cameraStarted) startCamera()
            else android.widget.Toast.makeText(
                this,
                if (activeCameraLens == CameraLens.FRONT) "الكاميرا الأمامية تعمل" else "الكاميرا الخلفية تعمل",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        val prefs = getSharedPreferences("eve_relationship", MODE_PRIVATE)
        val askedBefore = prefs.getBoolean("cameraPermissionRequested", false)
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("اسمح لإيف برؤية ما حولها")
                .setMessage("الكاميرا تُحلَّل على الهاتف حتى تتفاعل إيف مع الأشياء وتعبيرات الوجه. لا حاجة إلى اتصال بالإنترنت.")
                .setPositiveButton("السماح بالكاميرا") { _, _ ->
                    prefs.edit().putBoolean("cameraPermissionRequested", true).apply()
                    permissionRequest.launch(Manifest.permission.CAMERA)
                }
                .setNegativeButton("لاحقًا", null)
                .show()
        } else if (askedBefore) {
            android.app.AlertDialog.Builder(this)
                .setTitle("الكاميرا محظورة")
                .setMessage("يبدو أن Android منع طلب الإذن مرة أخرى. افتح إعدادات التطبيق وفعّل إذن الكاميرا، ثم ارجع واضغط على الشريط.")
                .setPositiveButton("فتح الإعدادات") { _, _ ->
                    try {
                        startActivity(Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName")
                        ))
                    } catch (error: Exception) {
                        Log.e("EVE", "Could not open application settings.", error)
                    }
                }
                .setNegativeButton("إلغاء", null)
                .show()
        } else {
            prefs.edit().putBoolean("cameraPermissionRequested", true).apply()
            permissionRequest.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        // Returning to EVE cancels the pending background reminder.
        EveNotificationScheduler.onForeground(this)
    }

    override fun onResume() {
        super.onResume()
        if (::face.isInitialized) {
            face.startAnimation()
            main.removeCallbacks(idleLoop)
            main.postDelayed(idleLoop, 5200L)
        }
        if (::face.isInitialized && hasCameraPermission() && !cameraStarted) startCamera()
        if (::localLanguageBrain.isInitialized) {
            localLanguageBrain.ensureReady()
            localLanguageBrain.refreshStatus()
        }
    }

    override fun onStop() {
        // Schedule a contextual local message only after the activity actually leaves the screen.
        EveNotificationScheduler.onBackground(this)
        super.onStop()
    }

    override fun onPause() {
        main.removeCallbacks(idleLoop)
        faceOrNull()?.stopAnimation()
        faceCueSmoother.reset()
        emotionSmoother.reset()
        cameraProvider?.unbindAll()
        cameraStarted = false
        if (::cameraStatus.isInitialized) showCameraStatus("الكاميرا متوقفة مؤقتًا خارج التطبيق", active = false)
        super.onPause()
    }

    private fun faceOrNull(): RobotFaceView? = if (::face.isInitialized) face else null

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun initializeHandGestureRecognizer() {
        try {
            gestureExecutor.execute {
                try {
                    val options = GestureRecognizer.GestureRecognizerOptions.builder()
                        .setBaseOptions(
                            BaseOptions.builder()
                                .setModelAssetPath("gesture_recognizer.task")
                                .build()
                        )
                        .setRunningMode(RunningMode.IMAGE)
                        .setNumHands(1)
                        .build()
                    handGestureRecognizer = GestureRecognizer.createFromOptions(applicationContext, options)
                    handGestureReady = true
                    Log.i("EVE_GESTURE", "Offline MediaPipe hand gesture model ready; activeLens=$activeCameraLens.")
                } catch (error: Exception) {
                    handGestureReady = false
                    Log.e(
                        "EVE_GESTURE",
                        "Offline hand gesture model failed to load; facial and object vision remain active.",
                        error
                    )
                }
            }
        } catch (error: Exception) {
            Log.e("EVE_GESTURE", "Gesture worker is not available.", error)
        }
    }

    private fun handleHandGesture(signal: String, confidence: Float) {
        if (isFinishing || isDestroyed) return
        val now = SystemClock.elapsedRealtime()
        val cooldown = if (signal == "wave") 4200L else 1900L
        if (now - (lastHandReactionAt[signal] ?: 0L) < cooldown) return
        lastHandReactionAt[signal] = now
        val decision = brain.observeHandGesture(signal, confidence)
        val learnedPersonality = when (signal) {
            "wave", "thumb_up", "victory", "i_love_you" -> memory.adaptPersonality("friendly_gesture")
            "pointing_up" -> memory.adaptPersonality("curiosity_reward")
            else -> memory.learnedPersonality()
        }
        brain.updatePersonality(learnedPersonality)
        Log.i(
            "EVE_GESTURE",
            "HAND_SIGNAL signal=$signal confidence=$confidence mood=${decision.mood} " +
                "personalityWarmth=${learnedPersonality.warmth} playfulness=${learnedPersonality.playfulness}"
        )
        react(decision, decision.playSound)
    }

    private val hideCameraStatus = Runnable {
        if (::cameraStatus.isInitialized && cameraStarted) {
            cameraStatus.animate().cancel()
            cameraStatus.animate()
                .alpha(0f)
                .setDuration(220L)
                .withEndAction {
                    if (cameraStarted) cameraStatus.visibility = View.INVISIBLE
                }
                .start()
        }
    }

    private fun showCameraStatus(message: String, active: Boolean) {
        if (!::cameraStatus.isInitialized) return
        main.removeCallbacks(hideCameraStatus)
        cameraStatus.animate().cancel()
        cameraStatus.alpha = 1f
        cameraStatus.visibility = View.VISIBLE
        val lensLabel = when {
            message.contains("الأمامية") -> "أمامية"
            message.contains("الخلفية") -> "خلفية"
            else -> message
        }
        // Show the active lens briefly for diagnostics, then leave only the robot eyes on screen.
        cameraStatus.text = if (active) "●  $lensLabel  •  انقر مرتين للتبديل" else "●  $lensLabel"
        cameraStatus.contentDescription = if (active) "الكاميرا $lensLabel تعمل. انقر مرتين للتبديل."
            else lensLabel
        cameraStatus.setTextColor(if (active) 0xFF8FFFC0.toInt() else 0xFFFFD180.toInt())
        cameraStatus.background = GradientDrawable().apply {
            setColor(0x9907151C.toInt())
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), if (active) 0x6637F5A1 else 0x667D8B92)
        }
        if (active) main.postDelayed(hideCameraStatus, 1800L)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun selectorFor(lens: CameraLens): CameraSelector =
        if (lens == CameraLens.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA

    private fun bindCameraLens(provider: ProcessCameraProvider, lens: CameraLens) {
        val selector = selectorFor(lens)
        check(provider.hasCamera(selector)) { "The ${lens.name.lowercase()} camera is not available on this device." }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(Size(480, 360))
            .build()
        analysis.setAnalyzer(cameraExecutor, ::analyzeFrame)

        provider.unbindAll()
        provider.bindToLifecycle(this, selector, analysis)
        activeCameraLens = lens
        cameraStarted = true
        lastAnalysis.set(0L) // process the first frame from a new lens immediately
        faceCueSmoother.reset()
        emotionSmoother.reset()
        lastFaceSeenAt = 0L
        showCameraStatus(
            if (lens == CameraLens.FRONT) "الكاميرا الأمامية تعمل" else "الكاميرا الخلفية تعمل",
            active = true
        )
        Log.i("EVE", "CAMERA_LENS active=${lens.name} analysis=offline")
    }

    private fun switchCamera() {
        if (!hasCameraPermission()) {
            Log.w("EVE", "CAMERA_SWITCH_BLOCKED reason=permission_denied")
            permissionRequest.launch(Manifest.permission.CAMERA)
            return
        }

        val previous = activeCameraLens
        val requested = if (previous == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
        showCameraStatus(
            if (requested == CameraLens.FRONT) "جارٍ التبديل إلى الكاميرا الأمامية..." else "جارٍ التبديل إلى الكاميرا الخلفية...",
            active = false
        )
        val provider = cameraProvider
        if (provider == null) {
            // If the provider is still initializing, remember the requested lens; startCamera
            // will bind that lens when CameraX becomes ready.
            activeCameraLens = requested
            Log.i("EVE", "CAMERA_SWITCH_PENDING target=${requested.name}")
            if (!cameraStarted) startCamera()
            return
        }

        try {
            bindCameraLens(provider, requested)
            lastInteractionAt = SystemClock.elapsedRealtime()
            react(brain.cameraSwitched(requested.name), playSound = false)
            Log.i("EVE", "CAMERA_SWITCH_SUCCESS from=${previous.name} to=${requested.name}")
        } catch (error: Exception) {
            cameraStarted = false
            Log.e("EVE", "CAMERA_SWITCH_FAILED target=${requested.name}", error)
            // Restore the prior lens when possible so a failed switch does not leave vision off.
            try {
                bindCameraLens(provider, previous)
                Log.i("EVE", "CAMERA_SWITCH_RECOVERED lens=${previous.name}")
            } catch (restoreError: Exception) {
                cameraStarted = false
                showCameraStatus("تعذر تشغيل الكاميرتين؛ تحقق من الإذن", active = false)
                Log.e("EVE", "CAMERA_SWITCH_RECOVERY_FAILED", restoreError)
            }
        }
    }

    private fun startCamera() {
        if (!hasCameraPermission() || cameraStarted || isFinishing) {
            if (!hasCameraPermission()) showCameraStatus("الكاميرا متوقفة: الإذن غير ممنوح", active = false)
            return
        }
        showCameraStatus(
            if (activeCameraLens == CameraLens.FRONT) "جارٍ تشغيل الكاميرا الأمامية..." else "جارٍ تشغيل الكاميرا الخلفية...",
            active = false
        )
        cameraStarted = true
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                bindCameraLens(provider, activeCameraLens)
                Log.i("EVE", "Offline camera analysis started lens=${activeCameraLens.name}.")
            } catch (e: Exception) {
                cameraStarted = false
                showCameraStatus("تعذر تشغيل الكاميرا؛ تحقق من الإذن أو جرّب التبديل", active = false)
                react(brain.cameraUnavailable("camera could not start"), playSound = false)
                Log.e("EVE", "Camera could not start.", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastAnalysis.get()
        // Keep CameraX's latest-frame backpressure and avoid the previous 1.5-second reaction lag.
        if (now - previous < 400L || !lastAnalysis.compareAndSet(previous, now)) {
            proxy.close()
            return
        }
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }

        // This bitmap is independent of ImageProxy memory. It lets the local FER classifier
        // crop a detected face, while the ML Kit tasks continue using the original camera image.
        val bitmapNeeded = (emotionClassifier.isReady && !ferInferenceRunning.get()) ||
            (handGestureReady && !handGestureInferenceRunning.get())
        val frameBitmap = if (bitmapNeeded) {
            try {
                imageProxyToUprightBitmap(proxy)
            } catch (error: Exception) {
                Log.w("EVE_FER", "Could not convert camera YUV frame for the local emotion model.", error)
                null
            }
        } else {
            null
        }

        val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        val labelsTask = labeler.process(input)
        val faceTask = faceDetector.process(input)
        val mainExecutor = ContextCompat.getMainExecutor(this)

        labelsTask
            .addOnSuccessListener(mainExecutor) { labels ->
                val best = labels.maxByOrNull { it.confidence } ?: return@addOnSuccessListener
                if (best.confidence < 0.58f || best.text.isBlank()) return@addOnSuccessListener
                val label = best.text.trim().lowercase()
                val elapsed = SystemClock.elapsedRealtime()
                if (elapsed - (lastLabelAt[label] ?: 0L) < 2600L) return@addOnSuccessListener
                if (elapsed - lastFaceSeenAt < 8000L && !isMeaningfulLabel(label)) {
                    Log.i("EVE", "VISION generic label suppressed during face interaction: $label")
                    return@addOnSuccessListener
                }
                lastLabelAt[label] = elapsed
                val sightings = memory.remember(label)
                if (isVisualNoiseLabel(label)) {
                    // Keep the sighting counter for diagnostics, but texture/background labels
                    // must not overwrite an intentional face expression (for example after a touch).
                    Log.i(
                        "EVE",
                        "VISION label=$label confidence=${best.confidence} sightings=$sightings " +
                            "affinity=${memory.learnedAffinity(label)} mood=UNCHANGED ignored=visual_noise"
                    )
                    return@addOnSuccessListener
                }
                if (best.confidence >= 0.80f &&
                    (isMeaningfulLabel(label) || isCuriosityWorthyLabel(label))
                ) {
                    lastMeaningfulLabel = label
                    lastMeaningfulLabelAt = elapsed
                    lastMeaningfulConfidence = best.confidence
                    // Persist only the latest high-confidence label and a count; never camera frames.
                    getSharedPreferences("eve_relationship", MODE_PRIVATE).edit()
                        .putString("lastObservationLabel", label)
                        .putFloat("lastObservationConfidence", best.confidence)
                        .putInt("lastObservationCount", sightings)
                        .putLong("lastObservationAt", System.currentTimeMillis())
                        .apply()
                }
                if (sightings == 1 && isMeaningfulLabel(label)) {
                    val learnedPersonality = memory.adaptPersonality("curiosity_reward", strength = best.confidence)
                    brain.updatePersonality(learnedPersonality)
                    Log.i("EVE_PERSONALITY", "novel observation nudged curiosity=${learnedPersonality.curiosityBias}")
                }
                val affinity = memory.learnedAffinity(label)
                val decision = brain.observe(label, best.confidence, sightings, affinity)
                Log.i(
                    "EVE",
                    "VISION label=$label confidence=${best.confidence} sightings=$sightings " +
                        "affinity=$affinity mood=${decision.mood}"
                )
                react(decision, decision.playSound && isMeaningfulLabel(label))
                maybeReflectWithLocalLanguage(label, best.confidence, sightings)
                maybeAskFromCuriosity(label, best.confidence, sightings)
            }
            .addOnFailureListener(mainExecutor) { error ->
                Log.w("EVE", "Offline image labeling frame failed.", error)
            }

        // Crop faces on the camera executor, then run TensorFlow Lite on its own single thread.
        // A 2-of-3 temporal vote prevents a single blink/blurred frame from changing the character.
        val runHandGesture = frameBitmap != null && handGestureReady &&
            handGestureInferenceRunning.compareAndSet(false, true)
        val pendingFrameConsumers = AtomicLong(if (runHandGesture) 3L else 2L)
        fun finishFrameConsumer() {
            if (pendingFrameConsumers.decrementAndGet() == 0L) {
                try { frameBitmap?.recycle() } catch (_: Exception) { }
                try { proxy.close() } catch (_: Exception) { }
            }
        }

        // Release the frame only after image labels are done and the face listener has finished
        // cropping the image. Inference then owns its independent crop bitmap.
        labelsTask.addOnCompleteListener(mainExecutor) { finishFrameConsumer() }

        if (runHandGesture && frameBitmap != null) {
            val gestureBitmap = frameBitmap
            try {
                gestureExecutor.execute {
                    try {
                        val inputBitmap = BitmapImageBuilder(gestureBitmap).build()
                        val result = handGestureRecognizer?.recognize(inputBitmap)
                        val category = result?.gestures()?.firstOrNull()?.firstOrNull()
                        val points = result?.landmarks()?.firstOrNull().orEmpty()
                        val xs = points.map { it.x() }.filter { it.isFinite() }
                        val ys = points.map { it.y() }.filter { it.isFinite() }
                        val centerX = xs.takeIf { it.isNotEmpty() }?.average()?.toFloat()
                        val spanX = if (xs.size >= 2) (xs.maxOrNull()!! - xs.minOrNull()!!) else 0f
                        val spanY = if (ys.size >= 2) (ys.maxOrNull()!! - ys.minOrNull()!!) else 0f
                        val span = maxOf(spanX, spanY).takeIf { it > 0f }
                        val label = category?.categoryName()
                        val confidence = category?.score() ?: 0f
                        val observedAt = SystemClock.elapsedRealtime()
                        main.post {
                            if (!isFinishing && !isDestroyed) {
                                val signal = handGestureInterpreter.observe(
                                    label, confidence, centerX, span, observedAt
                                )
                                if (signal != null) handleHandGesture(signal, confidence)
                            }
                        }
                        if (label != null) {
                            Log.d("EVE_GESTURE", "HAND_FRAME label=$label confidence=$confidence")
                        } else {
                            val previousDiagnostic = lastGestureDiagnosticAt.get()
                            if (observedAt - previousDiagnostic >= 5000L &&
                                lastGestureDiagnosticAt.compareAndSet(previousDiagnostic, observedAt)
                            ) {
                                Log.i("EVE_GESTURE", "HAND_FRAME label=none inference=completed")
                            }
                        }
                    } catch (error: Exception) {
                        Log.w("EVE_GESTURE", "Offline hand gesture inference failed for one frame.", error)
                    } finally {
                        handGestureInferenceRunning.set(false)
                        finishFrameConsumer()
                    }
                }
            } catch (error: Exception) {
                handGestureInferenceRunning.set(false)
                finishFrameConsumer()
                Log.e("EVE_GESTURE", "Could not queue hand gesture inference.", error)
            }
        }

        faceTask
            .addOnSuccessListener(cameraExecutor) { faces ->
                try {
                    Log.i("EVE", "FACE_SCAN count=${faces.size}")
                    val bestFace = faces.maxByOrNull {
                        it.boundingBox.width().toLong() * it.boundingBox.height().toLong()
                    }
                    if (bestFace == null) {
                        faceCueSmoother.reset()
                        emotionSmoother.reset()
                        return@addOnSuccessListener
                    }

                    val elapsed = SystemClock.elapsedRealtime()
                    lastFaceSeenAt = elapsed
                    val rawCue = EveFaceCue(
                        smilingProbability = bestFace.smilingProbability,
                        leftEyeOpenProbability = bestFace.leftEyeOpenProbability,
                        rightEyeOpenProbability = bestFace.rightEyeOpenProbability,
                        headYawDegrees = bestFace.headEulerAngleY
                    )
                    val stableCue = faceCueSmoother.add(rawCue)

                    // Side-facing faces are not trusted by the expression model.
                    if (bestFace.headEulerAngleY.isFinite() &&
                        kotlin.math.abs(bestFace.headEulerAngleY) > 22f
                    ) {
                        if (stableCue != null && kotlin.math.abs(stableCue.headYawDegrees) > 22f) {
                            main.post { applyFaceCueFallback(stableCue, "turned face") }
                        }
                        return@addOnSuccessListener
                    }

                    val crop = frameBitmap?.let { cropFaceBitmap(it, bestFace.boundingBox) }
                    if (crop != null && emotionClassifier.isReady) {
                        if (!ferInferenceRunning.compareAndSet(false, true)) {
                            // Drop this frame rather than queueing stale work behind a slow inference.
                            crop.recycle()
                            Log.d("EVE_FER", "FER_FRAME_SKIPPED reason=inference_busy")
                        } else {
                            try {
                                emotionExecutor.execute {
                                    try {
                                        val prediction = emotionClassifier.predict(crop)
                                        if (prediction == null) {
                                            Log.w("EVE_FER", "FER inference returned no valid prediction.")
                                            if (stableCue != null) {
                                                main.post { applyFaceCueFallback(stableCue, "FER inference fallback") }
                                            }
                                        } else {
                                            val stableEmotion = emotionSmoother.add(prediction)
                                            if (stableEmotion == null) {
                                                Log.d(
                                                    "EVE_FER",
                                                    "EMOTION_PENDING label=${prediction.label} confidence=${prediction.confidence}; " +
                                                        "waiting for a reliable 2-of-3 vote"
                                                )
                                                if (stableCue != null) {
                                                    main.post {
                                                        applyFaceCueFallback(stableCue, "low-confidence FER prediction")
                                                    }
                                                }
                                            } else {
                                                main.post {
                                                    if (isFinishing || isDestroyed) return@post
                                                    if (SystemClock.elapsedRealtime() - lastFaceReactionAt < 1800L) return@post
                                                    val decision = brain.observeFacialEmotion(
                                                        stableEmotion.label,
                                                        stableEmotion.confidence
                                                    )
                                                    if (decision.reason.startsWith("uncertain facial-expression model output") &&
                                                        stableCue != null
                                                    ) {
                                                        lastFaceReactionAt = SystemClock.elapsedRealtime()
                                                        val cueDecision = brain.observeHumanFace(
                                                            stableCue.smilingProbability,
                                                            stableCue.leftEyeOpenProbability,
                                                            stableCue.rightEyeOpenProbability,
                                                            stableCue.headYawDegrees
                                                        )
                                                        Log.i(
                                                            "EVE_FER",
                                                            "FER_UNCERTAIN_FALLBACK label=${stableEmotion.label} " +
                                                                "confidence=${stableEmotion.confidence} cueMood=${cueDecision.mood}"
                                                        )
                                                        react(cueDecision, cueDecision.playSound)
                                                    } else {
                                                        lastFaceReactionAt = SystemClock.elapsedRealtime()
                                                        Log.i(
                                                            "EVE_FER",
                                                            "FACIAL_EMOTION_MODEL label=${stableEmotion.label} " +
                                                                "confidence=${stableEmotion.confidence} mood=${decision.mood}"
                                                        )
                                                        react(decision, decision.playSound)
                                                    }
                                                }
                                            }
                                        }
                                    } catch (error: Exception) {
                                        Log.e("EVE_FER", "FER inference task failed.", error)
                                    } finally {
                                        try { crop.recycle() } catch (_: Exception) { }
                                        ferInferenceRunning.set(false)
                                    }
                                }
                            } catch (error: Exception) {
                                try { crop.recycle() } catch (_: Exception) { }
                                ferInferenceRunning.set(false)
                                Log.w("EVE_FER", "Emotion executor rejected a face crop.", error)
                            }
                        }
                    } else if (ferInferenceRunning.get()) {
                        // Avoid fallback flicker while FER is already processing a recent frame.
                        Log.d("EVE_FER", "FER_FRAME_SKIPPED reason=inference_busy")
                    } else if (stableCue != null) {
                        // Basic ML Kit cues remain available if the model crop could not be formed.
                        main.post { applyFaceCueFallback(stableCue, "FER asset/crop unavailable") }
                    } else {
                        Log.d("EVE", "FACE_CUE_PENDING: waiting for stable facial cues.")
                    }
                } finally {
                    finishFrameConsumer()
                }
            }
            .addOnFailureListener(cameraExecutor) { error ->
                try {
                    Log.w("EVE", "Offline face analysis frame failed.", error)
                } finally {
                    finishFrameConsumer()
                }
            }
    }

    /**
     * Convert CameraX YUV_420_888 planes to RGB without relying on ImageProxy.toBitmap(),
     * which is not present in all CameraX versions supported by this project. Rotate the
     * resulting bitmap to the same upright coordinate space used by ML Kit's bounding box.
     */
    private fun imageProxyToUprightBitmap(proxy: ImageProxy): Bitmap? {
        if (proxy.format != ImageFormat.YUV_420_888 || proxy.planes.size < 3) return null
        val width = proxy.width
        val height = proxy.height
        if (width < 2 || height < 2) return null

        val yPlane = proxy.planes[0]
        val uPlane = proxy.planes[1]
        val vPlane = proxy.planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val yBase = yBuffer.position()
        val uBase = uBuffer.position()
        val vBase = vBuffer.position()
        val pixels = IntArray(width * height)

        for (row in 0 until height) {
            val yRow = yBase + row * yPlane.rowStride
            val uvRowU = uBase + (row shr 1) * uPlane.rowStride
            val uvRowV = vBase + (row shr 1) * vPlane.rowStride
            for (col in 0 until width) {
                val yy = (yBuffer.get(yRow + col * yPlane.pixelStride).toInt() and 0xff) - 16
                val u = (uBuffer.get(uvRowU + (col shr 1) * uPlane.pixelStride).toInt() and 0xff) - 128
                val v = (vBuffer.get(uvRowV + (col shr 1) * vPlane.pixelStride).toInt() and 0xff) - 128
                val y = (yy.coerceAtLeast(0)) * 1192
                val red = ((y + 1634 * v) shr 10).coerceIn(0, 255)
                val green = ((y - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val blue = ((y + 2066 * u) shr 10).coerceIn(0, 255)
                pixels[row * width + col] =
                    (0xff shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }

        val raw = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val rotation = ((proxy.imageInfo.rotationDegrees % 360) + 360) % 360
        if (rotation == 0) return raw
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return try {
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also {
                if (it !== raw) raw.recycle()
            }
        } catch (error: Exception) {
            raw.recycle()
            throw error
        }
    }

    private fun cropFaceBitmap(frame: Bitmap, box: Rect): Bitmap? {
        if (frame.isRecycled || frame.width < 24 || frame.height < 24) return null
        val padX = (box.width() * 0.10f).toInt().coerceAtLeast(2)
        val padY = (box.height() * 0.10f).toInt().coerceAtLeast(2)
        val left = (box.left - padX).coerceIn(0, frame.width - 1)
        val top = (box.top - padY).coerceIn(0, frame.height - 1)
        val right = (box.right + padX).coerceIn(left + 1, frame.width)
        val bottom = (box.bottom + padY).coerceIn(top + 1, frame.height)
        val width = right - left
        val height = bottom - top
        if (width < 24 || height < 24) {
            Log.d("EVE_FER", "Rejected tiny/out-of-frame face crop ${width}x${height}.")
            return null
        }
        return try {
            Bitmap.createBitmap(frame, left, top, width, height)
        } catch (error: Exception) {
            Log.w("EVE_FER", "Could not crop detected face bounds=$box frame=${frame.width}x${frame.height}.", error)
            null
        }
    }

    private fun applyFaceCueFallback(cue: EveFaceCue, reason: String) {
        if (isFinishing || isDestroyed) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFaceReactionAt < 1800L) return
        lastFaceReactionAt = now
        val decision = brain.observeHumanFace(
            smilingProbability = cue.smilingProbability,
            leftEyeOpenProbability = cue.leftEyeOpenProbability,
            rightEyeOpenProbability = cue.rightEyeOpenProbability,
            headYawDegrees = cue.headYawDegrees
        )
        Log.i("EVE", "FACE_CUE_FALLBACK reason=$reason mood=${decision.mood}")
        react(decision, decision.playSound)
    }

    private fun isVisualNoiseLabel(label: String): Boolean =
        label.trim().lowercase() in setOf(
            "pattern", "texture", "background", "wall", "floor", "ceiling",
            "line", "geometry", "grid", "tile", "color", "colour", "design"
        )

    private fun isMeaningfulLabel(label: String): Boolean =
        listOf(
            "food", "fruit", "banana", "apple", "pizza", "bread", "meal", "sandwich",
            "vegetable", "cake", "orange", "animal", "cat", "dog", "bird", "pet",
            "puppy", "kitten", "horse", "rabbit", "fish", "person", "people", "human",
            "face", "man", "woman", "child", "boy", "girl", "plant", "flower", "tree",
            "leaf", "grass", "garden"
        ).any(label::contains)

    private fun isCuriosityWorthyLabel(label: String): Boolean {
        val value = label.trim().lowercase(Locale.ROOT)
        return listOf(
            "food", "fruit", "banana", "apple", "pizza", "bread", "meal", "sandwich",
            "vegetable", "cake", "orange", "strawberry", "egg", "animal", "cat", "dog",
            "bird", "pet", "puppy", "kitten", "horse", "rabbit", "fish", "hamster",
            "turtle", "butterfly", "person", "people", "human", "face", "man", "woman",
            "child", "boy", "girl", "plant", "flower", "tree", "leaf", "grass", "garden",
            "cactus", "phone", "mobile phone", "cell phone", "smartphone", "laptop",
            "computer", "keyboard", "book", "cup", "bottle", "car", "bicycle", "toy",
            "clock", "camera", "headphones", "remote control", "shoe", "umbrella",
            "bag", "backpack", "glasses", "watch"
        ).any(value::contains) && !isVisualNoiseLabel(value)
    }

    private fun maybeAskFromCuriosity(label: String, confidence: Float, sightings: Int) {
        if (isFinishing || isDestroyed || !::dialogueBubble.isInitialized) return
        val preferences = getSharedPreferences("eve_curiosity", MODE_PRIVATE)
        val safeKey = label.trim().lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(48)
        if (safeKey.isBlank()) return
        val now = System.currentTimeMillis()
        val lastAny = preferences.getLong("last_question_at", 0L)
        val lastForLabel = preferences.getLong("label_question_at:" + safeKey, 0L)
        val countForLabel = preferences.getInt("label_question_count:" + safeKey, 0)
        val learned = memory.learnedPersonality()
        val thought = EveCuriosityEngine.consider(
            label = label,
            confidence = confidence,
            sightings = sightings,
            curiosityBias = learned.curiosityBias,
            lastAnyQuestionAtMs = lastAny,
            lastLabelQuestionAtMs = lastForLabel,
            previousQuestionCount = countForLabel,
            nowMs = now
        ) ?: return

        preferences.edit()
            .putLong("last_question_at", now)
            .putLong("label_question_at:" + safeKey, now)
            .putInt("label_question_count:" + safeKey, countForLabel + 1)
            .putString("last_question_label", label)
            .putString("last_question_text", thought.text)
            .apply()

        val decision = brain.selfGeneratedCuriosity(label, confidence, sightings)
        react(decision, playSound = false)
        showDialogueText(thought.text, durationMs = 6100L)
        Log.i(
            "EVE_CURIOSITY",
            "SELF_QUESTION label=" + label + " confidence=" + confidence + " sightings=" + sightings +
                " interest=" + thought.interest + " reason=" + thought.reason + " trait=" + learned.curiosityBias
        )
    }

    /**
     * Shows brief setup/inference status in the existing top chip; it does not add an app button.
     * During model download the chip remains visible with progress and the camera stays usable.
     */
    private fun handleLocalLanguageStatus(state: LocalLlmState, percent: Int, detail: String) {
        if (isFinishing || isDestroyed || !::cameraStatus.isInitialized) return
        when (state) {
            LocalLlmState.READY -> {
                Log.i("EVE_LLM", "STATUS ready=true onDevice=true")
                if (cameraStarted) {
                    showCameraStatus(
                        if (activeCameraLens == CameraLens.FRONT) "الكاميرا الأمامية تعمل" else "الكاميرا الخلفية تعمل",
                        active = true
                    )
                } else {
                    cameraStatus.text = "الدماغ المحلي جاهز"
                    cameraStatus.contentDescription = detail
                    cameraStatus.visibility = View.VISIBLE
                    cameraStatus.alpha = 1f
                }
            }
            LocalLlmState.WAITING_FOR_WIFI -> {
                showLocalBrainChip("الدماغ المحلي ينتظر Wi‑Fi", detail, 0xFFFFD180.toInt())
            }
            LocalLlmState.DOWNLOADING -> {
                showLocalBrainChip("تنزيل الدماغ المحلي $percent%", detail, 0xFFFFD180.toInt())
            }
            LocalLlmState.LOADING -> {
                showLocalBrainChip("تشغيل الدماغ المحلي…", detail, 0xFFB3E5FC.toInt())
            }
            LocalLlmState.ERROR -> {
                showLocalBrainChip("الدماغ المحلي غير جاهز", detail, 0xFFFFAB91.toInt())
            }
        }
    }

    private fun showLocalBrainChip(label: String, description: String, textColor: Int) {
        if (!::cameraStatus.isInitialized) return
        main.removeCallbacks(hideCameraStatus)
        cameraStatus.animate().cancel()
        cameraStatus.alpha = 1f
        cameraStatus.visibility = View.VISIBLE
        cameraStatus.text = label
        cameraStatus.contentDescription = description
        cameraStatus.setTextColor(textColor)
        cameraStatus.background = GradientDrawable().apply {
            setColor(0xE6071015.toInt())
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), 0x667D8B92)
        }
    }

    private fun localLanguageContext(): LocalLlmContext {
        val relationship = getSharedPreferences("eve_relationship", MODE_PRIVATE)
        val label = lastMeaningfulLabel
        return LocalLlmContext(
            latestVisionLabel = label,
            visionConfidence = lastMeaningfulConfidence,
            sightingCount = label?.let(memory::sightings) ?: 0,
            learnedAffinity = label?.let(memory::learnedAffinity) ?: 0f,
            currentMood = relationship.getString("lastMood", EveMood.NEUTRAL.name) ?: EveMood.NEUTRAL.name,
            moodReason = relationship.getString("lastMoodReason", "").orEmpty(),
            personality = memory.learnedPersonality(),
            recentObservations = memory.recentVisualMemories(5),
            recentDialogues = memory.recentDialogues(3),
            recentReflections = memory.recentReflections(3)
        )
    }

    /** Handles open-ended speech with a real on-device LLM after deterministic commands are checked. */
    private fun requestLocalLanguageReply(text: String, addressedToEve: Boolean) {
        val baseline = brain.respondToSpeech(text, addressedToEve)
        react(baseline, playSound = false)
        if (!::localLanguageBrain.isInitialized || !localLanguageBrain.isReady) {
            showDialogueText(
                "أجهّز الدماغ اللغوي المحلي. أول تشغيل يحتاج تنزيل النموذج مرة واحدة عبر Wi‑Fi؛ بعد ذلك تعمل الإجابات دون إنترنت.",
                durationMs = 6500L
            )
            voice.respondToSpeech(baseline.mood, text)
            Log.i("EVE_LLM", "USER_REPLY_DEFERRED reason=model_not_ready")
            return
        }

        localLanguageBrain.answerUser(text, localLanguageContext()) { outcome ->
            if (isFinishing || isDestroyed) return@answerUser
            val answer = outcome.getOrNull()
            if (answer == null) {
                val fallback = brain.respondToSpeech(text, addressedToEve)
                react(fallback, playSound = false)
                showDialogueText(
                    "تعذّر إكمال الرد المحلي الآن. ما زلت أستطيع الرؤية والتفاعل، ويمكنك إعادة السؤال بعد لحظة.",
                    durationMs = 6000L
                )
                voice.respondToSpeech(fallback.mood, text)
                Log.w("EVE_LLM", "USER_REPLY_FAILED; kept deterministic perception/behavior fallback", outcome.exceptionOrNull())
                return@answerUser
            }

            answer.reflectionArabic.takeIf { it.isNotBlank() }?.let(memory::rememberReflection)
            val suggestion = brain.integrateLanguageInsight(
                moodHint = answer.moodHint,
                intentHint = answer.intentHint,
                confidence = answer.confidence,
                note = answer.reflectionArabic,
                source = "speech_context"
            )
            val reply = answer.replyArabic.trim().take(650)
            if (reply.isNotBlank()) {
                memory.rememberDialogue(text, reply)
                showDialogueText(reply, durationMs = 7600L)
                Log.i(
                    "EVE_LLM",
                    "USER_REPLY_OK localOnly=true mood=${suggestion.mood} intent=${suggestion.intent} " +
                        "vision=${lastMeaningfulLabel ?: "none"} memories=${memory.recentVisualMemories(3).size}"
                )
            } else {
                showDialogueText(
                    "فهمت أن هناك شيئًا يستحق الانتباه، لكنني لم أستطع صياغة رد موثوق هذه المرة.",
                    durationMs = 5600L
                )
                Log.w("EVE_LLM", "USER_REPLY_EMPTY; model output had no usable Arabic reply")
            }
            react(suggestion, playSound = false)
            voice.respondToSpeech(suggestion.mood, text)
        }
    }

    /**
     * The language model receives trusted metadata from the local vision stack and short local
     * memories. It never gets raw frames and never generates a spoken line on each camera frame.
     */
    private fun maybeReflectWithLocalLanguage(label: String, confidence: Float, sightings: Int) {
        if (!::localLanguageBrain.isInitialized || !localLanguageBrain.isReady) return
        if (!confidence.isFinite() || confidence < 0.84f) return
        if (!isMeaningfulLabel(label) && !isCuriosityWorthyLabel(label)) return
        val now = SystemClock.elapsedRealtime()
        val previous = lastLocalLlmVisionAt.get()
        if (now - previous < 90_000L) return
        if (!lastLocalLlmVisionAt.compareAndSet(previous, now)) return
        lastLocalLlmVisionLabel = label

        localLanguageBrain.reflectOnObservation(localLanguageContext()) { outcome ->
            if (isFinishing || isDestroyed) return@reflectOnObservation
            val insight = outcome.getOrNull() ?: run {
                Log.w("EVE_LLM", "VISION_REFLECTION_FAILED label=$label", outcome.exceptionOrNull())
                return@reflectOnObservation
            }
            // Discard stale reasoning if a clearly different scene has replaced the evidence.
            if (lastMeaningfulLabel != label ||
                SystemClock.elapsedRealtime() - lastMeaningfulLabelAt !in 0L..90_000L
            ) {
                Log.i("EVE_LLM", "VISION_REFLECTION_DROPPED reason=stale_context label=$label")
                return@reflectOnObservation
            }
            val reflection = insight.reflectionArabic.ifBlank { insight.replyArabic }.trim().take(240)
            if (reflection.isNotBlank()) memory.rememberReflection(reflection)
            val suggestion = brain.integrateLanguageInsight(
                moodHint = insight.moodHint,
                intentHint = insight.intentHint,
                confidence = insight.confidence,
                note = reflection,
                source = "vision_memory"
            )
            react(suggestion, playSound = false)
            Log.i(
                "EVE_LLM",
                "VISION_REFLECTION_OK label=$label classifierConfidence=$confidence sightings=$sightings " +
                    "languageConfidence=${insight.confidence} mood=${suggestion.mood} intent=${suggestion.intent}"
            )
        }
    }

    private fun showDialogueText(text: String, durationMs: Long = 5200L) {
        if (isFinishing || isDestroyed || !::dialogueBubble.isInitialized || text.isBlank()) return
        main.removeCallbacks(hideDialogue)
        dialogueBubble.animate().cancel()
        dialogueBubble.text = text
        dialogueBubble.contentDescription = text
        dialogueBubble.alpha = 0f
        dialogueBubble.visibility = View.VISIBLE
        dialogueBubble.animate().alpha(1f).setDuration(180L).start()
        main.postDelayed(hideDialogue, durationMs.coerceIn(2500L, 9000L))
    }

    private fun react(decision: EveDecision, playSound: Boolean) {
        // Perception/touch/camera events restart the idle clock; idle decisions do not restart themselves.
        if (!decision.reason.startsWith("contextual idle:")) {
            lastInteractionAt = SystemClock.elapsedRealtime()
        }
        // Persist a compact affect snapshot so generated reminders use EVE's recent state.
        getSharedPreferences("eve_relationship", MODE_PRIVATE).edit()
            .putString("lastMood", decision.mood.name)
            .putString("lastMoodReason", decision.reason.take(160))
            .putLong("lastMoodAt", System.currentTimeMillis())
            .apply()
        face.setExpression(decision, System.currentTimeMillis())
        if (playSound) voice.speakMood(decision.mood)
        Log.i("EVE", "BEHAVIOR mood=${decision.mood} reason=${decision.reason}")
    }

    override fun onDestroy() {
        main.removeCallbacks(idleLoop)
        main.removeCallbacks(hideDialogue)
        if (::dialogueBubble.isInitialized) dialogueBubble.animate().cancel()
        offlineArabicSpeech?.close()
        offlineArabicSpeech = null
        handGestureReady = false
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        try { labeler.close() } catch (_: Exception) { }
        try { faceDetector.close() } catch (_: Exception) { }
        cameraExecutor.shutdown()
        if (emotionClassifierDelegate.isInitialized()) {
            try {
                emotionExecutor.execute { emotionClassifier.close() }
            } catch (_: Exception) { }
        }
        emotionExecutor.shutdown()
        try {
            gestureExecutor.execute {
                try { handGestureRecognizer?.close() } catch (_: Exception) { }
                handGestureRecognizer = null
            }
            gestureExecutor.shutdown()
        } catch (_: Exception) {
            try { gestureExecutor.shutdownNow() } catch (_: Exception) { }
        }
        if (::voice.isInitialized) voice.close()
        if (::localLanguageBrain.isInitialized) localLanguageBrain.close()
        super.onDestroy()
    }
}
