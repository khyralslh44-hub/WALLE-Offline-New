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
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * EVE face experience: camera labeling and behavior operate locally while the activity is open.
 * No network permission is declared. The user can deny the camera; the animated face still works.
 */
class MainActivity : ComponentActivity() {
    private lateinit var face: RobotFaceView
    private lateinit var brain: RobotBrain
    private lateinit var memory: EveMemory
    private lateinit var peopleMemory: EvePeopleMemory
    private lateinit var faceIdentityMemory: EveFaceIdentityMemory
    private lateinit var voice: RobotVoice
    private lateinit var localLanguageBrain: LocalLanguageBrain
    private val lastLocalLlmVisionAt = AtomicLong(0L)
    @Volatile private var lastLocalLlmVisionLabel = ""
    @Volatile private var currentLocalLlmState = LocalLlmState.WAITING_FOR_WIFI
    @Volatile private var currentLocalLlmPercent = 0
    @Volatile private var currentLocalLlmDetail = "لم تكتمل تهيئة الدماغ اللغوي"
    private var offlineArabicSpeech: OfflineArabicSpeech? = null
    @Volatile private var offlineSpeechStatus = "OFFLINE_SPEECH_NOT_STARTED"
    private var lastSpeechText = ""
    private var lastSpeechAt = 0L
    // Follow-up statements belong to the same short conversation; unrelated ambient speech does not.
    private var lastConversationalExchangeAt = 0L
    private data class PendingLocalQuestion(
        val text: String,
        val addressedToEve: Boolean,
        val queuedAtElapsed: Long
    )
    @Volatile private var pendingLocalQuestion: PendingLocalQuestion? = null
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
    private val faceIdentityExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var faceIdentityMatcher: EveFaceIdentityMatcher? = null
    private val faceIdentityWorkRunning = AtomicBoolean(false)
    private val lastFaceIdentityCheckAt = AtomicLong(0L)
    @Volatile private var pendingFaceEnrollment: EveFaceEnrollmentRequest? = null
    @Volatile private var pendingFaceEnrollmentExpiresAt = 0L
    @Volatile private var pendingFaceEnrollmentConfirmed = false
    @Volatile private var lastIdentityGreetingId = ""
    @Volatile private var lastIdentityGreetingAt = 0L
    // EfficientDet is initialized/inferred on its own worker so the camera/UI threads stay responsive.
    private val objectExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var localObjectDetector: EveLocalObjectDetector? = null
    @Volatile private var objectDetectorReady = false
    @Volatile private var localImageClassifier: EveLocalImageClassifier? = null
    @Volatile private var imageClassifierReady = false
    private val objectInferenceRunning = AtomicBoolean(false)
    private val lastObjectInferenceAt = AtomicLong(0L)
    private val imageClassifierRunning = AtomicBoolean(false)
    private val lastImageClassifierAt = AtomicLong(0L)
    private val lastObjectDiagnosticAt = AtomicLong(0L)
    private val objectLabelMemoryAt = mutableMapOf<String, Long>()
    private val objectLabelReactionAt = mutableMapOf<String, Long>()
    @Volatile private var lastObjectLabel = ""
    @Volatile private var lastObjectLabelConfidence = 0f
    @Volatile private var lastObjectLabelAt = 0L
    @Volatile private var handGestureRecognizer: GestureRecognizer? = null
    @Volatile private var handGestureReady = false
    private val handGestureInferenceRunning = AtomicBoolean(false)
    private val lastGestureDiagnosticAt = AtomicLong(0L)
    private val handGestureInterpreter = HandGestureInterpreter()
    private val lastHandReactionAt = mutableMapOf<String, Long>()
    @Volatile private var poseLandmarker: PoseLandmarker? = null
    @Volatile private var poseLandmarkerReady = false
    private val poseInferenceRunning = AtomicBoolean(false)
    private val lastPoseInferenceAt = AtomicLong(0L)
    @Volatile private var latestPoseAngles: EvePoseAngles? = null
    @Volatile private var latestPoseObservedAt = 0L
    private var previousPoseAngles: EvePoseAngles? = null
    private val emotionClassifierDelegate = lazy { EveFacialEmotionClassifier(applicationContext) }
    private val emotionClassifier by emotionClassifierDelegate
    private val emotionSmoother = EveEmotionSmoother(windowSize = 3, requiredVotes = 2, minConfidence = 0.62f)
    private val lastAnalysis = AtomicLong(0L)
    // Allow at most one FER inference in flight; never build a queue of stale camera frames.
    private val ferInferenceRunning = AtomicBoolean(false)
    private val faceCueSmoother = EveFaceCueSmoother(windowSize = 3, requiredVotes = 2)
    private val activityUnderstanding = EveActivityUnderstanding()
    private val environmentUnderstanding = EveEnvironmentUnderstanding()
    private val autonomousMind = EveAutonomousMind()
    @Volatile private var autonomousExplorationEnabled = false
    @Volatile private var lastDirectUserActionAt = SystemClock.elapsedRealtime()
    @Volatile private var lastAutonomyPlanSignature = ""
    @Volatile private var lastAutonomyReflectionAt = 0L
    @Volatile private var activeHumanActivity = EveHumanActivity.UNKNOWN
    @Volatile private var activeHumanActivityConfidence = 0f
    @Volatile private var lastHumanActivityAt = 0L
    @Volatile private var latestActivitySummary = ""
    @Volatile private var latestEnvironmentSummary = ""
    @Volatile private var lastActivityObservationAt = 0L
    @Volatile private var lastEnvironmentObservationAt = 0L
    @Volatile private var lastEnvironmentDiagnosticAt = 0L
    @Volatile private var latestAnalysisWidth = 1
    @Volatile private var latestAnalysisHeight = 1
    @Volatile private var latestHandObservedAt = 0L
    @Volatile private var latestHandCenterX = Float.NaN
    @Volatile private var latestHandCenterY = Float.NaN
    @Volatile private var latestHandTrajectoryScore = 0f
    @Volatile private var latestHandFingerTips: List<Pair<Float, Float>> = emptyList()
    private var previousHandCenterSample: Pair<Float, Float>? = null
    private val lastLabelAt = mutableMapOf<String, Long>()
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }
    private val faceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
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
    @Volatile private var latestSceneSummary = ""
    @Volatile private var latestObjectSummary = ""
    @Volatile private var latestImageClassifierSummary = ""
    @Volatile private var lastObjectSceneAt = 0L
    @Volatile private var lastImageClassifierSceneAt = 0L
    @Volatile private var latestObjectLabels: Set<String> = emptySet()
    @Volatile private var lastSceneObservationAt = 0L

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
        else {
            offlineSpeechStatus = "MICROPHONE_PERMISSION_DENIED"
            Log.i("EVE_AUDIO", "Microphone permission declined; offline listening disabled.")
            showCameraStatus("الميكروفون متوقف: الإذن غير ممنوح", active = false)
        }
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
        peopleMemory = EvePeopleMemory(applicationContext)
        faceIdentityMemory = EveFaceIdentityMemory(applicationContext)
        brain = RobotBrain(memory.learnedPersonality())
        voice = RobotVoice()
        localLanguageBrain = LocalLanguageBrain(applicationContext) { state, percent, message ->
            handleLocalLanguageStatus(state, percent, message)
        }
        localLanguageBrain.start()
        initializeHandGestureRecognizer()
        initializeOfflineObjectDetector()

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
                        // Both a tap and a gentle hold receive a local character cue; the cue
                        // has its own cooldown so quick repeated touches do not become noise.
                        react(touchDecision, playSound = false)
                        voice.acknowledgeTouch(touchDecision.mood, held = longPress)
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
                offlineSpeechStatus = status
                Log.i("EVE_AUDIO", status)
                when {
                    status.contains("OFFLINE_SPEECH_MODEL_LOADING") ->
                        showCameraStatus("جارٍ تجهيز الاستماع العربي المحلي", active = cameraStarted)
                    status.contains("OFFLINE_SPEECH_LISTENING") ->
                        showCameraStatus("الميكروفون يستمع بالعربية دون إنترنت", active = cameraStarted)
                    status.contains("MICROPHONE_PERMISSION_DENIED") ->
                        showCameraStatus("الميكروفون متوقف: الإذن غير ممنوح", active = false)
                    status.contains("FAILED") || status.contains("ERROR") ->
                        showCameraStatus("تعذر تشغيل الاستماع العربي؛ انقر للتشخيص", active = false)
                }
            }
        )
        offlineArabicSpeech?.start()
    }

    /**
     * Quick, in-app proof that the installed LiteRT-LM conversation actually generated output.
     * A new nonce is created on every run; only the real LocalLanguageBrain inference callback
     * can pass by returning that exact nonce. No hard-coded/offline FAQ response can pass this path.
     */
    private fun runLocalLlmSelfTest() {
        if (isFinishing || isDestroyed) return
        if (!::localLanguageBrain.isInitialized) {
            showDialogueText("اختبار النموذج غير متاح: لم تتم تهيئة الدماغ المحلي.", durationMs = 6500L)
            Log.e("EVE_LLM", "LOCAL_LLM_SELF_TEST_FAIL reason=not_initialized")
            return
        }
        if (!localLanguageBrain.isReady) {
            localLanguageBrain.ensureReady()
            showDialogueText(
                "النموذج اللغوي ليس جاهزًا بعد. انتظر حتى تظهر حالة الجاهزية ثم قل: اختبري النموذج.",
                durationMs = 7000L
            )
            Log.i(
                "EVE_LLM",
                "LOCAL_LLM_SELF_TEST_DEFERRED state=${localLanguageBrain.currentState}"
            )
            return
        }

        val nonce = "EVE-" + UUID.randomUUID().toString()
            .replace("-", "").take(7).uppercase(Locale.ROOT)
        val challenge = "اختبار تشخيصي: أدرجي الرمز حرفيًا داخل reply_ar دون تغييره: $nonce"
        Log.i("EVE_LLM", "LOCAL_LLM_SELF_TEST_START nonce=$nonce state=${localLanguageBrain.currentState}")
        showDialogueText("أتحقق الآن من التوليد اللغوي الحقيقي على الهاتف…", durationMs = 9000L)

        localLanguageBrain.answerUser(challenge, localLanguageContext(), maxOutputTokens = 24) { outcome ->
            if (isFinishing || isDestroyed) return@answerUser
            val answer = outcome.getOrNull()
            if (answer == null) {
                showDialogueText(
                    "لم ينجح الاختبار: لم يكتمل توليد النموذج المحلي. راجع حالة الدماغ ثم أعد المحاولة.",
                    durationMs = 8500L
                )
                Log.e(
                    "EVE_LLM",
                    "LOCAL_LLM_SELF_TEST_FAIL reason=inference_error nonce=$nonce",
                    outcome.exceptionOrNull()
                )
                return@answerUser
            }

            val reply = answer.replyArabic.trim()
            val passed = reply.contains(nonce, ignoreCase = true)
            if (passed) {
                showDialogueText(
                    "نجح الاختبار ✓ النموذج اللغوي ولّد ردًا جديدًا يتضمن الرمز $nonce. التوليد محلي على الهاتف.",
                    durationMs = 9500L
                )
                Log.i(
                    "EVE_LLM",
                    "LOCAL_LLM_SELF_TEST_PASS nonce=$nonce localOnly=true " +
                        "replyChars=${reply.length} model=Qwen2-0.5B-Instruct"
                )
            } else {
                showDialogueText(
                    "وصل رد من المسار المحلي، لكنه لم يجتز تحدّي الرمز. لن أعتبر النموذج متحققًا حتى ينجح الاختبار.",
                    durationMs = 9000L
                )
                Log.w(
                    "EVE_LLM",
                    "LOCAL_LLM_SELF_TEST_FAIL reason=nonce_not_returned nonce=$nonce replyChars=${reply.length}"
                )
            }
        }
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

        // Explicit device actions are handled before language generation so the model
        // cannot turn a camera-switch request into ordinary chat.
        if (listOf(
                "بدلي الكاميرا", "غيري الكاميرا", "بدل الكاميرا", "غير الكاميرا",
                "switch camera", "change camera", "flip camera"
            ).any(text::contains)
        ) {
            switchCamera()
            voice.respondToSpeech(EveMood.CURIOUS, text)
            return
        }

        // Hidden voice diagnostic: prove the local LLM generated a fresh, unpredictable token.
        // It runs before canned answers and never accepts a deterministic fallback as a pass.
        if (listOf(
                "اختبار النموذج", "اختبري النموذج", "فحص النموذج",
                "اختبار الدماغ", "اختبري دماغك", "اختبار اللغه", "اختبار الذكاء الاصطناعي"
            ).any(text::contains)
        ) {
            runLocalLlmSelfTest()
            return
        }

        if (listOf("إلغاء حفظ الوجه", "الغاء حفظ الوجه", "ألغِ تذكر الوجه").any(text::contains)) {
            pendingFaceEnrollment = null
            pendingFaceEnrollmentExpiresAt = 0L
            pendingFaceEnrollmentConfirmed = false
            showDialogueText("ألغيت طلب حفظ الوجه. لم أحفظ مرجعًا جديدًا.", durationMs = 5_000L)
            Log.i("EVE_PEOPLE", "FACE_ENROLLMENT_CANCELLED")
            return
        }
        if (listOf("احذفي الوجوه المحفوظة", "امسحي الوجوه المحفوظة", "احذف الوجوه المحفوظة").any(text::contains)) {
            faceIdentityMemory.deleteAll()
            pendingFaceEnrollment = null
            pendingFaceEnrollmentExpiresAt = 0L
            pendingFaceEnrollmentConfirmed = false
            lastIdentityGreetingId = ""
            showDialogueText("حذفت مراجع الوجوه المحفوظة من هذا الهاتف.", durationMs = 5_000L)
            Log.i("EVE_PEOPLE", "FACE_PROFILES_DELETED")
            return
        }

        if (pendingFaceEnrollment != null && listOf(
                "نعم احفظ الوجه", "نعم احفظه", "اوافق على حفظ الوجه", "أكد حفظ الوجه", "اكد حفظ الوجه"
            ).any(text::contains)
        ) {
            if (now > pendingFaceEnrollmentExpiresAt) {
                pendingFaceEnrollment = null
                pendingFaceEnrollmentExpiresAt = 0L
                pendingFaceEnrollmentConfirmed = false
                showDialogueText("انتهت مهلة التأكيد؛ لم أحفظ وجهًا.", durationMs = 5_000L)
            } else {
                pendingFaceEnrollmentConfirmed = true
                pendingFaceEnrollmentExpiresAt = now + 60_000L
                showDialogueText(
                    "تم التأكيد. بعد استئذان الشخص، اجعل وجهه واضحًا أمام الكاميرا خلال دقيقة. " +
                        "سيبقى مرجع الوجه على هذا الهاتف فقط.",
                    durationMs = 8_000L
                )
                Log.i("EVE_PEOPLE", "FACE_ENROLLMENT_CONFIRMED localOnly=true")
            }
            return
        }

        val faceEnrollmentRequest = EveFaceEnrollmentParser.parse(text)
        if (faceEnrollmentRequest != null) {
            val storedPerson = peopleMemory.rememberIntroduction(faceEnrollmentRequest.spokenIntroduction)
                ?: faceEnrollmentRequest.person
            pendingFaceEnrollment = faceEnrollmentRequest.copy(person = storedPerson)
            pendingFaceEnrollmentExpiresAt = now + 60_000L
            pendingFaceEnrollmentConfirmed = false
            val confirmation = "أستطيع حفظ مرجع وجه ${storedPerson.relationArabic} باسم ${storedPerson.name} على هذا الهاتف فقط. " +
                "استأذن الشخص أولًا، ثم قل «نعم احفظ الوجه» للتأكيد. قل «إلغاء حفظ الوجه» للإلغاء."
            showDialogueText(confirmation, durationMs = 8_000L)
            Log.i(
                "EVE_PEOPLE",
                "FACE_ENROLLMENT_REQUESTED relation=${storedPerson.key} nameLength=${storedPerson.name.length} localOnly=true"
            )
            return
        }

        // Spoken name memory is separate from optional, explicit face-reference enrolment.
        val introducedPerson = peopleMemory.rememberIntroduction(text)
        if (introducedPerson != null) {
            val confirmation = when (introducedPerson.key) {
                "user" -> "حفظت اسمك، ${introducedPerson.name}، على هذا الهاتف. هل أناديك بهذا الاسم؟"
                else -> "حفظت أن ${introducedPerson.relationArabic} اسمه ${introducedPerson.name}. سأستخدم الاسم عندما تسألني عنه."
            }
            val decision = brain.observeKnownPerson(introducedPerson.relationArabic, isIntroduction = true)
            react(decision, playSound = false)
            memory.rememberDialogue(text, confirmation)
            showDialogueText(confirmation, durationMs = 8_000L)
            voice.respondToSpeech(decision.mood, text)
            lastConversationalExchangeAt = now
            Log.i("EVE_PEOPLE", "INTRODUCTION_SAVED relation=${introducedPerson.key} nameLength=${introducedPerson.name.length}")
            return
        }

        val peopleAnswer = peopleMemory.answer(text)
        if (peopleAnswer != null) {
            val decision = brain.observeKnownPerson("العائلة والأصدقاء", isIntroduction = false)
            react(decision, playSound = false)
            memory.rememberDialogue(text, peopleAnswer)
            showDialogueText(peopleAnswer, durationMs = 8_000L)
            voice.respondToSpeech(decision.mood, text)
            lastConversationalExchangeAt = now
            Log.i("EVE_PEOPLE", "MEMORY_ANSWER chars=${peopleAnswer.length}")
            return
        }

        val addressedToEve = listOf(
            "ايف", "يا ايف", "ايفي", "يا روبوت", "eve", "hey eve", "hello eve"
        ).any(text::contains)
        val wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() }
        val isQuestion = OfflineEveResponder.isQuestion(text)
        val activeConversation = lastConversationalExchangeAt > 0L &&
            now - lastConversationalExchangeAt in 0L..90_000L
        val conversationalTurn = isQuestion || addressedToEve ||
            (wordCount >= 3 && activeConversation)

        // Resolve known safe local questions first; never make an FAQ wait behind slow inference.
        val currentVisualAge = if (lastMeaningfulLabelAt > 0L) now - lastMeaningfulLabelAt else Long.MAX_VALUE
        val offlineAnswer = OfflineEveResponder.answer(
            rawQuestion = text,
            latestLabel = lastMeaningfulLabel,
            latestConfidence = lastMeaningfulConfidence,
            observationAgeMs = currentVisualAge
        )
        if (offlineAnswer != null &&
            offlineAnswer.topic != "unknown_offline_question" &&
            offlineAnswer.topic != "current_visual_label"
        ) {
            val speechState = brain.respondToSpeech(text, addressedToEve = true)
            val answerDecision = speechState.copy(
                mood = offlineAnswer.mood,
                reason = "local answer: " + offlineAnswer.topic,
                intent = if (offlineAnswer.mood == EveMood.CURIOUS) EveIntent.EXPLORE else EveIntent.FOCUS
            )
            react(answerDecision, playSound = false)
            showDialogueText(offlineAnswer.text, durationMs = 7200L)
            memory.rememberDialogue(text, offlineAnswer.text)
            voice.respondToSpeech(answerDecision.mood, text)
            Log.i("EVE_DIALOGUE", "ANSWER topic=" + offlineAnswer.topic + " mood=" + answerDecision.mood + " local=true")
            lastConversationalExchangeAt = now
            return
        }

        // Open-ended questions go to the local generator only after deterministic memory/FAQ paths.
        if (conversationalTurn && ::localLanguageBrain.isInitialized) {
            if (localLanguageBrain.isReady) {
                requestLocalLanguageReply(text, addressedToEve)
                return
            }
            if (isLocalLanguageBrainStarting()) {
                queueLocalQuestion(text, addressedToEve, "recognized speech before model ready")
                return
            }
        }

        // If the local generator is unavailable, return an honest grounded fallback immediately.
        if (offlineAnswer != null) {
            val speechState = brain.respondToSpeech(text, addressedToEve = true)
            val answerDecision = speechState.copy(
                mood = offlineAnswer.mood,
                reason = "local answer: " + offlineAnswer.topic,
                intent = if (offlineAnswer.mood == EveMood.CURIOUS) EveIntent.EXPLORE else EveIntent.FOCUS
            )
            react(answerDecision, playSound = false)
            showDialogueText(offlineAnswer.text, durationMs = 7200L)
            memory.rememberDialogue(text, offlineAnswer.text)
            voice.respondToSpeech(answerDecision.mood, text)
            Log.i("EVE_DIALOGUE", "ANSWER topic=" + offlineAnswer.topic + " mood=" + answerDecision.mood + " local=true")
            lastConversationalExchangeAt = now
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
            val cameraLine = if (!cameraStarted) {
                "الكاميرا قيد التشغيل"
            } else if (activeCameraLens == CameraLens.FRONT) {
                "الكاميرا الأمامية تعمل"
            } else {
                "الكاميرا الخلفية تعمل"
            }
            val brainLine = when (currentLocalLlmState) {
                LocalLlmState.READY -> "الدماغ اللغوي المحلي: جاهز دون إنترنت"
                LocalLlmState.DOWNLOADING -> "الدماغ اللغوي: تنزيل $currentLocalLlmPercent%"
                LocalLlmState.LOADING -> "الدماغ اللغوي: جارٍ التهيئة"
                LocalLlmState.WAITING_FOR_WIFI -> "الدماغ اللغوي: ينتظر اتصال Wi-Fi"
                LocalLlmState.ERROR -> "الدماغ اللغوي غير جاهز: $currentLocalLlmDetail"
            }
            val micLine = when {
                offlineSpeechStatus.contains("OFFLINE_SPEECH_LISTENING") -> "الميكروفون: يستمع بالعربية محليًا"
                offlineSpeechStatus.contains("FAILED") || offlineSpeechStatus.contains("ERROR") ->
                    "الميكروفون: تعذّر تشغيل التعرف المحلي"
                offlineSpeechStatus == "MICROPHONE_PERMISSION_DENIED" ->
                    "الميكروفون: الإذن مرفوض"
                else -> "الميكروفون: $offlineSpeechStatus"
            }
            android.widget.Toast.makeText(
                this, "$cameraLine\n$brainLine\n$micLine", android.widget.Toast.LENGTH_LONG
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
        val cameraDescription = when {
            message.contains("الأمامية") -> "الكاميرا الأمامية"
            message.contains("الخلفية") -> "الكاميرا الخلفية"
            else -> message
        }
        val brainDescription = when (currentLocalLlmState) {
            LocalLlmState.READY -> "الدماغ جاهز"
            LocalLlmState.DOWNLOADING -> "تنزيل الدماغ $currentLocalLlmPercent%"
            LocalLlmState.LOADING -> "تهيئة الدماغ اللغوي…"
            LocalLlmState.WAITING_FOR_WIFI -> "الدماغ ينتظر Wi-Fi"
            LocalLlmState.ERROR -> "خطأ في الدماغ اللغوي"
        }
        val shown = "$cameraDescription • $brainDescription"
        // Do not hide a missing/failed brain behind the normal camera status.
        cameraStatus.text = if (active) "●  $shown • انقر مرتين للتبديل" else "●  $shown"
        cameraStatus.contentDescription = "$shown. حالة الميكروفون: $offlineSpeechStatus. انقر لعرض التشخيص."
        cameraStatus.setTextColor(
            if (currentLocalLlmState == LocalLlmState.ERROR ||
                currentLocalLlmState == LocalLlmState.WAITING_FOR_WIFI
            ) 0xFFFFD180.toInt() else if (active) 0xFF8FFFC0.toInt() else 0xFFE6FFFF.toInt()
        )
        cameraStatus.background = GradientDrawable().apply {
            setColor(0x9907151C.toInt())
            val healthy = active && currentLocalLlmState == LocalLlmState.READY
            setStroke(dp(1), if (healthy) 0x6637F5A1 else 0x667D8B92)
            cornerRadius = dp(16).toFloat()
        }
        if (active && currentLocalLlmState == LocalLlmState.READY) {
            main.postDelayed(hideCameraStatus, 3200L)
        }
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

        // Run the detector on a throttled cadence: named object boxes are more useful than
        // generic whole-image guesses, and a single in-flight guard prevents CPU queues.
        val requestObjectDetection = objectDetectorReady &&
            now - lastObjectInferenceAt.get() >= objectDetectionIntervalMs &&
            objectInferenceRunning.compareAndSet(false, true)
        if (requestObjectDetection) lastObjectInferenceAt.set(now)

        // A slower whole-image pass broadens category coverage beyond the COCO detector's
        // object classes; single-flight scheduling prevents a backlog on mid-range phones.
        val requestImageClassification = imageClassifierReady &&
            now - lastImageClassifierAt.get() >= imageClassificationIntervalMs &&
            imageClassifierRunning.compareAndSet(false, true)
        if (requestImageClassification) lastImageClassifierAt.set(now)

        // This RGB bitmap is independent of ImageProxy memory and is shared read-only with
        // object detection, broad image classification, facial-expression inference, and gestures.
        val bitmapNeeded = requestObjectDetection || requestImageClassification ||
            (emotionClassifier.isReady && !ferInferenceRunning.get()) ||
            (handGestureReady && !handGestureInferenceRunning.get())
        val frameBitmap = if (bitmapNeeded) {
            try {
                imageProxyToUprightBitmap(proxy).also { bitmap ->
                    if (bitmap != null) {
                        latestAnalysisWidth = bitmap.width.coerceAtLeast(1)
                        latestAnalysisHeight = bitmap.height.coerceAtLeast(1)
                    }
                }
            } catch (error: Exception) {
                Log.w("EVE_FER", "Could not convert camera YUV frame for local on-device models.", error)
                null
            }
        } else {
            null
        }
        if (requestObjectDetection && frameBitmap == null) objectInferenceRunning.set(false)
        if (requestImageClassification && frameBitmap == null) imageClassifierRunning.set(false)
        val runObjectDetection = requestObjectDetection && frameBitmap != null
        val runImageClassification = requestImageClassification && frameBitmap != null

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
                if (shouldSuppressConflictingAnimalLabel(label, elapsed, "mlkit_image_labeling")) {
                    return@addOnSuccessListener
                }
                if (elapsed - (lastLabelAt[label] ?: 0L) < 2600L) return@addOnSuccessListener
                if (elapsed - lastFaceSeenAt < 8000L && !isMeaningfulLabel(label)) {
                    Log.i("EVE", "VISION generic label suppressed during face interaction: $label")
                    return@addOnSuccessListener
                }
                lastLabelAt[label] = elapsed
                if (isVisualNoiseLabel(label)) {
                    // A generic whole-image guess such as "pattern" is not object understanding.
                    // Do not count it as a sighting, change the mood, or overwrite fresh evidence.
                    Log.i(
                        "EVE",
                        "VISION label=$label confidence=${best.confidence} mood=UNCHANGED ignored=visual_noise"
                    )
                    return@addOnSuccessListener
                }
                if (!isMeaningfulLabel(label) && !isCuriosityWorthyLabel(label)) {
                    Log.i(
                        "EVE",
                        "VISION label=$label confidence=${best.confidence} mood=UNCHANGED ignored=unsupported_generic_label"
                    )
                    return@addOnSuccessListener
                }
                val sightings = memory.remember(label)
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
                // Curiosity questions take priority over silent reflections: the local LLM can
                // only execute one generation at a time, so never start two requests for one frame.
                val askedOrQueued = maybeAskFromCuriosity(label, best.confidence, sightings)
                if (!askedOrQueued) maybeReflectWithLocalLanguage(label, best.confidence, sightings)
            }
            .addOnFailureListener(mainExecutor) { error ->
                Log.w("EVE", "Offline image labeling frame failed.", error)
            }

        // Crop faces on the camera executor, then run TensorFlow Lite on its own single thread.
        // A 2-of-3 temporal vote prevents a single blink/blurred frame from changing the character.
        val runHandGesture = frameBitmap != null && handGestureReady &&
            handGestureInferenceRunning.compareAndSet(false, true)
        val pendingFrameConsumers = AtomicLong(
            2L +
                (if (runHandGesture) 1L else 0L) +
                (if (runObjectDetection) 1L else 0L) +
                (if (runImageClassification) 1L else 0L)
        )
        fun finishFrameConsumer() {
            if (pendingFrameConsumers.decrementAndGet() == 0L) {
                try { frameBitmap?.recycle() } catch (_: Exception) { }
                try { proxy.close() } catch (_: Exception) { }
            }
        }

        // Release the frame only after image labels are done and the face listener has finished
        // cropping the image. Inference then owns its independent crop bitmap.
        labelsTask.addOnCompleteListener(mainExecutor) { finishFrameConsumer() }

        if (runObjectDetection && frameBitmap != null) {
            val objectBitmap = frameBitmap
            try {
                objectExecutor.execute {
                    try {
                        val observations = localObjectDetector?.detect(objectBitmap).orEmpty()
                        main.post {
                            if (!isFinishing && !isDestroyed) applyObjectObservations(observations)
                        }
                    } catch (error: Exception) {
                        Log.w("EVE_OBJECTS", "Object detection failed for a live camera frame.", error)
                    } finally {
                        objectInferenceRunning.set(false)
                        finishFrameConsumer()
                    }
                }
            } catch (error: Exception) {
                objectInferenceRunning.set(false)
                finishFrameConsumer()
                Log.e("EVE_OBJECTS", "Could not queue live object detection.", error)
            }
        }

        if (runImageClassification && frameBitmap != null) {
            val imageBitmap = frameBitmap
            try {
                objectExecutor.execute {
                    try {
                        val categories = localImageClassifier?.classify(imageBitmap).orEmpty()
                        val meanLuminance = estimateMeanLuminance(imageBitmap)
                        main.post {
                            if (!isFinishing && !isDestroyed) {
                                applyImageClassifierObservations(categories, meanLuminance)
                            }
                        }
                    } catch (error: Exception) {
                        Log.w("EVE_IMAGENET", "Whole-image classification failed for one frame.", error)
                    } finally {
                        imageClassifierRunning.set(false)
                        finishFrameConsumer()
                    }
                }
            } catch (error: Exception) {
                imageClassifierRunning.set(false)
                finishFrameConsumer()
                Log.e("EVE_IMAGENET", "Could not queue image classifier inference.", error)
            }
        }

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
                        val centerY = ys.takeIf { it.isNotEmpty() }?.average()?.toFloat()
                        val fingerTips = listOf(4, 8, 12, 16, 20).mapNotNull { tipIndex ->
                            points.getOrNull(tipIndex)?.let { tip ->
                                val tx = tip.x()
                                val ty = tip.y()
                                if (tx.isFinite() && ty.isFinite()) {
                                    tx.coerceIn(0f, 1f) to ty.coerceIn(0f, 1f)
                                } else null
                            }
                        }
                        val movement = if (centerX != null && centerY != null) {
                            val current = centerX.coerceIn(0f, 1f) to centerY.coerceIn(0f, 1f)
                            val previous = previousHandCenterSample
                            previousHandCenterSample = current
                            if (previous == null) 0f else kotlin.math.hypot(
                                (current.first - previous.first).toDouble(),
                                (current.second - previous.second).toDouble()
                            ).toFloat().div(0.12f).coerceIn(0f, 1f)
                        } else {
                            previousHandCenterSample = null
                            0f
                        }
                        latestHandCenterX = centerX?.coerceIn(0f, 1f) ?: Float.NaN
                        latestHandCenterY = centerY?.coerceIn(0f, 1f) ?: Float.NaN
                        latestHandFingerTips = fingerTips
                        latestHandTrajectoryScore = movement
                        latestHandObservedAt = SystemClock.elapsedRealtime()
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
                        val elapsed = SystemClock.elapsedRealtime()
                        val unknown = activityUnderstanding.observe(
                            EveHumanFrameEvidence(timestampMs = elapsed, personConfidence = 0f)
                        )
                        applyHumanActivityInterpretation(unknown, elapsed)
                        return@addOnSuccessListener
                    }

                    val elapsed = SystemClock.elapsedRealtime()
                    lastFaceSeenAt = elapsed
                    observeHumanActivityFromFace(bestFace, elapsed)
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
                    if (crop != null) processFaceIdentityCrop(crop, elapsed)
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

    /**
     * Work is opt-in: no face template is stored until the user gives an explicit voice command.
     * Matching happens off the camera/UI thread, compares only private enrolled references, and
     * greets once per cooldown rather than speaking continuously for every frame.
     */
    private fun processFaceIdentityCrop(faceCrop: Bitmap, observedAt: Long) {
        val request = pendingFaceEnrollment
        if (request != null && observedAt > pendingFaceEnrollmentExpiresAt) {
            pendingFaceEnrollment = null
            pendingFaceEnrollmentExpiresAt = 0L
            pendingFaceEnrollmentConfirmed = false
            main.post {
                if (!isFinishing && !isDestroyed) {
                    showDialogueText("انتهت مهلة حفظ الوجه؛ لم أحفظ وجهًا جديدًا.", durationMs = 5_000L)
                }
            }
            return
        }
        val enrolling = request != null && pendingFaceEnrollmentConfirmed
        // Seeing someone or merely mentioning their name never silently enrolls their face.
        if (request != null && !enrolling) return
        if (!enrolling) {
            if (!faceIdentityMemory.hasProfiles()) return
            val previous = lastFaceIdentityCheckAt.get()
            if (observedAt - previous < 2_800L || !lastFaceIdentityCheckAt.compareAndSet(previous, observedAt)) return
        }
        if (!faceIdentityWorkRunning.compareAndSet(false, true)) return
        val owned = try {
            faceCrop.copy(Bitmap.Config.ARGB_8888, false)
        } catch (error: Exception) {
            faceIdentityWorkRunning.set(false)
            Log.w("EVE_PEOPLE", "Could not copy transient face crop for local matching.", error)
            return
        }
        try {
            faceIdentityExecutor.execute {
                try {
                    if (enrolling) {
                        if (pendingFaceEnrollment != request ||
                            SystemClock.elapsedRealtime() > pendingFaceEnrollmentExpiresAt
                        ) return@execute
                        // Loading the model first prevents claiming an enrolment when the model is absent.
                        val matcher = faceIdentityMatcher ?: EveFaceIdentityMatcher(applicationContext)
                            .also { faceIdentityMatcher = it }
                        val profile = faceIdentityMemory.enroll(requireNotNull(request).person, owned)
                        main.post {
                            if (isFinishing || isDestroyed) return@post
                            if (pendingFaceEnrollment == request) {
                                pendingFaceEnrollment = null
                                pendingFaceEnrollmentExpiresAt = 0L
                                pendingFaceEnrollmentConfirmed = false
                                lastIdentityGreetingId = profile.id
                                lastIdentityGreetingAt = SystemClock.elapsedRealtime()
                                val decision = brain.observeKnownPerson(profile.relationArabic, isIntroduction = true)
                                react(decision, playSound = false)
                                showDialogueText(
                                    "حفظت مرجع وجه ${profile.relationArabic} باسم ${profile.name} على هذا الهاتف. " +
                                        "سأحاول التعرّف عليه عندما تكون الصورة واضحة.",
                                    durationMs = 8_000L
                                )
                                Log.i(
                                    "EVE_PEOPLE",
                                    "FACE_PROFILE_ENROLLED id=${profile.id} relation=${profile.personKey} " +
                                        "imageStoredLocally=true network=false"
                                )
                            }
                        }
                    } else {
                        val matcher = faceIdentityMatcher ?: EveFaceIdentityMatcher(applicationContext)
                            .also { faceIdentityMatcher = it }
                        val match = faceIdentityMemory.recognize(owned, matcher) ?: return@execute
                        val now = SystemClock.elapsedRealtime()
                        val sameGreeting = lastIdentityGreetingId == match.profile.id
                        if (sameGreeting && now - lastIdentityGreetingAt < 30_000L) return@execute
                        main.post {
                            if (isFinishing || isDestroyed) return@post
                            val current = SystemClock.elapsedRealtime()
                            val repeated = lastIdentityGreetingId == match.profile.id &&
                                current - lastIdentityGreetingAt < 30_000L
                            if (repeated) return@post
                            lastIdentityGreetingId = match.profile.id
                            lastIdentityGreetingAt = current
                            val decision = brain.observeKnownPerson(
                                match.profile.relationArabic,
                                isIntroduction = false
                            )
                            react(decision, playSound = false)
                            showDialogueText(
                                "أهلًا ${match.profile.name}، أعتقد أنني تعرّفت عليك من الذاكرة المحلية.",
                                durationMs = 5_500L
                            )
                            Log.i(
                                "EVE_PEOPLE",
                                "FACE_PROFILE_MATCH id=${match.profile.id} relation=${match.profile.personKey} " +
                                    "similarity=${match.similarity} localOnly=true"
                            )
                        }
                    }
                } catch (error: Exception) {
                    Log.e("EVE_PEOPLE", "Local face enrolment/matching failed; no identity was asserted.", error)
                    main.post {
                        if (!isFinishing && !isDestroyed && enrolling && pendingFaceEnrollment == request) {
                            pendingFaceEnrollment = null
                            pendingFaceEnrollmentExpiresAt = 0L
                            pendingFaceEnrollmentConfirmed = false
                            showDialogueText(
                                "لم أستطع حفظ مرجع الوجه؛ لم أسجل تعرّفًا غير مؤكد.",
                                durationMs = 5_500L
                            )
                        }
                    }
                } finally {
                    try { owned.recycle() } catch (_: Exception) { }
                    faceIdentityWorkRunning.set(false)
                }
            }
        } catch (error: Exception) {
            try { owned.recycle() } catch (_: Exception) { }
            faceIdentityWorkRunning.set(false)
            Log.e("EVE_PEOPLE", "Could not queue local face matching.", error)
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

    private fun initializeOfflineObjectDetector() {
        try {
            objectExecutor.execute {
                // Each model has its own failure boundary: a missing classifier asset must not
                // disable the grounded detector, and a detector failure must not disable classification.
                try {
                    val candidate = EveLocalObjectDetector(applicationContext)
                    if (candidate.isReady) {
                        localObjectDetector = candidate
                        objectDetectorReady = true
                        Log.i("EVE_OBJECTS", "OBJECT_DETECTOR_AVAILABLE model=efficientdet_lite0.tflite offline=true")
                    } else {
                        Log.e("EVE_OBJECTS", "OBJECT_DETECTOR_UNAVAILABLE; generic image labels will not be treated as object recognition.")
                    }
                } catch (error: Exception) {
                    objectDetectorReady = false
                    Log.e("EVE_OBJECTS", "Could not initialize the object detector.", error)
                }

                try {
                    val classifier = EveLocalImageClassifier(applicationContext)
                    if (classifier.isReady) {
                        localImageClassifier = classifier
                        imageClassifierReady = true
                        Log.i(
                            "EVE_IMAGENET",
                            "IMAGE_CLASSIFIER_AVAILABLE model=efficientnet_lite0_int8.tflite classes~1000 offline=true"
                        )
                    } else {
                        Log.w("EVE_IMAGENET", "IMAGE_CLASSIFIER_UNAVAILABLE; COCO object detection remains active.")
                    }
                } catch (error: Exception) {
                    imageClassifierReady = false
                    Log.e("EVE_IMAGENET", "Could not initialize the broad image classifier.", error)
                }
            }
        } catch (error: Exception) {
            objectDetectorReady = false
            imageClassifierReady = false
            Log.e("EVE_OBJECTS", "Could not start local vision worker.", error)
        }
    }

    private fun applyObjectObservations(objects: List<EveVisualObject>) {
        if (isFinishing || isDestroyed) return
        val now = SystemClock.elapsedRealtime()
        // Build labels from this inference only; do not let old dog detections justify a new guess.
        val rawSceneObjects = objects.asSequence()
            .filter { it.confidence >= objectObservationMinConfidence }
            .filterNot { isVisualNoiseLabel(it.label) }
            .filterNot { it.label.isBlank() || it.label == "???" }
            .sortedByDescending { it.confidence * (0.85f + 0.15f * it.areaFraction.coerceIn(0f, 1f)) }
            .take(5)
            .toList()
        val rawObjectLabels = rawSceneObjects.map { EveSceneGeometry.normalizeLabel(it.label) }.toSet()
        val sceneObjects = rawSceneObjects.filterNot { candidate ->
            val suppress = EveVisionEvidencePolicy.shouldSuppressAnimalLabel(
                label = candidate.label,
                personFaceRecentlyDetected = isPersonFaceRecent(now),
                freshObjectLabels = rawObjectLabels
            )
            if (suppress) {
                Log.w(
                    "EVE_VISION_POLICY",
                    "SUPPRESSED_CONFLICTING_ANIMAL source=efficientdet_lite0 label=${candidate.label} " +
                        "faceAgeMs=${(now - lastFaceSeenAt).coerceAtLeast(0L)} boxes=$rawObjectLabels"
                )
            }
            suppress
        }
        // An empty current detector result must clear old animal labels so they cannot authorize
        // a contradictory whole-image guess on the next frame.
        latestObjectLabels = sceneObjects.map { EveSceneGeometry.normalizeLabel(it.label) }.toSet()
        if (sceneObjects.isNotEmpty()) {
            latestObjectSummary = "أجسام محددة: " + sceneObjects.joinToString("؛ ") {
                EveSceneGeometry.describe(
                    labelArabic = arabicVisualObjectName(it.label),
                    centerX = it.centerX,
                    centerY = it.centerY,
                    areaFraction = it.areaFraction,
                    confidence = it.confidence
                )
            }.take(260)
            lastObjectSceneAt = now
            refreshLatestSceneSummary(now)
            Log.i(
                "EVE_SCENE",
                "SCENE_SUMMARY objects=${sceneObjects.size} summary=${latestSceneSummary}"
            )
        } else {
            latestObjectSummary = ""
            lastObjectSceneAt = 0L
            refreshLatestSceneSummary(now)
        }
        val best = sceneObjects.maxByOrNull {
            it.confidence * (0.85f + 0.15f * it.areaFraction.coerceIn(0f, 1f))
        }
        if (best == null) {
            val previous = lastObjectDiagnosticAt.get()
            if (now - previous >= 6000L && lastObjectDiagnosticAt.compareAndSet(previous, now)) {
                Log.i("EVE_OBJECTS", "OBJECT_DETECTION_NO_CLEAR_OBJECT; no mood guess made")
            }
            return
        }

        val label = best.label.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        val previousLabel = lastObjectLabel
        val changed = previousLabel != label
        lastObjectLabel = label
        lastObjectLabelConfidence = best.confidence
        lastObjectLabelAt = now

        // Keep the latest real visual evidence for "what do you see?" answers. Do not store
        // camera pixels or identity; save only the category, score, and a count.
        val lastMemoryAt = objectLabelMemoryAt[label] ?: 0L
        val sightings = if (lastMemoryAt == 0L || now - lastMemoryAt >= objectLabelMemoryIntervalMs) {
            objectLabelMemoryAt[label] = now
            memory.remember(label)
        } else {
            memory.sightings(label)
        }
        lastMeaningfulLabel = label
        lastMeaningfulLabelAt = now
        lastMeaningfulConfidence = best.confidence
        getSharedPreferences("eve_relationship", MODE_PRIVATE).edit()
            .putString("lastObservationLabel", label)
            .putFloat("lastObservationConfidence", best.confidence)
            .putInt("lastObservationCount", sightings)
            .putLong("lastObservationAt", System.currentTimeMillis())
            .putString("lastObservationSource", "efficientdet_lite0")
            .apply()

        Log.i(
            "EVE_OBJECTS",
            "OBJECT_DETECTED label=" + label +
                " confidence=" + String.format(Locale.ROOT, "%.3f", best.confidence) +
                " area=" + String.format(Locale.ROOT, "%.3f", best.areaFraction) +
                " sightings=" + sightings + " changed=" + changed +
                " source=efficientdet_lite0 offline=true"
        )

        val lastReactionAt = objectLabelReactionAt[label] ?: 0L
        val shouldReact = changed || lastReactionAt == 0L ||
            now - lastReactionAt >= objectReactionCooldownMs
        if (!shouldReact) return
        objectLabelReactionAt[label] = now
        val decision = brain.observe(label, best.confidence, sightings, memory.learnedAffinity(label))
        react(decision, playSound = decision.playSound && isMeaningfulLabel(label))
        if (best.confidence >= 0.82f) {
            val askedOrQueued = maybeAskFromCuriosity(label, best.confidence, sightings)
            if (!askedOrQueued && changed) maybeReflectWithLocalLanguage(label, best.confidence, sightings)
        }
    }

    private val objectDetectionIntervalMs = 1400L
    private val imageClassificationIntervalMs = 4_500L
    private val imageClassificationMinConfidence = 0.10f
    private val objectObservationMinConfidence = 0.52f
    private val objectLabelMemoryIntervalMs = 9_000L
    private val objectReactionCooldownMs = 8_000L
    private data class MouthGeometry(
        val centerX: Float,
        val centerY: Float,
        val openingRatio: Float
    )

    /** Estimate average luminance by sparse pixel sampling; no photo is saved. */
    private fun estimateMeanLuminance(bitmap: Bitmap): Float {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return 0f
        val stepX = (bitmap.width / 32).coerceAtLeast(1)
        val stepY = (bitmap.height / 32).coerceAtLeast(1)
        var total = 0.0
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                total += 0.299 * android.graphics.Color.red(pixel) +
                    0.587 * android.graphics.Color.green(pixel) +
                    0.114 * android.graphics.Color.blue(pixel)
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 0f else (total / count).toFloat()
    }

    /** Lip contours are motion cues only; they cannot identify gum or prove intent. */
    private fun mouthGeometry(face: Face): MouthGeometry {
        val width = latestAnalysisWidth.toFloat().coerceAtLeast(1f)
        val height = latestAnalysisHeight.toFloat().coerceAtLeast(1f)
        val upper = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points.orEmpty()
        val lower = face.getContour(FaceContour.LOWER_LIP_TOP)?.points.orEmpty()
        if (upper.isNotEmpty() && lower.isNotEmpty()) {
            val upperY = upper.map { it.y }.average().toFloat()
            val lowerY = lower.map { it.y }.average().toFloat()
            val allX = (upper + lower).map { it.x }
            val centerX = allX.average().toFloat() / width
            val centerY = ((upperY + lowerY) * 0.5f) / height
            val ratio = kotlin.math.abs(lowerY - upperY) /
                face.boundingBox.height().coerceAtLeast(1)
            return MouthGeometry(
                centerX = centerX.coerceIn(0f, 1f),
                centerY = centerY.coerceIn(0f, 1f),
                openingRatio = ratio.coerceIn(0f, 1f)
            )
        }
        // Missing lip contours provide only a rough position, never mouth-opening evidence.
        val box = face.boundingBox
        return MouthGeometry(
            centerX = (box.centerX().toFloat() / width).coerceIn(0f, 1f),
            centerY = ((box.top + box.height() * 0.68f) / height).coerceIn(0f, 1f),
            openingRatio = 0f
        )
    }

    private fun handNearMouthConfidence(mouth: MouthGeometry, faceHeightPixels: Int, now: Long): Float {
        val observedAt = latestHandObservedAt
        if (observedAt == 0L || now - observedAt !in 0L..1_400L) return 0f
        val tips = latestHandFingerTips
        if (tips.isEmpty()) return 0f
        val frameHeight = latestAnalysisHeight.toFloat().coerceAtLeast(1f)
        val threshold = ((faceHeightPixels / frameHeight) * 0.34f).coerceIn(0.035f, 0.12f)
        val nearest = tips.minOfOrNull { (x, y) ->
            kotlin.math.hypot((x - mouth.centerX).toDouble(), (y - mouth.centerY).toDouble()).toFloat()
        } ?: return 0f
        return (1f - nearest / threshold).coerceIn(0f, 1f) * 0.95f
    }

    /** Connect face/mouth cues, tracked hands, and recent object detections. */
    private fun observeHumanActivityFromFace(face: Face, now: Long) {
        val mouth = mouthGeometry(face)
        val handConfidence = handNearMouthConfidence(mouth, face.boundingBox.height(), now)
        val handIsFresh = latestHandObservedAt > 0L && now - latestHandObservedAt in 0L..1_400L
        val labels = if (lastObjectSceneAt > 0L && now - lastObjectSceneAt in 0L..6_000L) {
            latestObjectLabels
        } else {
            emptySet()
        }
        val result = activityUnderstanding.observe(
            EveHumanFrameEvidence(
                timestampMs = now,
                // Detected face means a person is present; it does not identify their name.
                personConfidence = 0.88f,
                handToMouthConfidence = handConfidence,
                mouthOpeningRatio = mouth.openingRatio,
                jointMotionScore = if (handIsFresh) latestHandTrajectoryScore else 0f,
                handCenterX = latestHandCenterX.takeIf { handIsFresh && it.isFinite() },
                handCenterY = latestHandCenterY.takeIf { handIsFresh && it.isFinite() },
                visibleObjectLabels = labels
            )
        )
        applyHumanActivityInterpretation(result, now)
    }

    private fun applyHumanActivityInterpretation(result: EveActivityInterpretation, now: Long) {
        if (isFinishing || isDestroyed) return
        if (result.isActionable) {
            lastHumanActivityAt = now
            lastActivityObservationAt = now
            latestActivitySummary = result.explanationArabic +
                " (احتمال ${(result.confidence * 100f).toInt()}٪؛ وليس تأكيدًا)"
            if (result.activity != activeHumanActivity) {
                activeHumanActivity = result.activity
                val decision = brain.observeHumanActivity(result.activity, result.confidence)
                main.post {
                    if (!isFinishing && !isDestroyed) react(decision, playSound = false)
                }
            }
            Log.i(
                "EVE_ACTIVITY",
                "ACTIVITY_HYPOTHESIS activity=${result.activity} confidence=${result.confidence} " +
                    "frames=${result.evidenceCount} durationMs=${result.durationMs} offline=true"
            )
        } else if (lastHumanActivityAt == 0L || now - lastHumanActivityAt > activitySummaryTtlMs) {
            activeHumanActivity = EveHumanActivity.UNKNOWN
            latestActivitySummary = ""
            lastActivityObservationAt = 0L
        }
        refreshLatestSceneSummary(now)
    }

    private val sceneSummaryTtlMs = 15_000L
    private val activitySummaryTtlMs = 12_000L
    private val environmentSummaryTtlMs = 45_000L

    /** Combines spatial object boxes with broader ImageNet scene hypotheses, discarding stale evidence. */
    private fun refreshLatestSceneSummary(now: Long) {
        if (lastObjectSceneAt == 0L || now - lastObjectSceneAt > sceneSummaryTtlMs) {
            latestObjectSummary = ""
            latestObjectLabels = emptySet()
        }
        if (lastImageClassifierSceneAt == 0L || now - lastImageClassifierSceneAt > sceneSummaryTtlMs) {
            latestImageClassifierSummary = ""
        }
        if (lastActivityObservationAt == 0L || now - lastActivityObservationAt > activitySummaryTtlMs) {
            latestActivitySummary = ""
            activeHumanActivity = EveHumanActivity.UNKNOWN
        }
        if (lastEnvironmentObservationAt == 0L || now - lastEnvironmentObservationAt > environmentSummaryTtlMs) {
            latestEnvironmentSummary = ""
        }
        val parts = listOf(
            latestObjectSummary,
            latestImageClassifierSummary,
            latestActivitySummary,
            latestEnvironmentSummary
        ).filter { it.isNotBlank() }
        latestSceneSummary = parts.joinToString("؛ ").take(520)
        lastSceneObservationAt = maxOf(
            if (latestObjectSummary.isNotBlank()) lastObjectSceneAt else 0L,
            if (latestImageClassifierSummary.isNotBlank()) lastImageClassifierSceneAt else 0L,
            if (latestActivitySummary.isNotBlank()) lastActivityObservationAt else 0L,
            if (latestEnvironmentSummary.isNotBlank()) lastEnvironmentObservationAt else 0L
        )
    }

    /**
     * The classifier is a fallback/broad taxonomy source, not a replacement for object boxes.
     * Its scores are explicitly presented as hypotheses; they never claim identity or certainty.
     */
    private fun applyImageClassifierObservations(categories: List<EveImageClass>, meanLuminance: Float) {
        if (isFinishing || isDestroyed) return
        val now = SystemClock.elapsedRealtime()
        val localHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val environment = environmentUnderstanding.observe(
            timestampMs = now,
            candidates = categories,
            meanLuminance = meanLuminance,
            localHour = localHour
        )
        latestEnvironmentSummary = listOf(environment.summaryArabic, environment.clockPeriodArabic)
            .filter { it.isNotBlank() }
            .joinToString("؛ ")
        lastEnvironmentObservationAt = now
        refreshLatestSceneSummary(now)
        if (now - lastEnvironmentDiagnosticAt >= 10_000L) {
            lastEnvironmentDiagnosticAt = now
            Log.i(
                "EVE_ENV",
                "ENVIRONMENT weather=${environment.weather} weatherConfidence=${environment.weatherConfidence} " +
                    "light=${environment.visualLight} lightConfidence=${environment.lightConfidence} " +
                    "clock=${environment.clockPeriodArabic.ifBlank { "unknown" }} " +
                    "summary=${environment.summaryArabic.ifBlank { "no confident visual hypothesis" }} offline=true"
            )
        }
        val recentObjectLabels = if (
            lastObjectSceneAt > 0L && now - lastObjectSceneAt in 0L..2_500L
        ) latestObjectLabels else emptySet()
        val ranked = categories.asSequence()
            .filter { it.confidence >= imageClassificationMinConfidence }
            .filter { it.label.isNotBlank() && !isVisualNoiseLabel(it.label) }
            .filterNot { candidate ->
                val suppress = EveVisionEvidencePolicy.shouldSuppressAnimalLabel(
                    label = candidate.label,
                    personFaceRecentlyDetected = isPersonFaceRecent(now),
                    freshObjectLabels = recentObjectLabels
                )
                if (suppress) {
                    Log.w(
                        "EVE_VISION_POLICY",
                        "SUPPRESSED_CONFLICTING_ANIMAL source=imagenet label=${candidate.label} " +
                            "faceAgeMs=${(now - lastFaceSeenAt).coerceAtLeast(0L)} boxes=$recentObjectLabels"
                    )
                }
                suppress
            }
            .distinctBy { EveSceneGeometry.normalizeLabel(it.label) }
            .sortedByDescending { it.confidence }
            .take(5)
            .toList()
        if (ranked.isEmpty()) {
            // Do not leave an old, now-rejected animal guess in the live scene context.
            latestImageClassifierSummary = ""
            lastImageClassifierSceneAt = 0L
            refreshLatestSceneSummary(now)
            Log.i("EVE_IMAGENET", "IMAGE_CLASSIFICATION_NO_CONFIDENT_CATEGORY_AFTER_EVIDENCE_POLICY")
            return
        }

        val supplemental = ranked.filterNot { candidate ->
            val name = EveSceneGeometry.normalizeLabel(candidate.label)
            latestObjectLabels.any { objectName ->
                name == objectName || name.contains(objectName) || objectName.contains(name)
            }
        }.take(3)
        val sceneCandidates = (supplemental.ifEmpty { ranked.take(2) }).take(3)
        latestImageClassifierSummary = "تخمينات تصنيف عامة للصورة: " +
            sceneCandidates.joinToString("، ") {
                "${it.label} (ثقة ${(it.confidence * 100f).toInt()}٪)"
            }.take(180)
        lastImageClassifierSceneAt = now
        refreshLatestSceneSummary(now)

        val best = ranked.first()
        Log.i(
            "EVE_IMAGENET",
            "IMAGE_CLASSIFICATION_TOP labels=" +
                ranked.take(3).joinToString("|") {
                    "${it.label}:${String.format(Locale.ROOT, "%.3f", it.confidence)}"
                } +
                " spatialObjects=${latestObjectLabels.size} localOnly=true scene=${latestSceneSummary}"
        )

        // Only let the broad classifier initiate a behavioral reaction when there is no fresh
        // box-based observation and the class is useful to EVE's vocabulary. Otherwise it enriches
        // the scene context silently, avoiding a mood change for every ambiguous top-1 guess.
        val freshBoxEvidence = lastObjectSceneAt > 0L && now - lastObjectSceneAt in 0L..5_000L
        val label = EveSceneGeometry.normalizeLabel(best.label)
        if (!freshBoxEvidence && best.confidence >= 0.28f &&
            isCuriosityWorthyLabel(label) && label != lastMeaningfulLabel
        ) {
            val sightings = memory.remember(label)
            lastMeaningfulLabel = label
            lastMeaningfulLabelAt = now
            lastMeaningfulConfidence = best.confidence
            getSharedPreferences("eve_relationship", MODE_PRIVATE).edit()
                .putString("lastObservationLabel", label)
                .putFloat("lastObservationConfidence", best.confidence)
                .putInt("lastObservationCount", sightings)
                .putLong("lastObservationAt", System.currentTimeMillis())
                .putString("lastObservationSource", "efficientnet_lite0_imagenet")
                .apply()
            val decision = brain.observe(label, best.confidence, sightings, memory.learnedAffinity(label))
            react(decision, playSound = false)
            val askedOrQueued = maybeAskFromCuriosity(label, best.confidence, sightings)
            if (!askedOrQueued && best.confidence >= 0.45f) {
                maybeReflectWithLocalLanguage(label, best.confidence, sightings)
            }
        }
    }

    private fun arabicVisualObjectName(rawLabel: String): String {
        val label = rawLabel.trim().lowercase(Locale.ROOT).replace('_', ' ')
            .replace(Regex("\\s+"), " ")
        return when (label) {
            "person" -> "شخص"
            "bicycle" -> "دراجة"
            "car" -> "سيارة"
            "motorcycle" -> "دراجة نارية"
            "airplane" -> "طائرة"
            "bus" -> "حافلة"
            "train" -> "قطار"
            "truck" -> "شاحنة"
            "boat" -> "قارب"
            "bird" -> "طائر"
            "cat" -> "قطة"
            "dog" -> "كلب"
            "horse" -> "حصان"
            "sheep" -> "خروف"
            "cow" -> "بقرة"
            "elephant" -> "فيل"
            "bear" -> "دب"
            "zebra" -> "حمار وحشي"
            "giraffe" -> "زرافة"
            "backpack" -> "حقيبة ظهر"
            "umbrella" -> "مظلة"
            "handbag" -> "حقيبة يد"
            "tie" -> "ربطة عنق"
            "suitcase" -> "حقيبة سفر"
            "bottle" -> "زجاجة"
            "wine glass" -> "كأس"
            "cup" -> "كوب"
            "fork" -> "شوكة"
            "knife" -> "سكين"
            "spoon" -> "ملعقة"
            "bowl" -> "وعاء"
            "banana" -> "موزة"
            "apple" -> "تفاحة"
            "sandwich" -> "شطيرة"
            "orange" -> "برتقالة"
            "broccoli" -> "بروكلي"
            "carrot" -> "جزرة"
            "hot dog" -> "هوت دوغ"
            "pizza" -> "بيتزا"
            "donut" -> "دونات"
            "cake" -> "كعكة"
            "chair" -> "كرسي"
            "couch" -> "أريكة"
            "potted plant" -> "نبات في أصيص"
            "bed" -> "سرير"
            "dining table" -> "طاولة طعام"
            "toilet" -> "مرحاض"
            "tv" -> "تلفاز"
            "laptop" -> "حاسوب محمول"
            "mouse" -> "فأرة حاسوب"
            "remote" -> "جهاز تحكم"
            "keyboard" -> "لوحة مفاتيح"
            "cell phone" -> "هاتف"
            "microwave" -> "ميكروويف"
            "oven" -> "فرن"
            "toaster" -> "محمصة خبز"
            "sink" -> "حوض"
            "refrigerator" -> "ثلاجة"
            "book" -> "كتاب"
            "clock" -> "ساعة"
            "vase" -> "مزهرية"
            "scissors" -> "مقص"
            "teddy bear" -> "دب محشو"
            "hair drier" -> "مجفف شعر"
            "toothbrush" -> "فرشاة أسنان"
            else -> rawLabel.trim().take(32)
        }
    }

    private fun isPersonFaceRecent(now: Long): Boolean =
        lastFaceSeenAt > 0L && now - lastFaceSeenAt in 0L..3_000L

    /** Never promote a scene-level animal guess over a recently confirmed face without box evidence. */
    private fun shouldSuppressConflictingAnimalLabel(label: String, now: Long, source: String): Boolean {
        val recentObjectLabels = if (
            lastObjectSceneAt > 0L && now - lastObjectSceneAt in 0L..2_500L
        ) latestObjectLabels else emptySet()
        val suppress = EveVisionEvidencePolicy.shouldSuppressAnimalLabel(
            label = label,
            personFaceRecentlyDetected = isPersonFaceRecent(now),
            freshObjectLabels = recentObjectLabels
        )
        if (suppress) {
            Log.w(
                "EVE_VISION_POLICY",
                "SUPPRESSED_CONFLICTING_ANIMAL source=$source label=$label " +
                    "faceAgeMs=${(now - lastFaceSeenAt).coerceAtLeast(0L)} boxes=$recentObjectLabels"
            )
        }
        return suppress
    }

    private fun isVisualNoiseLabel(label: String): Boolean =
        label.trim().lowercase(Locale.ROOT) in setOf(
            "pattern", "texture", "background", "wall", "floor", "ceiling",
            "line", "geometry", "grid", "tile", "color", "colour", "design",
            "lighting", "shadow", "unknown", "???"
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
            "computer", "keyboard", "mouse", "monitor", "tv", "tv monitor", "book", "cup",
            "bottle", "car", "bicycle", "motorcycle", "bus", "truck", "train", "airplane", "boat",
            "chair", "couch", "dining table", "table", "bed", "toilet", "potted plant",
            "toy", "clock", "camera", "headphones", "remote", "remote control", "microwave",
            "oven", "toaster", "sink", "refrigerator", "vase", "scissors", "teddy bear",
            "hair drier", "toothbrush", "shoe", "umbrella", "bag", "backpack", "handbag",
            "suitcase", "glasses", "watch", "sports ball", "skateboard", "tennis racket",
            "baseball bat", "baseball glove", "traffic light", "stop sign", "parking meter",
            "fire hydrant", "bench", "spoon", "fork", "knife", "bowl", "broccoli", "carrot",
            "hot dog", "donut", "pizza", "apple", "banana", "orange", "cake", "cow", "sheep",
            "elephant", "bear", "zebra", "giraffe"
        ).any(value::contains) && !isVisualNoiseLabel(value)
    }

    private fun maybeAskFromCuriosity(label: String, confidence: Float, sightings: Int): Boolean {
        if (isFinishing || isDestroyed || !::dialogueBubble.isInitialized) return false
        val preferences = getSharedPreferences("eve_curiosity", MODE_PRIVATE)
        val safeKey = label.trim().lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(48)
        if (safeKey.isBlank()) return false
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
        ) ?: return false

        val fallback = thought.text
        val baselineDecision = brain.selfGeneratedCuriosity(label, confidence, sightings)

        fun publishQuestion(questionRaw: String, llmResult: LocalLlmResult?) {
            if (isFinishing || isDestroyed) return
            val question = questionRaw.trim().take(84).let {
                if (it.endsWith("؟") || it.endsWith("?")) it else "$it؟"
            }
            val publishedAt = System.currentTimeMillis()
            preferences.edit()
                .putLong("last_question_at", publishedAt)
                .putLong("label_question_at:" + safeKey, publishedAt)
                .putInt("label_question_count:" + safeKey, countForLabel + 1)
                .putString("last_question_label", label)
                .putString("last_question_text", question)
                .apply()

            llmResult?.reflectionArabic?.takeIf { it.isNotBlank() }?.let(memory::rememberReflection)
            val decision = if (llmResult != null) {
                brain.integrateLanguageInsight(
                    moodHint = llmResult.moodHint,
                    intentHint = llmResult.intentHint,
                    confidence = llmResult.confidence,
                    note = llmResult.reflectionArabic,
                    source = "visual_curiosity"
                )
            } else {
                baselineDecision
            }
            // Preserve that EVE herself asked this question so the next LLM turn can follow it.
            memory.rememberDialogue("ملاحظة بصرية حديثة: $label", question)
            react(decision, playSound = false)
            showDialogueText(question, durationMs = 7600L)
            val questionSource = if (llmResult != null) "local_llm" else "grounded_fallback"
            Log.i(
                "EVE_CURIOSITY",
                "SELF_QUESTION label=" + label + " confidence=" + confidence + " sightings=" + sightings +
                    " interest=" + thought.interest + " source=" + questionSource +
                    " reason=" + thought.reason + " trait=" + learned.curiosityBias
            )
        }

        if (::localLanguageBrain.isInitialized && localLanguageBrain.isReady) {
            localLanguageBrain.askFromCuriosity(
                context = localLanguageContext(),
                label = label,
                confidence = confidence,
                sightings = sightings,
                fallbackQuestion = fallback
            ) { outcome ->
                if (isFinishing || isDestroyed) return@askFromCuriosity
                val candidate = outcome.getOrNull()
                val generated = candidate?.replyArabic?.trim()?.takeIf(::isUsableCuriosityQuestion)
                if (generated != null) {
                    publishQuestion(generated, candidate)
                } else {
                    Log.w(
                        "EVE_CURIOSITY",
                        "LLM_QUESTION_FALLBACK label=$label reason=" +
                            (outcome.exceptionOrNull()?.message ?: "empty or malformed question")
                    )
                    publishQuestion(fallback, null)
                }
            }
        } else {
            publishQuestion(fallback, null)
        }
        return true
    }

    private fun isUsableCuriosityQuestion(value: String): Boolean {
        val text = value.trim().take(84)
        if (text.length !in 12..84) return false
        if (text.any { it == '{' || it == '}' || it == '\n' }) return false
        if (text.none { it in '\u0600'..'\u06FF' }) return false
        val questionSignals = listOf("؟", "?", "هل", "ما ", "ماذا", "كيف", "لماذا", "أتساءل", "اتساءل", "يمكننا", "نختبر")
        return questionSignals.any(text::contains)
    }

    /**
     * Shows brief setup/inference status in the existing top chip; it does not add an app button.
     * During model download the chip remains visible with progress and the camera stays usable.
     */
    private fun handleLocalLanguageStatus(state: LocalLlmState, percent: Int, detail: String) {
        currentLocalLlmState = state
        currentLocalLlmPercent = percent.coerceIn(0, 100)
        currentLocalLlmDetail = detail
        if (isFinishing || isDestroyed || !::cameraStatus.isInitialized) return
        when (state) {
            LocalLlmState.READY -> {
                Log.i("EVE_LLM", "STATUS ready=true onDevice=true")
                showCameraStatus(
                    if (cameraStarted && activeCameraLens == CameraLens.FRONT) {
                        "الكاميرا الأمامية تعمل"
                    } else if (cameraStarted) {
                        "الكاميرا الخلفية تعمل"
                    } else {
                        "الدماغ المحلي جاهز"
                    },
                    active = cameraStarted
                )
                cameraStatus.contentDescription =
                    "الدماغ اللغوي المحلي جاهز ويعمل دون إنترنت. حالة الميكروفون: $offlineSpeechStatus"
                drainPendingLocalQuestionIfReady()
            }
            LocalLlmState.WAITING_FOR_WIFI -> {
                if (detail == "لم تكتمل تهيئة الدماغ اللغوي") {
                    showLocalBrainChip("جارٍ بدء الدماغ المحلي…", "تهيئة أولية", 0xFFB3E5FC.toInt())
                } else {
                    val pending = pendingLocalQuestion
                    if (pending != null) {
                        pendingLocalQuestion = null
                        showDialogueText("لا أستطيع معالجة السؤال دون تجهيز النموذج المحلي. تحقق من حالة الدماغ أعلى الشاشة.", durationMs = 7000L)
                    }
                    showLocalBrainChip("الدماغ المحلي ينتظر Wi‑Fi", detail, 0xFFFFD180.toInt())
                }
            }
            LocalLlmState.DOWNLOADING -> {
                showLocalBrainChip("تنزيل الدماغ المحلي $percent%", detail, 0xFFFFD180.toInt())
            }
            LocalLlmState.LOADING -> {
                showLocalBrainChip("تشغيل الدماغ المحلي…", detail, 0xFFB3E5FC.toInt())
            }
            LocalLlmState.ERROR -> {
                if (pendingLocalQuestion != null) {
                    pendingLocalQuestion = null
                    showDialogueText("تعذر تشغيل الدماغ المحلي. لم أتمكن من الإجابة عن السؤال؛ انقر على حالة التشغيل للتفاصيل.", durationMs = 7500L)
                }
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
        val observationAge = if (lastMeaningfulLabelAt > 0L) {
            (SystemClock.elapsedRealtime() - lastMeaningfulLabelAt).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }
        return LocalLlmContext(
            latestVisionLabel = label,
            visionConfidence = lastMeaningfulConfidence,
            visionObservationAgeMs = observationAge,
            sightingCount = label?.let(memory::sightings) ?: 0,
            learnedAffinity = label?.let(memory::learnedAffinity) ?: 0f,
            currentMood = relationship.getString("lastMood", EveMood.NEUTRAL.name) ?: EveMood.NEUTRAL.name,
            moodReason = relationship.getString("lastMoodReason", "").orEmpty(),
            personality = memory.learnedPersonality(),
            recentObservations = memory.recentVisualMemories(5),
            recentDialogues = memory.recentDialogues(3),
            recentReflections = memory.recentReflections(3),
            knownPeople = peopleMemory.summary(),
            latestSceneSummary = latestSceneSummary,
            sceneObservationAgeMs = if (lastSceneObservationAt > 0L) {
                (SystemClock.elapsedRealtime() - lastSceneObservationAt).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }
        )
    }

    private fun isLocalLanguageBrainStarting(): Boolean =
        currentLocalLlmState == LocalLlmState.LOADING ||
            currentLocalLlmState == LocalLlmState.DOWNLOADING ||
            (currentLocalLlmState == LocalLlmState.WAITING_FOR_WIFI &&
                currentLocalLlmDetail == "لم تكتمل تهيئة الدماغ اللغوي")

    private fun queueLocalQuestion(text: String, addressedToEve: Boolean, reason: String) {
        pendingLocalQuestion = PendingLocalQuestion(
            text = text,
            addressedToEve = addressedToEve,
            queuedAtElapsed = SystemClock.elapsedRealtime()
        )
        showDialogueText("سمعتك. أجهّز الدماغ المحلي وسأجيب عندما يكتمل تشغيله…", durationMs = 8500L)
        Log.i("EVE_LLM", "USER_REPLY_QUEUED reason=$reason chars=0")
    }

    private fun drainPendingLocalQuestionIfReady() {
        if (isFinishing || isDestroyed || !::localLanguageBrain.isInitialized || !localLanguageBrain.isReady) return
        val pending = pendingLocalQuestion ?: return
        pendingLocalQuestion = null
        val age = SystemClock.elapsedRealtime() - pending.queuedAtElapsed
        if (age !in 0L..120_000L) {
            Log.i("EVE_LLM", "USER_REPLY_QUEUE_EXPIRED ageMs=$age")
            showDialogueText("اكتمل تشغيل الدماغ، لكن السؤال السابق انتهت صلاحيته. اسألني مرة أخرى.", durationMs = 6500L)
            return
        }
        Log.i("EVE_LLM", "USER_REPLY_QUEUE_DRAIN ageMs=$age")
        main.post {
            if (!isFinishing && !isDestroyed) requestLocalLanguageReply(pending.text, pending.addressedToEve)
        }
    }

    /** Handles open-ended speech with a real on-device LLM after deterministic commands are checked. */
    private fun requestLocalLanguageReply(text: String, addressedToEve: Boolean) {
        lastConversationalExchangeAt = SystemClock.elapsedRealtime()
        val baseline = brain.respondToSpeech(text, addressedToEve)
        react(baseline, playSound = false)
        if (!::localLanguageBrain.isInitialized || !localLanguageBrain.isReady) {
            if (::localLanguageBrain.isInitialized && isLocalLanguageBrainStarting()) {
                queueLocalQuestion(text, addressedToEve, "reply requested before model ready")
            } else {
                showDialogueText(
                    "الدماغ اللغوي المحلي غير جاهز الآن. انقر على حالة التشغيل أعلى الشاشة لمعرفة السبب.",
                    durationMs = 6500L
                )
                Log.i("EVE_LLM", "USER_REPLY_DEFERRED reason=model_not_ready state=$currentLocalLlmState detail=$currentLocalLlmDetail")
            }
            voice.respondToSpeech(baseline.mood, text)
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
        try {
            faceIdentityExecutor.execute {
                try { faceIdentityMatcher?.close() } catch (_: Exception) { }
                faceIdentityMatcher = null
            }
        } catch (_: Exception) { }
        faceIdentityExecutor.shutdown()
        try {
            objectExecutor.execute {
                try { localObjectDetector?.close() } catch (_: Exception) { }
                try { localImageClassifier?.close() } catch (_: Exception) { }
                localObjectDetector = null
                localImageClassifier = null
                objectDetectorReady = false
                imageClassifierReady = false
            }
        } catch (_: Exception) { }
        objectExecutor.shutdown()
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
