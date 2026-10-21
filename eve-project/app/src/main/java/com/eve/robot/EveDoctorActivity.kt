package com.eve.robot

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Optional offline experimental fingertip pulse estimation. No frames or readings are saved.
 * The result is not suitable for diagnosis, emergency triage, or medication decisions.
 */
class EveDoctorActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var bpmView: TextView
    private lateinit var qualityView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val processor = EvePulseSignalProcessor()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var measuring = false
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var measurementStartedAt = 0L
    private var consecutiveUncoveredFrames = 0
    private var lastGuidanceAt = 0L
    private var framesUntilEstimate = 0
    @Volatile private var latestEstimate: EvePulseEstimate? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startMeasurement()
            else setStatus("لم تمنح إذن الكاميرا. لم يبدأ القياس ولم تُلتقط أي عينة.")
        }

    private val measurementTicker = object : Runnable {
        override fun run() {
            if (!measuring || isFinishing || isDestroyed) return
            val elapsed = SystemClock.elapsedRealtime() - measurementStartedAt
            val remaining = (MEASUREMENT_DURATION_MS - elapsed).coerceAtLeast(0L)
            val estimate = processor.estimate(SystemClock.elapsedRealtime())
            if (elapsed >= 8_000L && estimate.bpm != null) {
                latestEstimate = estimate
                bpmView.text = estimate.bpm.toString()
                qualityView.text = "انتظام الإشارة: ${(estimate.periodicity * 100).toInt()} / 100 — مؤشر إشارة لا نسبة دقة طبية"
                setStatus("تقدير أولي؛ حافظ على ثبات الإصبع.")
            } else {
                // Do not keep an earlier number visible if current frames no longer support it.
                latestEstimate = null
                if (elapsed >= 8_000L) bpmView.text = "--"
                if (elapsed > 2_500L && remaining > 0L && estimate.bpm == null) {
                    qualityView.text = "جودة الإشارة: لم تُعتمد قراءة بعد"
                }
            }
            statusView.contentDescription = statusView.text
            if (remaining <= 0L) {
                finishMeasurement()
                return
            }
            handler.postDelayed(this, 300L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(3, 8, 12)
        window.navigationBarColor = Color.rgb(3, 8, 12)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(Color.rgb(3, 8, 12))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(text("E V E  •  إيف الطبيب", 13f, CYAN, true))
        root.addView(text("تقدير النبض بالكاميرا", 25f, WHITE, true))
        root.addView(text(
            "ضع طرف إصبعك برفق فوق عدسة الكاميرا الخلفية والفلاش، دون ضغط قوي. أبقِ يدك ثابتة حتى ينتهي القياس.",
            16f, MUTED
        ))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(22), dp(18), dp(22))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(13, 28, 35))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.rgb(0, 207, 224))
            }
        }
        bpmView = text("--", 54f, PINK, true)
        card.addView(bpmView)
        card.addView(text("نبضة في الدقيقة (BPM) — تقدير تجريبي", 14f, WHITE))
        qualityView = text("جودة الإشارة: لم يبدأ القياس", 13f, MUTED)
        card.addView(qualityView)
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(14)
            bottomMargin = dp(14)
        })
        statusView = text("اضغط «ابدأ القياس» للسماح بالكاميرا عند الحاجة.", 15f, WHITE)
        root.addView(statusView)
        startButton = button("ابدأ القياس (15 ثانية)") { requestMeasurement() }
        root.addView(startButton, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(14)
            bottomMargin = dp(8)
        })
        stopButton = button("إيقاف القياس") { finishMeasurement() }
        stopButton.visibility = View.GONE
        root.addView(stopButton, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })
        root.addView(text(
            "الخصوصية: يعالج الهاتف عينات الألوان في الذاكرة فقط؛ لا يُعرض الفيديو ولا تُحفظ الصور أو القراءة. هذه أداة تجريبية لا تشخّص مرضًا ولا تقيس الأكسجين أو ضغط الدم. إذا ظهرت أعراض شديدة مثل ألم الصدر أو صعوبة التنفس أو الإغماء، اطلب المساعدة الطبية العاجلة ولا تنتظر التطبيق.",
            13f, MUTED
        ))
        root.addView(button("العودة إلى غرفة الألعاب") { finish() }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(16)
        })
    }

    private fun requestMeasurement() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startMeasurement()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startMeasurement() {
        if (measuring) return
        processor.reset()
        latestEstimate = null
        consecutiveUncoveredFrames = 0
        framesUntilEstimate = 0
        measurementStartedAt = SystemClock.elapsedRealtime()
        measuring = true
        bpmView.text = "--"
        qualityView.text = "جودة الإشارة: جارٍ الجمع والتحقق"
        startButton.isEnabled = false
        startButton.text = "جارٍ القياس..."
        stopButton.visibility = View.VISIBLE
        setStatus("شغّل الفلاش وضع إصبعك بثبات على الكاميرا الخلفية.")
        handler.removeCallbacks(measurementTicker)
        handler.post(measurementTicker)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!measuring || isFinishing || isDestroyed) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val useCase = ImageAnalysis.Builder()
                    .setTargetResolution(Size(160, 120))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                useCase.setAnalyzer(cameraExecutor) { image ->
                    try {
                        if (measuring) processFrame(image)
                    } catch (error: Exception) {
                        android.util.Log.w("EVE_DOCTOR", "Frame analysis rejected", error)
                    } finally {
                        image.close()
                    }
                }
                analysis = useCase
                val boundCamera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, useCase
                )
                camera = boundCamera
                if (boundCamera.cameraInfo.hasFlashUnit()) {
                    boundCamera.cameraControl.enableTorch(true)
                } else {
                    mainThreadStatus("لا يوجد فلاش متاح؛ لا يمكن الوثوق بقياس الإصبع بهذه الطريقة.")
                }
            } catch (error: Exception) {
                android.util.Log.e("EVE_DOCTOR", "Unable to start camera measurement", error)
                mainThreadStatus("تعذّر تشغيل الكاميرا. أوقف القياس وجرّب مرة أخرى.")
                finishMeasurement()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(image: ImageProxy) {
        val plane = image.planes.firstOrNull() ?: return
        val buffer = plane.buffer.duplicate()
        val width = image.width
        val height = image.height
        if (width < 16 || height < 16) return

        // Sample the central region to avoid edges, lens vignette, and background pixels.
        val left = width / 4
        val right = max(left + 1, width * 3 / 4)
        val top = height / 4
        val bottom = max(top + 1, height * 3 / 4)
        var red = 0L
        var green = 0L
        var blue = 0L
        var saturated = 0
        var pixels = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val index = y * plane.rowStride + x * plane.pixelStride
                if (index < 0 || index + 2 >= buffer.limit()) continue
                val r = buffer.get(index).toInt() and 0xff
                val g = buffer.get(index + 1).toInt() and 0xff
                val b = buffer.get(index + 2).toInt() and 0xff
                red += r
                green += g
                blue += b
                if (r >= 250 || g >= 250 || b >= 250) saturated++
                pixels++
            }
        }
        if (pixels < 100) return
        val rMean = red.toDouble() / pixels
        val gMean = green.toDouble() / pixels
        val bMean = blue.toDouble() / pixels
        val saturationFraction = saturated.toDouble() / pixels
        val redDominant = rMean > 65.0 && rMean > gMean * 1.06 && rMean > bMean * 1.08
        val notOverexposed = saturationFraction < 0.35
        val covered = redDominant && notOverexposed
        val timestampMs = if (image.imageInfo.timestamp > 0L) {
            image.imageInfo.timestamp / 1_000_000L
        } else {
            SystemClock.elapsedRealtime()
        }

        if (!covered) {
            consecutiveUncoveredFrames++
            if (consecutiveUncoveredFrames >= 4) {
                processor.reset()
                latestEstimate = null
            }
            if (timestampMs - lastGuidanceAt >= 1_000L) {
                lastGuidanceAt = timestampMs
                val hint = when {
                    !notOverexposed -> "الصورة شديدة السطوع؛ حرّك الإصبع قليلًا ليغطي العدسة دون تشبّع."
                    else -> "لم أتحقق من تغطية الإصبع للكاميرا. غطّ العدسة والفلاش برفق."
                }
                mainThreadStatus(hint)
            }
            return
        }

        consecutiveUncoveredFrames = 0
        // Exposure-resistant red ratio; still experimental and not a calibrated optical sensor.
        val signal = rMean / (gMean + bMean + 1.0)
        processor.addSample(timestampMs, signal)
        framesUntilEstimate++
        if (framesUntilEstimate >= 20) {
            framesUntilEstimate = 0
            val estimate = processor.estimate(timestampMs)
            if (estimate.bpm == null) {
                mainThreadStatus("أجمع الإشارة وأتحقق من انتظامها؛ ثبّت إصبعك.")
            }
        }
    }

    private fun finishMeasurement() {
        val wasMeasuring = measuring
        measuring = false
        handler.removeCallbacks(measurementTicker)
        releaseCamera()
        startButton.isEnabled = true
        startButton.text = "إعادة القياس (15 ثانية)"
        stopButton.visibility = View.GONE
        if (!wasMeasuring) return

        val estimate = processor.estimate(SystemClock.elapsedRealtime())
        // Final output must come from the current sample window, never from a stale earlier estimate.
        val accepted = estimate.takeIf { it.bpm != null }
        if (accepted?.bpm != null) {
            bpmView.text = accepted.bpm.toString()
            qualityView.text =
                "انتظام الإشارة: ${(accepted.periodicity * 100).toInt()} / 100 — ليس احتمالًا أو نسبة دقة طبية"
            setStatus(
                "هذا تقدير بصري تجريبي. أعد القياس في وضع الراحة إذا شككت بالنتيجة، " +
                    "ولا تستخدمه لاتخاذ قرار علاجي."
            )
        } else {
            bpmView.text = "--"
            qualityView.text = "جودة الإشارة: غير كافية لعرض رقم"
            setStatus(
                "لم أحصل على إشارة دورية موثوقة؛ لم أعرض رقمًا تخمينيًا. " +
                    "حافظ على ثبات الإصبع وجرّب ثانية."
            )
        }
    }

    private fun releaseCamera() {
        try {
            camera?.cameraControl?.enableTorch(false)
        } catch (_: Exception) {
            // Camera may already be closed by the lifecycle.
        }
        try {
            val activeProvider = provider
            val activeAnalysis = analysis
            if (activeProvider != null && activeAnalysis != null) activeProvider.unbind(activeAnalysis)
        } catch (_: Exception) {
            // No bound use case remains.
        }
        camera = null
        analysis = null
        provider = null
    }

    private val mainHandler: Handler get() = handler
    private fun mainThreadStatus(message: String) {
        mainHandler.post { if (!isFinishing && !isDestroyed) setStatus(message) }
    }

    private fun setStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(9), dp(8), dp(9))
        }

    private fun button(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            minHeight = dp(48)
            setTextColor(WHITE)
            setBackgroundColor(Color.rgb(19, 42, 50))
            setOnClickListener { action() }
        }

    override fun onPause() {
        if (measuring) finishMeasurement()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        measuring = false
        releaseCamera()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val MEASUREMENT_DURATION_MS = 15_000L
        private val WHITE = Color.rgb(231, 248, 249)
        private val MUTED = Color.rgb(140, 178, 185)
        private val CYAN = Color.rgb(20, 215, 230)
        private val PINK = Color.rgb(233, 30, 99)
    }
}
