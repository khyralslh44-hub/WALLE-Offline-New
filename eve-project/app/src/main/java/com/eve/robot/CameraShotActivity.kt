package com.eve.robot

import android.Manifest
import android.content.pm.PackageManager
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

        shotOverlay = CameraShotOverlay(this) { toggleCameraLens() }
        root.addView(shotOverlay, FrameLayout.LayoutParams(-1, -1))

        cameraMessage = TextView(this).apply {
            text = "لمسة واحدة: أطلق طلقة افتراضية • لمستان: بدّل الكاميرا\nجارٍ تشغيل الكاميرا…"
            textSize = 14f
            setTextColor(Color.rgb(182, 250, 255))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.argb(178, 2, 10, 15))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
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
        return "الكاميرا $lensName • لمسة: طلقة افتراضية • لمستان: تبديل العدسة • رجوع: خروج"
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
            shotOverlay.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
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

    override fun onDestroy() {
        shotOverlay.cancelPendingTouch()
        cameraProvider?.unbindAll()
        cameraProvider = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}

/** Transparent AR overlay: single tap paints a virtual fiery tracer; double tap swaps camera lens. */
internal class CameraShotOverlay(
    context: android.content.Context,
    private val onDoubleTap: () -> Unit
) : View(context) {
    private data class Shot(
        val startX: Float,
        val startY: Float,
        val targetX: Float,
        val targetY: Float,
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
    internal var cameraSwitchCountForTest = 0
        private set

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
        val safeX = x.coerceIn(width * 0.04f, width * 0.96f)
        val safeY = y.coerceIn(dp(84).toFloat(), height * 0.94f)
        targetX = safeX
        targetY = safeY
        hasTarget = true
        val startX = width * 0.5f
        val startY = height * 0.91f
        shots.add(Shot(startX, startY, safeX, safeY, SystemClock.uptimeMillis()))
        shotCountForTest++
        muzzleFlashAt = SystemClock.uptimeMillis()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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

        if (hasTarget) drawReticle(canvas, targetX, targetY, now)
        for (shot in shots) drawShot(canvas, shot, now)

        if (now - muzzleFlashAt in 0L..90L) drawMuzzleFlash(canvas, width * 0.5f, height * 0.91f, now - muzzleFlashAt)
        if (shots.isNotEmpty() || now - muzzleFlashAt in 0L..90L) postInvalidateOnAnimation()
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
            paint.color = Color.argb(32, 255, 80, 12)
            canvas.drawLine(tailX, tailY, x, y, paint)
            paint.strokeWidth = dp(7).toFloat()
            paint.color = Color.argb(130, 255, 95, 20)
            canvas.drawLine(tailX, tailY, x, y, paint)
            paint.strokeWidth = dp(2.5f)
            paint.color = Color.rgb(255, 237, 120)
            canvas.drawLine(tailX, tailY, x, y, paint)

            fill.color = Color.argb(78, 255, 55, 10)
            canvas.drawCircle(x, y, dp(13).toFloat(), fill)
            fill.color = Color.rgb(255, 111, 22)
            canvas.drawCircle(x, y, dp(7).toFloat(), fill)
            fill.color = Color.rgb(255, 242, 150)
            canvas.drawCircle(x, y, dp(3).toFloat(), fill)
        } else {
            val impactAge = age - FLIGHT_MS
            val p = (impactAge.toFloat() / IMPACT_MS).coerceIn(0f, 1f)
            val radius = dp(7).toFloat() + dp(34).toFloat() * p
            val alpha = (190f * (1f - p)).toInt().coerceIn(0, 190)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2.5f) * (1f - p * 0.45f)
            paint.color = Color.argb(alpha, 255, 137, 37)
            canvas.drawCircle(shot.targetX, shot.targetY, radius, paint)
            paint.strokeWidth = dp(1.5f)
            paint.color = Color.argb((alpha * 0.75f).toInt(), 255, 232, 100)
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
