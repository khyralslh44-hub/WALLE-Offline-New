package com.nova.robot

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone offline-first Nova. The camera starts after Android permission and stops when the
 * activity leaves the foreground. Text intent classification and memory stay on-device; no network,
 * microphone, cloud inference, or raw image storage is used.
 * Vision runs on small 320x240 frames with a throttle so the phone is not continuously stressed.
 */
class NovaActivity : ComponentActivity() {
    private lateinit var face: NovaFaceView
    private lateinit var message: TextView
    private lateinit var status: TextView
    private lateinit var brain: NovaBrain
    private lateinit var mind: NovaMind
    private lateinit var store: SharedPreferencesNovaMemory
    private lateinit var localPreferences: SharedPreferences
    private lateinit var affect: NovaAffectEngine
    private lateinit var peopleMemory: NovaPeopleMemory
    private lateinit var curiosity: NovaCuriosityEngine
    private lateinit var placeMemory: NovaPlaceMemory
    private lateinit var experienceMemory: NovaExperienceMemory
    @Volatile private var latestPlaceEvidence: String = ""
    @Volatile private var latestPlaceEvidenceAtElapsed: Long = 0L
    @Volatile private var latestExperienceObservation: String = ""
    private val main = Handler(Looper.getMainLooper())
    private var lastInteractionElapsed = 0L
    private var lastTapElapsed = 0L
    private var pressDownX = 0f
    private var pressDownY = 0f
    private var resumed = false
    private var visionEnabled = false
    private var useFrontCamera = false
    private var lastVisionSummary = ""
    private var lastVisionFrameAt = 0L
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private val visionInFlight = AtomicBoolean(false)
    private val visionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var localFaceDetector: FaceDetector? = null
    private val faceCropLock = Any()
    private var latestFaceCrop: Bitmap? = null
    private var latestFaceCropAtElapsed = 0L
    private var latestFaceCount = 0
    private var localImageLabeler: ImageLabeler? = null
    private var advancedVision: NovaAdvancedVision? = null
    private var emotionClassifier: NovaEmotionClassifier? = null
    private var lastAdvancedFrameAtElapsed = 0L
    private var advancedInferenceSequence = 0L
    private var lastVisionAffectAtElapsed = 0L
    private val activityRecognizer = NovaActivityRecognizer()
    private val expressionStabilizer = NovaExpressionStabilizer()
    private var novaVoice: NovaVoice? = null
    private var localTts: NovaLocalTts? = null
    private var localSpeechInput: NovaOfflineSpeechInput? = null
    private var pendingSpeechTarget: EditText? = null
    private var mindInputDialog: AlertDialog? = null
    private var pendingNotificationAction: (() -> Unit)? = null

    private val cameraPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                enableVision()
            } else {
                message.text = "لا أستطيع ملاحظة ما حولي دون إذن الكاميرا، لكن يمكننا التفاعل وتبقى ذاكرتي المحلية متاحة."
                status.text = "N O V A • أعمل محليًا"
                face.setMood(NovaMood.CALM)
            }
        }

    private val microphonePermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startOfflineListening()
            } else {
                pendingSpeechTarget = null
                message.text = "لم تمنحني إذن الميكروفون، لذلك لم أستمع إلى أي صوت. ما زال بإمكانك الكتابة لي."
                status.text = "N O V A • أعمل محليًا"
            }
        }

    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingNotificationAction
            pendingNotificationAction = null
            if (granted) action?.invoke()
            else showEvent(NovaEvent(
                NovaMood.CAUTIOUS,
                "لم يُمنح إذن الإشعارات، لذلك لم أفعّل أي تنبيه. يمكنك السماح به من إعدادات أندرويد.",
                hapticMillis = 12L
            ), playSound = false)
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
        curiosity = NovaCuriosityEngine(store)
        placeMemory = NovaPlaceMemory(store)
        experienceMemory = NovaExperienceMemory(store)
        brain = NovaBrain(store)
        mind = NovaMind(store)
        affect = NovaAffectEngine()
        peopleMemory = NovaPeopleMemory(this)
        emotionClassifier = runCatching { NovaEmotionClassifier(this) }.onFailure {
            Log.w("NOVA_EMOTION", "Bundled facial-expression classifier could not initialize.", it)
        }.getOrNull()
        advancedVision = runCatching { NovaAdvancedVision(this) }.onFailure {
            Log.w("NOVA_ADV_VISION", "Bundled advanced models could not initialize.", it)
        }.getOrNull()
        readProtectedLocalValue(KEY_AFFECT_SNAPSHOT)?.let { affect.restore(it) }
        affect.observe(NovaAffectEvent.USER_RETURNED, System.currentTimeMillis())
        persistAffect()
        novaVoice = NovaVoice(this, main)
        localTts = NovaLocalTts(this)
        lastInteractionElapsed = SystemClock.elapsedRealtime()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(4, 8, 21)) }
        face = NovaFaceView(this)
        root.addView(face, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        status = TextView(this).apply {
            text = "N O V A • LOCAL ROBOT"
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
            text = "أنا نوفا. أتعلم مما ألاحظه ومن تفاعلاتنا، وأفضّل التحقق قبل الاستنتاج. المس وجهي للتفاعل أو اضغط مطولًا لتكتب لي."
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
            text = "اسحب أفقيًا لتغيير زاوية ملاحظتي • لمسة: تفاعل • لمستان: مرح • ضغط مطوّل: اكتب أو تحدث إليّ"
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
                    val deltaX = event.x - pressDownX
                    val deltaY = event.y - pressDownY
                    val moved = kotlin.math.hypot(
                        deltaX.toDouble(),
                        deltaY.toDouble()
                    ) > dp(18)
                    if (moved) {
                        if (kotlin.math.abs(deltaX) > dp(70) &&
                            kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 1.25f
                        ) {
                            switchCameraFacing()
                        }
                    } else {
                        val now = SystemClock.elapsedRealtime()
                        lastInteractionElapsed = now
                        val longPress = event.eventTime - event.downTime >= 650L
                        when {
                            longPress -> {
                                lastTapElapsed = 0L
                                showMindInputDialog()
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

    private fun showMindInputDialog() {
        val input = EditText(this).apply {
            hint = "اكتب رسالة قصيرة إلى نوفا"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(800))
            minLines = 2
            maxLines = 5
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val listen = Button(this).apply {
            text = "استمع إليّ محليًا"
            setAllCaps(false)
            setOnClickListener { requestOfflineListening(input) }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(listen, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("أنا نوفا — تحدث إليّ محليًا")
            .setMessage("يمكنني الاستماع فقط بعد إذن الميكروفون وعبر محرك التعرف المحلي على الجهاز. إذا لم تتوفر حزمة عربية دون إنترنت، يمكنك الكتابة بدلًا من ذلك. لتسجيل وجه، أحتاج موافقة صريحة من صاحبه.")
            .setView(content)
            .setNegativeButton("إلغاء", null)
            .setNeutralButton("مسح الذاكرة") { _, _ -> confirmClearLocalMemory() }
            .setPositiveButton("إرسال كتابة", null)
            .create()
        mindInputDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString()
                if (text.isNotBlank()) {
                    dialog.dismiss()
                    handleMindInput(text)
                } else {
                    input.error = "اكتب رسالة أو استخدم الاستماع المحلي"
                }
            }
        }
        dialog.setOnDismissListener {
            // Closing the dialog ends any unfinished listening session immediately.
            localSpeechInput?.cancel()
            if (pendingSpeechTarget === input) pendingSpeechTarget = null
            if (mindInputDialog === dialog) mindInputDialog = null
        }
        dialog.show()
    }

    private fun requestOfflineListening(target: EditText) {
        pendingSpeechTarget = target
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            microphonePermissionRequest.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startOfflineListening()
        }
    }

    private fun startOfflineListening() {
        if (pendingSpeechTarget == null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            microphonePermissionRequest.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (localSpeechInput == null) {
            localSpeechInput = NovaOfflineSpeechInput(
                context = this,
                onResult = { recognized ->
                    val destination = pendingSpeechTarget
                    if (destination != null && !isFinishing && !isDestroyed && destination.isAttachedToWindow) {
                        destination.setText(recognized)
                        destination.setSelection(recognized.length)
                        mindInputDialog?.dismiss()
                        pendingSpeechTarget = null
                        handleMindInput(recognized)
                    }
                },
                onStatus = { status.text = it }
            )
        }
        localSpeechInput?.start()
    }

    private fun confirmClearLocalMemory() {
        AlertDialog.Builder(this)
            .setTitle("مسح ذاكرة نوفا المحلية؟")
            .setMessage("ستُمسح الملاحظات الشخصية التي طلبت حفظها، وحدود الفضول، وسجل التفاعلات المختصر، وحالة الشخصية، وتصحيحات نموذج النوايا وأسماء الوجوه المحفوظة. لا تُحفظ صور الكاميرا أصلًا. لا يمكن التراجع عن المسح.")
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("مسح نهائي") { _, _ -> clearLocalMemory() }
            .show()
    }

    private fun clearLocalMemory() {
        localPreferences.edit().clear().apply()
        peopleMemory.forgetAll()
        placeMemory.clear()
        experienceMemory.clear()
        latestPlaceEvidence = ""
        latestExperienceObservation = ""
        clearLatestFaceCrop()
        store = SharedPreferencesNovaMemory(localPreferences)
        brain = NovaBrain(store)
        mind = NovaMind(store)
        affect = NovaAffectEngine()
        affect.observe(NovaAffectEvent.USER_RETURNED, System.currentTimeMillis())
        persistAffect()
        lastVisionSummary = ""
        lastAdvancedFrameAtElapsed = 0L
        advancedInferenceSequence = 0L
        lastVisionAffectAtElapsed = 0L
        activityRecognizer.reset()
        expressionStabilizer.reset()
        curiosity.reset()
        lastInteractionElapsed = SystemClock.elapsedRealtime()
        face.setMood(NovaMood.CALM)
        message.text = "مسحت ذاكرتي المحلية: الملاحظات الشخصية وأسماء الوجوه والتعلّم السابق. لم أحتفظ بصور للوجوه."
        status.text = if (visionEnabled) {
            if (useFrontCamera) "N O V A • ألاحظ محيطي • أمامية" else "N O V A • ألاحظ محيطي • خلفية"
        } else "N O V A • LOCAL ROBOT"
    }

    private fun handleMindInput(rawText: String) {
        val userText = rawText.trim().take(800)
        if (userText.isBlank()) return
        if (handleNotificationCommand(userText)) return
        if (openNovaGameIfRequested(userText)) return
        NovaExpressionCommand.parse(userText)?.let { request ->
            val reply = "أُظهر الآن تعبير «" + request.labelArabic + "». هذا عرض لتعبير الوجه، وليس ادعاءً بشعور داخلي."
            showEvent(NovaEvent(request.mood, reply, hapticMillis = 16L), playSound = true)
            return
        }
        val memoryReply = experienceMemory.handleCommand(userText)
        if (memoryReply != null) { showEvent(NovaEvent(NovaMood.THINKING, memoryReply, hapticMillis = 14L), playSound = false); return }
        if (handlePlaceMemoryCommand(userText)) return
        if (handleFaceEnrollment(userText)) return
        val correction = parseExplicitCorrection(userText)
        if (correction != null) {
            runCatching { mind.learnFromCorrection(correction.second, correction.first) }
                .onSuccess {
                    showEvent(NovaEvent(
                        NovaMood.THINKING,
                        "حفظت تصحيحًا صريحًا في النموذج المحلي. هذا يحسّن التصنيف تدريجيًا، لكنه لا يضمن فهمًا عامًا.",
                        hapticMillis = 18L
                    ), playSound = false)
                }
                .onFailure {
                    Log.w("NOVA_MIND", "Could not persist explicit local correction.", it)
                    showEvent(NovaEvent(NovaMood.CAUTIOUS, "لم أتمكن من حفظ التصحيح محليًا.", hapticMillis = 12L), playSound = false)
                }
            return
        }
        val reply = runCatching {
            mind.respond(NovaMindInput(text = userText, visualSummary = lastVisionSummary.takeIf { visionEnabled }))
        }.getOrElse {
            Log.w("NOVA_MIND", "Offline intent model failed safely.", it)
            showEvent(NovaEvent(NovaMood.CAUTIOUS, "واجهت مشكلة في تحليل النص محليًا. لم أرسل أي بيانات إلى الإنترنت.", hapticMillis = 12L), playSound = false)
            return
        }
        val confidence = (reply.intentConfidence.coerceIn(0f, 1f) * 100).toInt()
        val normalizedUserText = userText
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        val mood = when {
            normalizedUserText.contains("احبك") || normalizedUserText.contains("احبك يا نوفا") -> NovaMood.LOVE
            reply.intent == NovaIntent.UNKNOWN || confidence < 36 -> NovaMood.CONFUSED
            else -> when (reply.emotion) {
                NovaEmotion.CALM -> NovaMood.CALM
                NovaEmotion.CURIOUS -> NovaMood.CURIOUS
                NovaEmotion.HAPPY -> NovaMood.HAPPY
                NovaEmotion.SURPRISED -> NovaMood.SURPRISED
                NovaEmotion.FRUSTRATED -> NovaMood.FRUSTRATED
                NovaEmotion.SLEEPY -> NovaMood.SLEEPY
                NovaEmotion.PLAYFUL -> NovaMood.PLAYFUL
                NovaEmotion.THINKING -> NovaMood.THINKING
            }
        }
        val note = when {
            !reply.usedOwnModel && reply.intent == NovaIntent.MEMORY_REQUEST -> ""
            reply.intent == NovaIntent.UNKNOWN || confidence < 36 -> "\n(لم أكن واثقة من تصنيف القصد)"
            else -> "\n(تصنيف محلي: $confidence٪)"
        }
        showEvent(NovaEvent(mood, reply.textArabic + note, isQuestion = reply.intent == NovaIntent.QUESTION || reply.intent == NovaIntent.VISION_QUERY, hapticMillis = 16L), playSound = true)
        // Only an installed offline Arabic voice is used; there is no cloud TTS fallback.
        localTts?.speak(reply.textArabic)
    }

    private fun withNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingNotificationAction = action
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun handleNotificationCommand(rawText: String): Boolean {
        return when (val command = NovaNotificationCommandParser.parse(rawText)) {
            NovaNotificationCommand.EnableDaily -> {
                withNotificationPermission {
                    NovaLocalNotifications.enableDaily(this, 19, 0)
                    showEvent(NovaEvent(NovaMood.HAPPY,
                        "فعّلت إشعارًا لطيفًا واحدًا كل يوم الساعة 19:00. يمكنك إيقافه بقول «أوقفي إشعارات نوفا».",
                        hapticMillis = 14L), playSound = false)
                }
                true
            }
            NovaNotificationCommand.DisableDaily -> {
                NovaLocalNotifications.disableDaily(this)
                showEvent(NovaEvent(NovaMood.CALM,
                    "أوقفت الإشعارات اليومية. ستبقى التذكيرات التي طلبتِها بشكل منفصل كما هي.",
                    hapticMillis = 12L), playSound = false)
                true
            }
            NovaNotificationCommand.Test -> {
                withNotificationPermission {
                    val posted = NovaLocalNotifications.showTest(this)
                    showEvent(NovaEvent(if (posted) NovaMood.HAPPY else NovaMood.CAUTIOUS,
                        if (posted) "أرسلت إشعارًا تجريبيًا محليًا. إن لم يظهر، راجعي إعدادات إشعارات نوفا والبطارية."
                        else "لم أستطع عرض الإشعار. تحققي من أن إشعارات نوفا مسموحة في إعدادات أندرويد.",
                        hapticMillis = 14L), playSound = false)
                }
                true
            }
            is NovaNotificationCommand.Reminder -> {
                withNotificationPermission {
                    val scheduled = NovaLocalNotifications.scheduleReminder(this, command.delayMillis, command.message)
                    showEvent(NovaEvent(if (scheduled) NovaMood.CURIOUS else NovaMood.CAUTIOUS,
                        if (scheduled) "حفظت تذكيرًا محليًا بعد " + command.spokenDelay + ": " + command.message +
                            ". قد يؤخر أندرويد موعده قليلًا حسب البطارية ووضع توفير الطاقة."
                        else "لم أتمكن من جدولة هذا التذكير. جرّبي مدة بين دقيقة و30 يومًا.",
                        hapticMillis = 14L), playSound = false)
                }
                true
            }
            null -> false
        }
    }

    private fun openNovaGameIfRequested(rawText: String): Boolean {
        val normalized = rawText.trim().lowercase()
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي')
        val asksToPlay = listOf("العب", "تلعب", "نلعب", "لعب", "مباراة", "تحدي").any { normalized.contains(it) }
        val game = when {
            normalized.contains("شطرنج") && asksToPlay -> "chess"
            asksToPlay && (normalized.contains("اكس او") || normalized.contains("اكس-او") ||
                normalized.contains("tic tac toe") || Regex("\\bxo\\b").containsMatchIn(normalized)) -> "xo"
            asksToPlay && (normalized.contains("العاب") || normalized.contains("لعبة")) -> "menu"
            else -> return false
        }
        startActivity(Intent(this, NovaGamesActivity::class.java).putExtra(NovaGamesActivity.EXTRA_GAME, game))
        return true
    }

    private fun handlePlaceMemoryCommand(rawText: String): Boolean {
        if (NovaPlaceMemory.isClearCommand(rawText)) { placeMemory.clear(); showEvent(NovaEvent(NovaMood.CALM, "مسحت أسماء الأماكن التي علّمتِني إياها.", hapticMillis = 12L), playSound = false); return true }
        if (NovaPlaceMemory.isListCommand(rawText)) { showEvent(NovaEvent(NovaMood.THINKING, placeMemory.describe(), hapticMillis = 12L), playSound = false); return true }
        val name = NovaPlaceMemory.parseTrainingCommand(rawText) ?: return false
        if (!visionEnabled || latestPlaceEvidence.isBlank() || SystemClock.elapsedRealtime() - latestPlaceEvidenceAtElapsed > 10_000L) {
            showEvent(NovaEvent(NovaMood.CAUTIOUS, "شغّلي الرؤية أولًا وانتظري ظهور أشياء واضحة، ثم أعيدي الأمر. لن أخمّن المكان دون دليل بصري حديث.", hapticMillis = 12L), playSound = false); return true
        }
        val saved = placeMemory.enroll(name, latestPlaceEvidence)
        val reply = if (saved) "تعلّمت وصفًا بصريًا تجريبيًا للمكان باسم «" + name + "» محليًا؛ قد أخلط بين غرف متشابهة." else "لم أحفظ المكان؛ أحتاج إلى تصنيفين بصريين مختلفين على الأقل في المشهد."
        showEvent(NovaEvent(if (saved) NovaMood.CURIOUS else NovaMood.CAUTIOUS, reply, hapticMillis = 14L), playSound = false); return true
    }

    private fun parseExplicitCorrection(rawText: String): Pair<NovaIntent, String>? {
        val parts = rawText.split(':', limit = 3).map { it.trim() }
        if (parts.size != 3 || parts[0] !in setOf("صحح", "تعلم")) return null
        val intent = when (parts[1]) {
            "تحية" -> NovaIntent.GREETING
            "سؤال" -> NovaIntent.QUESTION
            "رؤية" -> NovaIntent.VISION_QUERY
            "ذاكرة" -> NovaIntent.MEMORY_REQUEST
            "إيجابي" -> NovaIntent.POSITIVE_FEEDBACK
            "سلبي" -> NovaIntent.NEGATIVE_FEEDBACK
            "وداع" -> NovaIntent.FAREWELL
            else -> return null
        }
        val example = parts[2].take(800)
        if (example.isBlank()) return null
        return intent to example
    }

    private fun toggleVision() {
        lastInteractionElapsed = SystemClock.elapsedRealtime()
        if (visionEnabled) {
            visionEnabled = false
            stopVisionCamera()
            lastVisionSummary = ""
            status.text = "N O V A • LOCAL ROBOT"
            message.text = "توقفت عن ملاحظة محيطي. لا أحتفظ بصور المشهد، ويمكنني العودة للملاحظة عندما تسمح بذلك."
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
        useFrontCamera = false
        lastVisionFrameAt = 0L
        lastAdvancedFrameAtElapsed = 0L
        advancedInferenceSequence = 0L
        lastVisionAffectAtElapsed = 0L
        activityRecognizer.reset()
        status.text = "N O V A • أتهيأ للملاحظة"
        message.text = "سأبدأ بملاحظة محيطي. أعالج ما أراه على هذا الهاتف ولا أحتفظ بصور المشهد."
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
                status.text = "N O V A • أحتاج مساعدة"
                message.text = "لم أتمكن من رؤية محيطي الآن، لذلك لن أخمّن ما يحدث أمامي."
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
            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            provider.bindToLifecycle(this, selector, useCase)
            analysisUseCase = useCase
            status.text = if (useFrontCamera) "N O V A • ألاحظ محيطي • أمامية" else "N O V A • ألاحظ محيطي • خلفية"
            Log.i("NOVA_VISION", "Camera started: ${if (useFrontCamera) "front" else "back"} camera, 320x240, throttled local inference.")
        } catch (error: Exception) {
            Log.w("NOVA_VISION", "Binding the camera analyzer failed.", error)
            visionEnabled = false
            stopVisionCamera()
            status.text = "N O V A • CAMERA UNAVAILABLE"
            message.text = "لم أتمكن من ملاحظة محيطي على هذا الجهاز، لكن ذاكرتي وتفاعلاتي المحلية ما زالت متاحة."
            face.setMood(NovaMood.CALM)
        }
    }

    /** Swipe horizontally across Nova's face to switch cameras without adding a visible button. */
    private fun switchCameraFacing() {
        if (!visionEnabled) {
            message.text = "أحتاج إذن الرؤية أولًا. اسحب أفقيًا لتغيير زاوية ملاحظتي."
            return
        }
        val nextSelector = if (useFrontCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val provider = cameraProvider
        if (provider == null || !provider.hasCamera(nextSelector)) {
            message.text = "لا أستطيع استخدام زاوية الرؤية هذه على جهازك."
            return
        }
        useFrontCamera = !useFrontCamera
        lastVisionSummary = ""
        status.text = if (useFrontCamera) "N O V A • أغيّر زاوية ملاحظتي • أمامية" else "N O V A • أغيّر زاوية ملاحظتي • خلفية"
        message.text = if (useFrontCamera) "أغيّر زاوية ملاحظتي إلى الأمامية." else "أغيّر زاوية ملاحظتي إلى الخلفية."
        face.setMood(NovaMood.CURIOUS)
        bindLowLoadAnalyzer(provider)
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
                    val detectedFaces = if (faceTask.isSuccessful) faceTask.result.orEmpty() else emptyList()
                    val hasFace = detectedFaces.isNotEmpty()
                    var identityNote = ""
                    var faceCueNote = ""
                    var emotionNote = ""
                    var gazeX = 0.5f
                    var gazeY = 0.5f
                    var emotionPrediction: NovaEmotionPrediction? = null
                    if (hasFace) {
                        val largest = detectedFaces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                        if (largest != null) {
                            val rotatedWidth = if (proxy.imageInfo.rotationDegrees % 180 == 0) proxy.width else proxy.height
                            val rotatedHeight = if (proxy.imageInfo.rotationDegrees % 180 == 0) proxy.height else proxy.width
                            gazeX = (largest.boundingBox.centerX().toFloat() / rotatedWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
                            gazeY = (largest.boundingBox.centerY().toFloat() / rotatedHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
                        }
                        faceCueNote = largest?.let {
                            NovaFaceCueEvidence.summarize(
                                it.smilingProbability,
                                it.leftEyeOpenProbability,
                                it.rightEyeOpenProbability
                            )
                        }.orEmpty()
                        val crop = largest?.let { runCatching { extractFaceCrop(proxy, it.boundingBox) }.getOrNull() }
                        if (crop != null) {
                            synchronized(faceCropLock) {
                                latestFaceCrop?.recycle()
                                latestFaceCrop = crop
                                latestFaceCropAtElapsed = SystemClock.elapsedRealtime()
                                latestFaceCount = detectedFaces.size
                            }
                            val match = runCatching { peopleMemory.recognize(crop) }.getOrNull()
                            emotionPrediction = runCatching { emotionClassifier?.classify(crop) }.getOrNull()
                            emotionNote = emotionPrediction?.note.orEmpty()
                            identityNote = when {
                                match != null -> "أظن أنني رأيت وجهًا مألوفًا يشبه ${match.person.name}، لكنني لست متأكدة بعد."
                                peopleMemory.count() > 0 -> "أرى وجهًا، لكنني لا أستطيع مطابقته بثقة مع شخص أتذكره."
                                else -> "أرى وجهًا جديدًا بالنسبة إلى ذاكرتي؛ لم أعرف اسم صاحبه بعد."
                            }
                        } else {
                            clearLatestFaceCrop()
                        }
                    } else {
                        clearLatestFaceCrop()
                    }
                    val mlKitLabels = if (labelTask.isSuccessful) {
                        labelTask.result.orEmpty().map { NovaLabelEvidence(it.text, it.confidence) }
                    } else {
                        emptyList()
                    }
                    var poseNote = ""
                    val shouldRunAdvanced = now - lastAdvancedFrameAtElapsed >= MIN_ADVANCED_FRAME_INTERVAL_MS
                    var advancedResult: NovaAdvancedVisionResult? = null
                    if (shouldRunAdvanced) {
                        val advancedFrame = runCatching {
                            val raw = imageProxyToBitmap(proxy) ?: return@runCatching null
                            val rotation = proxy.imageInfo.rotationDegrees
                            if (rotation % 360 == 0) raw else {
                                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also { raw.recycle() }
                            }
                        }.getOrNull()
                        val sceneCycle = NovaVisionSchedule.includeSceneModels(advancedInferenceSequence++)
                        advancedResult = try {
                            if (advancedFrame != null) {
                                advancedVision?.analyze(
                                    advancedFrame,
                                    includeSceneModels = sceneCycle,
                                    includeFaceBlendshapes = !sceneCycle,
                                    includePose = true
                                )
                            } else null
                        } catch (error: Exception) {
                            Log.w("NOVA_ADV_VISION", "Advanced local model inference failed safely.", error)
                            null
                        } finally {
                            if (advancedFrame != null && !advancedFrame.isRecycled) advancedFrame.recycle()
                        }
                        lastAdvancedFrameAtElapsed = now
                    }
                    if (advancedResult?.poseNote != null) poseNote = advancedResult.poseNote
                    val labels = (mlKitLabels + advancedResult?.labels.orEmpty())
                        .groupBy { it.label.lowercase(java.util.Locale.ROOT) }
                        .mapNotNull { (_, items) -> items.maxByOrNull { it.confidence } }
                    val stableExpressionMood = if (hasFace) {
                        expressionStabilizer.observe(emotionPrediction, now)
                    } else {
                        expressionStabilizer.reset()
                        null
                    }
                    val activityNote = activityRecognizer.observe(advancedResult?.poseFeatures, labels, now).orEmpty()
                    val environmentNote = NovaEnvironmentRecognizer.summarize(labels).orEmpty()
                    latestPlaceEvidence = NovaPlaceMemory.signature(labels)
                    latestPlaceEvidenceAtElapsed = SystemClock.elapsedRealtime()
                    val baseSummary = NovaVisionEvidence.summarize(hasFace, labels)
                    latestExperienceObservation = listOf(baseSummary, activityNote, environmentNote).filter { it.isNotBlank() }.joinToString(System.lineSeparator())
                    val notes = listOf(
                        identityNote,
                        if (emotionNote.isNotBlank()) emotionNote else faceCueNote,
                        poseNote,
                        activityNote,
                        environmentNote
                    ).filter { it.isNotBlank() }
                    val summary = if (notes.isNotEmpty()) "$baseSummary\n${notes.joinToString("\n")}" else baseSummary
                    runOnUiThread {
                        if (visionEnabled && resumed && !isFinishing && !isDestroyed) {
                            face.setGaze(gazeX, gazeY)
                            applyVisionSummary(summary, hasFace, stableExpressionMood)
                        }
                    }
                } catch (error: Exception) {
                    Log.w("NOVA_VISION", "Local vision parsing failed; keeping uncertainty.", error)
                } finally {
                    val frameAnalysisMs = SystemClock.elapsedRealtime() - now
                    if (frameAnalysisMs >= SLOW_FRAME_LOG_THRESHOLD_MS) {
                        Log.w("NOVA_PERF", "local_frame_analysis_ms=" + frameAnalysisMs + "; model_input=320x240")
                    } else {
                        Log.d("NOVA_PERF", "local_frame_analysis_ms=" + frameAnalysisMs + "; model_input=320x240")
                    }
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

    private fun handleFaceEnrollment(rawText: String): Boolean {
        val request = NovaEnrollmentParser.parse(rawText) ?: return false
        if (!visionEnabled) {
            showEvent(NovaEvent(NovaMood.CAUTIOUS, "شغّل الرؤية أولًا، ثم اجعل الشخص المقصود أمام الكاميرا وحده.", hapticMillis = 12L), playSound = false)
            return true
        }
        val cropCopy = synchronized(faceCropLock) {
            val crop = latestFaceCrop
            if (crop == null || latestFaceCount != 1 ||
                SystemClock.elapsedRealtime() - latestFaceCropAtElapsed > FRESH_FACE_CROP_MS
            ) null else runCatching { crop.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        }
        if (cropCopy == null) {
            showEvent(NovaEvent(NovaMood.CAUTIOUS, "لم أحفظ الوجه: أحتاج وجهًا واحدًا واضحًا وحديثًا أمام الكاميرا. قرّب الشخص وحاول مجددًا.", hapticMillis = 12L), playSound = false)
            return true
        }
        // The proposal requires explicit consent before retaining any biometric reference.
        // Keep only this temporary in-memory crop while the user confirms; never write the photo.
        val dialog = AlertDialog.Builder(this)
            .setTitle("موافقة على حفظ مرجع الوجه")
            .setMessage(
                "سيُحفظ وصف رقمي تجريبي للوجه مع الاسم وصلة القرابة على هذا الهاتف فقط، " +
                    "مشفّرًا باستخدام Android Keystore. لن تُحفظ صورة الوجه ولن تُرسل بيانات إلى الإنترنت. " +
                    "تأكد من موافقة الشخص نفسه قبل المتابعة. يمكنك حذف الذاكرة من قائمة الرسائل."
            )
            .setNegativeButton("لا تحفظ") { _, _ ->
                if (!cropCopy.isRecycled) cropCopy.recycle()
                showEvent(NovaEvent(NovaMood.CALM, "لم أحفظ أي مرجع للوجه؛ احترمت خيار عدم الحفظ.", hapticMillis = 10L), playSound = false)
            }
            .setPositiveButton("أوافق على الحفظ") { _, _ ->
                try {
                    val saved = peopleMemory.enroll(request, cropCopy)
                    showEvent(NovaEvent(
                        NovaMood.WARM,
                        "حفظت مرجعًا بصريًا محليًا لـ${saved.relation} ${saved.name}. لم أحفظ صورة الوجه؛ خزّنت وصفًا رقميًا مشفّرًا فقط. التعرف تجريبي وقد أخطئ.",
                        hapticMillis = 22L
                    ), playSound = false)
                } catch (error: Exception) {
                    Log.w("NOVA_PEOPLE", "Could not enroll a local face reference.", error)
                    showEvent(NovaEvent(NovaMood.CAUTIOUS, "لم أتمكن من حفظ المرجع البصري محليًا.", hapticMillis = 12L), playSound = false)
                } finally {
                    if (!cropCopy.isRecycled) cropCopy.recycle()
                }
            }
            .create()
        dialog.setOnCancelListener {
            if (!cropCopy.isRecycled) cropCopy.recycle()
        }
        dialog.show()
        return true
    }

    private fun clearLatestFaceCrop() {
        synchronized(faceCropLock) {
            latestFaceCrop?.recycle()
            latestFaceCrop = null
            latestFaceCropAtElapsed = 0L
            latestFaceCount = 0
        }
    }

    /** Converts only the current low-resolution frame long enough to crop the detected face. */
    private fun extractFaceCrop(proxy: ImageProxy, faceBounds: Rect): Bitmap? {
        val frame = imageProxyToBitmap(proxy) ?: return null
        try {
            val rotation = proxy.imageInfo.rotationDegrees
            val rotated = if (rotation % 360 == 0) frame else {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
            }
            try {
                val padX = (faceBounds.width() * 0.12f).toInt().coerceAtLeast(2)
                val padY = (faceBounds.height() * 0.12f).toInt().coerceAtLeast(2)
                val left = (faceBounds.left - padX).coerceIn(0, rotated.width - 1)
                val top = (faceBounds.top - padY).coerceIn(0, rotated.height - 1)
                val right = (faceBounds.right + padX).coerceIn(left + 1, rotated.width)
                val bottom = (faceBounds.bottom + padY).coerceIn(top + 1, rotated.height)
                if (right - left < 24 || bottom - top < 24) return null
                return Bitmap.createBitmap(rotated, left, top, right - left, bottom - top)
            } finally {
                if (rotated !== frame) rotated.recycle()
            }
        } finally {
            frame.recycle()
        }
    }

    private fun imageProxyToBitmap(proxy: ImageProxy): Bitmap? {
        return try {
            if (proxy.format != ImageFormat.YUV_420_888) return null
            val width = proxy.width
            val height = proxy.height
            val planes = proxy.planes
            if (planes.size < 3) return null
            val nv21 = ByteArray(width * height + width * height / 2)
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer
            val yStart = yBuffer.position()
            val uStart = uBuffer.position()
            val vStart = vBuffer.position()
            var offset = 0
            for (row in 0 until height) {
                for (col in 0 until width) {
                    nv21[offset++] = yBuffer.get(yStart + row * planes[0].rowStride + col * planes[0].pixelStride)
                }
            }
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    nv21[offset++] = vBuffer.get(vStart + row * planes[2].rowStride + col * planes[2].pixelStride)
                    nv21[offset++] = uBuffer.get(uStart + row * planes[1].rowStride + col * planes[1].pixelStride)
                }
            }
            val output = ByteArrayOutputStream()
            YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), 82, output)
            BitmapFactory.decodeByteArray(output.toByteArray(), 0, output.size())
        } catch (error: Exception) {
            Log.w("NOVA_PEOPLE", "Could not crop a detected face locally.", error)
            null
        }
    }

    private fun applyVisionSummary(summary: String, hasFace: Boolean, expressionMood: NovaMood? = null) {
        if (summary == lastVisionSummary) return
        lastVisionSummary = summary
        val elapsedNow = SystemClock.elapsedRealtime()
        val affectMood = if (
            lastVisionAffectAtElapsed == 0L ||
            elapsedNow < lastVisionAffectAtElapsed ||
            elapsedNow - lastVisionAffectAtElapsed >= MIN_VISION_AFFECT_INTERVAL_MS
        ) {
            lastVisionAffectAtElapsed = elapsedNow
            recordAffect(if (hasFace) NovaAffectEvent.NOVEL_OBSERVATION else NovaAffectEvent.UNCERTAIN_OBSERVATION)
        } else {
            affect.mood(System.currentTimeMillis())
        }
        val knownPlace = placeMemory.match(latestPlaceEvidence)
        val placeNote = knownPlace?.let { "قد يشبه المكان الحالي «" + it + "» وفق الأشياء التي علّمتِني إياها؛ هذه مطابقة تقريبية وليست تحديدًا مؤكدًا للغرفة." }
        val displaySummary = listOf(summary, placeNote).filter { !it.isNullOrBlank() }.joinToString(System.lineSeparator())
        val question = curiosity.questionFor(displaySummary, System.currentTimeMillis())
        message.text = if (question == null) displaySummary else displaySummary + System.lineSeparator() + question
        experienceMemory.observe(latestExperienceObservation, System.currentTimeMillis())
        face.setMood(NovaBehaviorPlanner.chooseFaceMood(expressionMood, affectMood, hasFace))
        status.text = if (useFrontCamera) "N O V A • ألاحظ محيطي • أمامية" else "N O V A • ألاحظ محيطي • خلفية"
        Log.i("NOVA_VISION", "LOCAL_FRAME_SUMMARY face=$hasFace; imagesStored=false; network=false")
    }

    private fun faceDetector(): FaceDetector {
        if (localFaceDetector == null) {
            localFaceDetector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
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
        localSpeechInput?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { advancedVision?.close() }
        advancedVision = null
        runCatching { peopleMemory.close() }
        runCatching { emotionClassifier?.close() }
        emotionClassifier = null
        runCatching { localSpeechInput?.close() }
        localSpeechInput = null
        runCatching { localTts?.close() }
        localTts = null
        main.removeCallbacksAndMessages(null)
        stopVisionCamera()
        novaVoice?.close()
        novaVoice = null
        localFaceDetector?.close()
        localFaceDetector = null
        clearLatestFaceCrop()
        localImageLabeler?.close()
        localImageLabeler = null
        visionExecutor.shutdown()
        super.onDestroy()
    }

    private fun showEvent(event: NovaEvent, playSound: Boolean) {
        val affectSignal = when (event.mood) {
            NovaMood.HAPPY, NovaMood.PLAYFUL, NovaMood.WARM, NovaMood.LOVE -> NovaAffectEvent.FRIENDLY_INTERACTION
            NovaMood.FRUSTRATED -> NovaAffectEvent.USER_CORRECTION
            NovaMood.THINKING, NovaMood.CURIOUS, NovaMood.CONFUSED, NovaMood.SAD,
            NovaMood.AFRAID, NovaMood.DISGUSTED -> NovaAffectEvent.UNCERTAIN_OBSERVATION
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
            if (useFrontCamera) "N O V A • ألاحظ محيطي • أمامية" else "N O V A • ألاحظ محيطي • خلفية"
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
            NovaMood.SAD -> "N O V A • S A D"
            NovaMood.DISGUSTED -> "N O V A • REACTING"
            NovaMood.AFRAID -> "N O V A • ALERT"
            NovaMood.LOVE -> "N O V A • W A R M"
            NovaMood.CONFUSED -> "N O V A • THINKING"
            NovaMood.EMBARRASSED -> "N O V A • SHY"
            NovaMood.JEALOUS -> "N O V A • JEALOUS"
            NovaMood.ADMIRING -> "N O V A • ADMIRING"
            NovaMood.CALM -> "N O V A • LOCAL ROBOT"
        }
        if (event.hapticMillis > 0L) face.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (playSound) {
            novaVoice?.play(visibleMood, event.isQuestion)
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

    private fun readProtectedLocalValue(key: String): String? {
        val stored = localPreferences.getString(key, null) ?: return null
        val plain = NovaLocalCipher.decryptOrLegacy(stored) ?: return null
        // Migrate a legacy plaintext snapshot on first read. Only encrypted text is retained afterward.
        if (!stored.startsWith("enc:v1:")) {
            localPreferences.edit().putString(key, NovaLocalCipher.encrypt(plain)).apply()
        }
        return plain
    }

    private fun persistAffect() {
        val encrypted = NovaLocalCipher.encrypt(affect.snapshot(System.currentTimeMillis()))
        localPreferences.edit().putString(KEY_AFFECT_SNAPSHOT, encrypted).apply()
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
        // Heavy object, pose and face-landmark models are throttled separately from face tracking.
        private const val MIN_ADVANCED_FRAME_INTERVAL_MS = 3_600L
        private const val MIN_VISION_AFFECT_INTERVAL_MS = 12_000L
        private const val SLOW_FRAME_LOG_THRESHOLD_MS = 300L
        private const val FRESH_FACE_CROP_MS = 4_000L
        private const val KEY_AFFECT_SNAPSHOT = "nova_affect_snapshot_v1"
    }
}

private class SharedPreferencesNovaMemory(
    private val preferences: android.content.SharedPreferences
) : NovaMemory, NovaMindMemory {
    override fun getInt(key: String, defaultValue: Int): Int =
        readProtected(key)?.toIntOrNull() ?: defaultValue

    override fun getLong(key: String, defaultValue: Long): Long =
        readProtected(key)?.toLongOrNull() ?: defaultValue

    override fun putInt(key: String, value: Int) {
        writeProtected(key, value.toString())
    }

    override fun putLong(key: String, value: Long) {
        writeProtected(key, value.toString())
    }

    override fun read(key: String): String? = readProtected("mind:$key")

    override fun write(item: NovaMemoryItem) {
        // Model weights/snapshots, coarse topics and timestamps are all encrypted at rest.
        writeProtected("mind:${item.key}", item.value)
        writeProtected("mind:${item.key}:updated_at", item.updatedAtMillis.toString())
    }

    override fun increment(key: String): Int {
        val storageKey = "mind:counter:$key"
        val next = getInt(storageKey, 0) + 1
        putInt(storageKey, next)
        return next
    }

    /**
     * Reads old primitive preference values for migration, then rewrites them as AES-GCM strings.
     * Preference keys remain non-sensitive labels; their values are encrypted with Android Keystore.
     */
    private fun readProtected(key: String): String? {
        val stored = preferences.all[key] ?: return null
        val plain = when (stored) {
            is String -> NovaLocalCipher.decryptOrLegacy(stored)
            is Int, is Long, is Float, is Boolean -> stored.toString()
            else -> null
        } ?: return null

        val isAlreadyEncrypted = stored is String && stored.startsWith("enc:v1:")
        if (!isAlreadyEncrypted) writeProtected(key, plain)
        return plain
    }

    private fun writeProtected(key: String, plain: String) {
        val encrypted = NovaLocalCipher.encrypt(plain)
        check(preferences.edit().putString(key, encrypted).commit()) {
            "Could not persist encrypted Nova memory."
        }
    }
}
