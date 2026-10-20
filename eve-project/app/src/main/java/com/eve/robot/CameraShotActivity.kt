package com.eve.robot

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Offline augmented-camera arcade mode. Every shot is a drawn screen effect only:
 * it never controls a physical device, captures a photo, or transmits camera data.
 */
class CameraShotActivity : ComponentActivity() {
    internal lateinit var previewView: PreviewView
        private set
    internal lateinit var shotOverlay: CameraShotOverlay
        private set
    internal lateinit var cameraMessage: TextView
        private set

    private var cameraProvider: ProcessCameraProvider? = null
    private var activeLens = Lens.BACK
    private val isRpsMode: Boolean
        get() = intent.getStringExtra("eve_camera_game") == "rps"

    private val rpsHandler = Handler(Looper.getMainLooper())
    private val rpsWorker = Executors.newSingleThreadExecutor()
    private val rpsInferenceRunning = AtomicBoolean(false)
    @Volatile private var rpsRecognizer: GestureRecognizer? = null
    internal var gestureModelReadyForTest = false
        private set
    private var rpsSamplingActive = false
    private var rpsFace: RobotFaceView? = null
    private var rpsBrain: RobotBrain? = null
    private var rpsVoice: RobotVoice? = null
    private var rpsPlayerScore = 0
    private var rpsEveScore = 0
    private var rpsRound = 0
    private val rpsHistory = mutableListOf<RpsMove>()
    private var rpsCandidate: RpsMove? = null
    private var rpsCandidateFrames = 0
    private var rpsBlankFrames = 0
    private var rpsArmed = true
    private var rpsLastPlayedMove: RpsMove? = null
    private var rpsLastRoundAt = 0L
    private var rpsStatusLine = "ارفع يدك: قبضة = حجر، كف مفتوح = ورقة، علامة النصر = مقص."
    private var rpsLastGeneratedAt = 0L
    private var rpsGenerationSerial = 0L

    private val rpsSampleRunnable = Runnable { sampleRpsPreview() }

    private enum class Lens { BACK, FRONT }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCameraPreview()
        else cameraMessage.text = "يلزم إذن الكاميرا لعرض المشهد. ارجع ثم اسمح بالكاميرا من إعدادات التطبيق."
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

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            contentDescription = "عرض الكاميرا المباشر"
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        cameraMessage = TextView(this).apply {
            text = if (isRpsMode) {
                "وضع حجر وورقة ومقص بالكاميرا: قبضة = حجر • كف = ورقة • إصبعان = مقص"
            } else {
                "لمسة واحدة: أطلق على الهدف المتحرك • لمستان: بدّل الكاميرا\nجارٍ تشغيل الكاميرا…"
            }
            textSize = 14f
            setTextColor(Color.rgb(182, 250, 255))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.argb(178, 2, 10, 15))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        if (isRpsMode) {
            cameraMessage.setOnClickListener { toggleCameraLens() }
            rpsBrain = RobotBrain(EveMemory(applicationContext).learnedPersonality())
            rpsVoice = RobotVoice()
            rpsHistory.addAll(
                getSharedPreferences("eve_games", MODE_PRIVATE)
                    .getString("rps:move_history", "").orEmpty()
                    .split(',').mapNotNull { raw ->
                        raw.toIntOrNull()?.let { RpsMove.values().getOrNull(it) }
                    }.takeLast(24)
            )
            val face = RobotFaceView(this).apply {
                isClickable = false
                contentDescription = "EVE hand-gesture RPS reaction face"
                setExpression(requireNotNull(rpsBrain).awakenExperience(), SystemClock.uptimeMillis())
                startAnimation()
            }
            rpsFace = face
            root.addView(
                face,
                FrameLayout.LayoutParams(dp(124), dp(86), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = dp(20)
                }
            )
            initializeRpsRecognizer()
        } else {
            shotOverlay = CameraShotOverlay(
                this,
                onDoubleTap = { toggleCameraLens() },
                onShotResult = { hit, shotCount, hitCount ->
                    if (!isFinishing && !isDestroyed) {
                        val lens = if (activeLens == Lens.FRONT) "الأمامية" else "الخلفية"
                        cameraMessage.text = "الكاميرا $lens • التصويب $hitCount/$shotCount إصابة" +
                            if (hit) " • إصابة دقيقة!" else " • أخطأت الهدف؛ حرّك التصويب."
                    }
                }
            )
            root.addView(shotOverlay, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(cameraMessage, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCameraPreview()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        rpsSamplingActive = isRpsMode
        if (isRpsMode) {
            rpsFace?.startAnimation()
            rpsHandler.removeCallbacks(rpsSampleRunnable)
            rpsHandler.postDelayed(rpsSampleRunnable, 500L)
        }
    }

    override fun onStop() {
        rpsSamplingActive = false
        rpsHandler.removeCallbacks(rpsSampleRunnable)
        rpsFace?.stopAnimation()
        super.onStop()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun selector(lens: Lens): CameraSelector =
        if (lens == Lens.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA

    private fun startCameraPreview() {
        if (isFinishing || isDestroyed) return
        cameraMessage.text = "جارٍ تشغيل الكاميرا…"
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val initialLens = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    Lens.BACK
                } else {
                    Lens.FRONT
                }
                bindLens(provider, initialLens)
            } catch (error: Exception) {
                cameraMessage.text = "تعذّر تشغيل الكاميرا. تحقق من الإذن أو جرّب الكاميرا الأخرى."
                android.util.Log.e("EVE_CAMERA_SHOTS", "Unable to start offline camera preview", error)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindLens(provider: ProcessCameraProvider, lens: Lens) {
        val cameraSelector = selector(lens)
        check(provider.hasCamera(cameraSelector)) { "Requested camera lens is unavailable: $lens" }
        val previewUseCase = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        provider.unbindAll()
        provider.bindToLifecycle(this, cameraSelector, previewUseCase)
        activeLens = lens
        cameraMessage.text = hintForActiveLens()
        android.util.Log.i("EVE_CAMERA_SHOTS", "CAMERA_PREVIEW active=$activeLens mode=offline")
    }

    private fun hintForActiveLens(): String {
        val lensName = if (activeLens == Lens.FRONT) "الأمامية" else "الخلفية"
        return if (isRpsMode) {
            "الكاميرا " + lensName + " • " + rpsStatusLine + " • اضغط هنا لتبديل العدسة"
        } else {
            "الكاميرا $lensName • صوّب على الهدف المتحرك بلمسة • إصابات ${shotOverlay.shotHitsForTest}/${shotOverlay.shotCountForTest} • لمستان لتبديل العدسة"
        }
    }

    private fun toggleCameraLens() {
        val provider = cameraProvider ?: run {
            cameraMessage.text = "انتظر حتى تعمل الكاميرا ثم جرّب التبديل."
            return
        }
        val previous = activeLens
        val next = if (previous == Lens.BACK) Lens.FRONT else Lens.BACK
        try {
            bindLens(provider, next)
            if (::shotOverlay.isInitialized) {
                shotOverlay.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            } else {
                cameraMessage.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
        } catch (error: Exception) {
            try {
                bindLens(provider, previous)
            } catch (restoreError: Exception) {
                android.util.Log.e("EVE_CAMERA_SHOTS", "Unable to restore camera preview", restoreError)
            }
            cameraMessage.text = "لم تتوفر العدسة الأخرى على هذا الجهاز."
            android.util.Log.w("EVE_CAMERA_SHOTS", "Camera switch failed target=$next", error)
        }
    }

    private fun initializeRpsRecognizer() {
        try {
            rpsWorker.execute {
                try {
                    val options = GestureRecognizer.GestureRecognizerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                        .setRunningMode(RunningMode.IMAGE)
                        .setNumHands(1)
                        .build()
                    rpsRecognizer = GestureRecognizer.createFromOptions(applicationContext, options)
                    gestureModelReadyForTest = true
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            rpsStatusLine = "جاهزة للرصد: قبضة حجر • كف ورقة • إصبعان مقص"
                            cameraMessage.text = hintForActiveLens()
                            android.util.Log.i("EVE_CAMERA_RPS", "OFFLINE_GESTURE_MODEL_READY")
                        }
                    }
                } catch (error: Exception) {
                    android.util.Log.e("EVE_CAMERA_RPS", "OFFLINE_GESTURE_MODEL_FAILED", error)
                    runOnUiThread {
                        rpsStatusLine = "تعذّر تحميل نموذج إشارات اليد المحلي؛ استخدم وضع اللمس."
                        cameraMessage.text = hintForActiveLens()
                    }
                }
            }
        } catch (error: Exception) {
            android.util.Log.e("EVE_CAMERA_RPS", "Could not queue gesture recognizer setup.", error)
        }
    }

    private fun sampleRpsPreview() {
        if (!rpsSamplingActive || isFinishing || isDestroyed || !isRpsMode) return
        val recognizer = rpsRecognizer
        if (recognizer == null || !rpsInferenceRunning.compareAndSet(false, true)) {
            rpsHandler.postDelayed(rpsSampleRunnable, 450L)
            return
        }
        val original = try { previewView.bitmap } catch (_: Exception) { null }
        if (original == null) {
            rpsInferenceRunning.set(false)
            rpsHandler.postDelayed(rpsSampleRunnable, 450L)
            return
        }
        val maxDimension = max(original.width, original.height)
        val scaled = if (maxDimension > 480) {
            val ratio = 480f / maxDimension
            Bitmap.createScaledBitmap(
                original,
                (original.width * ratio).toInt().coerceAtLeast(1),
                (original.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else original
        try {
            rpsWorker.execute {
                try {
                    val result = recognizer.recognize(BitmapImageBuilder(scaled).build())
                    val category = result.gestures().firstOrNull()?.firstOrNull()
                    val label = category?.categoryName()
                    val confidence = category?.score() ?: 0f
                    val move = RpsGestureLogic.fromGesture(label, confidence)
                    rpsHandler.post {
                        if (rpsSamplingActive && !isFinishing && !isDestroyed) {
                            handleRpsGestureSample(move, label, confidence)
                        }
                    }
                } catch (error: Exception) {
                    android.util.Log.w("EVE_CAMERA_RPS", "One offline hand-gesture frame failed.", error)
                } finally {
                    if (scaled !== original) try { scaled.recycle() } catch (_: Exception) { }
                    try { original.recycle() } catch (_: Exception) { }
                    rpsInferenceRunning.set(false)
                    rpsHandler.postDelayed(rpsSampleRunnable, 420L)
                }
            }
        } catch (error: Exception) {
            if (scaled !== original) try { scaled.recycle() } catch (_: Exception) { }
            try { original.recycle() } catch (_: Exception) { }
            rpsInferenceRunning.set(false)
            android.util.Log.e("EVE_CAMERA_RPS", "Could not queue one RPS frame.", error)
            rpsHandler.postDelayed(rpsSampleRunnable, 650L)
        }
    }

    private fun handleRpsGestureSample(move: RpsMove?, label: String?, confidence: Float) {
        if (move == null) {
            rpsBlankFrames++
            rpsCandidate = null
            rpsCandidateFrames = 0
            if (rpsBlankFrames >= 2) {
                rpsArmed = true
                rpsLastPlayedMove = null
                rpsStatusLine = "ارفع يدك بوضوح: قبضة، كف مفتوح، أو إصبعان للنصر."
                cameraMessage.text = hintForActiveLens()
            }
            return
        }
        rpsBlankFrames = 0
        if (move == rpsCandidate) rpsCandidateFrames++ else {
            rpsCandidate = move
            rpsCandidateFrames = 1
        }
        rpsStatusLine = "أرى " + move.arabicName + " (" + (confidence * 100).toInt() + "%)… أثبّت الإشارة."
        cameraMessage.text = hintForActiveLens()
        val stable = rpsCandidateFrames >= 3
        val changedGesture = move != rpsLastPlayedMove
        val cooldownPassed = SystemClock.elapsedRealtime() - rpsLastRoundAt >= 1_500L
        if (stable && (rpsArmed || changedGesture) && cooldownPassed) {
            rpsArmed = false
            rpsLastPlayedMove = move
            rpsLastRoundAt = SystemClock.elapsedRealtime()
            playCameraRpsRound(move, label ?: "unknown", confidence)
        }
    }

    private fun playCameraRpsRound(player: RpsMove, label: String, confidence: Float) {
        val strategyRoll = kotlin.random.Random.nextInt(100)
        val eve = RpsGestureLogic.chooseEveMove(rpsHistory, strategyRoll, kotlin.random.Random.nextInt(3))
        val outcome = RpsGestureLogic.outcome(player, eve)
        rpsRound++
        when (outcome) {
            RpsOutcome.PLAYER -> rpsPlayerScore++
            RpsOutcome.EVE -> rpsEveScore++
            RpsOutcome.DRAW -> Unit
        }
        rpsHistory.add(player)
        while (rpsHistory.size > 24) rpsHistory.removeAt(0)
        getSharedPreferences("eve_games", MODE_PRIVATE).edit()
            .putString("rps:move_history", rpsHistory.joinToString(",") { it.ordinal.toString() }).apply()
        val resultKey = when (outcome) {
            RpsOutcome.PLAYER -> "player"
            RpsOutcome.EVE -> "robot"
            RpsOutcome.DRAW -> "draw"
        }
        EveNotificationScheduler.recordGameResult(this, "rps", resultKey)
        val brain = rpsBrain
        val decision = when (outcome) {
            RpsOutcome.PLAYER -> brain?.gameOutcome("rps", "miss")
            RpsOutcome.EVE -> brain?.gameOutcome("rps", "robot")
            RpsOutcome.DRAW -> brain?.gameOutcome("rps", "draw")
        }
        if (decision != null) rpsFace?.setExpression(decision, SystemClock.uptimeMillis())
        rpsVoice?.playGameCue(
            when (outcome) {
                RpsOutcome.PLAYER -> "game_loss"
                RpsOutcome.EVE -> "game_win"
                RpsOutcome.DRAW -> "game_draw"
            }
        )
        rpsStatusLine = "جولة $rpsRound: أنت " + player.arabicName + " • إيف " + eve.arabicName + " • " +
            when (outcome) {
                RpsOutcome.PLAYER -> "نقطة لك"
                RpsOutcome.EVE -> "نقطة لي"
                RpsOutcome.DRAW -> "تعادل"
            } + " • النتيجة $rpsPlayerScore–$rpsEveScore. أبعد يدك ثم ارفع الإشارة التالية."
        cameraMessage.text = hintForActiveLens()
        android.util.Log.i(
            "EVE_CAMERA_RPS",
            "RPS_ROUND_PASS round=$rpsRound label=$label confidence=$confidence player=$player eve=$eve outcome=$outcome score=$rpsPlayerScore-$rpsEveScore"
        )
        requestGeneratedCameraRpsComment(outcome, player, eve, decision)
    }

    private fun requestGeneratedCameraRpsComment(
        outcome: RpsOutcome,
        player: RpsMove,
        eve: RpsMove,
        decision: EveDecision?
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - rpsLastGeneratedAt < 5_000L) return
        val model = LocalLanguageBrain.currentInstance() ?: return
        if (!model.isReady) return
        rpsLastGeneratedAt = now
        val serial = ++rpsGenerationSerial
        val outcomeText = when (outcome) {
            RpsOutcome.PLAYER -> "player"
            RpsOutcome.EVE -> "robot"
            RpsOutcome.DRAW -> "draw"
        }
        val detail = "حجر وورقة ومقص عبر الكاميرا؛ الجولة $rpsRound؛ إشارة اللاعب " + player.arabicName +
            "؛ اختيار إيف " + eve.arabicName + "؛ النتيجة $outcomeText؛ الإجمالي " +
            "$rpsPlayerScore-$rpsEveScore. ابتكر تعليقًا عربيًا مرتبطًا بهذه الجولة."
        val memory = EveMemory(applicationContext)
        val context = LocalLlmContext(
            currentMood = decision?.mood?.name ?: EveMood.CURIOUS.name,
            moodReason = decision?.reason.orEmpty(),
            personality = memory.learnedPersonality(),
            recentDialogues = memory.recentDialogues(3),
            recentReflections = memory.recentReflections(2)
        )
        model.commentOnGame(
            gameName = "rps_camera",
            outcome = outcomeText,
            detail = detail,
            playerScore = rpsPlayerScore,
            eveScore = rpsEveScore,
            round = rpsRound,
            difficulty = 2,
            recentResults = emptyList(),
            context = context
        ) { result ->
            if (isFinishing || isDestroyed || !isRpsMode || serial != rpsGenerationSerial) return@commentOnGame
            val generated = result.getOrNull() ?: return@commentOnGame
            val reply = generated.replyArabic.trim().take(220)
            if (reply.isBlank()) return@commentOnGame
            rpsStatusLine = reply
            generated.moodHint?.let { mood ->
                val generatedDecision = rpsBrain?.integrateLanguageInsight(
                    moodHint = mood,
                    intentHint = generated.intentHint,
                    confidence = generated.confidence,
                    note = generated.reflectionArabic,
                    source = "rps_game"
                )
                if (generatedDecision != null) rpsFace?.setExpression(generatedDecision, SystemClock.uptimeMillis())
            }
            cameraMessage.text = hintForActiveLens()
        }
    }

    override fun onDestroy() {
        rpsSamplingActive = false
        rpsHandler.removeCallbacksAndMessages(null)
        if (::shotOverlay.isInitialized) shotOverlay.cancelPendingTouch()
        rpsFace?.stopAnimation()
        cameraProvider?.unbindAll()
        cameraProvider = null
        try {
            rpsWorker.execute {
                try { rpsRecognizer?.close() } catch (_: Exception) { }
                rpsRecognizer = null
            }
            rpsWorker.shutdown()
        } catch (_: Exception) {
            try { rpsWorker.shutdownNow() } catch (_: Exception) { }
        }
        rpsVoice?.close()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}

/** Touch-controlled AR shooting with a moving virtual target, real hit/miss scoring, and offline effects. */
internal class CameraShotOverlay(
    context: android.content.Context,
    private val onDoubleTap: () -> Unit,
    private val onShotResult: (hit: Boolean, shotCount: Int, hitCount: Int) -> Unit
) : View(context) {
    private data class Shot(
        val startX: Float,
        val startY: Float,
        val targetX: Float,
        val targetY: Float,
        val hit: Boolean,
        val startedAt: Long
    )

    private val handler = Handler(Looper.getMainLooper())
    private val shots = mutableListOf<Shot>()
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var pendingShot: Runnable? = null
    private var targetX = 0f
    private var targetY = 0f
    private var hasTarget = false
    private var muzzleFlashAt = 0L

    internal var shotCountForTest = 0
        private set
    internal var shotHitsForTest = 0
        private set
    internal var shotMissesForTest = 0
        private set
    internal var cameraSwitchCountForTest = 0
        private set

    internal val targetCenterForTest: Pair<Float, Float>
        get() = movingTargetAt(SystemClock.uptimeMillis())

    private fun movingTargetAt(now: Long): Pair<Float, Float> {
        if (width <= 0 || height <= 0) return width * 0.5f to height * 0.46f
        val phaseX = sin(now / 1_100.0).toFloat()
        val phaseY = sin(now / 1_700.0 + 0.85).toFloat()
        val x = width * 0.5f + phaseX * width * 0.24f
        val y = (height * 0.45f + phaseY * height * 0.12f)
            .coerceIn(dp(105f), height * 0.77f)
        return x.coerceIn(width * 0.12f, width * 0.88f) to y
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = "لمسة لإطلاق شرارة افتراضية؛ لمستان للتبديل بين الكاميرتين"
    }

    internal fun fireAt(x: Float, y: Float) {
        if (width <= 0 || height <= 0) return
        val now = SystemClock.uptimeMillis()
        val safeX = x.coerceIn(width * 0.04f, width * 0.96f)
        val safeY = y.coerceIn(dp(84).toFloat(), height * 0.94f)
        val (movingX, movingY) = movingTargetAt(now)
        val dx = safeX - movingX
        val dy = safeY - movingY
        val hit = dx * dx + dy * dy <= dp(52f) * dp(52f)
        targetX = safeX
        targetY = safeY
        hasTarget = true
        val startX = width * 0.5f
        val startY = height * 0.91f
        shots.add(Shot(startX, startY, safeX, safeY, hit, now))
        shotCountForTest++
        if (hit) shotHitsForTest++ else shotMissesForTest++
        muzzleFlashAt = now
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onShotResult(hit, shotCountForTest, shotHitsForTest)
        invalidate()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    /** Preserve fractional stroke widths for Canvas paints (e.g. 1.5dp). */
    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val x = event.x
                val y = event.y
                val doubleTapDistance = dp(72).toFloat()
                val dx = x - lastTapX
                val dy = y - lastTapY
                val isDoubleTap = lastTapAt > 0L &&
                    now - lastTapAt <= DOUBLE_TAP_WINDOW_MS &&
                    dx * dx + dy * dy <= doubleTapDistance * doubleTapDistance

                if (isDoubleTap) {
                    pendingShot?.let(handler::removeCallbacks)
                    pendingShot = null
                    lastTapAt = 0L
                    cameraSwitchCountForTest++
                    onDoubleTap()
                    return true
                }

                pendingShot?.let(handler::removeCallbacks)
                lastTapAt = now
                lastTapX = x
                lastTapY = y
                val action = Runnable {
                    pendingShot = null
                    lastTapAt = 0L
                    fireAt(x, y)
                }
                pendingShot = action
                handler.postDelayed(action, DOUBLE_TAP_WINDOW_MS + 40L)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPendingTouch()
                return true
            }
        }
        return true
    }

    internal fun cancelPendingTouch() {
        pendingShot?.let(handler::removeCallbacks)
        pendingShot = null
        lastTapAt = 0L
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        shots.removeAll { now - it.startedAt > FLIGHT_MS + IMPACT_MS }

        val (movingX, movingY) = movingTargetAt(now)
        drawMovingTarget(canvas, movingX, movingY, now)
        if (hasTarget) drawReticle(canvas, targetX, targetY, now)
        for (shot in shots) drawShot(canvas, shot, now)

        if (now - muzzleFlashAt in 0L..90L) drawMuzzleFlash(canvas, width * 0.5f, height * 0.91f, now - muzzleFlashAt)
        // The target moves continuously; keep the overlay alive while the activity is visible.
        postInvalidateOnAnimation()
    }

    private fun drawMovingTarget(canvas: Canvas, x: Float, y: Float, now: Long) {
        val pulse = 1f + 0.08f * sin(now / 140.0).toFloat()
        val radius = dp(25f) * pulse
        fill.color = Color.argb(54, 44, 246, 210)
        canvas.drawCircle(x, y, radius * 1.35f, fill)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3f)
        paint.color = Color.rgb(56, 255, 205)
        canvas.drawCircle(x, y, radius, paint)
        paint.strokeWidth = dp(1.5f)
        paint.color = Color.argb(220, 199, 255, 241)
        canvas.drawCircle(x, y, radius * 0.52f, paint)
        fill.color = Color.rgb(56, 255, 205)
        canvas.drawCircle(x, y, dp(4f), fill)
        val arm = radius * 1.75f
        val gap = radius * 1.12f
        paint.strokeWidth = dp(2f)
        canvas.drawLine(x - arm, y, x - gap, y, paint)
        canvas.drawLine(x + gap, y, x + arm, y, paint)
        canvas.drawLine(x, y - arm, x, y - gap, paint)
        canvas.drawLine(x, y + gap, x, y + arm, paint)
    }

    private fun drawReticle(canvas: Canvas, x: Float, y: Float, now: Long) {
        val pulse = 1f + 0.05f * kotlin.math.sin(now / 180.0).toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = Color.argb(105, 70, 238, 245)
        canvas.drawCircle(x, y, dp(13).toFloat() * pulse, paint)
        canvas.drawCircle(x, y, dp(4).toFloat(), paint)
        val arm = dp(20).toFloat()
        val gap = dp(7).toFloat()
        canvas.drawLine(x - arm, y, x - gap, y, paint)
        canvas.drawLine(x + gap, y, x + arm, y, paint)
        canvas.drawLine(x, y - arm, x, y - gap, paint)
        canvas.drawLine(x, y + gap, x, y + arm, paint)
    }

    private fun drawShot(canvas: Canvas, shot: Shot, now: Long) {
        val age = (now - shot.startedAt).coerceAtLeast(0L)
        val dx = shot.targetX - shot.startX
        val dy = shot.targetY - shot.startY
        if (age < FLIGHT_MS) {
            val raw = age.toFloat() / FLIGHT_MS
            val p = raw * raw * (3f - 2f * raw)
            val x = shot.startX + dx * p
            val y = shot.startY + dy * p
            val tailP = max(0f, p - 0.16f)
            val tailX = shot.startX + dx * tailP
            val tailY = shot.startY + dy * tailP

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(14).toFloat()
            val core = if (shot.hit) Color.rgb(60, 255, 170) else Color.rgb(255, 111, 22)
            val pale = if (shot.hit) Color.rgb(205, 255, 225) else Color.rgb(255, 242, 150)
            paint.color = Color.argb(32, 255, 80, 12)
            canvas.drawLine(tailX, tailY, x, y, paint)
            paint.strokeWidth = dp(7).toFloat()
            paint.color = if (shot.hit) Color.argb(70, 30, 210, 135) else Color.argb(130, 255, 95, 20)
            canvas.drawLine(tailX, tailY, x, y, paint)
            paint.strokeWidth = dp(2.5f)
            paint.color = pale
            canvas.drawLine(tailX, tailY, x, y, paint)

            fill.color = Color.argb(78, 255, 55, 10)
            canvas.drawCircle(x, y, dp(13).toFloat(), fill)
            fill.color = core
            canvas.drawCircle(x, y, dp(7).toFloat(), fill)
            fill.color = pale
            canvas.drawCircle(x, y, dp(3).toFloat(), fill)
        } else {
            val impactAge = age - FLIGHT_MS
            val p = (impactAge.toFloat() / IMPACT_MS).coerceIn(0f, 1f)
            val radius = dp(7).toFloat() + dp(34).toFloat() * p
            val alpha = (190f * (1f - p)).toInt().coerceIn(0, 190)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2.5f) * (1f - p * 0.45f)
            val impactColor = if (shot.hit) Color.rgb(48, 255, 171) else Color.rgb(255, 137, 37)
            paint.color = Color.argb(alpha, Color.red(impactColor), Color.green(impactColor), Color.blue(impactColor))
            canvas.drawCircle(shot.targetX, shot.targetY, radius, paint)
            paint.strokeWidth = dp(1.5f)
            paint.color = Color.argb((alpha * 0.75f).toInt(), if (shot.hit) 190 else 255, 232, if (shot.hit) 220 else 100)
            canvas.drawCircle(shot.targetX, shot.targetY, radius * 0.52f, paint)

            for (i in 0 until 8) {
                val angle = i * (Math.PI / 4.0)
                val travel = dp(9).toFloat() + dp(24).toFloat() * p
                val sx = shot.targetX + cos(angle).toFloat() * travel
                val sy = shot.targetY + sin(angle).toFloat() * travel
                val ex = sx + cos(angle).toFloat() * dp(9).toFloat() * (1f - p)
                val ey = sy + sin(angle).toFloat() * dp(9).toFloat() * (1f - p)
                paint.strokeWidth = dp(2).toFloat()
                paint.color = Color.argb(alpha, 255, if (i % 2 == 0) 160 else 220, 52)
                canvas.drawLine(sx, sy, ex, ey, paint)
            }
            fill.color = Color.argb((alpha * 0.55f).toInt(), 255, 105, 25)
            canvas.drawCircle(shot.targetX, shot.targetY, dp(7).toFloat() * (1f - p), fill)
        }
    }

    private fun drawMuzzleFlash(canvas: Canvas, x: Float, y: Float, age: Long) {
        val fade = (1f - age.toFloat() / 90f).coerceIn(0f, 1f)
        fill.color = Color.argb((130f * fade).toInt(), 255, 90, 15)
        canvas.drawCircle(x, y, dp(22).toFloat() * fade, fill)
        fill.color = Color.argb((220f * fade).toInt(), 255, 227, 110)
        canvas.drawCircle(x, y, dp(8).toFloat() * fade, fill)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        for (i in 0 until 8) {
            val angle = i * (Math.PI / 4.0)
            val inner = dp(10).toFloat() * fade
            val outer = dp(24).toFloat() * fade
            canvas.drawLine(
                x + cos(angle).toFloat() * inner,
                y + sin(angle).toFloat() * inner,
                x + cos(angle).toFloat() * outer,
                y + sin(angle).toFloat() * outer,
                paint.apply { color = Color.argb((190f * fade).toInt(), 255, 154, 40) }
            )
        }
    }

    companion object {
        private const val DOUBLE_TAP_WINDOW_MS = 330L
        private const val FLIGHT_MS = 245L
        private const val IMPACT_MS = 500L
    }
}
