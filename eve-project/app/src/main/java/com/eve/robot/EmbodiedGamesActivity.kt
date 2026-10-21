package com.eve.robot

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Camera-only embodied mini-games.
 *
 * These modes require a ready compatible body plus fresh safety telemetry and odometry.
 * This first implementation deliberately leaves the wheels stopped: face presence is a
 * visual cue, not a localisation system, and the game must not move a real base while its
 * search/target-following behaviour has not been validated on physical hardware.
 */
class EmbodiedGamesActivity : ComponentActivity() {
    private enum class Mode { HIDE_AND_SEEK, REACTION }
    private enum class Phase { IDLE, COUNTDOWN, SEARCHING, HIDDEN, WAITING, SIGNAL, RESULT }

    private lateinit var root: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val random = Random(SystemClock.elapsedRealtime())
    private var mode = Mode.HIDE_AND_SEEK
    private var phase = Phase.IDLE
    private var previewView: PreviewView? = null
    private var statusView: TextView? = null
    private var gameProvider: ProcessCameraProvider? = null
    private var cameraAnalysis: ImageAnalysis? = null
    private var detector: FaceDetector? = null
    private var cameraExecutor: ExecutorService? = null
    @Volatile private var visibleFaceCount = 0
    private var countdownRemaining = 5
    private var searchingStartedAt = 0L
    private var firstNoFaceAt = 0L
    private var signalAt = 0L
    private var roundTask: Runnable? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCameraGame(mode)
        else showMessage("تحتاج لعبة الغميضة إلى إذن الكاميرا. لم ألتقط أي صورة ولم أحفظ أي وجه.", showBack = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(20))
            setBackgroundColor(Color.rgb(3, 8, 12))
        }
        setContentView(root)
        val readiness = EveEmbodiedGameGate.evaluate(this)
        if (readiness.allowed) showMenu() else showLocked(readiness)
    }

    override fun onResume() {
        super.onResume()
        val readiness = EveEmbodiedGameGate.evaluate(this)
        if (!readiness.allowed && detector != null) {
            stopCamera()
            showLocked(readiness)
        }
    }

    override fun onPause() {
        roundTask?.let(handler::removeCallbacks)
        roundTask = null
        stopCamera()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopCamera()
        detector?.close()
        detector = null
        cameraExecutor?.shutdown()
        cameraExecutor = null
        super.onDestroy()
    }

    @Deprecated("Compatibility with older Android navigation")
    override fun onBackPressed() {
        stopCamera()
        finish()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun text(value: String, size: Float = 16f, color: Int = 0xFFE7F8F9.toInt(), bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

    private fun button(label: String, action: () -> Unit, accent: Boolean = false) =
        Button(this).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            minHeight = dp(48)
            setTextColor(if (accent) Color.rgb(3, 8, 12) else 0xFFE7F8F9.toInt())
            setBackgroundColor(if (accent) Color.rgb(20, 215, 230) else Color.rgb(19, 42, 50))
            setOnClickListener { action() }
        }

    private fun addButton(label: String, action: () -> Unit, accent: Boolean = false) {
        root.addView(button(label, action, accent), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })
    }

    private fun header(title: String, subtitle: String) {
        root.addView(text("E V E  •  ألعاب الجسد", 12f, 0xFF14D7E6.toInt(), true))
        root.addView(text(title, 24f, 0xFFE7F8F9.toInt(), true))
        root.addView(text(subtitle, 14f, 0xFF8CB2B9.toInt()))
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(6)))
    }

    private fun showLocked(readiness: EveEmbodiedGameReadiness) {
        root.removeAllViews()
        header("الجسد غير جاهز للعب", readiness.reasonArabic)
        root.addView(text(
            "لن أبدأ اللعبة إذا كان الاتصال غير ثابت، أو كانت بيانات الحساسات/الموقع قديمة، " +
                "أو كان أحد حساسي الحافة غير آمن. هذه اللعبة لا تحرّك العجلات.",
            15f, 0xFFFFB158.toInt()
        ))
        if (readiness.batteryPercent >= 0) {
            root.addView(text("شحن القاعدة: ${readiness.batteryPercent}%", 13f, 0xFF8CB2B9.toInt()))
        }
        addButton("إعادة فحص اتصال الجسد", {
            val latest = EveEmbodiedGameGate.evaluate(this)
            if (latest.allowed) showMenu() else showLocked(latest)
        }, true)
        addButton("العودة إلى قائمة الألعاب", { finish() })
    }

    private fun showMenu() {
        val readiness = EveEmbodiedGameGate.evaluate(this)
        if (!readiness.allowed) {
            showLocked(readiness)
            return
        }
        stopCamera()
        root.removeAllViews()
        header("اختر لعبة", "متصل بالجسد • الشروط الحسية آمنة • معالجة الكاميرا محلية")
        root.addView(text("الغميضة البصرية", 19f, 0xFF14D7E6.toInt(), true))
        root.addView(text("تغلق إيف عينيها خمس ثوانٍ، ثم تلاحظ اختفاء الوجه وعودته إلى مجال الكاميرا.", 14f))
        addButton("🙈 بدء الغميضة", {
            mode = Mode.HIDE_AND_SEEK
            requestCameraOrStart()
        }, true)
        root.addView(text("تحدّي الظهور السريع", 19f, 0xFF14D7E6.toInt(), true))
        root.addView(text("انتظر الإشارة ثم أظهر وجهك أمام الكاميرا؛ لا تُحسب المحاولة المبكرة.", 14f))
        addButton("⚡ بدء تحدّي الظهور", {
            mode = Mode.REACTION
            requestCameraOrStart()
        })
        root.addView(text(
            "ملاحظة أمان: هذه النسخة تكتشف وجود وجه داخل الإطار فقط ولا تحدد هوية الشخص أو عمره. " +
                "لا تُحفَظ الصور، وتبقى المحركات متوقفة؛ البحث بالحركة يحتاج اختبارًا مستقلًا على قاعدة حقيقية.",
            12f, 0xFF8CB2B9.toInt()
        ))
        addButton("العودة إلى قائمة الألعاب", { finish() })
    }

    private fun showMessage(message: String, showBack: Boolean = false) {
        stopCamera()
        root.removeAllViews()
        header("إيف تقول", message)
        if (showBack) addButton("العودة إلى ألعاب الجسد", { showMenu() }, true)
        addButton("العودة إلى قائمة الألعاب", { finish() })
    }

    private fun requestCameraOrStart() {
        val readiness = EveEmbodiedGameGate.evaluate(this)
        if (!readiness.allowed) {
            showLocked(readiness)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCameraGame(mode)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraGame(requestedMode: Mode) {
        val readiness = EveEmbodiedGameGate.evaluate(this)
        if (!readiness.allowed) {
            showLocked(readiness)
            return
        }
        mode = requestedMode
        phase = Phase.IDLE
        visibleFaceCount = 0
        firstNoFaceAt = 0L
        searchingStartedAt = 0L
        signalAt = 0L
        root.removeAllViews()
        header(
            if (mode == Mode.HIDE_AND_SEEK) "الغميضة" else "تحدّي الظهور",
            "الكاميرا الخلفية • اكتشاف وجه فقط • دون حفظ صور"
        )
        val preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
        previewView = preview
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(260)))
        statusView = text("أجهّز الكاميرا…", 17f, 0xFF14D7E6.toInt(), true)
        root.addView(statusView)
        if (mode == Mode.HIDE_AND_SEEK) {
            addButton("ابدئي العدّ حتى 5", { startHideCountdown() }, true)
        } else {
            addButton("ابدئي جولة سرعة الظهور", { scheduleReactionSignal() }, true)
        }
        addButton("العودة إلى ألعاب الجسد", { showMenu() })
        setupFaceDetector()
        openCamera(preview)
        startSafetyWatch()
    }

    private fun setupFaceDetector() {
        if (detector != null) return
        detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .enableTracking()
                .build()
        )
    }

    private fun openCamera(previewView: PreviewView) {
        cameraExecutor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val readiness = EveEmbodiedGameGate.evaluate(this)
            if (!readiness.allowed) {
                showLocked(readiness)
                return@addListener
            }
            try {
                val provider = future.get()
                gameProvider = provider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage == null) {
                        imageProxy.close()
                    } else {
                        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        val task = detector?.process(input)
                        if (task == null) {
                            imageProxy.close()
                        } else {
                            task.addOnSuccessListener { faces ->
                                visibleFaceCount = faces.size
                            }.addOnFailureListener {
                                visibleFaceCount = 0
                            }.addOnCompleteListener {
                                imageProxy.close()
                            }
                        }
                    }
                }
                cameraAnalysis = analysis
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                updateStatus("الكاميرا جاهزة. ابدئي الجولة عندما تكونين مستعدة.")
            } catch (error: Exception) {
                showMessage("تعذّر تشغيل الكاميرا: ${error.javaClass.simpleName}. لم أبدأ اللعبة.", showBack = true)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startSafetyWatch() {
        roundTask?.let(handler::removeCallbacks)
        val task = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed || detector == null) return
                val readiness = EveEmbodiedGameGate.evaluate(this@EmbodiedGamesActivity)
                if (!readiness.allowed) {
                    stopCamera()
                    showLocked(readiness)
                    return
                }
                tickGame()
                handler.postDelayed(this, 180L)
            }
        }
        roundTask = task
        handler.postDelayed(task, 180L)
    }

    private fun startHideCountdown() {
        val readiness = EveEmbodiedGameGate.evaluate(this)
        if (!readiness.allowed) {
            showLocked(readiness)
            return
        }
        phase = Phase.COUNTDOWN
        countdownRemaining = 5
        updateStatus("سأغلق عينيّ… ${countdownRemaining}")
    }

    private fun scheduleReactionSignal() {
        val readiness = EveEmbodiedGameGate.evaluate(this)
        if (!readiness.allowed) {
            showLocked(readiness)
            return
        }
        phase = Phase.WAITING
        signalAt = 0L
        updateStatus("انتظري الإشارة؛ ظهور الوجه الآن يُعدّ بداية مبكرة!")
        val delay = random.nextLong(1_800L, 4_200L)
        handler.postDelayed({
            if (!isFinishing && phase == Phase.WAITING) {
                signalAt = SystemClock.elapsedRealtime()
                phase = Phase.SIGNAL
                updateStatus("الآن! أظهري وجهك في مجال الكاميرا.")
            }
        }, delay)
    }

    private fun tickGame() {
        val now = SystemClock.elapsedRealtime()
        when (phase) {
            Phase.COUNTDOWN -> {
                countdownRemaining--
                if (countdownRemaining > 0) {
                    updateStatus("سأغلق عينيّ… ${countdownRemaining}")
                } else {
                    phase = Phase.SEARCHING
                    searchingStartedAt = now
                    firstNoFaceAt = 0L
                    updateStatus("افسحي عن مجال الكاميرا واختبئي، ثم عودي أمامها لأرى إن كنت وجدتك!")
                }
            }
            Phase.SEARCHING, Phase.HIDDEN -> {
                if (visibleFaceCount <= 0) {
                    if (firstNoFaceAt == 0L) firstNoFaceAt = now
                    if (now - firstNoFaceAt >= 1_400L && phase != Phase.HIDDEN) {
                        phase = Phase.HIDDEN
                        updateStatus("هممم… اختفيتِ عن مجال الرؤية! عودي إلى الإطار لأجدك.")
                    }
                } else {
                    if (phase == Phase.HIDDEN) {
                        phase = Phase.RESULT
                        updateStatus("وجدتك! 👀 انتهت الغميضة. يمكنك بدء جولة جديدة.")
                    } else {
                        firstNoFaceAt = 0L
                        if (now - searchingStartedAt > 2_000L) {
                            updateStatus("ما زلت أراك! اختبئي خارج مجال الكاميرا ثم عودي.")
                        }
                    }
                }
            }
            Phase.WAITING -> {
                if (visibleFaceCount > 0) {
                    phase = Phase.RESULT
                    updateStatus("قفزتِ قبل الإشارة! 😄 أعيدي الجولة وانتظري.")
                }
            }
            Phase.SIGNAL -> {
                if (visibleFaceCount > 0) {
                    val elapsed = (now - signalAt).coerceAtLeast(0L)
                    phase = Phase.RESULT
                    updateStatus("رأيت وجهًا بعد ${elapsed} مللي ثانية! ابدئي جولة أخرى لتحسين نتيجتك.")
                }
            }
            Phase.IDLE, Phase.RESULT -> Unit
        }
    }

    private fun updateStatus(message: String) {
        statusView?.text = message
    }

    private fun stopCamera() {
        roundTask?.let(handler::removeCallbacks)
        roundTask = null
        try {
            cameraAnalysis?.clearAnalyzer()
            gameProvider?.unbindAll()
        } catch (_: Exception) {
            // A provider may not have finished binding yet.
        }
        cameraAnalysis = null
        gameProvider = null
        previewView = null
        visibleFaceCount = 0
    }
}
