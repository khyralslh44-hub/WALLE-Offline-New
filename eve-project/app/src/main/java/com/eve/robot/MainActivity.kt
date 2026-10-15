package com.eve.robot

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * EVE face experience: camera labeling and behavior operate locally while the activity is open.
 * No network permission is declared. The user can deny the camera; the animated face still works.
 */
class MainActivity : ComponentActivity() {
    private lateinit var face: RobotFaceView
    private lateinit var brain: RobotBrain
    private lateinit var memory: EveMemory
    private lateinit var voice: RobotVoice
    private lateinit var cameraStatus: TextView
    private val main = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val emotionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
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
    private var activeCameraLens = CameraLens.BACK
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
            event.eventTime - dispatchLastTapUptime in 0L..900L &&
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
            showCameraStatus("الكاميرا متوقفة: لم يُمنح إذن الكاميرا", active = false)
            react(brain.cameraUnavailable("permission declined"), playSound = false)
            Log.i("EVE", "Camera permission declined; face remains active.")
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
            textSize = 13f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(0xCC07151C.toInt())
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0x6637F5A1)
            }
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
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
        }
        setContentView(root)
        brain = RobotBrain()
        memory = EveMemory(applicationContext)
        voice = RobotVoice()

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
                        releasedAt - lastTapUptime in 0L..800L &&
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
                        react(brain.touched(longPress, lastInteractionAt), longPress)
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
        if (hasCameraPermission()) startCamera()
        else permissionRequest.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        if (::face.isInitialized) {
            face.startAnimation()
            main.removeCallbacks(idleLoop)
            main.postDelayed(idleLoop, 5200L)
        }
        if (::face.isInitialized && hasCameraPermission() && !cameraStarted) startCamera()
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

    private fun showCameraStatus(message: String, active: Boolean) {
        if (!::cameraStatus.isInitialized) return
        cameraStatus.text = if (active) "●  $message  •  انقر مرتين للتبديل" else "●  $message"
        cameraStatus.setTextColor(if (active) 0xFF8FFFC0.toInt() else 0xFFFFD180.toInt())
        cameraStatus.background = GradientDrawable().apply {
            setColor(0xCC07151C.toInt())
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), if (active) 0x8837F5A1.toInt() else 0x887D8B92.toInt())
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
            .setTargetResolution(Size(320, 240))
            .build()
        analysis.setAnalyzer(cameraExecutor, ::analyzeFrame)

        provider.unbindAll()
        provider.bindToLifecycle(this, selector, analysis)
        activeCameraLens = lens
        cameraStarted = true
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
        if (now - previous < 1500L || !lastAnalysis.compareAndSet(previous, now)) {
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
        val frameBitmap = if (emotionClassifier.isReady && !ferInferenceRunning.get()) {
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
                if (elapsed - (lastLabelAt[label] ?: 0L) < 9000L) return@addOnSuccessListener
                if (elapsed - lastFaceSeenAt < 8000L && !isMeaningfulLabel(label)) {
                    Log.i("EVE", "VISION generic label suppressed during face interaction: $label")
                    return@addOnSuccessListener
                }
                lastLabelAt[label] = elapsed
                val sightings = memory.remember(label)
                if (best.confidence >= 0.80f && isMeaningfulLabel(label)) {
                    lastMeaningfulLabel = label
                    lastMeaningfulLabelAt = elapsed
                }
                val affinity = memory.learnedAffinity(label)
                val decision = brain.observe(label, best.confidence, sightings, affinity)
                Log.i(
                    "EVE",
                    "VISION label=$label confidence=${best.confidence} sightings=$sightings " +
                        "affinity=$affinity mood=${decision.mood}"
                )
                react(decision, decision.playSound && isMeaningfulLabel(label))
            }
            .addOnFailureListener(mainExecutor) { error ->
                Log.w("EVE", "Offline image labeling frame failed.", error)
            }

        // Crop faces on the camera executor, then run TensorFlow Lite on its own single thread.
        // A 2-of-3 temporal vote prevents a single blink/blurred frame from changing the character.
        val pendingFrameConsumers = AtomicLong(2L)
        fun finishFrameConsumer() {
            if (pendingFrameConsumers.decrementAndGet() == 0L) {
                try { frameBitmap?.recycle() } catch (_: Exception) { }
                try { proxy.close() } catch (_: Exception) { }
            }
        }

        // Release the frame only after image labels are done and the face listener has finished
        // cropping the image. Inference then owns its independent crop bitmap.
        labelsTask.addOnCompleteListener(mainExecutor) { finishFrameConsumer() }

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
                                                    if (SystemClock.elapsedRealtime() - lastFaceReactionAt < 6500L) return@post
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
        if (now - lastFaceReactionAt < 6500L) return
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

    private fun isMeaningfulLabel(label: String): Boolean =
        listOf(
            "food", "fruit", "banana", "apple", "pizza", "bread", "meal", "sandwich",
            "vegetable", "cake", "orange", "animal", "cat", "dog", "bird", "pet",
            "puppy", "kitten", "horse", "rabbit", "fish", "person", "people", "human",
            "face", "man", "woman", "child", "boy", "girl", "plant", "flower", "tree",
            "leaf", "grass", "garden"
        ).any(label::contains)

    private fun react(decision: EveDecision, playSound: Boolean) {
        // Perception/touch/camera events restart the idle clock; idle decisions do not restart themselves.
        if (!decision.reason.startsWith("contextual idle:")) {
            lastInteractionAt = SystemClock.elapsedRealtime()
        }
        face.setExpression(decision, System.currentTimeMillis())
        if (playSound) voice.speakMood(decision.mood)
        Log.i("EVE", "BEHAVIOR mood=${decision.mood} reason=${decision.reason}")
    }

    override fun onDestroy() {
        main.removeCallbacks(idleLoop)
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
        if (::voice.isInitialized) voice.close()
        super.onDestroy()
    }
}
