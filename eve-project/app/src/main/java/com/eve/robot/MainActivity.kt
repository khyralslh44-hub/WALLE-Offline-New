package com.eve.robot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
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
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * EVE face experience: camera labeling and behavior operate locally while the activity is open.
 * No network permission is declared. The user can deny the camera; the animated face still works.
 */
class MainActivity : ComponentActivity() {
    private lateinit var face: RobotFaceView
    private lateinit var brain: RobotBrain
    private lateinit var memory: EveMemory
    private lateinit var voice: RobotVoice
    private val main = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val lastAnalysis = AtomicLong(0L)
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
    @Volatile private var lastFaceSeenAt = 0L
    private var lastFaceReactionAt = 0L
    private var lastInteractionAt = SystemClock.elapsedRealtime()
    private var idleCount = 0
    private var touchDownAt = 0L

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            face.setMood(EveMood.CONCERNED)
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
                face.setExpression(decision, System.currentTimeMillis() xor Random.nextLong())
                Log.i("EVE", "AUTONOMOUS mood=${decision.mood} reason=${decision.reason} inactiveMs=$inactive")
            }
            main.postDelayed(this, 5200L + Random.nextLong(2200L))
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
        setContentView(face)
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
                    val longPress = SystemClock.uptimeMillis() - touchDownAt >= 550L
                    lastInteractionAt = SystemClock.elapsedRealtime()
                    react(brain.touched(longPress), longPress)
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }

        face.setMood(EveMood.AWAKE)
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
        cameraProvider?.unbindAll()
        cameraStarted = false
        super.onPause()
    }

    private fun faceOrNull(): RobotFaceView? = if (::face.isInitialized) face else null

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (!hasCameraPermission() || cameraStarted || isFinishing) return
        cameraStarted = true
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(320, 240))
                    .build()
                analysis.setAnalyzer(cameraExecutor, ::analyzeFrame)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                Log.i("EVE", "Offline camera analysis started.")
            } catch (e: Exception) {
                cameraStarted = false
                face.setMood(EveMood.CONCERNED)
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
        val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        // Run both bundled, on-device vision models on the same frame, then release ImageProxy.
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
                // Once a real face is present, don't let a generic texture label override its cues.
                if (elapsed - lastFaceSeenAt < 8000L && !isMeaningfulLabel(label)) {
                    Log.i("EVE", "VISION generic label suppressed during face interaction: $label")
                    return@addOnSuccessListener
                }
                lastLabelAt[label] = elapsed
                val sightings = memory.remember(label)
                val decision = brain.observe(label, best.confidence, sightings)
                Log.i("EVE", "VISION label=$label confidence=${best.confidence} sightings=$sightings mood=${decision.mood}")
                // Generic classifier labels should never trigger a voice cue.
                react(decision, decision.playSound && isMeaningfulLabel(label))
            }
            .addOnFailureListener(mainExecutor) { error ->
                Log.w("EVE", "Offline image labeling frame failed.", error)
            }

        faceTask
            .addOnSuccessListener(mainExecutor) { faces ->
                Log.i("EVE", "FACE_SCAN count=${faces.size}")
                val bestFace = faces.maxByOrNull {
                    it.boundingBox.width().toLong() * it.boundingBox.height().toLong()
                } ?: return@addOnSuccessListener
                val elapsed = SystemClock.elapsedRealtime()
                lastFaceSeenAt = elapsed
                if (elapsed - lastFaceReactionAt < 6500L) return@addOnSuccessListener
                lastFaceReactionAt = elapsed
                val decision = brain.observeHumanFace(
                    smilingProbability = bestFace.smilingProbability,
                    leftEyeOpenProbability = bestFace.leftEyeOpenProbability,
                    rightEyeOpenProbability = bestFace.rightEyeOpenProbability,
                    headYawDegrees = bestFace.headEulerAngleY
                )
                Log.i(
                    "EVE",
                    "FACE_CUE mood=${decision.mood} smile=${bestFace.smilingProbability} " +
                        "leftEye=${bestFace.leftEyeOpenProbability} rightEye=${bestFace.rightEyeOpenProbability} " +
                        "yaw=${bestFace.headEulerAngleY}"
                )
                react(decision, decision.playSound)
            }
            .addOnFailureListener(mainExecutor) { error ->
                Log.w("EVE", "Offline face analysis frame failed.", error)
            }

        Tasks.whenAllComplete(labelsTask, faceTask)
            .addOnCompleteListener { proxy.close() }
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
        face.setExpression(decision, System.currentTimeMillis() xor Random.nextLong())
        if (playSound) voice.speakMood(decision.mood)
        Log.i("EVE", "BEHAVIOR mood=${decision.mood} reason=${decision.reason}")
    }

    override fun onDestroy() {
        main.removeCallbacks(idleLoop)
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        try { labeler.close() } catch (_: Exception) { }
        try { faceDetector.close() } catch (_: Exception) { }
        cameraExecutor.shutdown()
        if (::voice.isInitialized) voice.close()
        super.onDestroy()
    }
}
