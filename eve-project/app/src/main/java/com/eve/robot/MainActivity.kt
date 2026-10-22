package com.eve.robot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import kotlin.math.sqrt

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
    private var motionSensorManager: SensorManager? = null
    private var motionAccelerometer: Sensor? = null
    private var shakeSensorRegistered = false
    private var shakeGravityReady = false
    private var shakeGravityX = 0f
    private var shakeGravityY = 0f
    private var shakeGravityZ = 0f
    private var lastStrongShakeAtMs = 0L
    private val strongShakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || event.values.size < 3) return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            if (!shakeGravityReady) {
                shakeGravityX = x
                shakeGravityY = y
                shakeGravityZ = z
                shakeGravityReady = true
                return
            }
            // Low-pass gravity estimate; subtract it so tilting the phone is not itself a shake.
            val gravityRetention = 0.82f
            shakeGravityX = gravityRetention * shakeGravityX + (1f - gravityRetention) * x
            shakeGravityY = gravityRetention * shakeGravityY + (1f - gravityRetention) * y
            shakeGravityZ = gravityRetention * shakeGravityZ + (1f - gravityRetention) * z
            val ax = x - shakeGravityX
            val ay = y - shakeGravityY
            val az = z - shakeGravityZ
            val strength = sqrt(ax * ax + ay * ay + az * az)
            val now = SystemClock.elapsedRealtime()
            // Require a forceful shake and enforce a cooldown to avoid repeating the pose every frame.
            if (strength >= 16.5f && now - lastStrongShakeAtMs >= 2600L) {
                lastStrongShakeAtMs = now
                faceOrNull()?.showDizzyReaction(2400L)
                Log.i("EVE", "PHYSICAL_SHAKE dizzy expression strength=${"%.1f".format(Locale.ROOT, strength)}")
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
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
    @Volatile private var latestVisualObjects: List<EveVisualObject> = emptyList()
    @Volatile private var latestPersonContextSummary = ""
    @Volatile private var lastPersonContextSummaryAt = 0L
    @Volatile private var latestPersonContextSnapshot: EvePersonContextSnapshot? = null
    @Volatile private var lastPersonContextCaptureAt = 0L
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
    private val lastPoseDiagnosticAt = AtomicLong(0L)
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
    private lateinit var activityLearningBridge: EveActivityLearningBridge
    @Volatile private var latestRawActivityInterpretation: EveActivityInterpretation? = null
    private val personContextUnderstanding = EvePersonContextUnderstanding()
    private val personalTravelMemory by lazy {
        EveTravelMemory(getSharedPreferences("eve_personal_travel_memory", MODE_PRIVATE).getString("records", "").orEmpty())
    }
    private val wellbeingUnderstanding = EveHumanWellbeingUnderstanding()
    private val environmentUnderstanding = EveEnvironmentUnderstanding()
    private val roomUnderstanding = EveRoomUnderstanding()
    private val autonomousMind = EveAutonomousMind()
    private val robotBaseNavigator = EveBaseNavigator()
    private val homeMapMemory = EveHomeMapMemory()
    private lateinit var homeMapStore: EveHomeMapStore
    @Volatile private var savedHomeMapState: EveHomeMapPersistedState? = null
    @Volatile private var homeMapFrameDecisionMade = false
    @Volatile private var homeMapPersistenceEnabled = false
    @Volatile private var lastHomeMapPersistenceSaveAt = 0L
    @Volatile private var warnedAboutNonPersistentHomeFrame = false
    private val HOME_MAP_SAVE_INTERVAL_MS = 5_000L
    private lateinit var robotBaseLink: EveBleRobotBase
    @Volatile private var baseAutonomyRequested = false
    @Volatile private var pendingBaseAutonomyAfterPermission = false
    @Volatile private var lastBaseDecisionReason = ""
    @Volatile private var lastBaseSafetyNoticeAt = 0L
    @Volatile private var lastHomeMapDiagnosticSignature = ""
    @Volatile private var lastBaseAnnouncedRoom = EveRoomType.UNKNOWN
    @Volatile private var autonomousExplorationEnabled = false
    @Volatile private var lastDirectUserActionAt = SystemClock.elapsedRealtime()
    @Volatile private var lastAutonomyPlanSignature = ""
    @Volatile private var lastAutonomyReflectionAt = 0L
    private val autonomyPlanningRunning = AtomicBoolean(false)
    @Volatile private var lastAutonomyDecisionAt = 0L
    @Volatile private var lastAutonomyGoal = ""
    @Volatile private var lastAutonomyAction = ""
    @Volatile private var lastAutonomyOutcome = "لم تُنفّذ دورة قرار بعد"
    @Volatile private var lastAutonomyRationale = ""
    private val autonomyDecisionCooldownMs = 20_000L
    private val lastAutonomyCameraSwitchAt = AtomicLong(0L)
    private val autonomousCameraSwitchCooldownMs = 60_000L
    @Volatile private var activeHumanActivity = EveHumanActivity.UNKNOWN
    @Volatile private var activeHumanActivityConfidence = 0f
    @Volatile private var lastHumanActivityAt = 0L
    @Volatile private var latestActivitySummary = ""
    @Volatile private var latestWellbeingSummary = ""
    @Volatile private var lastWellbeingObservationAt = 0L
    @Volatile private var latestStableFaceEmotionLabel = ""
    @Volatile private var latestStableFaceEmotionConfidence = 0f
    @Volatile private var latestStableFaceEmotionAt = 0L
    @Volatile private var latestEnvironmentSummary = ""
    private var pendingCalendarQuestion = ""
    @Volatile private var latestRoomSummary = ""
    @Volatile private var latestRoomType = EveRoomType.UNKNOWN
    @Volatile private var lastRoomObservationAt = 0L
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
    @Volatile private var latestImageClassifierLabels: Set<String> = emptySet()
    @Volatile private var lastSceneObservationAt = 0L
    // Visible, low-frequency feedback closes the gap between internal perception and what the
    // person can actually observe. It is event-driven, not a repeating narration loop.
    @Volatile private var lastVisibleCueKey = ""
    @Volatile private var lastVisibleCueAt = 0L
    @Volatile private var lastPresenceCueAt = 0L
    private val observationResponseRunning = AtomicBoolean(false)
    private val visibleCueGlobalCooldownMs = 12_000L
    private val visibleCueSameLabelCooldownMs = 75_000L
    private val presenceCueCooldownMs = 45_000L

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

    private val calendarPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val question = pendingCalendarQuestion
        pendingCalendarQuestion = ""
        if (question.isNotBlank()) answerLocalBirthdayQuestion(question)
    }

    private val baseBluetoothPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allowed = grants.isNotEmpty() && grants.values.all { it }
        val shouldStartAutonomy = pendingBaseAutonomyAfterPermission
        pendingBaseAutonomyAfterPermission = false
        if (allowed && ::robotBaseLink.isInitialized) {
            if (shouldStartAutonomy) baseAutonomyRequested = true
            robotBaseLink.startScan()
        } else {
            baseAutonomyRequested = false
            showDialogueText(
                "لم أمنح أذونات Bluetooth؛ بقيت القاعدة متوقفة ولم أرسل أوامر حركة.",
                durationMs = 6500L
            )
            Log.i("EVE_BASE", "BASE_PERMISSION_DENIED")
        }
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

    private val baseControlLoop = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || !baseAutonomyRequested) return
            runBaseControlTick()
            if (baseAutonomyRequested) main.postDelayed(this, baseControlTickMs)
        }
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
            val directIdle = (SystemClock.elapsedRealtime() - lastDirectUserActionAt).coerceAtLeast(0L)
            if (directIdle >= 8_000L) runAutonomousCognition(directIdle)
            main.postDelayed(this, 6000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityLearningBridge = EveActivityLearningBridge(java.io.File(filesDir, "eve_activity_learning.properties"))
        homeMapStore = EveHomeMapStore(applicationContext)
        savedHomeMapState = homeMapStore.load()
        savedHomeMapState?.let {
            Log.i("EVE_MAP", "PERSISTED_HOME_MAP loaded cells=${it.cells.size} routePoints=${it.routePoints.size} savedAt=${it.savedAtEpochMs}")
        }
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
        val relationshipPreferences = getSharedPreferences("eve_relationship", MODE_PRIVATE)
        autonomousExplorationEnabled = relationshipPreferences.getBoolean("autonomousExplorationEnabled", true)
        val priorReflectionKey = relationshipPreferences.getString("lastAutonomyReflectionKey", "").orEmpty()
        val mindMemoryRestored = autonomousMind.restoreMemory(
            relationshipPreferences.getString("autonomousMindMemory", "").orEmpty()
        )
        if (!mindMemoryRestored && priorReflectionKey.isNotBlank()) {
            // Backward-compatible restore for installs created before versioned mind memory existed.
            recordMindFeedback(
                EveMindFeedback(
                    EveMindGoal.REFLECT_ON_NEW_EVIDENCE,
                    EveMindAction.REQUEST_LOCAL_REFLECTION,
                    succeeded = true,
                    note = priorReflectionKey,
                    atMs = SystemClock.elapsedRealtime()
                )
            )
        }
        val priorReflectionWallAt = relationshipPreferences.getLong("lastAutonomyReflectionWallAt", 0L)
        val reflectionAge = if (priorReflectionWallAt > 0L) {
            (System.currentTimeMillis() - priorReflectionWallAt).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }
        lastAutonomyReflectionAt = if (reflectionAge <= EveAutonomousMind.REFLECTION_COOLDOWN_MS) {
            (SystemClock.elapsedRealtime() - reflectionAge).coerceAtLeast(0L)
        } else {
            0L
        }
        brain = RobotBrain(memory.learnedPersonality())
        voice = RobotVoice()
        localLanguageBrain = LocalLanguageBrain(applicationContext) { state, percent, message ->
            handleLocalLanguageStatus(state, percent, message)
        }
        localLanguageBrain.start()
        initializeHandGestureRecognizer()
        initializeOfflineObjectDetector()
        initializeRobotBaseLink()

        face.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownAt = SystemClock.uptimeMillis()
                    lastDirectUserActionAt = SystemClock.elapsedRealtime()
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

    private fun currentCalendarHemisphere(): EveHemisphere =
        environmentUnderstanding.inferHemisphere(
            Locale.getDefault().country,
            java.util.TimeZone.getDefault().id
        )

    private fun handleCalendarQuestion(text: String): Boolean {
        val now = System.currentTimeMillis()
        val eidAnswer = EveCalendarKnowledge.holidayAnswer(text, now)
        if (eidAnswer != null) {
            showDialogueText(eidAnswer, durationMs = 8500L)
            return true
        }

        if (listOf(
                "فصول السنة", "الفصل الحالي", "الفصل الآن", "اي فصل", "أي فصل",
                "ما هو الفصل", "ما الفصل", "أي فصل نحن", "what season", "current season"
            ).any(text::contains)
        ) {
            showDialogueText(EveCalendarKnowledge.seasonAnswer(now, currentCalendarHemisphere()), durationMs = 8500L)
            return true
        }

        if (listOf(
                "عيد ميلاد", "أعياد الميلاد", "اعياد الميلاد", "تاريخ ميلاد", "متى ميلاد",
                "عيد ميلادي", "birthday", "birthdays"
            ).any(text::contains)
        ) {
            val permissionsNeeded = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded += Manifest.permission.READ_CONTACTS
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded += Manifest.permission.READ_CALENDAR
            }
            if (permissionsNeeded.isNotEmpty()) {
                pendingCalendarQuestion = text
                showDialogueText(
                    "لأبحث عن أعياد الميلاد المحفوظة، سأطلب إذن قراءة جهات الاتصال والتقويم على هذا الهاتف فقط. لن أرسل بيانات المناسبات إلى الإنترنت.",
                    durationMs = 8500L
                )
                calendarPermissionRequest.launch(permissionsNeeded.toTypedArray())
            } else {
                answerLocalBirthdayQuestion(text)
            }
            return true
        }

        if (listOf(
                "تاريخ اليوم", "ما تاريخ اليوم", "كم التاريخ", "ما اليوم", "التاريخ الهجري",
                "كم رمضان", "ما التاريخ الهجري", "today's date", "what date is it"
            ).any(text::contains)
        ) {
            showDialogueText(
                EveCalendarKnowledge.todaySummary(now, currentCalendarHemisphere()),
                durationMs = 8500L
            )
            return true
        }
        return false
    }


    private fun handleTravelMemorySpeech(text: String, addressedToEve: Boolean, activeConversation: Boolean, nowElapsed: Long): Boolean {
        val q = normalizeArabicSpeech(text)
        val clear = listOf("امسحي ذاكره السفر","احذفي ذاكره السفر","امسحي معلومات السفر","احذفي معلومات السفر","انسي ذاكره السفر","انسي معلومات السفر").any(q::contains)
        if (clear && (addressedToEve || activeConversation)) {
            personalTravelMemory.clear()
            getSharedPreferences("eve_personal_travel_memory", MODE_PRIVATE).edit().putString("records", personalTravelMemory.serialize()).apply()
            val reply = "حذفت ذاكرة السفر المحلية من هذا الهاتف."
            showDialogueText(reply, durationMs = 6_000L); memory.rememberDialogue(text, reply); lastConversationalExchangeAt = nowElapsed
            return true
        }
        val answer = personalTravelMemory.answerQuestion(q)
        if (answer != null) {
            showDialogueText(answer, durationMs = 8_000L); memory.rememberDialogue(text, answer); lastConversationalExchangeAt = nowElapsed
            return true
        }
        if (!addressedToEve && !activeConversation) return false
        val fact = personalTravelMemory.recordStatement(text, System.currentTimeMillis()) ?: return false
        getSharedPreferences("eve_personal_travel_memory", MODE_PRIVATE).edit().putString("records", personalTravelMemory.serialize()).apply()
        val reply = when (fact.type) {
            EveTravelFactType.TRAVEL_HISTORY -> "حفظت على هذا الهاتف أنك ذكرت: «" + fact.statement + "». هذا تذكّر لما قلته، وليس تحققًا مستقلًا من السفر."
            EveTravelFactType.TRAVEL_INTENTION -> "تذكرت محليًا أنك عبّرت عن رغبتك أو موقفك من السفر: «" + fact.statement + "». يمكنك أن تطلب مني حذف ذاكرة السفر في أي وقت."
        }
        showDialogueText(reply, durationMs = 8_000L); memory.rememberDialogue(text, reply); lastConversationalExchangeAt = nowElapsed
        Log.i("EVE_TRAVEL", "TRAVEL_MEMORY_SAVED type=" + fact.type + " localOnly=true statementChars=" + fact.statement.length)
        return true
    }

    private fun answerPersonContextQuestion(text: String, addressedToEve: Boolean, activeConversation: Boolean): Boolean {
        if (!OfflineEveResponder.isQuestion(text) && !addressedToEve && !activeConversation) return false
        val q = normalizeArabicSpeech(text)
        val kind = when {
            listOf("هل الشخص جالس","هل هو جالس","هل هي جالسه","هل يجلس","هل تجلس","قاعد","جالس","جالسه","sitting").any(q::contains) -> EvePersonContextActivity.SITTING_POSSIBLE
            listOf("مكالمة","يتصل عبر الهاتف","يتحدث عبر الهاتف","يكلم احد عبر الهاتف","يجري اتصال","phone call","calling").any(q::contains) -> EvePersonContextActivity.PHONE_CALL_POSSIBLE
            listOf("يتصفح الهاتف","يتصفح جواله","يتصفح هاتفه","يستخدم الهاتف","يستخدم جواله","يمسك الهاتف","browsing phone","using the phone").any(q::contains) -> EvePersonContextActivity.PHONE_BROWSING_POSSIBLE
            listOf("يتشاجر","شجار","يتمازح","يمزحون","يتمازحون","يتضاربون","يتعاركون","مزاح","يتشاجرون","fighting","joking","roughhousing").any(q::contains) -> EvePersonContextActivity.INTERACTION_UNCLEAR
            else -> null
        } ?: return false
        val now = SystemClock.elapsedRealtime()
        val snap = latestPersonContextSnapshot
        val fresh = snap != null && now - lastPersonContextCaptureAt in 0L..3_500L
        val confidence = if (fresh) snap!!.confidenceFor(kind) else 0f
        val reply = if (confidence >= 0.55f) when (kind) {
            EvePersonContextActivity.SITTING_POSSIBLE -> "من وضعية الجسم عبر إطارات حديثة، يبدو أن الشخص قد يكون جالسًا أو في وضعية قرفصاء (تقدير بصري فقط)."
            EvePersonContextActivity.PHONE_CALL_POSSIBLE -> "رصدت هاتفًا قرب جانب الرأس في أكثر من لقطة؛ قد يكون الشخص في مكالمة، لكن الكاميرا لا تثبت أن هناك اتصالًا فعليًا."
            EvePersonContextActivity.PHONE_BROWSING_POSSIBLE -> "رصدت هاتفًا أمام الشخص مع حركة يد قريبة منه؛ قد يكون يستخدمه أو يتصفحه، ولا أستطيع قراءة محتوى الشاشة."
            EvePersonContextActivity.INTERACTION_UNCLEAR -> "توجد حركة متكررة بين شخصين قريبين، لكن لا أستطيع التمييز بثقة بين المزاح والشجار من الرؤية الحالية. إذا بدا أن أحدًا يتأذى فتعامل مع الأمر بجدية واطلب المساعدة."
        } else "لا تتوفر لدي الآن أدلة بصرية متكررة وكافية لأحدد ذلك. ظهور الهاتف أو انحناء الركبة في لقطة واحدة لا يكفي، وعدم توفر دليل لا يعني أن النشاط غير حاصل."
        showDialogueText(reply, durationMs = 7_500L); memory.rememberDialogue(text, reply); lastConversationalExchangeAt = now
        return true
    }

    private fun answerLocalBirthdayQuestion(question: String) {
        showDialogueText(EveLocalCalendarEvents.answerBirthdayQuestion(this, question), durationMs = 8500L)
    }

    private fun handleRecognizedSpeech(rawText: String, source: String) {
        val text = normalizeArabicSpeech(rawText)
        if (text.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        if (text == lastSpeechText && now - lastSpeechAt < 2800L) return
        lastSpeechText = text
        lastSpeechAt = now
        lastDirectUserActionAt = now
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

        if (handleActivityLearningSpeech(text)) return

        if (handleCalendarQuestion(text)) return

        // EVE's drive base is her embodied locomotion layer, not a separate assistant.
        // Keep connection explicit and never move just because a body link becomes available.
        if (listOf(
                "استكشفي المنزل بالقاعدة", "استكشاف المنزل بالقاعدة", "استكشفي بالقاعدة",
                "استكشفي المنزل بجسدك", "استكشفي بجسدك", "تحركي بجسدك", "شغلي حركة جسدك",
                "ابدئي حركة القاعدة", "تحركي بالقاعدة", "ابدئي استكشاف المنزل"
            ).any(text::contains)
        ) {
            requestRobotBaseConnection(enableAutonomy = true)
            return
        }
        if (listOf(
                "اتصلي بالقاعدة", "اتصل بالقاعدة", "ابحثي عن القاعدة", "ابحث عن القاعدة",
                "اتصلي بجسدك", "اتصل بجسدك", "اتصلي بوحدة الحركة", "وصلي جسدك",
                "اتصال بالقاعدة", "اتصال القاعدة", "اربط القاعدة", "bluetooth base"
            ).any(text::contains)
        ) {
            requestRobotBaseConnection(enableAutonomy = false)
            return
        }
        if (listOf(
                "اوقفي القاعدة", "اوقف القاعدة", "ايقاف القاعدة", "توقفي عن الحركة",
                "اوقفي حركة القاعدة", "اوقفي استكشاف المنزل", "stop robot base"
            ).any(text::contains)
        ) {
            stopRobotBaseAutonomy("أوقفت القاعدة. ستبقى المحركات متوقفة.")
            return
        }
        if (listOf("افصلي القاعدة", "قطع اتصال القاعدة", "افصل القاعدة").any(text::contains)) {
            stopRobotBaseAutonomy("أوقفت الحركة.")
            if (::robotBaseLink.isInitialized) robotBaseLink.disconnect()
            showDialogueText("فصلت جزء الحركة من جسدي. ستبقى المحركات متوقفة حتى أعيد الاتصال وأفحص الحساسات.", durationMs = 6500L)
            return
        }
        if (listOf("استعادة الحركة", "اعيدي ضبط امان القاعدة", "اعيدي ضبط امان الروبوت").any(text::contains)) {
            val nowForReset = SystemClock.elapsedRealtime()
            val safe = ::robotBaseLink.isInitialized &&
                robotBaseLink.state == EveBaseLinkState.READY &&
                robotBaseNavigator.clearSafetyLatch(robotBaseLink.latestTelemetry, nowForReset)
            if (safe) {
                baseAutonomyRequested = true
                showDialogueText("قراءات الحساسات حديثة وآمنة؛ سأستأنف الحركة ببطء.", durationMs = 6000L)
                startBaseControlLoop()
            } else {
                showDialogueText("لم أستأنف الحركة: ما زالت بعض قراءات الحافة أو العوائق غير آمنة أو قديمة.", durationMs = 7000L)
            }
            return
        }
        if (listOf("حالة القاعدة", "هل القاعدة متصلة", "حالة البلوتوث", "حالة جسدك", "هل جسدك متصل").any(text::contains)) {
            val status = if (::robotBaseLink.isInitialized) robotBaseLink.lastDetail else "طبقة القاعدة لم تجهز بعد."
            val movement = if (baseAutonomyRequested) "الاستكشاف مطلوب" else "المحركات متوقفة"
            val map = homeMapMemory.snapshot()
            val persistenceText = if (homeMapPersistenceEnabled) "الحفظ الدائم مفعّل بإطار منزل ثابت" else "الحفظ الدائم ينتظر قاعدة تدعم إطار منزل ثابت"
            val mapLine = "خريطة محلية: خلايا ${map.knownCells}، عوائق ${map.obstacleCells}، مناطق مسماة ${map.namedRoomCells}، نقاط مسار ${map.routePointCount}. $persistenceText."
            showDialogueText("$status $movement $mapLine", durationMs = 7000L)
            return
        }
        if (listOf("ما اسم الغرفة", "اي غرفة هذه", "اين نحن").any(text::contains)) {
            showDialogueText(
                latestRoomSummary.ifBlank { "لم تتوفر أدلة بصرية كافية لتحديد الغرفة بعد." },
                durationMs = 7000L
            )
            return
        }

        if (listOf(
                "ابدئي الاستكشاف", "ابداي الاستكشاف", "ابدأ الاستكشاف",
                "استكشفي المكان", "استكشفي", "انظري حولك", "دوري حولك",
                "ابحثي حولك", "استكشاف تلقائي", "explore", "scan the room"
            ).any(text::contains)
        ) {
            autonomousExplorationEnabled = true
            getSharedPreferences("eve_relationship", MODE_PRIVATE).edit()
                .putBoolean("autonomousExplorationEnabled", true)
                .apply()
            Log.i("EVE_MIND", "AUTONOMOUS_EXPLORATION enabled=true mode=local_current_view")
            if (activeCameraLens != CameraLens.BACK) {
                showDialogueText("بدأت الاستكشاف المحلي. سأستخدم الكاميرا الخلفية لمعاينة المكان.", durationMs = 5200L)
                switchCamera()
            } else {
                showDialogueText("الاستكشاف المحلي نشط. سأحلل المشهد وأقارن الأدلة دون تخمين.", durationMs = 5200L)
                runAutonomousCognition(8_000L)
            }
            return
        }
        if (listOf(
                "اوقفي الاستكشاف", "اوقف الاستكشاف", "ايقاف الاستكشاف",
                "توقفي عن الاستكشاف", "stop exploring", "disable exploration"
            ).any(text::contains)
        ) {
            autonomousExplorationEnabled = false
            getSharedPreferences("eve_relationship", MODE_PRIVATE).edit()
                .putBoolean("autonomousExplorationEnabled", false)
                .apply()
            Log.i("EVE_MIND", "AUTONOMOUS_EXPLORATION enabled=false")
            showDialogueText("أوقفت وضع الاستكشاف التلقائي. ستبقى الرؤية المحلية متاحة أثناء تشغيل الكاميرا.", durationMs = 5200L)
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

        if (handleTravelMemorySpeech(text, addressedToEve, activeConversation, now)) return
        if (answerPersonContextQuestion(text, addressedToEve, activeConversation)) return

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
        lastDirectUserActionAt = SystemClock.elapsedRealtime()
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
        registerStrongShakeDetection()
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
        unregisterStrongShakeDetection()
        main.removeCallbacks(idleLoop)
        if (baseAutonomyRequested) {
            // A backgrounded phone must not continue commanding wheel motion.
            baseAutonomyRequested = false
            main.removeCallbacks(baseControlLoop)
            if (::robotBaseLink.isInitialized) robotBaseLink.stopMotors()
            robotBaseNavigator.resetAvoidance()
        }
        faceOrNull()?.stopAnimation()
        faceCueSmoother.reset()
        emotionSmoother.reset()
        cameraProvider?.unbindAll()
        cameraStarted = false
        if (::cameraStatus.isInitialized) showCameraStatus("الكاميرا متوقفة مؤقتًا خارج التطبيق", active = false)
        super.onPause()
    }

    private fun registerStrongShakeDetection() {
        if (shakeSensorRegistered) return
        if (motionSensorManager == null) {
            motionSensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
            motionAccelerometer = motionSensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        val manager = motionSensorManager ?: return
        val sensor = motionAccelerometer ?: run {
            Log.i("EVE", "PHYSICAL_SHAKE unavailable: no accelerometer")
            return
        }
        shakeGravityReady = false
        shakeSensorRegistered = manager.registerListener(
            strongShakeListener,
            sensor,
            SensorManager.SENSOR_DELAY_GAME
        )
    }

    private fun unregisterStrongShakeDetection() {
        if (!shakeSensorRegistered) return
        motionSensorManager?.unregisterListener(strongShakeListener)
        shakeSensorRegistered = false
        shakeGravityReady = false
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
        val requestPoseInference = poseLandmarkerReady &&
            now - lastPoseInferenceAt.get() >= poseInferenceIntervalMs &&
            poseInferenceRunning.compareAndSet(false, true)
        if (requestPoseInference) lastPoseInferenceAt.set(now)

        // This RGB bitmap is independent of ImageProxy memory and is shared read-only with
        // local object classification, facial analysis, gestures, and human-pose estimation.
        val bitmapNeeded = requestObjectDetection || requestImageClassification || requestPoseInference ||
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
        if (requestPoseInference && frameBitmap == null) poseInferenceRunning.set(false)
        val runObjectDetection = requestObjectDetection && frameBitmap != null
        val runImageClassification = requestImageClassification && frameBitmap != null
        val runPoseInference = requestPoseInference && frameBitmap != null

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
                Log.i(
                    "EVE",
                    "VISION_EVIDENCE label=" + label + " confidence=" + best.confidence +
                        " sightings=" + sightings + " affinity=" + affinity +
                        " source=local_image_labeler"
                )
                // A detected category is evidence, not an instruction to change mood.
                // Curiosity questions take priority. When EVE has no grounded question to ask,
                // show a brief observation cue so successful local perception is not invisible.
                val askedOrQueued = maybeAskFromCuriosity(label, best.confidence, sightings)
                if (!askedOrQueued) {
                    val generatedReactionStarted = maybeShowVisibleObservationCue(label, best.confidence, elapsed)
                    if (!generatedReactionStarted) {
                        maybeReflectWithLocalLanguage(label, best.confidence, sightings)
                    }
                }
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
                (if (runImageClassification) 1L else 0L) +
                (if (runPoseInference) 1L else 0L)
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

        if (runPoseInference && frameBitmap != null) {
            val poseBitmap = frameBitmap
            try {
                objectExecutor.execute {
                    try {
                        val result = poseLandmarker?.detect(BitmapImageBuilder(poseBitmap).build())
                        val landmarks = result?.landmarks()?.firstOrNull().orEmpty()
                        val points = landmarks.map { EvePosePoint(it.x(), it.y()) }
                        val previous = previousPoseAngles
                        val angles = if (landmarks.isEmpty()) null else
                            EvePoseAnglesAnalyzer.analyze(points, poseBitmap.width, poseBitmap.height, previous)
                        val observedAt = SystemClock.elapsedRealtime()
                        if (angles != null) {
                            previousPoseAngles = angles
                            latestPoseAngles = angles
                            latestPoseObservedAt = observedAt
                            Log.d(
                                "EVE_POSE",
                                "POSE_FRAME landmarks=${points.size} " +
                                    "knees=${angles.leftKnee?.toInt()},${angles.rightKnee?.toInt()} " +
                                    "elbows=${angles.leftElbow?.toInt()},${angles.rightElbow?.toInt()} " +
                                    "motion=${angles.jointMotionScore} offline=true"
                            )
                        } else {
                            latestPoseAngles = null
                            latestPoseObservedAt = observedAt
                            previousPoseAngles = null
                            val previousDiagnostic = lastPoseDiagnosticAt.get()
                            if (observedAt - previousDiagnostic >= 7_000L &&
                                lastPoseDiagnosticAt.compareAndSet(previousDiagnostic, observedAt)
                            ) {
                                Log.i("EVE_POSE", "POSE_FRAME_NO_BODY landmarks=${landmarks.size}; activity remains uncertain")
                            }
                        }
                    } catch (error: Exception) {
                        Log.w("EVE_POSE", "Offline pose inference failed for one frame.", error)
                    } finally {
                        poseInferenceRunning.set(false)
                        finishFrameConsumer()
                    }
                }
            } catch (error: Exception) {
                poseInferenceRunning.set(false)
                finishFrameConsumer()
                Log.e("EVE_POSE", "Could not queue local pose inference.", error)
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
                        personContextUnderstanding.reset()
                        latestPersonContextSnapshot = null
                        latestPersonContextSummary = ""
                        lastPersonContextSummaryAt = 0L
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
                    val previousFaceSeenAt = lastFaceSeenAt
                    lastFaceSeenAt = elapsed
                    // A detected person should produce one visible acknowledgement; otherwise
                    // face tracking can run successfully while the screen appears completely idle.
                    if (previousFaceSeenAt == 0L || elapsed - previousFaceSeenAt >= 7_000L) {
                        maybeAcknowledgeObservedPerson(elapsed)
                    }
                    observeHumanActivityFromFace(bestFace, elapsed, faces)
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
                                                    latestStableFaceEmotionLabel = stableEmotion.label
                                                    latestStableFaceEmotionConfidence = stableEmotion.confidence
                                                    latestStableFaceEmotionAt = SystemClock.elapsedRealtime()
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

                try {
                    val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(
                            BaseOptions.builder()
                                .setModelAssetPath("pose_landmarker_lite.task")
                                .build()
                        )
                        .setRunningMode(RunningMode.IMAGE)
                        .setNumPoses(1)
                        .setMinPoseDetectionConfidence(0.45f)
                        .setMinPosePresenceConfidence(0.45f)
                        .setMinTrackingConfidence(0.45f)
                        .build()
                    val pose = PoseLandmarker.createFromOptions(applicationContext, options)
                    poseLandmarker = pose
                    poseLandmarkerReady = true
                    Log.i("EVE_POSE", "POSE_LANDMARKER_AVAILABLE model=pose_landmarker_lite.task landmarks=33 offline=true")
                } catch (error: Exception) {
                    poseLandmarkerReady = false
                    Log.e("EVE_POSE", "Could not initialize offline pose landmarker.", error)
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
        latestVisualObjects = sceneObjects
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
            latestVisualObjects = emptyList()
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
        Log.i(
            "EVE_OBJECTS",
            "OBJECT_EVIDENCE_READY label=" + label +
                " confidence=" + best.confidence +
                " sightings=" + sightings +
                " affinity=" + memory.learnedAffinity(label) +
                " source=local_object_detector"
        )
        // Let the local generative model interpret evidence before it changes emotional state.
        val askedOrQueued = best.confidence >= 0.82f &&
            maybeAskFromCuriosity(label, best.confidence, sightings)
        if (!askedOrQueued) {
            val generatedReactionStarted = maybeShowVisibleObservationCue(label, best.confidence, now)
            if (!generatedReactionStarted && best.confidence >= 0.82f && changed) {
                maybeReflectWithLocalLanguage(label, best.confidence, sightings)
            }
        }
    }

    /**
     * Requests an original local-model response from current perception and remembered context.
     * There is deliberately no canned fallback: if the generative brain is not ready or fails,
     * EVE does not pretend a scripted line came from independent understanding.
     */
    private fun maybeShowVisibleObservationCue(label: String, confidence: Float, now: Long): Boolean {
        if (isFinishing || isDestroyed || !::dialogueBubble.isInitialized) return false
        if (!confidence.isFinite() || confidence < 0.72f) return false

        val key = EveSceneGeometry.normalizeLabel(label)
            .trim()
            .lowercase(Locale.ROOT)
        if (key.isBlank() || (!isMeaningfulLabel(key) && !isCuriosityWorthyLabel(key))) return false
        if (!::localLanguageBrain.isInitialized || !localLanguageBrain.isReady) return false
        if (observationResponseRunning.get()) return false

        if (lastVisibleCueAt > 0L && now - lastVisibleCueAt < visibleCueGlobalCooldownMs) return false
        if (key == lastVisibleCueKey && lastVisibleCueAt > 0L &&
            now - lastVisibleCueAt < visibleCueSameLabelCooldownMs
        ) return false

        val sceneIsFresh = lastSceneObservationAt > 0L &&
            now - lastSceneObservationAt in 0L..EveAutonomousMind.SCENE_FRESH_MS
        val sceneEvidence = if (sceneIsFresh && latestSceneSummary.isNotBlank()) {
            latestSceneSummary.trim().take(600)
        } else {
            "التصنيف البصري المحلي الحالي: " + key +
                "؛ ثقة النموذج=" + (confidence * 100f).toInt() + "٪؛ " +
                "عدد مرات الرصد=" + memory.sightings(key) +
                ". هذا احتمال تصنيفي وليس وصفًا يقينيًا لكل المشهد."
        }
        val context = localLanguageContext().copy(
            latestVisionLabel = key,
            visionConfidence = confidence,
            visionObservationAgeMs = 0L,
            sightingCount = memory.sightings(key),
            latestSceneSummary = sceneEvidence,
            sceneObservationAgeMs = 0L
        )
        return requestGeneratedObservationResponse(
            key = key,
            confidence = confidence,
            now = now,
            evidence = sceneEvidence,
            context = context
        )
    }

    /** Face presence is evidence of a person, not evidence of their name or relationship. */
    private fun maybeAcknowledgeObservedPerson(now: Long) {
        if (isFinishing || isDestroyed || !::dialogueBubble.isInitialized) return
        if (!::localLanguageBrain.isInitialized || !localLanguageBrain.isReady) return
        if (lastPresenceCueAt > 0L && now - lastPresenceCueAt < presenceCueCooldownMs) return
        if (lastVisibleCueAt > 0L && now - lastVisibleCueAt < visibleCueGlobalCooldownMs) return
        if (observationResponseRunning.get()) return

        val freshScene = lastSceneObservationAt > 0L &&
            now - lastSceneObservationAt in 0L..EveAutonomousMind.SCENE_FRESH_MS &&
            latestSceneSummary.isNotBlank()
        val faceEvidence = if (freshScene) {
            latestSceneSummary.trim().take(420) +
                "؛ كاشف الوجه المحلي وجد وجهًا بشريًا في الإطار الحالي، لكن الهوية غير مثبتة بمجرد وجود الوجه."
        } else {
            "كاشف الوجه المحلي وجد وجهًا بشريًا في الإطار الحالي. لا يوجد دليل كافٍ لتحديد الاسم أو العلاقة."
        }
        val context = localLanguageContext().copy(
            latestVisionLabel = "person",
            visionConfidence = 0.88f,
            visionObservationAgeMs = 0L,
            latestSceneSummary = faceEvidence,
            sceneObservationAgeMs = 0L
        )
        if (requestGeneratedObservationResponse(
                key = "person_presence",
                confidence = 0.88f,
                now = now,
                evidence = faceEvidence,
                context = context
            )
        ) {
            lastPresenceCueAt = now
        }
    }

    private fun requestGeneratedObservationResponse(
        key: String,
        confidence: Float,
        now: Long,
        evidence: String,
        context: LocalLlmContext
    ): Boolean {
        if (!observationResponseRunning.compareAndSet(false, true)) return false
        lastVisibleCueKey = key
        lastVisibleCueAt = now

        localLanguageBrain.commentOnObservation(context, evidence) { outcome ->
            try {
                if (isFinishing || isDestroyed) return@commentOnObservation
                val generated = outcome.getOrNull()
                val reply = generated?.replyArabic?.trim()?.take(180).orEmpty()
                if (reply.isBlank() || generated == null) {
                    Log.w(
                        "EVE_VISIBLE_BEHAVIOR",
                        "GENERATED_REACTION_FAILED label=" + key + " confidence=" + confidence,
                        outcome.exceptionOrNull()
                    )
                    return@commentOnObservation
                }

                generated.reflectionArabic.trim().takeIf { it.isNotBlank() }
                    ?.let(memory::rememberReflection)
                memory.rememberDialogue("دليل بصري محلي: " + evidence.take(160), reply)
                val decision = brain.integrateLanguageInsight(
                    moodHint = generated.moodHint,
                    intentHint = generated.intentHint,
                    confidence = generated.confidence,
                    note = generated.reflectionArabic,
                    source = "offline_visual_awareness"
                )
                react(decision, playSound = false)
                showDialogueText(reply, durationMs = 5_200L)
                Log.i(
                    "EVE_VISIBLE_BEHAVIOR",
                    "GENERATED_REACTION_OK label=" + key + " confidence=" + confidence +
                        " modelConfidence=" + generated.confidence + " localOnly=true"
                )
            } finally {
                observationResponseRunning.set(false)
            }
        }
        return true
    }

    private val objectDetectionIntervalMs = 1400L
    private val baseControlTickMs = 250L
    private val roomSummaryTtlMs = 45_000L
    private val baseSafetyNoticeCooldownMs = 5_000L
    private val imageClassificationIntervalMs = 4_500L
    private val poseInferenceIntervalMs = 2_200L
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
    private fun observeHumanActivityFromFace(face: Face, now: Long, detectedFaces: List<Face>) {
        val mouth = mouthGeometry(face)
        val handConfidence = handNearMouthConfidence(mouth, face.boundingBox.height(), now)
        val handIsFresh = latestHandObservedAt > 0L && now - latestHandObservedAt in 0L..1_400L
        val poseIsFresh = latestPoseObservedAt > 0L && now - latestPoseObservedAt in 0L..3_200L
        val pose = latestPoseAngles.takeIf { poseIsFresh }
        val labels = buildSet {
            if (lastObjectSceneAt > 0L && now - lastObjectSceneAt in 0L..6_000L) addAll(latestObjectLabels)
            if (lastImageClassifierSceneAt > 0L && now - lastImageClassifierSceneAt in 0L..8_000L) addAll(latestImageClassifierLabels)
        }
        val result = activityUnderstanding.observe(
            EveHumanFrameEvidence(
                timestampMs = now,
                // Detected face means a person is present; it does not identify their name.
                personConfidence = 0.88f,
                handToMouthConfidence = handConfidence,
                mouthOpeningRatio = mouth.openingRatio,
                jointMotionScore = maxOf(
                    if (handIsFresh) latestHandTrajectoryScore else 0f,
                    pose?.jointMotionScore ?: 0f
                ),
                leftKneeAngleDegrees = pose?.leftKnee,
                rightKneeAngleDegrees = pose?.rightKnee,
                leftElbowAngleDegrees = pose?.leftElbow,
                rightElbowAngleDegrees = pose?.rightElbow,
                handCenterX = latestHandCenterX.takeIf { handIsFresh && it.isFinite() },
                handCenterY = latestHandCenterY.takeIf { handIsFresh && it.isFinite() },
                visibleObjectLabels = labels
            )
        )
        applyHumanActivityInterpretation(result, now)
        observePersonContextFromFace(face, now, pose, handIsFresh, detectedFaces)
    }

    private fun isPhoneObject(label: String) = EveSceneGeometry.normalizeLabel(label) in setOf("cell phone", "mobile phone", "smartphone", "phone")
    private fun isSeatObject(label: String) = EveSceneGeometry.normalizeLabel(label) in setOf("chair", "couch", "bench", "bed")

    private fun observePersonContextFromFace(
        face: Face, now: Long, pose: EvePoseAngles?, handIsFresh: Boolean, detectedFaces: List<Face>
    ) {
        val age = if (lastObjectSceneAt > 0L) now - lastObjectSceneAt else Long.MAX_VALUE
        val freshObjects = age in 0L..2_200L
        val objects = if (freshObjects) latestVisualObjects else emptyList()
        val phones = objects.filter { isPhoneObject(it.label) && it.confidence >= 0.42f }
        val seats = objects.filter { isSeatObject(it.label) && it.confidence >= 0.42f }
        val fw = latestAnalysisWidth.toFloat().coerceAtLeast(1f)
        val fh = latestAnalysisHeight.toFloat().coerceAtLeast(1f)
        val box = face.boundingBox
        val fx = (box.centerX() / fw).coerceIn(0f, 1f)
        val fy = (box.centerY() / fh).coerceIn(0f, 1f)
        val faceW = (box.width() / fw).coerceIn(0.01f, 1f)
        val faceH = (box.height() / fh).coerceIn(0.01f, 1f)
        fun nearEar(p: EveVisualObject): Float {
            val dx = kotlin.math.abs(p.centerX - fx)
            val dy = kotlin.math.abs(p.centerY - fy)
            val xl = (faceW * 1.45f + p.widthFraction * 0.25f + 0.025f).coerceAtLeast(0.075f)
            val yl = (faceH * 0.78f + p.heightFraction * 0.25f + 0.025f).coerceAtLeast(0.09f)
            if (dx > xl || dy > yl) return 0f
            val proximity = maxOf(dx / xl, dy / yl).coerceIn(0f, 1f)
            return (p.confidence * (0.55f + 0.45f * (1f - proximity))).coerceIn(0f, 1f)
        }
        fun inFront(p: EveVisualObject): Float {
            val below = p.centerY - (fy + faceH * 0.28f)
            if (below <= 0f || p.centerY >= fy + faceH * 2.8f) return 0f
            val vertical = (1f - below / (faceH * 2.55f + 0.08f)).coerceIn(0f, 1f)
            return (p.confidence * (0.5f + 0.5f * vertical)).coerceIn(0f, 1f)
        }
        fun handNear(p: EveVisualObject): Float {
            if (!handIsFresh || !latestHandCenterX.isFinite() || !latestHandCenterY.isFinite()) return 0f
            val dx = (kotlin.math.abs(latestHandCenterX - p.centerX) - p.widthFraction / 2f).coerceAtLeast(0f)
            val dy = (kotlin.math.abs(latestHandCenterY - p.centerY) - p.heightFraction / 2f).coerceAtLeast(0f)
            val distance = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val radius = (maxOf(p.widthFraction, p.heightFraction) * 1.7f + 0.045f).coerceAtLeast(0.06f)
            return if (distance <= radius) (0.35f + 0.65f * (1f - distance / radius)).coerceIn(0f, 1f) else 0f
        }
        val phone = phones.maxByOrNull { it.confidence * (0.8f + 0.2f * it.areaFraction) }
        val near = phone?.let(::nearEar) ?: 0f
        // The caller passes a pose only when it is still fresh; keep freshness scoped here.
        val poseIsFresh = pose != null
        val proximity = faceProximityConfidence(detectedFaces)
        val motionAt = maxOf(if (poseIsFresh) latestPoseObservedAt else 0L, if (handIsFresh) latestHandObservedAt else 0L)
        val motionScore = maxOf(if (poseIsFresh) pose?.jointMotionScore ?: 0f else 0f,
            if (handIsFresh) latestHandTrajectoryScore else 0f)
        val snap = personContextUnderstanding.observe(EvePersonContextEvidence(
            timestampMs = now, personConfidence = 0.88f,
            visiblePersonCount = detectedFaces.size, faceProximityConfidence = proximity,
            motionObservationAtMs = motionAt, motionScore = motionScore,
            poseObservationAtMs = if (pose != null) latestPoseObservedAt else 0L,
            sittingPoseConfidence = pose?.sittingPoseConfidence ?: 0f,
            objectObservationAtMs = if (freshObjects) lastObjectSceneAt else 0L,
            phoneConfidence = phone?.confidence ?: 0f, phoneNearEarConfidence = near,
            phoneInFrontConfidence = if (near >= 0.52f) 0f else phone?.let(::inFront) ?: 0f,
            handObservationAtMs = if (handIsFresh) latestHandObservedAt else 0L,
            handNearPhoneConfidence = phone?.let(::handNear) ?: 0f,
            nearbySeatConfidence = seats.maxOfOrNull { it.confidence } ?: 0f
        ))
        latestPersonContextSnapshot = snap
        lastPersonContextCaptureAt = now
        latestPersonContextSummary = snap.summaryArabic
        lastPersonContextSummaryAt = if (snap.summaryArabic.isNotBlank()) now else 0L
        refreshLatestSceneSummary(now)
    }

    private fun faceProximityConfidence(faces: List<Face>): Float {
        if (faces.size < 2) return 0f
        var closestRatio = Float.POSITIVE_INFINITY
        for (i in faces.indices) for (j in i + 1 until faces.size) {
            val a = faces[i].boundingBox
            val b = faces[j].boundingBox
            val dx = (a.centerX() - b.centerX()).toDouble()
            val dy = (a.centerY() - b.centerY()).toDouble()
            val distance = kotlin.math.hypot(dx, dy).toFloat()
            val scale = ((a.height() + b.height()) * 0.5f).coerceAtLeast(1f)
            closestRatio = minOf(closestRatio, distance / scale)
        }
        if (!closestRatio.isFinite() || closestRatio >= 4.5f) return 0f
        return (1f - closestRatio / 4.5f).coerceIn(0f, 1f)
    }

    private fun applyHumanActivityInterpretation(rawResult: EveActivityInterpretation, now: Long) {
        if (isFinishing || isDestroyed) return
        if (rawResult.isActionable) latestRawActivityInterpretation = rawResult
        // The learner can only influence a fresh activity hypothesis after at least three
        // explicit user-confirmed examples and only when its local prototype match is unambiguous.
        val result = if (rawResult.isActionable && ::activityLearningBridge.isInitialized) {
            activityLearningBridge.applyLearnedCorrection(rawResult)
        } else rawResult
        if (result.isActionable) {
            lastHumanActivityAt = now
            lastActivityObservationAt = now
            activeHumanActivityConfidence = result.confidence
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
                "ACTIVITY_HYPOTHESIS raw=${rawResult.activity} effective=${result.activity} " +
                    "confidence=${result.confidence} frames=${result.evidenceCount} " +
                    "durationMs=${result.durationMs} localLearning=true offline=true"
            )
        } else if (lastHumanActivityAt == 0L || now - lastHumanActivityAt > activitySummaryTtlMs) {
            activeHumanActivity = EveHumanActivity.UNKNOWN
            activeHumanActivityConfidence = 0f
            latestActivitySummary = ""
            lastActivityObservationAt = 0L
        }
        refreshLatestSceneSummary(now)
    }

    /**
     * Speech-only correction path (no visible buttons). Vague "yes" is never treated as training
     * data; the person must explicitly confirm the activity estimate or issue a correction.
     */
    private fun handleActivityLearningSpeech(text: String): Boolean {
        val confirmPhrases = listOf(
            "تقديرك صحيح", "النشاط صحيح", "هذا النشاط صحيح", "رصدك صحيح", "تحليلك صحيح"
        )
        val correctionPhrases = listOf(
            "تعلمي من تصحيحي", "تعلمي أن النشاط", "علّمي نفسك", "علمي نفسك",
            "صححي النشاط", "صححي تقديرك", "التقدير غير صحيح", "النشاط الصحيح",
            "هذا ليس", "هذه ليست", "ليس هذا", "ليست هذه", "الذي ترينه هو", "النشاط هو",
            "learn from my correction", "this is not"
        )
        val confirmed = confirmPhrases.any { text.contains(normalizeArabicSpeech(it)) }
        val correctionRequested = correctionPhrases.any { text.contains(normalizeArabicSpeech(it)) }
        if (!confirmed && !correctionRequested) return false

        val now = SystemClock.elapsedRealtime()
        val raw = latestRawActivityInterpretation
        if (raw == null || !raw.isActionable ||
            now - raw.observedAtMs !in 0L..activitySummaryTtlMs
        ) {
            showDialogueText(
                "لا توجد لدي ملاحظة نشاط حديثة كافية لأتعلّم منها. انتظر حتى أعرض احتمالًا بصريًا جديدًا، ثم أكّد تقديري أو صحّحه بوضوح.",
                durationMs = 7_500L
            )
            return true
        }

        val target = if (confirmed) raw.activity else activityFromCorrectionSpeech(text)
        if (target == null || target == EveHumanActivity.UNKNOWN) {
            showDialogueText(
                "لم أفهم النشاط الذي تريد تصحيحه. قل مثلًا: «صححي النشاط إلى مضغ العلكة» أو «تعلمي أن النشاط هو الرسم».",
                durationMs = 7_500L
            )
            return true
        }

        val count = activityLearningBridge.recordUserCorrection(raw, target)
        if (count == null) {
            showDialogueText("لم أحفظ هذا المثال؛ أحتاج إلى نشاط واضح وحديث وتصحيح صريح.", durationMs = 6_000L)
            return true
        }
        val progress = if (count < 3) {
            "لدي الآن $count من 3 أمثلة مطلوبة لهذا التصنيف. لن أغيّر تقديراتي تلقائيًا بعد."
        } else {
            "لدي الآن $count أمثلة مؤكدة. سأستخدمها محليًا فقط عندما يتطابق النمط بوضوح، وإلا سأبقي النتيجة غير محسومة."
        }
        val message = "حفظت تصحيحك محليًا: «${activityLearningArabicName(target)}». $progress لا أرسل هذه البيانات إلى الإنترنت."
        showDialogueText(message, durationMs = 8_000L)
        Log.i(
            "EVE_ACTIVITY_LEARNING",
            "USER_CORRECTION_SAVED target=$target examples=$count rawActivity=${raw.activity} localOnly=true"
        )
        return true
    }

    private fun activityFromCorrectionSpeech(text: String): EveHumanActivity? {
        // Select the last-mentioned activity term. In phrases such as "not eating, but drinking",
        // this tends to select the correction rather than the rejected hypothesis.
        val terms = listOf(
            "علكة" to EveHumanActivity.CHEWING_MOTION_POSSIBLE,
            "لبان" to EveHumanActivity.CHEWING_MOTION_POSSIBLE,
            "مضغ" to EveHumanActivity.CHEWING_MOTION_POSSIBLE,
            "chewing gum" to EveHumanActivity.CHEWING_MOTION_POSSIBLE,
            "مثلجات" to EveHumanActivity.EATING_TREAT_POSSIBLE,
            "ايس كريم" to EveHumanActivity.EATING_TREAT_POSSIBLE,
            "بوظة" to EveHumanActivity.EATING_TREAT_POSSIBLE,
            "مصاصة" to EveHumanActivity.EATING_TREAT_POSSIBLE,
            "حلوى" to EveHumanActivity.EATING_TREAT_POSSIBLE,
            "يشرب" to EveHumanActivity.DRINKING_POSSIBLE,
            "تشرب" to EveHumanActivity.DRINKING_POSSIBLE,
            "شرب" to EveHumanActivity.DRINKING_POSSIBLE,
            "drinking" to EveHumanActivity.DRINKING_POSSIBLE,
            "يلعب" to EveHumanActivity.PLAYING_GAME_POSSIBLE,
            "لعب" to EveHumanActivity.PLAYING_GAME_POSSIBLE,
            "لعبة" to EveHumanActivity.PLAYING_GAME_POSSIBLE,
            "playing game" to EveHumanActivity.PLAYING_GAME_POSSIBLE,
            "يرسم" to EveHumanActivity.DRAWING_OR_COLORING_POSSIBLE,
            "رسم" to EveHumanActivity.DRAWING_OR_COLORING_POSSIBLE,
            "تلوين" to EveHumanActivity.DRAWING_OR_COLORING_POSSIBLE,
            "يلون" to EveHumanActivity.DRAWING_OR_COLORING_POSSIBLE,
            "drawing" to EveHumanActivity.DRAWING_OR_COLORING_POSSIBLE,
            "تمرين" to EveHumanActivity.EXERCISE_LIKE_MOTION,
            "رياضة" to EveHumanActivity.EXERCISE_LIKE_MOTION,
            "يتمرن" to EveHumanActivity.EXERCISE_LIKE_MOTION,
            "exercise" to EveHumanActivity.EXERCISE_LIKE_MOTION,
            "يتناول الطعام" to EveHumanActivity.EATING_POSSIBLE,
            "ياكل" to EveHumanActivity.EATING_POSSIBLE,
            "اكل" to EveHumanActivity.EATING_POSSIBLE,
            "eating" to EveHumanActivity.EATING_POSSIBLE
        )
        return terms.mapNotNull { (phrase, activity) ->
            val index = text.lastIndexOf(normalizeArabicSpeech(phrase))
            if (index >= 0) Triple(index, phrase.length, activity) else null
        }.maxWithOrNull(compareBy<Triple<Int, Int, EveHumanActivity>> { it.first }.thenBy { it.second })
            ?.third
    }

    private fun activityLearningArabicName(activity: EveHumanActivity): String = when (activity) {
        EveHumanActivity.EATING_POSSIBLE -> "تناول الطعام"
        EveHumanActivity.EATING_TREAT_POSSIBLE -> "تناول حلوى أو مثلجات"
        EveHumanActivity.DRINKING_POSSIBLE -> "الشرب"
        EveHumanActivity.CHEWING_MOTION_POSSIBLE -> "حركة المضغ"
        EveHumanActivity.PLAYING_GAME_POSSIBLE -> "اللعب"
        EveHumanActivity.DRAWING_OR_COLORING_POSSIBLE -> "الرسم أو التلوين"
        EveHumanActivity.EXERCISE_LIKE_MOTION -> "حركة شبيهة بالتمرين"
        EveHumanActivity.UNKNOWN -> "نشاط غير معروف"
    }

    private val sceneSummaryTtlMs = 15_000L
    private val activitySummaryTtlMs = 12_000L
    private val wellbeingSummaryTtlMs = 12_000L
    private val environmentSummaryTtlMs = 45_000L

    /** Combines spatial object boxes with broader ImageNet scene hypotheses, discarding stale evidence. */
    private fun refreshLatestSceneSummary(now: Long) {
        if (lastObjectSceneAt == 0L || now - lastObjectSceneAt > sceneSummaryTtlMs) {
            latestObjectSummary = ""
            latestObjectLabels = emptySet()
        }
        if (lastImageClassifierSceneAt == 0L || now - lastImageClassifierSceneAt > sceneSummaryTtlMs) {
            latestImageClassifierSummary = ""
            latestImageClassifierLabels = emptySet()
        }
        if (lastActivityObservationAt == 0L || now - lastActivityObservationAt > activitySummaryTtlMs) {
            latestActivitySummary = ""
            activeHumanActivity = EveHumanActivity.UNKNOWN
            activeHumanActivityConfidence = 0f
        }
        if (lastWellbeingObservationAt == 0L || now - lastWellbeingObservationAt > wellbeingSummaryTtlMs) {
            latestWellbeingSummary = ""
        }
        if (lastEnvironmentObservationAt == 0L || now - lastEnvironmentObservationAt > environmentSummaryTtlMs) {
            latestEnvironmentSummary = ""
        }
        if (lastRoomObservationAt == 0L || now - lastRoomObservationAt > roomSummaryTtlMs) {
            latestRoomSummary = ""
            latestRoomType = EveRoomType.UNKNOWN
        }
        if (lastPersonContextSummaryAt == 0L || now - lastPersonContextSummaryAt > activitySummaryTtlMs) latestPersonContextSummary = ""
        val parts = listOf(
            latestObjectSummary, latestImageClassifierSummary, latestActivitySummary,
            latestPersonContextSummary, latestWellbeingSummary, latestEnvironmentSummary, latestRoomSummary
        ).filter { it.isNotBlank() }
        latestSceneSummary = parts.joinToString("؛ ").take(600)
        lastSceneObservationAt = maxOf(
            if (latestObjectSummary.isNotBlank()) lastObjectSceneAt else 0L,
            if (latestImageClassifierSummary.isNotBlank()) lastImageClassifierSceneAt else 0L,
            if (latestActivitySummary.isNotBlank()) lastActivityObservationAt else 0L,
            if (latestPersonContextSummary.isNotBlank()) lastPersonContextSummaryAt else 0L,
            if (latestWellbeingSummary.isNotBlank()) lastWellbeingObservationAt else 0L,
            if (latestEnvironmentSummary.isNotBlank()) lastEnvironmentObservationAt else 0L,
            if (latestRoomSummary.isNotBlank()) lastRoomObservationAt else 0L
        )
    }

    /**
     * The classifier is a fallback/broad taxonomy source, not a replacement for object boxes.
     * Its scores are explicitly presented as hypotheses; they never claim identity or certainty.
     */
    private fun applyImageClassifierObservations(categories: List<EveImageClass>, meanLuminance: Float) {
        if (isFinishing || isDestroyed) return
        val now = SystemClock.elapsedRealtime()
        val freshFaceEmotion = if (
            latestStableFaceEmotionAt > 0L && now - latestStableFaceEmotionAt in 0L..6_000L
        ) latestStableFaceEmotionLabel else null
        val wellbeing = wellbeingUnderstanding.observe(
            timestampMs = now,
            personPresent = isPersonFaceRecent(now),
            faceEmotionLabel = freshFaceEmotion,
            faceEmotionConfidence = latestStableFaceEmotionConfidence,
            candidates = categories
        )
        if (wellbeing.isActionable) {
            latestWellbeingSummary = wellbeing.explanationArabic +
                " (احتمال " + (wellbeing.confidence * 100f).toInt() + "٪؛ لا يثبت مرضًا)"
            lastWellbeingObservationAt = now
            Log.i(
                "EVE_WELLBEING",
                "WELLBEING_CUE cue=" + wellbeing.cue + " confidence=" + wellbeing.confidence +
                    " frames=" + wellbeing.evidenceFrames + "; medicalDiagnosis=false offline=true"
            )
        } else if (lastWellbeingObservationAt == 0L || now - lastWellbeingObservationAt > wellbeingSummaryTtlMs) {
            latestWellbeingSummary = ""
        }
        val localHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val freshObjectsForRoom = if (
            lastObjectSceneAt > 0L && now - lastObjectSceneAt in 0L..3_000L
        ) latestObjectLabels.map { it to 0.62f } else emptyList()
        val room = roomUnderstanding.observe(
            timestampMs = now,
            candidates = categories.map { it.label to it.confidence } + freshObjectsForRoom
        )
        if (room.room != EveRoomType.UNKNOWN) {
            latestRoomType = room.room
            latestRoomSummary = room.explanationArabic
            lastRoomObservationAt = now
            Log.i(
                "EVE_ROOM",
                "ROOM_HYPOTHESIS room=${room.room} confidence=${room.confidence} " +
                    "evidence=${room.evidence.joinToString("|")} offline=true"
            )
        }
        val environment = environmentUnderstanding.observe(
            timestampMs = now,
            candidates = categories,
            meanLuminance = meanLuminance,
            localHour = localHour,
            localMonth = Calendar.getInstance().get(Calendar.MONTH) + 1,
            hemisphere = environmentUnderstanding.inferHemisphere(
                Locale.getDefault().country,
                java.util.TimeZone.getDefault().id
            )
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
                "ENVIRONMENT season=${environment.season} seasonConfidence=${environment.seasonConfidence} " +
                    "hemisphere=${environment.hemisphere} visualSeasonCue=${environment.seasonalClue} " +
                    "visualSeasonConfidence=${environment.seasonalClueConfidence} " +
                    "weather=${environment.weather} weatherConfidence=${environment.weatherConfidence} " +
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
            latestImageClassifierLabels = emptySet()
            lastImageClassifierSceneAt = 0L
            refreshLatestSceneSummary(now)
            Log.i("EVE_IMAGENET", "IMAGE_CLASSIFICATION_NO_CONFIDENT_CATEGORY_AFTER_EVIDENCE_POLICY")
            return
        }

        latestImageClassifierLabels = ranked
            .filter { it.confidence >= 0.35f }
            .map { EveSceneGeometry.normalizeLabel(it.label) }
            .toSet()
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
            Log.i(
                "EVE_IMAGENET",
                "IMAGE_EVIDENCE_READY label=" + label + " confidence=" + best.confidence +
                    " sightings=" + sightings + " source=local_image_classifier"
            )
            // Classification alone does not dictate EVE's mood; generated interpretation does.
            val askedOrQueued = maybeAskFromCuriosity(label, best.confidence, sightings)
            if (!askedOrQueued) {
                val generatedReactionStarted = maybeShowVisibleObservationCue(
                    label, best.confidence, SystemClock.elapsedRealtime()
                )
                if (!generatedReactionStarted && best.confidence >= 0.45f) {
                    maybeReflectWithLocalLanguage(label, best.confidence, sightings)
                }
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

    private fun maybeAskFromCuriosity(
        label: String,
        confidence: Float,
        sightings: Int,
        onOutcome: ((Boolean, String) -> Unit)? = null
    ): Boolean {
        if (isFinishing || isDestroyed || !::dialogueBubble.isInitialized) return false
        if (!::localLanguageBrain.isInitialized || !localLanguageBrain.isReady) return false

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

        fun publishGeneratedQuestion(questionRaw: String, result: LocalLlmResult) {
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

            result.reflectionArabic.takeIf { it.isNotBlank() }?.let(memory::rememberReflection)
            val decision = brain.integrateLanguageInsight(
                moodHint = result.moodHint,
                intentHint = result.intentHint,
                confidence = result.confidence,
                note = result.reflectionArabic,
                source = "visual_curiosity"
            )
            memory.rememberDialogue("دليل بصري حديث: " + label, question)
            react(decision, playSound = false)
            showDialogueText(question, durationMs = 7600L)
            onOutcome?.invoke(true, "generated_question_shown")
            Log.i(
                "EVE_CURIOSITY",
                "GENERATED_QUESTION label=" + label + " confidence=" + confidence +
                    " sightings=" + sightings + " interest=" + thought.interest +
                    " source=local_llm reason=" + thought.reason +
                    " trait=" + learned.curiosityBias
            )
        }

        localLanguageBrain.askFromCuriosity(
            context = localLanguageContext(),
            label = label,
            confidence = confidence,
            sightings = sightings,
            fallbackQuestion = "أنشئ سؤال فضول جديدًا ومحددًا بناءً على الأدلة البصرية الحالية والذاكرة ذات الصلة؛ لا تكرر سؤالًا جاهزًا."
        ) { outcome ->
            if (isFinishing || isDestroyed) return@askFromCuriosity
            val candidate = outcome.getOrNull()
            val generated = candidate?.replyArabic?.trim()?.takeIf(::isUsableCuriosityQuestion)
            if (generated != null && candidate != null) {
                publishGeneratedQuestion(generated, candidate)
            } else {
                val failureReason = outcome.exceptionOrNull()?.message
                    ?: "model output was not a usable contextual question"
                onOutcome?.invoke(false, failureReason.take(160))
                Log.w(
                    "EVE_CURIOSITY",
                    "GENERATED_QUESTION_UNAVAILABLE label=" + label + " reason=" + failureReason
                )
                // Do not present heuristic fallback text as if the model invented it.
                maybeShowVisibleObservationCue(label, confidence, SystemClock.elapsedRealtime())
            }
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

    /**
     * Runs an evidence-based planning tick. Perception continues on its own cadence; this loop
     * chooses whether to observe, review an activity hypothesis, reflect with the local LLM,
     * explore the current view, or wait. The planner never switches camera lenses by itself.
     */
    /** Record action outcomes and persist the bounded cognitive history on this device only. */
    private fun recordMindFeedback(item: EveMindFeedback) {
        autonomousMind.recordFeedback(item)
        getSharedPreferences("eve_relationship", MODE_PRIVATE).edit()
            .putString("autonomousMindMemory", autonomousMind.exportMemory())
            .apply()
    }

    /**
     * The local generative model chooses the next cognitive step from a constrained allow-list.
     * The rule-based mind remains a bounded feedback/memory store, not the source of every choice.
     * Every model suggestion is checked again against current live conditions before execution.
     */
    private fun runAutonomousCognition(inactiveMs: Long) {
        if (isFinishing || isDestroyed || !::brain.isInitialized || !::memory.isInitialized) return
        if (!::localLanguageBrain.isInitialized || !localLanguageBrain.isReady) {
            Log.d("EVE_COGNITION", "DECISION_SKIPPED reason=local_brain_not_ready")
            return
        }
        if (localLanguageBrain.isGenerating || !autonomyPlanningRunning.compareAndSet(false, true)) return

        val now = SystemClock.elapsedRealtime()
        if (lastAutonomyDecisionAt > 0L && now - lastAutonomyDecisionAt < autonomyDecisionCooldownMs) {
            autonomyPlanningRunning.set(false)
            return
        }
        val sceneAge = if (lastSceneObservationAt > 0L) {
            (now - lastSceneObservationAt).coerceAtLeast(0L)
        } else Long.MAX_VALUE
        val label = lastMeaningfulLabel.orEmpty()
        val labelAge = if (lastMeaningfulLabelAt > 0L) {
            (now - lastMeaningfulLabelAt).coerceAtLeast(0L)
        } else Long.MAX_VALUE
        val labelConfidence = if (labelAge in 0L..EveAutonomousMind.SCENE_FRESH_MS) {
            lastMeaningfulConfidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        } else 0f
        val faceRecent = lastFaceSeenAt > 0L && now - lastFaceSeenAt in 0L..4_500L
        val familiarPersonRecent = faceRecent &&
            lastIdentityGreetingId.isNotBlank() &&
            now - lastIdentityGreetingAt in 0L..30_000L
        val context = EveMindContext(
            nowMs = now,
            inactiveMs = inactiveMs.coerceAtLeast(0L),
            sceneAgeMs = sceneAge,
            sceneSummary = latestSceneSummary,
            latestLabel = label,
            labelConfidence = labelConfidence,
            faceSeenRecently = faceRecent,
            familiarPersonRecognized = familiarPersonRecent,
            activity = activeHumanActivity,
            activityConfidence = activeHumanActivityConfidence,
            explorationEnabled = autonomousExplorationEnabled,
            localLanguageReady = true,
            lastReflectionAtMs = lastAutonomyReflectionAt,
            cameraIsFront = activeCameraLens == CameraLens.FRONT,
            cameraSwitchAllowed = hasCameraPermission() && run {
                val switchedAt = lastAutonomyCameraSwitchAt.get()
                switchedAt <= 0L || now - switchedAt >= autonomousCameraSwitchCooldownMs
            }
        )
        val allowedActions = allowedAutonomousActions(
            context = context,
            labelAgeMs = labelAge,
            labelConfidence = labelConfidence,
            sceneAgeMs = sceneAge,
            nowElapsedMs = now
        )
        if (allowedActions.isEmpty()) {
            autonomyPlanningRunning.set(false)
            return
        }

        val preferences = getSharedPreferences("eve_relationship", MODE_PRIVATE)
        val previousGoal = preferences.getString("autonomy_last_goal", lastAutonomyGoal).orEmpty()
        val previousAction = preferences.getString("autonomy_last_action", lastAutonomyAction).orEmpty()
        val previousOutcome = preferences.getString("autonomy_last_outcome", lastAutonomyOutcome).orEmpty()
        lastAutonomyDecisionAt = now

        Log.i(
            "EVE_COGNITION",
            "CYCLE_START idleMs=$inactiveMs sceneAgeMs=$sceneAge label=$label " +
                "confidence=$labelConfidence activity=$activeHumanActivity allowed=$allowedActions"
        )
        localLanguageBrain.planAutonomousStep(
            context = localLanguageContext(),
            allowedActions = allowedActions,
            previousGoal = previousGoal,
            previousAction = previousAction,
            previousOutcome = previousOutcome,
            recentActionOutcomes = autonomousMind.recentFeedback().takeLast(6).map { item ->
                item.goal.name + "/" + item.action.name + "=" +
                    (if (item.succeeded) "success" else "failure") + ":" + item.note.take(54)
            }
        ) { result ->
            autonomyPlanningRunning.set(false)
            if (isFinishing || isDestroyed) return@planAutonomousStep

            val proposal = result.getOrNull()
            if (proposal == null) {
                updateAutonomyOutcome(
                    goal = previousGoal.ifBlank { "observe_current_evidence" },
                    action = "none",
                    outcome = "planner_generation_failed",
                    reason = result.exceptionOrNull()?.message.orEmpty().take(180)
                )
                Log.w("EVE_COGNITION", "PLAN_REJECTED reason=generation_failed", result.exceptionOrNull())
                return@planAutonomousStep
            }

            val action = proposal.planningAction.orEmpty()
            val proposedGoal = proposal.planningGoal.orEmpty().ifBlank { "understand_current_evidence" }
            val reason = proposal.planningReason.orEmpty().ifBlank { "لم يذكر النموذج سببًا واضحًا." }
            val successCriterion = proposal.successCriterion.orEmpty().ifBlank { "مراجعة الدليل الجديد عند وصوله." }

            // Recompute the allow-list after inference; face, freshness, permission, or cooldown
            // may have changed while the model was thinking.
            val liveNow = SystemClock.elapsedRealtime()
            val liveSceneAge = if (lastSceneObservationAt > 0L) {
                (liveNow - lastSceneObservationAt).coerceAtLeast(0L)
            } else Long.MAX_VALUE
            val liveLabelAge = if (lastMeaningfulLabelAt > 0L) {
                (liveNow - lastMeaningfulLabelAt).coerceAtLeast(0L)
            } else Long.MAX_VALUE
            val liveLabelConfidence = if (liveLabelAge in 0L..EveAutonomousMind.SCENE_FRESH_MS) {
                lastMeaningfulConfidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            } else 0f
            val liveFaceRecent = lastFaceSeenAt > 0L && liveNow - lastFaceSeenAt in 0L..4_500L
            val liveContext = context.copy(
                nowMs = liveNow,
                inactiveMs = (liveNow - lastDirectUserActionAt).coerceAtLeast(0L),
                sceneAgeMs = liveSceneAge,
                sceneSummary = latestSceneSummary,
                latestLabel = lastMeaningfulLabel.orEmpty(),
                labelConfidence = liveLabelConfidence,
                faceSeenRecently = liveFaceRecent,
                cameraIsFront = activeCameraLens == CameraLens.FRONT,
                cameraSwitchAllowed = hasCameraPermission() &&
                    (lastAutonomyCameraSwitchAt.get() <= 0L ||
                        liveNow - lastAutonomyCameraSwitchAt.get() >= autonomousCameraSwitchCooldownMs)
            )
            val liveAllowed = allowedAutonomousActions(
                context = liveContext,
                labelAgeMs = liveLabelAge,
                labelConfidence = liveLabelConfidence,
                sceneAgeMs = liveSceneAge,
                nowElapsedMs = liveNow
            )
            val planFieldsComplete = !proposal.planningGoal.isNullOrBlank() &&
                !proposal.planningReason.isNullOrBlank() &&
                !proposal.successCriterion.isNullOrBlank()
            if (action !in allowedActions || action !in liveAllowed || !planFieldsComplete) {
                val rejection = if (!planFieldsComplete) "incomplete_goal_reason_or_success_criterion"
                    else "not_currently_allowed"
                updateAutonomyOutcome(
                    goal = proposedGoal,
                    action = action.ifBlank { "missing_action" },
                    outcome = "rejected_$rejection",
                    reason = reason
                )
                Log.w(
                    "EVE_COGNITION",
                    "PLAN_REJECTED goal=$proposedGoal action=$action complete=$planFieldsComplete " +
                        "originallyAllowed=$allowedActions currentlyAllowed=$liveAllowed reason=$reason"
                )
                return@planAutonomousStep
            }

            lastAutonomyGoal = proposedGoal
            lastAutonomyAction = action
            lastAutonomyRationale = reason
            preferences.edit()
                .putString("autonomy_last_goal", proposedGoal.take(80))
                .putString("autonomy_last_action", action)
                .putString("autonomy_last_rationale", reason.take(180))
                .apply()
            Log.i(
                "EVE_COGNITION",
                "PLAN_ACCEPTED goal=$proposedGoal action=$action confidence=${proposal.confidence} " +
                    "reason=$reason successCriterion=$successCriterion"
            )

            when (action) {
                "observe_scene" -> {
                    val fresh = liveSceneAge in 0L..EveAutonomousMind.SCENE_FRESH_MS &&
                        latestSceneSummary.isNotBlank()
                    val outcome = if (fresh) "fresh_evidence_reviewed" else "waiting_for_fresh_evidence"
                    recordMindFeedback(
                        EveMindFeedback(
                            goal = EveMindGoal.OBSERVE_SCENE,
                            action = EveMindAction.REVIEW_RECENT_EVIDENCE,
                            succeeded = fresh,
                            note = "planner:$reason;scene_fresh=$fresh",
                            atMs = liveNow
                        )
                    )
                    updateAutonomyOutcome(proposedGoal, action, outcome, reason)
                    Log.i("EVE_COGNITION", "ACTION_RESULT action=$action outcome=$outcome")
                }
                "quiet_attention" -> {
                    recordMindFeedback(
                        EveMindFeedback(
                            goal = EveMindGoal.WAIT_QUIETLY,
                            action = EveMindAction.MAINTAIN_QUIET_ATTENTION,
                            succeeded = true,
                            note = "planner:$reason",
                            atMs = liveNow
                        )
                    )
                    updateAutonomyOutcome(proposedGoal, action, "quiet_attention_maintained", reason)
                    Log.i("EVE_COGNITION", "ACTION_RESULT action=$action outcome=quiet_attention_maintained")
                }
                "ask_curiosity_question" -> {
                    val currentLabel = lastMeaningfulLabel.orEmpty()
                    val requested = maybeAskFromCuriosity(
                        label = currentLabel,
                        confidence = liveLabelConfidence,
                        sightings = memory.sightings(currentLabel),
                        onOutcome = { succeeded, detail ->
                            recordMindFeedback(
                                EveMindFeedback(
                                    goal = EveMindGoal.OBSERVE_SCENE,
                                    action = EveMindAction.REVIEW_RECENT_EVIDENCE,
                                    succeeded = succeeded,
                                    note = "planner_question:$detail",
                                    atMs = SystemClock.elapsedRealtime()
                                )
                            )
                            updateAutonomyOutcome(
                                proposedGoal, action,
                                if (succeeded) "generated_question_shown" else "question_generation_failed",
                                detail
                            )
                        }
                    )
                    if (!requested) {
                        recordMindFeedback(
                            EveMindFeedback(
                                goal = EveMindGoal.OBSERVE_SCENE,
                                action = EveMindAction.REVIEW_RECENT_EVIDENCE,
                                succeeded = false,
                                note = "planner_question_not_eligible",
                                atMs = liveNow
                            )
                        )
                        updateAutonomyOutcome(proposedGoal, action, "question_not_eligible", reason)
                    }
                }
                "reflect_on_scene" -> {
                    val currentLabel = lastMeaningfulLabel.orEmpty()
                    val safeToReflect = currentLabel.isNotBlank() &&
                        liveLabelConfidence >= EveAutonomousMind.MIN_REFLECTION_CONFIDENCE &&
                        liveLabelAge <= EveAutonomousMind.SCENE_FRESH_MS &&
                        (lastAutonomyReflectionAt == 0L ||
                            liveNow - lastAutonomyReflectionAt >= EveAutonomousMind.REFLECTION_COOLDOWN_MS) &&
                        liveNow - lastLocalLlmVisionAt.get() >= EveAutonomousMind.REFLECTION_COOLDOWN_MS
                    if (safeToReflect) {
                        val evidenceKey = (
                            currentLabel.trim().lowercase(Locale.ROOT) + "|" +
                                latestSceneSummary.trim().take(180)
                            ).take(180)
                        maybeReflectWithLocalLanguage(
                            currentLabel,
                            liveLabelConfidence,
                            memory.sightings(currentLabel),
                            mindEvidenceKey = evidenceKey
                        )
                        updateAutonomyOutcome(proposedGoal, action, "reflection_requested", reason)
                    } else {
                        recordMindFeedback(
                            EveMindFeedback(
                                goal = EveMindGoal.REFLECT_ON_NEW_EVIDENCE,
                                action = EveMindAction.REQUEST_LOCAL_REFLECTION,
                                succeeded = false,
                                note = "planner_reflection_blocked:$reason",
                                atMs = liveNow
                            )
                        )
                        updateAutonomyOutcome(proposedGoal, action, "reflection_blocked_by_freshness_or_cooldown", reason)
                    }
                }
                "switch_to_world_camera" -> {
                    val switchedAt = lastAutonomyCameraSwitchAt.get()
                    val cooldownDone = switchedAt <= 0L ||
                        liveNow - switchedAt >= autonomousCameraSwitchCooldownMs
                    val safeToSwitch = autonomousExplorationEnabled &&
                        hasCameraPermission() &&
                        activeCameraLens == CameraLens.FRONT &&
                        !liveFaceRecent &&
                        (liveNow - lastDirectUserActionAt) >= EveAutonomousMind.AUTO_CAMERA_SWITCH_IDLE_MS &&
                        cooldownDone
                    if (safeToSwitch) {
                        lastAutonomyCameraSwitchAt.set(liveNow)
                        switchCamera()
                        main.postDelayed({
                            if (isFinishing || isDestroyed) return@postDelayed
                            val succeeded = cameraStarted && activeCameraLens == CameraLens.BACK
                            recordMindFeedback(
                                EveMindFeedback(
                                    goal = EveMindGoal.EXPLORE_CURRENT_VIEW,
                                    action = EveMindAction.SWITCH_TO_WORLD_CAMERA,
                                    succeeded = succeeded,
                                    note = "rear_camera_active=$succeeded;planner=$reason",
                                    atMs = SystemClock.elapsedRealtime()
                                )
                            )
                            updateAutonomyOutcome(
                                proposedGoal, action,
                                if (succeeded) "rear_camera_active" else "camera_switch_failed",
                                reason
                            )
                            Log.i("EVE_COGNITION", "ACTION_RESULT action=$action succeeded=$succeeded")
                        }, 1_500L)
                    } else {
                        recordMindFeedback(
                            EveMindFeedback(
                                goal = EveMindGoal.EXPLORE_CURRENT_VIEW,
                                action = EveMindAction.SWITCH_TO_WORLD_CAMERA,
                                succeeded = false,
                                note = "planner_camera_switch_blocked:$reason",
                                atMs = liveNow
                            )
                        )
                        updateAutonomyOutcome(proposedGoal, action, "camera_switch_blocked_by_safety", reason)
                    }
                }
            }
        }
    }

    private fun allowedAutonomousActions(
        context: EveMindContext,
        labelAgeMs: Long,
        labelConfidence: Float,
        sceneAgeMs: Long,
        nowElapsedMs: Long
    ): List<String> {
        val allowed = mutableListOf("observe_scene", "quiet_attention")
        val localBrainReady = ::localLanguageBrain.isInitialized && localLanguageBrain.isReady
        if (!localBrainReady) return allowed

        val label = context.latestLabel.trim()
        val labelFresh = label.isNotBlank() &&
            labelAgeMs in 0L..EveAutonomousMind.SCENE_FRESH_MS &&
            labelConfidence >= 0.72f
        val sceneFresh = sceneAgeMs in 0L..EveAutonomousMind.SCENE_FRESH_MS &&
            latestSceneSummary.isNotBlank()

        if (labelFresh && sceneFresh) {
            val prefs = getSharedPreferences("eve_curiosity", MODE_PRIVATE)
            val safeKey = label.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(48)
            if (safeKey.isNotBlank()) {
                val learned = memory.learnedPersonality()
                val thought = EveCuriosityEngine.consider(
                    label = label,
                    confidence = labelConfidence,
                    sightings = memory.sightings(label),
                    curiosityBias = learned.curiosityBias,
                    lastAnyQuestionAtMs = prefs.getLong("last_question_at", 0L),
                    lastLabelQuestionAtMs = prefs.getLong("label_question_at:" + safeKey, 0L),
                    previousQuestionCount = prefs.getInt("label_question_count:" + safeKey, 0),
                    nowMs = System.currentTimeMillis()
                )
                if (thought != null) allowed += "ask_curiosity_question"
            }
        }

        val lastReflectionWall = getSharedPreferences("eve_relationship", MODE_PRIVATE)
            .getLong("lastAutonomyReflectionWallAt", 0L)
        val reflectionCooldownDone = lastAutonomyReflectionAt == 0L ||
            nowElapsedMs - lastAutonomyReflectionAt >= EveAutonomousMind.REFLECTION_COOLDOWN_MS
        val labelIsUseful = isMeaningfulLabel(label) || isCuriosityWorthyLabel(label)
        val evidenceKey = (label.lowercase(Locale.ROOT) + "|" + latestSceneSummary.trim().take(180)).take(180)
        val lastReflectedKey = getSharedPreferences("eve_relationship", MODE_PRIVATE)
            .getString("lastAutonomyReflectionKey", "").orEmpty()
        val lastReflectionWallCooldownDone = lastReflectionWall == 0L ||
            System.currentTimeMillis() - lastReflectionWall >= EveAutonomousMind.REFLECTION_COOLDOWN_MS
        if (labelFresh && sceneFresh && labelConfidence >= EveAutonomousMind.MIN_REFLECTION_CONFIDENCE &&
            labelIsUseful && reflectionCooldownDone && lastReflectionWallCooldownDone &&
            evidenceKey != lastReflectedKey &&
            nowElapsedMs - lastLocalLlmVisionAt.get() >= EveAutonomousMind.REFLECTION_COOLDOWN_MS
        ) {
            allowed += "reflect_on_scene"
        }

        val cooldownAt = lastAutonomyCameraSwitchAt.get()
        val cameraCooldownDone = cooldownAt <= 0L ||
            nowElapsedMs - cooldownAt >= autonomousCameraSwitchCooldownMs
        val faceStillVisible = lastFaceSeenAt > 0L && nowElapsedMs - lastFaceSeenAt in 0L..4_500L
        val safeCameraSwitch = autonomousExplorationEnabled &&
            context.explorationEnabled &&
            context.cameraIsFront &&
            context.cameraSwitchAllowed &&
            hasCameraPermission() &&
            !faceStillVisible &&
            context.inactiveMs >= EveAutonomousMind.AUTO_CAMERA_SWITCH_IDLE_MS &&
            cameraCooldownDone
        if (safeCameraSwitch) allowed += "switch_to_world_camera"
        return allowed
    }

    private fun updateAutonomyOutcome(goal: String, action: String, outcome: String, reason: String) {
        lastAutonomyGoal = goal.take(80)
        lastAutonomyAction = action.take(40)
        lastAutonomyOutcome = outcome.take(180)
        lastAutonomyRationale = reason.take(180)
        getSharedPreferences("eve_relationship", MODE_PRIVATE).edit()
            .putString("autonomy_last_goal", lastAutonomyGoal)
            .putString("autonomy_last_action", lastAutonomyAction)
            .putString("autonomy_last_outcome", lastAutonomyOutcome)
            .putString("autonomy_last_rationale", lastAutonomyRationale)
            .apply()
        Log.i(
            "EVE_COGNITION",
            "LEARNING_FEEDBACK goal=$lastAutonomyGoal action=$lastAutonomyAction " +
                "outcome=$lastAutonomyOutcome reason=$lastAutonomyRationale"
        )
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
            },
            calendarContext = EveCalendarKnowledge.todaySummary(
                System.currentTimeMillis(), currentCalendarHemisphere()
            )
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
    private fun maybeReflectWithLocalLanguage(
        label: String,
        confidence: Float,
        sightings: Int,
        mindEvidenceKey: String? = null
    ) {
        if (!::localLanguageBrain.isInitialized || !localLanguageBrain.isReady) return
        if (!confidence.isFinite() || confidence < 0.84f) return
        if (!isMeaningfulLabel(label) && !isCuriosityWorthyLabel(label)) return
        val now = SystemClock.elapsedRealtime()
        val previous = lastLocalLlmVisionAt.get()
        if (now - previous < 90_000L) return
        if (!lastLocalLlmVisionAt.compareAndSet(previous, now)) return
        lastLocalLlmVisionLabel = label
        lastAutonomyReflectionAt = now

        localLanguageBrain.reflectOnObservation(localLanguageContext()) { outcome ->
            if (isFinishing || isDestroyed) return@reflectOnObservation
            val insight = outcome.getOrNull() ?: run {
                mindEvidenceKey?.let { key ->
                    recordMindFeedback(
                        EveMindFeedback(
                            EveMindGoal.REFLECT_ON_NEW_EVIDENCE,
                            EveMindAction.REQUEST_LOCAL_REFLECTION,
                            succeeded = false,
                            note = key,
                            atMs = SystemClock.elapsedRealtime()
                        )
                    )
                }
                Log.w("EVE_LLM", "VISION_REFLECTION_FAILED label=$label", outcome.exceptionOrNull())
                return@reflectOnObservation
            }
            // Discard stale reasoning if a clearly different scene has replaced the evidence.
            if (lastMeaningfulLabel != label ||
                SystemClock.elapsedRealtime() - lastMeaningfulLabelAt !in 0L..90_000L
            ) {
                mindEvidenceKey?.let { key ->
                    recordMindFeedback(
                        EveMindFeedback(
                            EveMindGoal.REFLECT_ON_NEW_EVIDENCE,
                            EveMindAction.REQUEST_LOCAL_REFLECTION,
                            succeeded = false,
                            note = key,
                            atMs = SystemClock.elapsedRealtime()
                        )
                    )
                }
                Log.i("EVE_LLM", "VISION_REFLECTION_DROPPED reason=stale_context label=$label")
                return@reflectOnObservation
            }
            val reflection = insight.reflectionArabic.ifBlank { insight.replyArabic }.trim().take(240)
            if (reflection.isNotBlank()) memory.rememberReflection(reflection)
            mindEvidenceKey?.let { key ->
                val reflectionSucceeded = insight.confidence >= 0.45f && reflection.isNotBlank()
                recordMindFeedback(
                    EveMindFeedback(
                        EveMindGoal.REFLECT_ON_NEW_EVIDENCE,
                        EveMindAction.REQUEST_LOCAL_REFLECTION,
                        succeeded = reflectionSucceeded,
                        note = key,
                        atMs = SystemClock.elapsedRealtime()
                    )
                )
                if (reflectionSucceeded) {
                    getSharedPreferences("eve_relationship", MODE_PRIVATE).edit()
                        .putString("lastAutonomyReflectionKey", key)
                        .putLong("lastAutonomyReflectionWallAt", System.currentTimeMillis())
                        .apply()
                }
            }
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

    private fun initializeRobotBaseLink() {
        if (::robotBaseLink.isInitialized) return
        robotBaseLink = EveBleRobotBase(applicationContext, object : EveBleRobotBase.Listener {
            override fun onStateChanged(state: EveBaseLinkState, detail: String) {
                EveEmbodiedGameGate.publishConnectionState(this@MainActivity, state, detail)
                Log.i("EVE_BASE", "BASE_LINK state=$state detail=$detail")
                if (state == EveBaseLinkState.SCANNING) {
                    // A reconnect can change odometry frames. Save first, then wait for the
                    // next pose to confirm whether the previous home-map frame can be reused.
                    persistHomeMapIfAligned(force = true)
                    homeMapMemory.reset()
                    savedHomeMapState = homeMapStore.load() ?: savedHomeMapState
                    homeMapFrameDecisionMade = false
                    homeMapPersistenceEnabled = false
                    lastHomeMapPersistenceSaveAt = 0L
                    lastHomeMapDiagnosticSignature = ""
                    lastBaseAnnouncedRoom = EveRoomType.UNKNOWN
                    warnedAboutNonPersistentHomeFrame = false
                }
                when (state) {
                    EveBaseLinkState.READY -> {
                        showDialogueText(
                            "اتصل جزء الحركة من جسدي وأصبح ضمن نظامي الحسي والحركي. سأفحص الحساسات والموقع أولًا؛ الاتصال وحده لا يبدأ الحركة.",
                            durationMs = 7000L
                        )
                        if (baseAutonomyRequested) startBaseControlLoop()
                    }
                    EveBaseLinkState.ERROR -> {
                        baseAutonomyRequested = false
                        main.removeCallbacks(baseControlLoop)
                        robotBaseNavigator.resetAvoidance()
                        showDialogueText("$detail تعطّل اتصال جزء الحركة من جسدي، لذلك ستبقى المحركات متوقفة.", durationMs = 8000L)
                    }
                    EveBaseLinkState.DISCONNECTED -> {
                        if (baseAutonomyRequested) {
                            baseAutonomyRequested = false
                            main.removeCallbacks(baseControlLoop)
                            robotBaseNavigator.resetAvoidance()
                        }
                        showDialogueText("$detail انقطع جزء الحركة من جسدي؛ يجب أن توقف اللوحة المحركات أيضًا عند فقد الاتصال.", durationMs = 7500L)
                    }
                    EveBaseLinkState.SCANNING,
                    EveBaseLinkState.CONNECTING,
                    EveBaseLinkState.CONFIGURING,
                    EveBaseLinkState.WAITING_FOR_BLUETOOTH -> {
                        showDialogueText(detail, durationMs = 6500L)
                    }
                }
            }

            override fun onPose(pose: EveRobotBaseProtocol.Pose) {
                // Keep the body-game gate current without exposing pose data to the game UI.
                EveEmbodiedGameGate.publishPose(this@MainActivity, pose)
                // Pose samples are generated by the base's fused wheel-encoder/IMU odometry.
                // Keep raw camera pixels out of the metric map.
                Log.d("EVE_BASE", "BASE_POSE xMm=${pose.xMm} yMm=${pose.yMm} heading=${pose.headingDegrees}")
            }

            override fun onTelemetry(telemetry: EveRobotBaseProtocol.Telemetry) {
                // The gate never moves the base; it only checks whether body games may open.
                EveEmbodiedGameGate.publishTelemetry(this@MainActivity, telemetry)
                val now = SystemClock.elapsedRealtime()
                val possibleDrop =
                    telemetry.leftCliffDistanceMm == null ||
                        telemetry.rightCliffDistanceMm == null ||
                        (telemetry.leftCliffDistanceMm ?: Int.MAX_VALUE) >= EveRobotBaseProtocol.CLIFF_EDGE_DISTANCE_MM ||
                        (telemetry.rightCliffDistanceMm ?: Int.MAX_VALUE) >= EveRobotBaseProtocol.CLIFF_EDGE_DISTANCE_MM
                val hardFault = telemetry.emergencyStopPressed || telemetry.baseFault
                if (baseAutonomyRequested && (possibleDrop || hardFault)) {
                    robotBaseNavigator.decide(
                        telemetry = telemetry,
                        nowMs = now,
                        autonomyEnabled = true,
                        personInPath = true
                    )
                    robotBaseLink.send(
                        EveRobotBaseProtocol.MotorCommand(
                            type = EveRobotBaseProtocol.CommandType.STOP,
                            reason = if (possibleDrop) "drop sensor safety stop" else "hardware fault stop"
                        )
                    )
                    if (now - lastBaseSafetyNoticeAt >= baseSafetyNoticeCooldownMs) {
                        lastBaseSafetyNoticeAt = now
                        showDialogueText(
                            if (possibleDrop) {
                                "رصدت احتمال حافة أو فقدت حساس الحافة. أوقفت الحركة حتى يتم فحص القاعدة واستعادة الأمان."
                            } else {
                                "زر إيقاف الطوارئ أو عطل القاعدة فعّال. الحركة ممنوعة حتى إصلاح السبب."
                            },
                            durationMs = 8000L
                        )
                    }
                }
            }
        })
    }

    private fun requiredRobotBasePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            // Android 8–11 require foreground location permission for BLE scanning.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasRobotBasePermissions(): Boolean =
        requiredRobotBasePermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestRobotBaseConnection(enableAutonomy: Boolean) {
        if (!::robotBaseLink.isInitialized) {
            showDialogueText("لم تجهز إضافة القاعدة بعد. أعيدي فتح التطبيق.", durationMs = 6000L)
            return
        }
        if (enableAutonomy) {
            baseAutonomyRequested = true
        } else {
            stopRobotBaseAutonomy("اتصال فقط: لم أفعّل حركة العجلات.")
        }

        if (!hasRobotBasePermissions()) {
            pendingBaseAutonomyAfterPermission = enableAutonomy
            baseBluetoothPermissionRequest.launch(requiredRobotBasePermissions())
            return
        }
        pendingBaseAutonomyAfterPermission = false
        if (robotBaseLink.state == EveBaseLinkState.READY) {
            if (enableAutonomy) {
                startBaseControlLoop()
            } else {
                showDialogueText("قاعدة إيف متصلة، لكن العجلات متوقفة. قولي «استكشفي المنزل بالقاعدة» لطلب الحركة.", durationMs = 7000L)
            }
            return
        }
        robotBaseLink.startScan()
    }

    private fun startBaseControlLoop() {
        if (isFinishing || isDestroyed || !baseAutonomyRequested || !::robotBaseLink.isInitialized) return
        if (robotBaseLink.state != EveBaseLinkState.READY) return
        if (!hasCameraPermission()) {
            robotBaseLink.stopMotors()
            showDialogueText("لا أستطيع استكشاف المنزل بالقاعدة دون الكاميرا. أوقفت الحركة.", durationMs = 7000L)
            return
        }
        // A phone mounted as the robot's head should use its rear/world camera as the forward view.
        if (activeCameraLens != CameraLens.BACK) switchCamera()
        main.removeCallbacks(baseControlLoop)
        main.postDelayed(baseControlLoop, 700L)
        Log.i("EVE_BASE", "BASE_AUTONOMY_REQUESTED camera=BACK pendingFreshSensors=true")
    }

    private fun stopRobotBaseAutonomy(message: String) {
        baseAutonomyRequested = false
        pendingBaseAutonomyAfterPermission = false
        main.removeCallbacks(baseControlLoop)
        robotBaseNavigator.resetAvoidance()
        if (::robotBaseLink.isInitialized) robotBaseLink.stopMotors()
        if (message.isNotBlank()) showDialogueText(message, durationMs = 6500L)
        Log.i("EVE_BASE", "BASE_AUTONOMY_STOPPED")
    }

    private fun robotBasePersonInPath(now: Long): Boolean {
        val freshFace = lastFaceSeenAt > 0L && now - lastFaceSeenAt in 0L..1_500L
        val freshObjectFrame = lastObjectSceneAt > 0L && now - lastObjectSceneAt in 0L..2_200L
        val personLabels = setOf("person", "people", "human", "man", "woman", "child", "boy", "girl")
        val visiblePersonBox = freshObjectFrame &&
            latestObjectLabels.any { EveSceneGeometry.normalizeLabel(it) in personLabels }
        return freshFace || visiblePersonBox
    }


    /**
     * A saved grid is safe to merge only if the base explicitly guarantees a persistent home
     * coordinate frame. Otherwise keep this run's map isolated and leave the previous saved map
     * untouched. This flag is supplied by firmware in pose-frame byte 14 bit 1.
     */
    private fun prepareHomeMapFrame(pose: EveRobotBaseProtocol.Pose) {
        if (homeMapFrameDecisionMade) {
            if (homeMapPersistenceEnabled && !pose.persistentHomeFrame) {
                homeMapMemory.reset()
                homeMapPersistenceEnabled = false
                Log.e("EVE_MAP", "Persistent home-frame guarantee was withdrawn; cleared active map and disabled saving.")
                showDialogueText("تغيّر إطار إحداثيات القاعدة؛ مسحت الخريطة النشطة ولن أدمجها بالخريطة المحفوظة.", durationMs = 7000L)
            }
            return
        }
        homeMapFrameDecisionMade = true
        homeMapPersistenceEnabled = pose.persistentHomeFrame
        if (pose.persistentHomeFrame) {
            val saved = savedHomeMapState
            if (saved != null && !homeMapMemory.restoreState(saved)) {
                Log.w("EVE_MAP", "Stored map rejected due to incompatible or invalid state; starting a new local map.")
                homeMapMemory.reset()
                savedHomeMapState = null
            } else if (saved != null) {
                Log.i("EVE_MAP", "PERSISTED_HOME_MAP restored cells=${saved.cells.size} routePoints=${saved.routePoints.size}")
                showDialogueText("تذكّرت خريطة المنزل ومسارات سابقة. سأحدّثها فقط ببيانات الموقع والحساسات الآمنة.", durationMs = 6500L)
            } else {
                Log.i("EVE_MAP", "Persistent home frame confirmed; recording a new private home map.")
            }
            warnedAboutNonPersistentHomeFrame = false
        } else {
            // The stored map may belong to another coordinate origin. Never use it to steer.
            homeMapMemory.reset()
            if (savedHomeMapState != null && !warnedAboutNonPersistentHomeFrame) {
                warnedAboutNonPersistentHomeFrame = true
                showDialogueText("هذه القاعدة لا تؤكد إطار إحداثيات منزليًا ثابتًا؛ لن أستخدم الخريطة القديمة للحركة ولن أستبدلها.", durationMs = 7500L)
            }
            Log.w("EVE_MAP", "Pose lacks persistent-home-frame flag; mapping is session-only and will not overwrite saved map.")
        }
    }

    private fun persistHomeMapIfAligned(force: Boolean) {
        if (!homeMapPersistenceEnabled || !::homeMapStore.isInitialized) return
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!force && nowElapsed - lastHomeMapPersistenceSaveAt < HOME_MAP_SAVE_INTERVAL_MS) return
        val state = homeMapMemory.exportState(System.currentTimeMillis())
        if (homeMapStore.save(state)) {
            savedHomeMapState = state
            lastHomeMapPersistenceSaveAt = nowElapsed
            Log.d("EVE_MAP", "PERSISTED_HOME_MAP saved cells=${state.cells.size} routePoints=${state.routePoints.size}")
        } else {
            Log.w("EVE_MAP", "PERSISTED_HOME_MAP save skipped because state failed bounds checks.")
        }
    }

    private fun runBaseControlTick() {
        if (!baseAutonomyRequested || !::robotBaseLink.isInitialized) return
        if (robotBaseLink.state != EveBaseLinkState.READY) {
            // The board firmware watchdog must brake if GATT disappears while the phone is unable to write STOP.
            return
        }
        val now = SystemClock.elapsedRealtime()
        val cameraReady = cameraStarted && activeCameraLens == CameraLens.BACK
        val telemetry = robotBaseLink.latestTelemetry
        val pose = robotBaseLink.latestPose
        val currentPoseFresh = pose != null && pose.isFresh(now)
        val telemetryFresh = telemetry != null && telemetry.isFresh(now)
        val decision = when {
            !cameraReady ->
                EveBaseMotionDecision(0, 0, 0, "rear camera is not ready", false)
            !currentPoseFresh ->
                EveBaseMotionDecision(0, 0, 0, "odometry pose is missing or stale", false)
            !telemetryFresh ->
                EveBaseMotionDecision(0, 0, 0, "base sensors are missing or stale", false)
            else -> {
                val activePose = pose!!
                prepareHomeMapFrame(activePose)
                val freshRoom = if (
                    lastRoomObservationAt > 0L && now - lastRoomObservationAt in 0L..roomSummaryTtlMs
                ) latestRoomType else EveRoomType.UNKNOWN
                val mapSnapshot = homeMapMemory.observe(
                    pose = activePose,
                    telemetry = telemetry!!,
                    room = freshRoom,
                    nowMs = now
                )
                if (mapSnapshot == null) {
                    EveBaseMotionDecision(0, 0, 0, "home map rejected stale or unsafe base data", false)
                } else {
                    val frontierHeading = homeMapMemory.bestFrontierHeadingDegrees(activePose, now)
                    val mapSignature = "${mapSnapshot.knownCells}|${mapSnapshot.obstacleCells}|" +
                        "${mapSnapshot.knownRooms.sortedBy { it.name }.joinToString(",")}|${mapSnapshot.currentRoom}"
                    if (homeMapPersistenceEnabled && now - lastHomeMapPersistenceSaveAt >= HOME_MAP_SAVE_INTERVAL_MS) {
                        persistHomeMapIfAligned(force = false)
                    }
                    if (mapSignature != lastHomeMapDiagnosticSignature) {
                        lastHomeMapDiagnosticSignature = mapSignature
                        Log.i(
                            "EVE_MAP",
                            "HOME_MAP cells=${mapSnapshot.knownCells} free=${mapSnapshot.freeCells} " +
                                "obstacles=${mapSnapshot.obstacleCells} namedRooms=${mapSnapshot.knownRooms} " +
                                "routePoints=${mapSnapshot.routePointCount} persistent=$homeMapPersistenceEnabled " +
                                "currentRoom=${mapSnapshot.currentRoom} frontierHeading=${frontierHeading ?: "none"} " +
                                "frame=home_odometry"
                        )
                    }
                    if (
                        mapSnapshot.currentRoom != EveRoomType.UNKNOWN &&
                        mapSnapshot.currentRoom != lastBaseAnnouncedRoom
                    ) {
                        lastBaseAnnouncedRoom = mapSnapshot.currentRoom
                        val roomName = when (mapSnapshot.currentRoom) {
                            EveRoomType.KITCHEN -> "المطبخ"
                            EveRoomType.BEDROOM -> "غرفة النوم"
                            EveRoomType.BATHROOM -> "الحمّام"
                            EveRoomType.LIVING_ROOM -> "غرفة المعيشة"
                            EveRoomType.DINING_AREA -> "منطقة الطعام"
                            EveRoomType.OFFICE -> "المكتب"
                            EveRoomType.HALLWAY -> "الممر"
                            EveRoomType.UNKNOWN -> "منطقة غير معروفة"
                        }
                        showDialogueText(
                            "تبدو هذه المنطقة $roomName من الأدلة البصرية؛ أضفتها إلى خريطتي المحلية كاحتمال، وليس كحقيقة مؤكدة.",
                            durationMs = 6000L
                        )
                        Log.i(
                            "EVE_MAP",
                            "ROOM_TRANSITION room=${mapSnapshot.currentRoom} knownRooms=${mapSnapshot.knownRooms} confidence=visual_hypothesis"
                        )
                    }
                    if (frontierHeading == null) {
                        // The map must never fall back to blind forward motion when it has no
                        // reachable, unmapped frontier to target.
                        EveBaseMotionDecision(0, 0, 0, "no safe unexplored frontier; stop and wait", false)
                    } else {
                        robotBaseNavigator.decide(
                            telemetry = telemetry,
                            nowMs = now,
                            autonomyEnabled = baseAutonomyRequested,
                            personInPath = robotBasePersonInPath(now),
                            preferredHeadingDegrees = frontierHeading,
                            currentHeadingDegrees = activePose.headingDegrees
                        )
                    }
                }
            }
        }
        val commandType = if (decision.movingAllowed) {
            EveRobotBaseProtocol.CommandType.DRIVE
        } else {
            EveRobotBaseProtocol.CommandType.STOP
        }
        robotBaseLink.send(
            EveRobotBaseProtocol.MotorCommand(
                type = commandType,
                leftPercent = decision.leftPercent,
                rightPercent = decision.rightPercent,
                leaseMs = decision.leaseMs,
                reason = decision.reason
            )
        )
        if (decision.reason != lastBaseDecisionReason) {
            lastBaseDecisionReason = decision.reason
            Log.i(
                "EVE_BASE",
                "BASE_NAV action=$commandType left=${decision.leftPercent} right=${decision.rightPercent} " +
                    "reason=${decision.reason} telemetryAgeMs=${robotBaseLink.latestTelemetry?.let { now - it.receivedAtMs } ?: -1L}"
            )
        }
        val safetyWorthy = !decision.movingAllowed && (
            decision.reason.contains("cliff") ||
                decision.reason.contains("safety latch") ||
                decision.reason.contains("emergency") ||
                decision.reason.contains("fault") ||
                decision.reason.contains("bumper") ||
                decision.reason.contains("person detected") ||
                decision.reason.contains("sensor unavailable")
            )
        if (safetyWorthy && now - lastBaseSafetyNoticeAt >= baseSafetyNoticeCooldownMs) {
            lastBaseSafetyNoticeAt = now
            showDialogueText(
                "أوقفت القاعدة احترازيًا: ${decision.reason}. سأنتظر بيانات آمنة وحديثة.",
                durationMs = 7000L
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
        // Trace the offline perception -> decision -> animated-face path for on-device verification.
        // Camera pixels and frames are never logged or uploaded by this diagnostic.
        Log.i(
            "EVE_PIPELINE",
            "DECISION_TO_FACE mood=${decision.mood} intent=${decision.intent} " +
                "reason=${decision.reason.take(120)} sceneAgeMs=" +
                (if (lastSceneObservationAt > 0L) (SystemClock.elapsedRealtime() - lastSceneObservationAt).coerceAtLeast(0L) else -1L) +
                " label=${lastMeaningfulLabel.orEmpty().take(48)} confidence=${"%.2f".format(Locale.ROOT, lastMeaningfulConfidence)}"
        )
        if (playSound) voice.speakMood(decision.mood)
        Log.i("EVE", "BEHAVIOR mood=${decision.mood} reason=${decision.reason}")
    }

    override fun onDestroy() {
        persistHomeMapIfAligned(force = true)
        main.removeCallbacks(idleLoop)
        main.removeCallbacks(baseControlLoop)
        if (::robotBaseLink.isInitialized) robotBaseLink.close()
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
                try { poseLandmarker?.close() } catch (_: Exception) { }
                localObjectDetector = null
                localImageClassifier = null
                poseLandmarker = null
                objectDetectorReady = false
                imageClassifierReady = false
                poseLandmarkerReady = false
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
