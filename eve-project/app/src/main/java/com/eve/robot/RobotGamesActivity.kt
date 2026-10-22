package com.eve.robot

import android.graphics.Color
import android.graphics.Typeface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.random.Random

/**
 * Offline game room opened by EVE's notification or a hidden long-press gesture.
 * Results persist locally and feed later challenge messages. No game needs internet.
 */
class RobotGamesActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val random = Random(System.currentTimeMillis())
    private var game = Game.MENU
    private var difficulty = 2
    private var xoDifficulty = 2
    private var statusText = "سأحاول التكيّف مع أسلوبك بدل تكرار الحركة نفسها."
    private val prefs by lazy { getSharedPreferences("eve_games", MODE_PRIVATE) }
    private val gameMemory by lazy { EveMemory(applicationContext) }
    private lateinit var gameBrain: RobotBrain
    private lateinit var robotVoice: RobotVoice
    private var eveGameFace: RobotFaceView? = null
    private var gameDialogueView: TextView? = null
    @Volatile private var gameSessionId = 0L
    private var gameLlmRequestSerial = 0L
    private var lastGameLlmRequestAt = 0L
    private var pendingGameComment: PendingGameComment? = null
    private var pendingGameCommentRunnable: Runnable? = null
    private var modelGameIntent = "play"
    private var latestGameDecision: EveDecision? = null

    private data class PendingGameComment(
        val gameName: String,
        val outcome: String,
        val detail: String,
        val playerScore: Int?,
        val eveScore: Int?,
        val round: Int?,
        val force: Boolean,
        val sessionId: Long,
        val game: Game
    )

    private enum class Game { MENU, XO, RPS, CHESS, TARGET, PAINT_SHOT, MEMORY, REFLEX }

    // Tic-tac-toe
    private val xo = CharArray(9) { ' ' }
    private var xoFinished = false
    private var xoHumanMoves = 0

    // Chess
    private val chess = ChessEngine()
    private var chessSelected: Int? = null
    private var chessFinished = false
    private var chessThinking = false
    private var chessPly = 0
    private var lastChessMove: ChessEngine.Move? = null
    private val chessWorker = Executors.newSingleThreadExecutor()

    // RPS adapts by choosing a winning response to the player's most common move.
    private val playerRpsHistory = mutableListOf<Int>()
    private var rpsScoreHuman = 0
    private var rpsScoreEve = 0
    private var rpsRound = 0

    // EMO-inspired Paint Shot: moving local target, virtual paint projectiles and on-device score.
    private var paintShotScore = 0
    private var paintShotRound = 0
    private var paintShotFinished = false
    private var paintShotMessage = "ارتدِ النظارة الخيالية، ثم المس الهدف المتحرك لإطلاق طلقة الطلاء."
    private var paintShotArena: PaintShotArenaView? = null
    private var paintShotProgressView: TextView? = null
    private var paintShotMessageView: TextView? = null

    // Target practice
    private var targetScore = 0
    private var targetRound = 0
    private var targetFinished = false
    private var targetDeadline = 0L
    private var targetCell = 4
    private var targetMoveTask: Runnable? = null

    // Memory sequence
    private val sequence = mutableListOf<Int>()
    private var memoryInput = 0
    private var memoryShowing = false
    private var memoryFinished = false
    private var memoryLevel = 1
    private var memoryCue: TextView? = null

    // Reflex challenge
    private var reflexGameStarted = false
    private var reflexReady = false
    private var reflexFinished = false
    private var reflexStartedAt = 0L
    private var reflexDelayUntil = 0L
    private var reflexRound = 0
    private var reflexHits = 0
    private var reflexTotalMs = 0L

    private val ivory = Color.rgb(231, 248, 249)
    private val cyan = Color.rgb(20, 215, 230)
    private val muted = Color.rgb(140, 178, 185)
    private val dark = Color.rgb(3, 8, 12)
    private val panel = Color.rgb(13, 28, 35)
    private val panel2 = Color.rgb(19, 42, 50)
    private val orange = Color.rgb(255, 177, 88)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        EveNotificationScheduler.ensureChannel(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(22))
            setBackgroundColor(dark)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        gameBrain = RobotBrain(EveMemory(applicationContext).learnedPersonality())
        robotVoice = RobotVoice()
        eveGameFace = RobotFaceView(this).apply {
            isClickable = false
            isFocusable = false
            setExpression(gameBrain.awakenExperience(), SystemClock.uptimeMillis())
            startAnimation()
        }
        showMenu()
    }

    override fun onStart() {
        super.onStart()
        eveGameFace?.startAnimation()
        EveNotificationScheduler.onForeground(this)
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        eveGameFace?.stopAnimation()
        EveNotificationScheduler.onBackground(this)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        gameLlmRequestSerial++
        gameDialogueView = null
        eveGameFace?.stopAnimation()
        eveGameFace = null
        if (::robotVoice.isInitialized) robotVoice.close()
        chessWorker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Compatibility with older Android navigation")
    override fun onBackPressed() {
        if (game != Game.MENU) {
            showMenu()
        } else {
            super.onBackPressed()
        }
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

    private fun text(value: String, size: Float = 16f, color: Int = ivory, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

    private fun button(value: String, onClick: () -> Unit, accent: Boolean = false): Button =
        Button(this).apply {
            text = value
            textSize = 15f
            setTextColor(if (accent) dark else ivory)
            setBackgroundColor(if (accent) cyan else panel2)
            isAllCaps = false
            minHeight = dp(50)
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setOnClickListener { onClick() }
        }

    private fun addSpace(height: Int = 8) {
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun header(title: String, subtitle: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val companion = eveGameFace ?: RobotFaceView(this).also { eveGameFace = it }
        // The same animated face is reused across game screens; detach it from its old header first.
        (companion.parent as? android.view.ViewGroup)?.removeView(companion)
        row.addView(companion, LinearLayout.LayoutParams(dp(86), dp(72)).apply {
            marginEnd = dp(8)
        })
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labels.addView(text("E V E  •  ساحة التحدّي", 11f, cyan, true))
        labels.addView(text(title, 22f, ivory, true))
        gameDialogueView = text(subtitle, 12f, muted)
        labels.addView(gameDialogueView)
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row, LinearLayout.LayoutParams(-1, -2))
        addSpace(8)
    }

    private fun beginGameSession() {
        gameSessionId++
        gameLlmRequestSerial++
        pendingGameComment = null
        pendingGameCommentRunnable?.let(handler::removeCallbacks)
        pendingGameCommentRunnable = null
        gameDialogueView = null
    }

    /** Use the shared local Qwen engine for contextual game commentary and bounded style hints. */
    private fun queueGameComment(request: PendingGameComment, delayMs: Long) {
        pendingGameComment = request
        pendingGameCommentRunnable?.let(handler::removeCallbacks)
        val task = Runnable {
            val pending = pendingGameComment ?: return@Runnable
            pendingGameComment = null
            pendingGameCommentRunnable = null
            if (pending.sessionId != gameSessionId || pending.game != game || isFinishing || isDestroyed) {
                android.util.Log.d("EVE_GAME_LLM", "COMMENT_DROPPED stale game=${pending.gameName}")
                return@Runnable
            }
            requestModelGameComment(
                pending.gameName, pending.outcome, pending.detail,
                pending.playerScore, pending.eveScore, pending.round, pending.force
            )
        }
        pendingGameCommentRunnable = task
        handler.postDelayed(task, delayMs.coerceAtLeast(250L))
    }

    private fun requestModelGameComment(
        gameName: String,
        outcome: String,
        detail: String = "",
        playerScore: Int? = null,
        eveScore: Int? = null,
        round: Int? = null,
        force: Boolean = false
    ) {
        val requestId = ++gameLlmRequestSerial
        val sessionAtRequest = gameSessionId
        val gameAtRequest = game
        val request = PendingGameComment(
            gameName, outcome, detail, playerScore, eveScore, round, force, sessionAtRequest, gameAtRequest
        )
        val model = LocalLanguageBrain.currentInstance()
        if (model == null) {
            android.util.Log.i("EVE_GAME_LLM", "COMMENT_FALLBACK reason=model_instance_unavailable game=$gameName")
            return
        }
        if (!model.isReady) {
            model.ensureReady()
            queueGameComment(request, 1_200L)
            android.util.Log.i("EVE_GAME_LLM", "COMMENT_QUEUED reason=model_not_ready game=$gameName")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastGameLlmRequestAt < 5_000L) {
            queueGameComment(request, 5_000L - (now - lastGameLlmRequestAt))
            android.util.Log.d("EVE_GAME_LLM", "COMMENT_QUEUED cooldown game=$gameName outcome=$outcome")
            return
        }
        pendingGameComment = null
        pendingGameCommentRunnable?.let(handler::removeCallbacks)
        pendingGameCommentRunnable = null
        lastGameLlmRequestAt = now
        val decision = latestGameDecision
        val history = prefs.getString("recent_game_results", "").orEmpty()
            .split("\\n").filter { it.isNotBlank() }.takeLast(3)
        val context = LocalLlmContext(
            latestVisionLabel = null,
            visionConfidence = 0f,
            visionObservationAgeMs = Long.MAX_VALUE,
            currentMood = decision?.mood?.name ?: EveMood.NEUTRAL.name,
            moodReason = decision?.reason.orEmpty().take(90),
            personality = gameMemory.learnedPersonality(),
            recentObservations = emptyList(),
            recentDialogues = gameMemory.recentDialogues(3),
            recentReflections = gameMemory.recentReflections(2)
        )
        model.commentOnGame(
            gameName = gameName,
            outcome = outcome,
            detail = detail,
            playerScore = playerScore,
            eveScore = eveScore,
            round = round,
            difficulty = difficulty,
            recentResults = history,
            context = context
        ) { result ->
            if (isFinishing || isDestroyed || requestId != gameLlmRequestSerial ||
                sessionAtRequest != gameSessionId || gameAtRequest != game
            ) return@commentOnGame

            val generated = result.getOrNull()
            val reply = generated?.replyArabic?.trim()?.take(320).orEmpty()
            if (generated == null || reply.isBlank()) {
                val error = result.exceptionOrNull()
                android.util.Log.w(
                    "EVE_GAME_LLM",
                    "COMMENT_FAILED game=$gameName outcome=$outcome; kept immediate local fallback",
                    error
                )
                if (gameLlmRequestSerial == requestId &&
                    error?.message.orEmpty().contains("مشغول بطلب سابق")
                ) {
                    queueGameComment(
                        PendingGameComment(
                            gameName, outcome, detail, playerScore, eveScore, round, force,
                            sessionAtRequest, gameAtRequest
                        ),
                        650L
                    )
                }
                return@commentOnGame
            }
            generated.intentHint?.takeIf {
                it in setOf("play", "focus", "observe", "explore", "connect", "rest", "regulate")
            }?.let { modelGameIntent = it }
            val languageDecision = gameBrain.integrateLanguageInsight(
                moodHint = generated.moodHint,
                intentHint = generated.intentHint,
                confidence = generated.confidence,
                note = generated.reflectionArabic.ifBlank { "game event $gameName/$outcome" },
                source = "game_$gameName"
            )
            latestGameDecision = languageDecision
            eveGameFace?.setExpression(languageDecision, SystemClock.uptimeMillis())
            gameDialogueView?.text = reply
            if (gameName == "paint_shot") {
                paintShotMessage = reply
                paintShotMessageView?.text = reply
            }
            if (gameName == "target") statusText = reply
            if (gameName == "memory" && !memoryShowing) memoryCue?.text = reply
            prefs.edit()
                .putString("last_game_reply", reply)
                .putLong("last_game_reply_at", System.currentTimeMillis())
                .putString("last_game_reply_game", gameName)
                .putString("last_game_reply_outcome", outcome)
                .putString("last_game_reply_source", "local_qwen")
                .apply()
            android.util.Log.i(
                "EVE_GAME_LLM",
                "COMMENT_OK source=local_qwen game=$gameName outcome=$outcome " +
                    "mood=${languageDecision.mood} intent=$modelGameIntent chars=${reply.length}"
            )
        }
    }

    // Procedural dialogue is the immediate fallback while Qwen downloads, is busy, or errors.
    private var lastGeneratedGameReply = ""
    private var generatedReplyCount = 0

    private fun generateGameReply(gameName: String, outcome: String, detail: String = "", score: Int? = null, round: Int? = null): String {
        // This is a fast local fallback only. The local language model replaces it with a fresh,
        // context-grounded reaction as soon as inference is available.
        val decision = if (::gameBrain.isInitialized) (latestGameDecision ?: gameBrain.gameOutcome(gameName, outcome)) else null
        val mood = decision?.mood ?: EveMood.CURIOUS
        val seed = SystemClock.elapsedRealtime() / 97L + ++generatedReplyCount * 31L + (score ?: 0) * 13L + (round ?: 0) * 7L
        fun pick(values: List<String>, salt: Long = 0L): String {
            var i = ((seed + salt) % values.size).toInt(); if (i < 0) i += values.size
            var value = values[i]; if (value == lastGeneratedGameReply && values.size > 1) value = values[(i + 1) % values.size]
            return value
        }
        val label = when (gameName.lowercase()) { "xo" -> "إكس-أو"; "rps" -> "حجر ورقة مقص"; "chess" -> "الشطرنج"; "paint_shot" -> "تصويب الطلاء"; "target" -> "التصويب"; "memory" -> "اختبار الذاكرة"; "reflex" -> "اختبار ردّ الفعل"; else -> "التحدّي" }
        val opening = when (outcome.lowercase()) {
            "player", "success", "hit" -> pick(listOf("أوه، " + label + "! هذه الجولة لك.", "حركة موفّقة؛ سجّلت لك نقطة.", "أعجبتني دقّتك، لقد تفوّقت في هذه المحاولة.", "سأحتفظ بهذه النتيجة للجولة القادمة."))
            "robot" -> pick(listOf("هذه المرة مالت النتيجة إليّ في " + label + ".", "وجدت فرصة مناسبة وفزت بهذه الجولة.", "نجحت خطتي الآن، لكن الجولة التالية قد تختلف.", "نقطة لي هذه المرة؛ لنرَ كيف ستغيّر أسلوبك."))
            "draw" -> pick(listOf("تعادل مثير في " + label + ".", "لم يظهر فائز هذه المرة.", "تقاربنا في النتيجة؛ سأختبر طريقة أخرى."))
            "miss", "wrong" -> pick(listOf("لم تصب الهدف هذه المرة؛ سأراقب نتيجتك التالية.", "قريبة! عدّل توقيتك وجرب من جديد.", "هذه المحاولة لم تُصب، لكن يمكنك تغيير استراتيجيتك."))
            "start" -> pick(listOf("بدأت " + label + "! سأكيّف ردة فعلي مع الجولة.", "حسنًا، دخلنا " + label + ". ورّني خطتك.", "الجولة بدأت؛ سأستخدم نتيجتها لأختار تعليقي التالي."))
            else -> pick(listOf("أراقب مجريات " + label + " وأحدّث تقييمي.", "هذه معلومة جديدة عن الجولة؛ لنرَ ما يحدث بعدها.", "سأبني ردي التالي على نتيجة هذه الحركة."))
        }
        val moodLine = when (mood) {
            EveMood.HAPPY, EveMood.FAMILIAR -> pick(listOf("أنا راضية عن هذه الجولة.", "أحبّ المنافسة الممتعة هكذا.", "هذا منحني دفعة حماس إضافية."), 5L)
            EveMood.PLAYFUL -> pick(listOf("لا تظن أنني سأكرر الحركة نفسها!", "لديّ حيلة أخرى، لكن لن أكشفها الآن.", "التحدّي صار ألطف؛ هيا نكمل."), 11L)
            EveMood.FOCUSED -> pick(listOf("أركّز على الأنماط بدل التخمين.", "سأقارن النتيجة بالحركات السابقة.", "أحتاج إلى حركة أدق في الجولة القادمة."), 17L)
            EveMood.IRRITATED, EveMood.ANGRY -> pick(listOf("أحتاج إلى لحظة لإعادة ضبط خطتي.", "أربكت الجولة إيقاعي قليلًا؛ سأعيد المحاولة بهدوء.", "لن أكرر الخطة نفسها بلا تفكير."), 23L)
            else -> pick(listOf("سأحتفظ بهذه الملاحظة للجولة التالية.", "دعنا نرى إن كان نمط جديد سيظهر.", "النتيجة تساعدني على تعديل أسلوبي."), 29L)
        }
        val progress = (if (score != null && round != null) " النتيجة: " + score + " من " + round + "." else "") + (if (detail.isNotBlank()) " " + detail.trim().take(120) else "")
        var reply = opening + " " + moodLine + progress
        if (reply == lastGeneratedGameReply) reply = opening + " " + pick(listOf("سأبدّل خطتي في الجولة التالية.", "لنجرّب مسارًا مختلفًا الآن.", "سأقارن المحاولة القادمة بهذه النتيجة."), 37L) + progress
        lastGeneratedGameReply = reply
        prefs.edit().putString("last_game_reply", reply).putLong("last_game_reply_at", System.currentTimeMillis()).putString("last_game_reply_game", gameName).putString("last_game_reply_outcome", outcome).apply()
        android.util.Log.i("EVE_GAME_DIALOGUE", "game=" + gameName + " outcome=" + outcome + " mood=" + mood + " reply=" + reply)
        return reply
    }

    /** Game events update the affect engine; starts and final results invoke the local LLM. */
    private fun reactWithEve(
        gameName: String,
        outcome: String,
        detail: String = "",
        playerScore: Int? = null,
        eveScore: Int? = null,
        round: Int? = null
    ) {
        if (!::gameBrain.isInitialized) return
        val event = outcome.lowercase()
        val decision = if (gameName.equals("chess", ignoreCase = true)) {
            when (event) {
                "player" -> gameBrain.gameOutcome("chess", "miss")
                "robot" -> gameBrain.gameOutcome("chess", "robot")
                "draw" -> gameBrain.gameOutcome("chess", "draw")
                "player_capture", "player_check", "player_checkmate" -> gameBrain.gameOutcome("chess", "miss")
                "eve_capture", "eve_check", "eve_checkmate" -> gameBrain.gameOutcome("chess", "robot")
                else -> gameBrain.integrateLanguageInsight(
                    moodHint = null, intentHint = "focus", confidence = 0.58f,
                    note = detail, source = "chess_turn"
                )
            }
        } else gameBrain.gameOutcome(gameName, outcome)
        latestGameDecision = decision
        eveGameFace?.setExpression(decision, SystemClock.uptimeMillis())
        android.util.Log.i("EVE_GAME", "game=$gameName outcome=$outcome mood=${decision.mood} reason=${decision.reason}")
        if (::robotVoice.isInitialized) {
            val cue = when {
                gameName.equals("chess", ignoreCase = true) -> when (event) {
                    "start" -> "game_start"
                    "player" -> "chess_loss"
                    "robot" -> "chess_win"
                    "player_capture", "eve_capture" -> "chess_capture"
                    "player_check", "eve_check" -> "chess_check"
                    "player_move" -> "chess_move"
                    "eve_move" -> "chess_robot_move"
                    "player_checkmate" -> "chess_loss"
                    "eve_checkmate" -> "chess_win"
                    else -> null
                }
                event in setOf("player", "success", "win") -> "game_win"
                event in setOf("robot", "miss", "loss") -> "game_loss"
                event == "draw" -> "game_draw"
                event == "start" -> "game_start"
                else -> null
            }
            if (cue != null) robotVoice.playGameCue(cue)
        }
        val metrics = currentGameMetrics(gameName)
        val resolvedPlayerScore = playerScore ?: metrics.first
        val resolvedEveScore = eveScore ?: metrics.second
        val resolvedRound = round ?: metrics.third
        if (outcome == "start") {
            requestModelGameComment(
                gameName, outcome,
                detail.ifBlank { "بدأت مباراة جديدة. أنشئ رد فعل شخصيًا مناسبًا للعبة ولا تخترع نتيجة." },
                playerScore = resolvedPlayerScore ?: 0,
                eveScore = resolvedEveScore ?: 0,
                round = resolvedRound ?: 0,
                force = true
            )
            return
        }
        val terminal = when (game) {
            Game.XO -> xoFinished
            Game.CHESS -> chessFinished
            Game.PAINT_SHOT -> paintShotFinished
            Game.TARGET -> targetFinished
            Game.MEMORY -> memoryFinished
            Game.REFLEX -> reflexFinished
            Game.RPS, Game.MENU -> false
        }
        val eventDetail = detail.ifBlank {
            when (gameName.lowercase()) {
                "chess" -> "حدث شطرنج=$outcome؛ نقلة=$chessPly؛ تقييم القطع للاعب=${chessMaterial(true)} ولإيف=${chessMaterial(false)}."
                "rps" -> "الجولة $rpsRound؛ نتيجة الجولة=$outcome؛ سجل محلي لأسلوب الاختيار."
                "paint_shot" -> "التصويبات $paintShotScore من $paintShotRound؛ الحدث=$outcome."
                "target" -> "إصابات الهدف $targetScore من $targetRound؛ الحدث=$outcome."
                "xo" -> "علامات اللاعب=${xo.count { it == 'X' }} وإيف=${xo.count { it == 'O' }}؛ الحركة $xoHumanMoves."
                "memory" -> "مستوى الذاكرة $memoryLevel؛ طول التسلسل ${sequence.size}؛ الحدث=$outcome."
                "reflex" -> "جولات الاستجابة $reflexRound؛ إصابات اللاعب $reflexHits؛ الحدث=$outcome."
                else -> "اللعبة=$gameName؛ الحدث=$outcome."
            }
        }
        if (terminal && game != Game.RPS) {
            requestModelGameComment(
                gameName, outcome,
                (eventDetail + " انتهت المباراة؛ علّق على النتيجة الحقيقية ورد الفعل المناسب.").take(220),
                playerScore = resolvedPlayerScore,
                eveScore = resolvedEveScore,
                round = resolvedRound,
                force = true
            )
        } else {
            // Rapid events are coalesced so the freshest event wins instead of queueing stale reactions.
            requestModelGameComment(
                gameName, outcome, eventDetail.take(220),
                resolvedPlayerScore, resolvedEveScore, resolvedRound, force = false
            )
        }
    }

    private fun currentGameMetrics(gameName: String): Triple<Int?, Int?, Int?> = when (gameName.lowercase()) {
        "chess" -> Triple(chessMaterial(true), chessMaterial(false), chessPly)
        "rps" -> Triple(rpsScoreHuman, rpsScoreEve, rpsRound)
        "paint_shot" -> Triple(paintShotScore, (paintShotRound - paintShotScore).coerceAtLeast(0), paintShotRound)
        "target" -> Triple(targetScore, (targetRound - targetScore).coerceAtLeast(0), targetRound)
        "xo" -> Triple(xo.count { it == 'X' }, xo.count { it == 'O' }, xoHumanMoves)
        "memory" -> Triple(memoryLevel, 0, sequence.size)
        "reflex" -> Triple(reflexHits, (reflexRound - reflexHits).coerceAtLeast(0), reflexRound)
        else -> Triple(null, null, null)
    }

    private fun chessMaterial(white: Boolean): Int = chess.position.board.sumOf { piece ->
        if (piece == '.' || piece.isUpperCase() != white) 0 else when (piece.uppercaseChar()) {
            'P' -> 1
            'N', 'B' -> 3
            'R' -> 5
            'Q' -> 9
            'K' -> 0
            else -> 0
        }
    }

    private fun addWideButton(label: String, action: () -> Unit, accent: Boolean = false) {
        root.addView(button(label, action, accent), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })
    }

    private fun showMenu() {
        beginGameSession()
        game = Game.MENU
        handler.removeCallbacksAndMessages(null)
        root.removeAllViews()
        header("اختر لعبتنا", "كل الألعاب تعمل دون إنترنت. أحتفظ بنتائجك على هذا الهاتف.")
        addWideButton("♟  الشطرنج — أنا الأسود وأنت الأبيض", ::startChess, true)
        addWideButton("⭕  إكس-أو — أحاول ألّا أترك لك فرصة", ::startXo)
        addWideButton("✊  حجر • ورقة • مقص — أتعلّم من اختياراتك", ::startRps)
        addWideButton("🎨  Paint Shot — صوّب على الهدف المتحرّك", ::startPaintShot, true)
        addWideButton("📷  Paint Shot عبر الكاميرا (مثل EMO)", ::startCameraShots, true)
        addWideButton("🎯  إصابة الهدف — هل ردّ فعلك أسرع؟", ::startTarget)
        addWideButton("◈  ذاكرة الأنماط — كرّر تسلسل الإضاءة", ::startMemory)
        addWideButton("⚡  اختبار سرعة الاستجابة", ::startReflex)
        addWideButton("🙈  الغميضة وألعاب الجسد المتصل", { startActivity(Intent(this, EmbodiedGamesActivity::class.java)) }, true)
        addWideButton("🤖  ألعاب الجسد — الغميضة والتحديات البصرية", {
            startActivity(Intent(this, EmbodiedGamesActivity::class.java))
        }, true)
        addSpace(12)
        root.addView(text("شخصيتي في اللعب", 18f, cyan, true))
        root.addView(text(
            "أتذكّر الفوز والخسارة. أستطيع رفع الصعوبة تدريجيًا، وأعود إلى آخر تحدٍّ برسالة مناسبة. " +
                "هذه شخصية برمجية محلية، وليست وعيًا حقيقيًا.", 14f, muted
        ))
        addSpace(12)
        addWideButton("عودة إلى وجه إيف", { finish() })
    }

    /** Opens live camera mode with offline, purely visual virtual projectile effects. */
    private fun startCameraShots() {
        startActivity(Intent(this, CameraShotActivity::class.java))
    }

    private fun saveResult(
        name: String,
        result: String,
        detail: String = "",
        playerScore: Int? = null,
        eveScore: Int? = null,
        round: Int? = null
    ) {
        EveNotificationScheduler.recordGameResult(this, name, result)
        val historyLine = name + ":" + result
        val history = prefs.getString("recent_game_results", "").orEmpty()
            .split("\\n").filter { it.isNotBlank() }
        prefs.edit().putString("recent_game_results", (history + historyLine).takeLast(8).joinToString("\\n")).apply()
        reactWithEve(name, result, detail, playerScore, eveScore, round)
        val ratingKey = "rating:" + name
        val old = prefs.getInt(ratingKey, 1000)
        val update = when (result) { "player" -> old + 18; "robot" -> old - 16; else -> old + 2 }
        prefs.edit().putInt(ratingKey, update.coerceIn(700, 2000)).apply()
        difficulty = when {
            prefs.getInt("games:" + name + ":player", 0) >= 3 -> 3
            prefs.getInt("games:" + name + ":robot", 0) >= 3 -> 2
            else -> 1
        }
        if (name == "xo") adaptXoDifficulty(result)
    }

    /** Two consecutive results nudge the next X/O match without abruptly changing mid-game. */
    private fun adaptXoDifficulty(result: String) {
        var level = prefs.getInt("difficulty:xo", xoDifficulty).coerceIn(1, 3)
        var playerStreak = prefs.getInt("difficulty:xo:playerStreak", 0)
        var robotStreak = prefs.getInt("difficulty:xo:robotStreak", 0)
        when (result) {
            "player" -> { playerStreak++; robotStreak = 0 }
            "robot" -> { robotStreak++; playerStreak = 0 }
            else -> { playerStreak = 0; robotStreak = 0 }
        }
        if (playerStreak >= 2) {
            if (level < 3) level++
            playerStreak = 0
        } else if (robotStreak >= 2) {
            if (level > 1) level--
            robotStreak = 0
        }
        prefs.edit()
            .putInt("difficulty:xo", level)
            .putInt("difficulty:xo:playerStreak", playerStreak)
            .putInt("difficulty:xo:robotStreak", robotStreak)
            .apply()
        xoDifficulty = level
    }

    private fun addResultLine(gameName: String, result: String, line: String) {
        if (result.isNotBlank()) saveResult(gameName, result)
        statusText = line
    }

    // ------------------------------------------------------------------ Tic-tac-toe
    private fun startXo() {
        beginGameSession()
        game = Game.XO
        xoDifficulty = prefs.getInt("difficulty:xo", 2).coerceIn(1, 3)
        reactWithEve("xo", "start")
        xo.fill(' ')
        xoFinished = false
        xoHumanMoves = 0
        renderXo("أنت ✕. أنا ○. سأحاول قراءة خياراتك.")
    }

    private fun renderXo(message: String) {
        root.removeAllViews()
        header("إكس-أو", message)
        val grid = GridLayout(this).apply { columnCount = 3 }
        for (i in 0..8) {
            val cell = button(if (xo[i] == ' ') " " else xo[i].toString(), { moveXo(i) }, xo[i] != ' ')
            cell.textSize = 28f
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0; height = dp(92)
                columnSpec = GridLayout.spec(i % 3, 1f)
                rowSpec = GridLayout.spec(i / 3)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, -2))
        root.addView(text(
            "مستوى إيف: $xoDifficulty/3 • سجلّك: فوز ${prefs.getInt("games:xo:player", 0)} • " +
                "خسارة ${prefs.getInt("games:xo:robot", 0)} • تعادل ${prefs.getInt("games:xo:draw", 0)}",
            14f, muted
        ))
        addWideButton("تغيير مستوى التحدّي: $xoDifficulty/3", {
            xoDifficulty = xoDifficulty % 3 + 1
            prefs.edit().putInt("difficulty:xo", xoDifficulty).apply()
            renderXo(when (xoDifficulty) {
                1 -> "سأجرّب أسلوبًا أخفّ؛ يمكنك استغلال أي ثغرة."
                2 -> "سأوازن بين الدفاع والهجوم. لا تمنحني فرصة سهلة."
                else -> "المستوى الصعب: سأحسب أفضل نتيجة ممكنة لكل حركة."
            })
        })
        addWideButton("جولة جديدة", ::startXo)
        addWideButton("قائمة الألعاب", ::showMenu)
    }

    private fun winner(board: CharArray): Char? {
        val lines = arrayOf(
            intArrayOf(0,1,2), intArrayOf(3,4,5), intArrayOf(6,7,8),
            intArrayOf(0,3,6), intArrayOf(1,4,7), intArrayOf(2,5,8),
            intArrayOf(0,4,8), intArrayOf(2,4,6)
        )
        for (line in lines) if (board[line[0]] != ' ' && board[line[0]] == board[line[1]] && board[line[1]] == board[line[2]]) return board[line[0]]
        return null
    }

    private fun xoScore(board: CharArray, maximizing: Boolean, depth: Int): Int {
        val won = winner(board)
        if (won == 'O') return 10 - depth
        if (won == 'X') return depth - 10
        if (board.none { it == ' ' }) return 0
        if (maximizing) {
            var best = -100
            for (i in board.indices) if (board[i] == ' ') {
                board[i] = 'O'
                best = max(best, xoScore(board, false, depth + 1))
                board[i] = ' '
            }
            return best
        }
        var best = 100
        for (i in board.indices) if (board[i] == ' ') {
            board[i] = 'X'
            best = minOf(best, xoScore(board, true, depth + 1))
            board[i] = ' '
        }
        return best
    }

    private fun moveXo(index: Int) {
        if (game != Game.XO || xoFinished || xo[index] != ' ') return
        xo[index] = 'X'; xoHumanMoves++
        val humanMoveDetail = "اللاعب وضع X في الخانة $index. عدد حركات اللاعب $xoHumanMoves؛ " +
            "لوحة إكس-أو الحالية: اللاعب ${xo.count { it == 'X' }} وإيف ${xo.count { it == 'O' }}."
        val humanWin = winner(xo)
        if (humanWin == 'X') {
            xoFinished = true; saveResult("xo", "player"); renderXo("فزت! هذه الجولة لك. سأغيّر خطتي."); return
        }
        if (xo.none { it == ' ' }) {
            xoFinished = true; saveResult("xo", "draw"); renderXo("تعادلنا. لم تمنحني فرصة سهلة."); return
        }
        val available = xo.indices.filter { xo[it] == ' ' }
        val scoredMoves = available.map { move ->
            xo[move] = 'O'
            val score = xoScore(xo, false, 0)
            xo[move] = ' '
            move to score
        }
        // Never miss an immediate win, even on the easier setting.
        val immediateWins = scoredMoves.filter { (move, _) ->
            xo[move] = 'O'
            val winsNow = winner(xo) == 'O'
            xo[move] = ' '
            winsNow
        }
        val chosen = when {
            immediateWins.isNotEmpty() -> immediateWins[random.nextInt(immediateWins.size)].first
            xoDifficulty == 1 -> {
                // Friendly mode blocks an immediate one-move win but may miss deeper forks.
                val safe = scoredMoves.filter { (move, _) ->
                    xo[move] = 'O'
                    val givesHumanImmediateWin = xo.indices.any { humanMove ->
                        if (xo[humanMove] != ' ') false
                        else {
                            xo[humanMove] = 'X'
                            val wins = winner(xo) == 'X'
                            xo[humanMove] = ' '
                            wins
                        }
                    }
                    xo[move] = ' '
                    !givesHumanImmediateWin
                }
                val pool = safe.ifEmpty { scoredMoves }
                pool[random.nextInt(pool.size)].first
            }
            xoDifficulty == 2 -> {
                // Tactical mode prefers a forced win, then a draw, and avoids a forced loss.
                fun outcomeTier(score: Int) = when {
                    score > 0 -> 2
                    score == 0 -> 1
                    else -> 0
                }
                val tier = scoredMoves.maxOf { outcomeTier(it.second) }
                val candidates = scoredMoves.filter { outcomeTier(it.second) == tier }
                // The validated focus/observe hint changes tie-breaking only; minimax still
                // determines safe candidates and legal board state.
                if (modelGameIntent in setOf("focus", "observe")) {
                    val bestWithinTier = candidates.maxOf { it.second }
                    val tactical = candidates.filter { it.second == bestWithinTier }
                    tactical[random.nextInt(tactical.size)].first
                } else {
                    candidates[random.nextInt(candidates.size)].first
                }
            }
            else -> {
                // Hard mode picks the best minimax score (fastest win / slowest loss).
                val bestScore = scoredMoves.maxOf { it.second }
                val candidates = scoredMoves.filter { it.second == bestScore }
                candidates[random.nextInt(candidates.size)].first
            }
        }
        xo[chosen] = 'O'
        reactWithEve(
            "xo", "move",
            "$humanMoveDetail إيف ردت بوضع O في الخانة $chosen. اختارت الحركة بمقارنة احتمالات الفوز والتعادل.",
            xo.count { it == 'X' }, xo.count { it == 'O' }, xoHumanMoves
        )
        when {
            winner(xo) == 'O' -> {
                xoFinished = true
                saveResult("xo", "robot")
                renderXo("هذه الجولة لي. راجعت احتمالاتك؛ هل تستطيع قلب النتيجة في مباراة العودة؟")
            }
            xo.none { it == ' ' } -> {
                xoFinished = true
                saveResult("xo", "draw")
                renderXo("تعادل قوي! سأبحث عن خطة أفضل، وأنت حاول كسر دفاعي.")
            }
            else -> {
                val taunt = when (xoDifficulty) {
                    1 -> listOf("هذه حركة ودّية. هل لاحظت زاوية أخرى؟", "سأترك لك مساحة للمناورة… دورك.")
                    2 -> listOf("أراقب خطوتك التالية؛ هل ستستطيع منع خطتي؟", "أغلقت طريقًا وفتحت آخر. دورك.")
                    else -> listOf("حسبت عدة نهايات؛ اختيارك الآن سيغيّر المباراة.", "المستوى الصعب لا يرحم الأخطاء الصغيرة. دورك.")
                }
                renderXo(taunt[random.nextInt(taunt.size)])
            }
        }
    }

    // ------------------------------------------------------------------ Rock / Paper / Scissors
    private fun startRps() {
        beginGameSession()
        game = Game.RPS
        reactWithEve("rps", "start")
        playerRpsHistory.clear()
        playerRpsHistory.addAll(
            prefs.getString("rps:move_history", "").orEmpty()
                .split(',').mapNotNull { it.toIntOrNull()?.takeIf { move -> move in 0..2 } }
                .takeLast(18)
        )
        rpsScoreHuman = 0; rpsScoreEve = 0; rpsRound = 0
        val opening = if (playerRpsHistory.size >= 5)
            "أتذكر نمطًا من مبارياتنا السابقة. حاول أن تخدعني هذه المرة!"
        else
            "لنبدأ؛ سأراقب اختياراتك وأحاول قراءة نمطك."
        renderRps("$opening قبضة = حجر، كف مفتوح = ورقة، إصبعان = مقص.")
    }

    private fun renderRps(message: String) {
        root.removeAllViews()
        header("حجر • ورقة • مقص", message)
        root.addView(text("أنت $rpsScoreHuman   —   إيف $rpsScoreEve", 21f, cyan, true))
        root.addView(text("وضع الرؤية: افتح كاميرا اللعب لتقرأ إيف إشارة اليد دون لمس خيارات الحركة.", 14f, cyan))
        addWideButton("📷 العب بإشارات اليد عبر الكاميرا", { startActivity(Intent(this, CameraShotActivity::class.java).putExtra("eve_camera_game", "rps")) }, true)
        addSpace(8)
        root.addView(text("الجولات: $rpsRound • تتعلم إيف أنماط اختيارك من سجل الجولات المحلي.", 13f, muted))
        addWideButton("لعب احتياطي باللمس", {
            root.removeAllViews()
            header("حجر • ورقة • مقص", "وضع اللمس الاحتياطي")
            root.addView(text("أنت $rpsScoreHuman   —   إيف $rpsScoreEve", 21f, cyan, true))
            listOf("✊ حجر" to 0, "✋ ورقة" to 1, "✌ مقص" to 2).forEach { (label, move) ->
                addWideButton(label, { playRps(move) }, move == 1)
            }
            addWideButton("عودة إلى وضع الرؤية", { renderRps("أرني إشارة يدك أمام الكاميرا.") })
            addWideButton("قائمة الألعاب", ::showMenu)
        })
        addWideButton("تصفير المباراة", ::startRps)
        addWideButton("قائمة الألعاب", ::showMenu)
    }

    private fun playRps(player: Int) {
        if (game != Game.RPS) return
        val frequencies = (0..2).map { move -> playerRpsHistory.count { it == move } }
        val common = frequencies.maxOrNull() ?: 0
        val mostUsed = (0..2).filter { frequencies[it] == common }
        val leastUsedCount = frequencies.minOrNull() ?: 0
        val leastUsed = (0..2).filter { frequencies[it] == leastUsedCount }
        val inferredCounter = if (playerRpsHistory.size >= 3 && mostUsed.size == 1) (mostUsed[0] + 1) % 3 else null
        val robot = when (modelGameIntent) {
            "focus", "observe" -> inferredCounter ?: random.nextInt(3)
            "explore" -> {
                val lessSeenPlayerChoice = leastUsed[random.nextInt(leastUsed.size)]
                (lessSeenPlayerChoice + 1) % 3
            }
            "play", "connect", "rest", "regulate" -> {
                if (playerRpsHistory.size >= 6 && mostUsed.size == 1) inferredCounter!!
                else random.nextInt(3)
            }
            else -> inferredCounter ?: random.nextInt(3)
        }
        playerRpsHistory.add(player)
        if (playerRpsHistory.size > 24) playerRpsHistory.removeAt(0)
        prefs.edit().putString("rps:move_history", playerRpsHistory.joinToString(",")).apply()
        rpsRound++
        val labels = listOf("حجر", "ورقة", "مقص")
        val result = when {
            player == robot -> "draw"
            (player + 1) % 3 == robot -> "robot"
            else -> "player"
        }
        when (result) { "player" -> rpsScoreHuman++; "robot" -> rpsScoreEve++ }
        val detail = "الجولة $rpsRound: اخترت ${labels[player]} واخترت إيف ${labels[robot]}؛ " +
            "النتيجة $result؛ اللاعب $rpsScoreHuman مقابل إيف $rpsScoreEve. " +
            if (playerRpsHistory.size >= 4) "إيف تحلل نمط اختياراتك السابقة." else "إيف ما زالت تجمع أمثلة من اختياراتك."
        saveResult("rps", result, detail, rpsScoreHuman, rpsScoreEve, rpsRound)
        renderRps(generateGameReply("rps", result, detail, rpsScoreEve, rpsRound))
    }

    // ------------------------------------------------------------------ Chess
    private fun startChess() {
        beginGameSession()
        game = Game.CHESS
        chess.reset()
        chessSelected = null
        chessFinished = false
        chessThinking = false
        chessPly = 0
        lastChessMove = null
        reactWithEve("chess", "start", "بدأت مباراة الشطرنج من الوضع القياسي؛ أنت الأبيض وإيف الأسود.")
        renderChess("أنت الأبيض، وأنا الأسود. أراقب كل نقلة وأغيّر ردة فعلي وفق ما يحدث.")
    }

    private fun renderChess(message: String) {
        root.removeAllViews()
        header("الشطرنج", message)
        val board = chess.position.board
        val grid = GridLayout(this).apply { columnCount = 8 }
        val unicode = mapOf(
            'K' to "♔", 'Q' to "♕", 'R' to "♖", 'B' to "♗", 'N' to "♘", 'P' to "♙",
            'k' to "♚", 'q' to "♛", 'r' to "♜", 'b' to "♝", 'n' to "♞", 'p' to "♟", '.' to ""
        )
        val legalFrom = chessSelected?.let { from -> chess.legalMoves(from).map { it.to }.toSet() } ?: emptySet()
        for (i in 0..63) {
            val selected = chessSelected == i
            val possible = i in legalFrom
            val lastMoveFrom = lastChessMove?.from == i
            val lastMoveTo = lastChessMove?.to == i
            val shade = if ((i / 8 + i % 8) % 2 == 0) Color.rgb(29, 54, 61) else Color.rgb(172, 207, 207)
            val cell = button(unicode[board[i]] ?: "", { selectChess(i) }, selected || possible)
            cell.textSize = 27f
            cell.setTextColor(if (board[i] != '.' && board[i].isUpperCase()) Color.WHITE else Color.BLACK)
            cell.setBackgroundColor(
                when {
                    selected -> cyan
                    possible -> Color.rgb(55, 100, 94)
                    lastMoveTo -> Color.rgb(98, 133, 95)
                    lastMoveFrom -> Color.rgb(68, 91, 79)
                    else -> shade
                }
            )
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0; height = dp(43)
                columnSpec = GridLayout.spec(i % 8, 1f)
                rowSpec = GridLayout.spec(i / 8)
                setMargins(dp(1), dp(1), dp(1), dp(1))
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, -2))
        val chessState = when {
            chess.isCheckmate() -> "كش مات"
            chess.isStalemate() -> "تعادل"
            chess.isCheck() -> "كش — يجب إيجاد رد"
            chessThinking -> "إيف تفكّر وتحلل النقلات"
            chess.position.whiteToMove -> "دورك"
            else -> "دور إيف"
        }
        val lastMoveLabel = lastChessMove?.let { chess.moveLabel(it) } ?: "بداية المباراة"
        root.addView(text("الحالة: $chessState • آخر نقلة: $lastMoveLabel", 13f, cyan, true))
        addSpace(4)
        root.addView(text("الصعوبة: $difficulty/3 • تذكّرت مبارياتك السابقة: ${prefs.getInt("games:chess:played", 0)}", 13f, muted))
        addWideButton(if (chessThinking) "إيف تفكّر…" else "تغيير الصعوبة: $difficulty", {
            difficulty = difficulty % 3 + 1
            renderChess("سأبحث أعمق في الحركات على المستوى $difficulty.")
        })
        addWideButton("إعادة المباراة", ::startChess)
        addWideButton("قائمة الألعاب", ::showMenu)
    }

    private fun selectChess(square: Int) {
        if (game != Game.CHESS || chessFinished || chessThinking || !chess.position.whiteToMove) return
        if (square !in 0..63) return
        val board = chess.position.board
        val from = chessSelected

        if (from == null) {
            if (board[square] != '.' && board[square].isUpperCase()) {
                chessSelected = square
                robotVoice.playGameCue("chess_select")
                renderChess("اخترت ${squareName(square)}. المس مربعًا أخضر للحركة.")
            } else {
                robotVoice.playGameCue("chess_invalid")
                renderChess("اختر قطعة بيضاء أولًا.")
            }
            return
        }

        if (from == square) {
            chessSelected = null
            robotVoice.playGameCue("chess_select")
            renderChess("ألغيت اختيار القطعة.")
            return
        }

        // Selecting another white piece changes the selection. A black piece is instead
        // treated as a capture destination and is checked against the engine's legal moves.
        if (board[square] != '.' && board[square].isUpperCase()) {
            chessSelected = square
            robotVoice.playGameCue("chess_select")
            renderChess("اخترت ${squareName(square)}. المس مربعًا أخضر للحركة.")
            return
        }

        val move = chess.legalMoves(from).firstOrNull { it.to == square }
        if (move == null) {
            robotVoice.playGameCue("chess_invalid")
            renderChess("هذه الحركة غير قانونية؛ جرّب مربعًا أخضر.")
            return
        }
        val playerCapture = board[move.to] != '.' ||
            (board[move.from].uppercaseChar() == 'P' && move.to == chess.position.enPassant)
        chess.play(move)
        lastChessMove = move
        chessPly++
        val playerEvent = when {
            chess.isCheckmate() -> "player_checkmate"
            chess.isCheck() -> "player_check"
            playerCapture -> "player_capture"
            else -> "player_move"
        }
        val playerDetail = buildString {
            append("اللاعب لعب نقلة ")
            append(chess.moveLabel(move))
            append("؛ الحدث=")
            append(playerEvent)
            append("؛ تقييم القطع: اللاعب ")
            append(chessMaterial(true))
            append(" وإيف ")
            append(chessMaterial(false))
            append(".")
        }
        reactWithEve(
            "chess", playerEvent, playerDetail,
            chessMaterial(true), chessMaterial(false), chessPly
        )
        chessSelected = null
        when {
            chess.isCheckmate() -> {
                chessFinished = true; saveResult("chess", "player")
                renderChess("كش مات! فزت عليّ. سأحتاج إلى مراجعة خطتي.")
            }
            chess.isStalemate() -> {
                chessFinished = true; saveResult("chess", "draw")
                renderChess("تعادل بالخنق. أحسنت الدفاع.")
            }
            else -> {
                renderChess(if (chess.isCheck()) "كش! أبحث عن رد." else "حركتك ${chess.moveLabel(move)}. دوري.")
                chessThinking = true
                val thinkingSession = gameSessionId
                handler.postDelayed({
                    if (game != Game.CHESS || chessFinished || isFinishing ||
                        thinkingSession != gameSessionId
                    ) return@postDelayed
                    gameDialogueView?.text = "أفكر… أوازن بين الهجوم وحماية الملك، وأراجع الردود المحتملة."
                    val searchDifficulty = difficulty
                    val variationSeed = prefs.getInt("games:chess:played", 0).toLong() * 97L + chessPly.toLong()
                    chessWorker.execute {
                        if (thinkingSession != gameSessionId) return@execute
                        val searchResult = runCatching {
                            chess.robotMove(searchDifficulty, variationSeed)
                        }
                        handler.post {
                            if (game != Game.CHESS || chessFinished || isFinishing ||
                                thinkingSession != gameSessionId
                            ) return@post
                            if (searchResult.isFailure) {
                                gameDialogueView?.text = "أعيد التفكير بسرعة حتى لا تتوقف المباراة."
                            }
                            // If deep search fails, use a legal fallback instead of leaving the turn locked.
                            val robotMove = searchResult.getOrNull() ?: chess.legalMoves().firstOrNull()
                            if (robotMove == null) {
                                chessThinking = false
                                chessFinished = true
                                saveResult("chess", if (chess.isCheck()) "player" else "draw", "لا توجد نقلة قانونية لإيف.")
                                renderChess("انتهت المباراة؛ لا توجد لديّ نقلة قانونية.")
                                return@post
                            }
                            val boardBeforeRobotMove = chess.position.board
                            val robotCapture = boardBeforeRobotMove[robotMove.to] != '.' ||
                                (boardBeforeRobotMove[robotMove.from].lowercaseChar() == 'p' &&
                                    robotMove.to == chess.position.enPassant)
                            if (!chess.play(robotMove)) {
                                chessThinking = false
                                renderChess("تغيّر وضع الرقعة أثناء التفكير؛ أراجع الوضع الحالي بدل تنفيذ نقلة غير صالحة.")
                                return@post
                            }
                            lastChessMove = robotMove
                            chessPly++
                            chessThinking = false
                            val eveEvent = when {
                                chess.isCheckmate() -> "eve_checkmate"
                                chess.isCheck() -> "eve_check"
                                robotCapture -> "eve_capture"
                                else -> "eve_move"
                            }
                            val eveDetail = buildString {
                                append("إيف لعبت نقلة ")
                                append(chess.moveLabel(robotMove))
                                append("؛ الحدث=")
                                append(eveEvent)
                                append("؛ تقييم القطع: اللاعب ")
                                append(chessMaterial(true))
                                append(" وإيف ")
                                append(chessMaterial(false))
                                append(".")
                            }
                            reactWithEve(
                                "chess", eveEvent, eveDetail,
                                chessMaterial(true), chessMaterial(false), chessPly
                            )
                            when {
                                chess.isCheckmate() -> {
                                    chessFinished = true
                                    saveResult("chess", "robot", eveDetail, chessMaterial(true), chessMaterial(false), chessPly)
                                    renderChess("كش مات! قرأتُ سلسلة النقلات حتى النهاية. مباراة قوية!")
                                }
                                chess.isStalemate() -> {
                                    chessFinished = true
                                    saveResult("chess", "draw", "انتهت مباراة الشطرنج بالتعادل بالخنق.", chessMaterial(true), chessMaterial(false), chessPly)
                                    renderChess("تعادل بالخنق. لا توجد حركة قانونية؛ أحسنت اللعب.")
                                }
                                else -> {
                                    val response = when {
                                        chess.isCheck() -> "كش عليك! سأراقب كيف تنقذ ملكك في الرد التالي."
                                        robotCapture -> "التقطت قطعة بعد مقارنة التبادل. هل تستطيع استعادة المبادرة؟"
                                        robotMove.from == 4 && robotMove.to == 6 ||
                                            robotMove.from == 4 && robotMove.to == 2 ->
                                            "أمّنت ملكي بالتبييت؛ والآن لنختبر خطتك."
                                        isCenterSquare(robotMove.to) ->
                                            "أحاول تثبيت حضوري في الوسط. دورك—سأعيد الحساب بعد نقلتك."
                                        else -> "لعبت ${chess.moveLabel(robotMove)}. أراجع وضع الرقعة مع كل نقلة؛ دورك."
                                    }
                                    renderChess(response)
                                }
                            }
                        }
                    }
                }, 180L)
            }
        }
    }

    private fun isCenterSquare(square: Int): Boolean {
        val row = square / 8
        val col = square % 8
        return row in 2..5 && col in 2..5
    }

    private fun squareName(i: Int) = "" + ('a'.code + i % 8).toChar() + (8 - i / 8)

    // ------------------------------------------------------------------ EMO-inspired Paint Shot
    // EMO uses glasses, a gun/palm-cannon gesture and "bang!".
    // On a phone, each tap is the virtual "bang": the player must hit a moving target.
    // All projectiles and scoring are drawn locally; no camera or network is required.
    private fun startPaintShot() {
        beginGameSession()
        // The standard Paint Shot mode is a self-contained moving-target game with hit scoring.
        // The separate camera mode is available from the menu and has its own moving AR target.
        paintShotArena?.setGameActive(false)
        game = Game.PAINT_SHOT
        paintShotScore = 0
        paintShotRound = 0
        paintShotFinished = false
        paintShotMessage = "👓 النظارة مفعّلة! المس الهدف التركوازي المتحرك وأطلق طلقات طلاء."
        paintShotArena = null
        paintShotProgressView = null
        paintShotMessageView = null
        reactWithEve("paint_shot", "start")
        renderPaintShot()
    }

    private fun renderPaintShot() {
        paintShotArena?.setGameActive(false)
        root.removeAllViews()
        header("🎨 Paint Shot", "تحدّي تصويب مستوحى من EMO — 10 طلقات، دون إنترنت")
        root.addView(text("👓 وضع النظارة مفعّل  •  المس الهدف لتقول «بانغ!»", 14f, cyan, true))
        paintShotProgressView = text("الإصابات: $paintShotScore  •  الطلقات: $paintShotRound / 10", 21f, ivory, true)
        root.addView(paintShotProgressView, LinearLayout.LayoutParams(-1, -2))
        addSpace(4)

        val arena = PaintShotArenaView(this) { result -> handlePaintShotResult(result) }
        paintShotArena = arena
        root.addView(arena, LinearLayout.LayoutParams(-1, dp(390)))
        paintShotMessageView = text(paintShotMessage, 14f, muted)
        root.addView(paintShotMessageView, LinearLayout.LayoutParams(-1, -2))
        addSpace(4)
        addWideButton("جولة جديدة", ::startPaintShot, true)
        addWideButton("العودة إلى ألعاب إيف", ::showMenu)
    }

    private fun handlePaintShotResult(result: PaintShotResult) {
        if (game != Game.PAINT_SHOT || paintShotFinished) return
        paintShotRound = result.shotNumber
        if (result.hit) paintShotScore++
        val outcome = if (result.hit) "success" else "miss"
        val shotDetail = "الطلقة $paintShotRound من 10؛ إصابات اللاعب $paintShotScore؛ " +
            if (result.hit) "أصاب اللاعب الهدف المتحرك." else "أخطأ اللاعب الهدف المتحرك."
        reactWithEve(
            "paint_shot", outcome, shotDetail,
            paintShotScore, paintShotRound - paintShotScore, paintShotRound
        )
        paintShotMessage = generateGameReply("paint_shot", outcome, shotDetail, paintShotScore, paintShotRound)
        android.util.Log.i(
            "EVE_PAINT_SHOT",
            "shot=${paintShotRound} hit=${result.hit} score=${paintShotScore}/${paintShotRound}"
        )

        paintShotProgressView?.text =
            "الإصابات: $paintShotScore  •  الطلقات: $paintShotRound / 10"
        paintShotMessageView?.text = paintShotMessage

        if (paintShotRound >= 10) {
            paintShotFinished = true
            paintShotArena?.setGameActive(false)
            val resultName = when {
                paintShotScore >= 7 -> "player"
                paintShotScore <= 3 -> "robot"
                else -> "draw"
            }
            saveResult("paint_shot", resultName)
            paintShotMessage += "\nانتهى التحدّي: $paintShotScore إصابة من 10."
            paintShotMessageView?.text = paintShotMessage
        }
    }

    // ------------------------------------------------------------------ Target practice
    private fun startTarget() {
        beginGameSession()
        targetMoveTask?.let(handler::removeCallbacks)
        game = Game.TARGET
        targetScore = 0
        targetRound = 0
        targetFinished = false
        targetCell = random.nextInt(9)
        statusText = "اضرب الدائرة التركوازية قبل أن تنتقل إلى مربع آخر."
        reactWithEve("target", "start")
        renderTarget()
        scheduleTargetMove()
    }

    private fun renderTarget() {
        root.removeAllViews()
        header("إصابة الهدف", "المس الدائرة التركوازية قبل أن تنتقل. أمامك عشر محاولات.")
        root.addView(text("الإصابات: $targetScore / $targetRound", 21f, cyan, true))
        val grid = GridLayout(this).apply { columnCount = 3 }
        for (cellIndex in 0..8) {
            val isTarget = cellIndex == targetCell
            val cell = button(if (isTarget) "●" else "·", { hitTarget(cellIndex) }, isTarget)
            cell.textSize = if (isTarget) 31f else 25f
            cell.setTextColor(if (isTarget) dark else muted)
            cell.setBackgroundColor(if (isTarget) cyan else panel2)
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(76)
                columnSpec = GridLayout.spec(cellIndex % 3, 1f)
                rowSpec = GridLayout.spec(cellIndex / 3, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, -2))
        root.addView(text(statusText, 14f, muted))
        addWideButton("ابدأ من جديد", ::startTarget)
        addWideButton("قائمة الألعاب", ::showMenu)
    }

    private fun scheduleTargetMove() {
        targetMoveTask?.let(handler::removeCallbacks)
        if (game != Game.TARGET || targetFinished || isFinishing) return
        val delay = 700L + random.nextLong(650L)
        targetDeadline = SystemClock.elapsedRealtime() + delay
        val task = Runnable {
            if (game != Game.TARGET || targetFinished || isFinishing) return@Runnable
            val previous = targetCell
            val next = random.nextInt(8)
            targetCell = if (next >= previous) next + 1 else next
            statusText = "تحرّك الهدف! تتبّع الدائرة."
            renderTarget()
            scheduleTargetMove()
        }
        targetMoveTask = task
        handler.postDelayed(task, delay)
    }

    private fun hitTarget(cellIndex: Int) {
        if (game != Game.TARGET || targetFinished) return
        val previousTarget = targetCell
        val hit = cellIndex == targetCell
        targetMoveTask?.let(handler::removeCallbacks)
        targetMoveTask = null
        targetRound++
        if (hit) {
            targetScore++
            statusText = listOf(
                "إصابة دقيقة!", "سريع! بدأت أتوقع حركتك.", "هذه نقطة لك."
            )[targetScore % 3]
            reactWithEve(
                "target", "success",
                "الجولة $targetRound من 10؛ أصاب اللاعب الهدف. النقاط $targetScore من $targetRound.",
                targetScore, targetRound - targetScore, targetRound
            )
        } else {
            statusText = "أخطأت هذه المرة؛ انتقل الهدف إلى موضع جديد."
            reactWithEve(
                "target", "miss",
                "الجولة $targetRound من 10؛ أخطأ اللاعب الهدف. النقاط $targetScore من $targetRound.",
                targetScore, targetRound - targetScore, targetRound
            )
        }
        if (targetRound >= 10) {
            targetFinished = true
            val result = when {
                targetScore >= 8 -> "player"
                targetScore <= 3 -> "robot"
                else -> "draw"
            }
            saveResult("target", result)
            statusText += " انتهت الجولة: $targetScore من 10."
        } else {
            // Every attempt advances the target and starts a fresh timer; misses cannot freeze it.
            val next = random.nextInt(8)
            targetCell = if (next >= previousTarget) next + 1 else next
            renderTarget()
            scheduleTargetMove()
            return
        }
        renderTarget()
    }

    // ------------------------------------------------------------------ Pattern memory
    private fun startMemory() {
        beginGameSession()
        game = Game.MEMORY
        reactWithEve("memory", "start")
        sequence.clear()
        memoryLevel = 1; memoryInput = 0
        memoryShowing = false; memoryFinished = false
        sequence.add(random.nextInt(4))
        renderMemory("شاهد التسلسل، ثم كرره بالترتيب.")
        playSequence()
    }

    private fun renderMemory(message: String) {
        root.removeAllViews()
        header("ذاكرة الأنماط", message)
        root.addView(text("المستوى $memoryLevel • طول التسلسل ${sequence.size}", 20f, cyan, true))
        val cuePrompt = when {
            memoryShowing -> "راقب الألوان…"
            message.contains("حان دورك") -> "حان دورك — كرّر التسلسل"
            message.startsWith("صحيح!") -> "جولة جديدة — راقب الألوان…"
            else -> "استعد لمشاهدة الألوان…"
        }
        memoryCue = text(cuePrompt, 19f, ivory, true).apply {
            setBackgroundColor(panel)
            minHeight = dp(70)
        }
        root.addView(memoryCue, LinearLayout.LayoutParams(-1, dp(76)).apply {
            bottomMargin = dp(8)
        })
        val grid = GridLayout(this).apply { columnCount = 2 }
        val names = listOf("أزرق", "أخضر", "برتقالي", "بنفسجي")
        names.forEachIndexed { index, name ->
            val b = button(name, { inputMemory(index) }, false)
            b.setBackgroundColor(listOf(Color.rgb(8,154,185), Color.rgb(38,156,102), Color.rgb(205,116,38), Color.rgb(116,79,191))[index])
            grid.addView(b, GridLayout.LayoutParams().apply {
                width = 0; height = dp(76)
                columnSpec = GridLayout.spec(index % 2, 1f)
                rowSpec = GridLayout.spec(index / 2)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, -2))
        addWideButton("ابدأ التسلسل من جديد", ::startMemory)
        addWideButton("قائمة الألعاب", ::showMenu)
    }

    private fun playSequence() {
        memoryShowing = true
        memoryInput = 0
        memoryCue?.text = "راقب الألوان…"
        memoryCue?.setBackgroundColor(panel)
        var index = 0
        val colorNames = listOf("أزرق", "أخضر", "برتقالي", "بنفسجي")
        val colorValues = listOf(
            Color.rgb(8, 154, 185), Color.rgb(38, 156, 102),
            Color.rgb(205, 116, 38), Color.rgb(116, 79, 191)
        )
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (game != Game.MEMORY || memoryFinished || isFinishing) return
                if (index >= sequence.size) {
                    memoryShowing = false
                    memoryCue?.text = "حان دورك — كرّر التسلسل"
                    memoryCue?.setBackgroundColor(panel)
                    renderMemory("حان دورك. كرّر الألوان بالترتيب.")
                    return
                }
                val cue = sequence[index]
                statusText = "أضئ اللون ${cue + 1}"
                memoryCue?.text = "الإشارة ${index + 1}: ${colorNames[cue]}"
                memoryCue?.setBackgroundColor(colorValues[cue])
                memoryCue?.setTextColor(Color.WHITE)
                index++
                handler.postDelayed(this, 620L)
            }
        }, 600L)
    }

    private fun inputMemory(choice: Int) {
        if (game != Game.MEMORY || memoryFinished) return
        if (memoryShowing) { renderMemory("انتظر حتى ينتهي عرض التسلسل."); return }
        if (sequence.getOrNull(memoryInput) != choice) {
            memoryFinished = true
            saveResult("memory", "robot")
            renderMemory("اقتربت، لكن التسلسل اختلف. وصلت إلى المستوى $memoryLevel.")
            return
        }
        memoryInput++
        if (memoryInput >= sequence.size) {
            memoryLevel++
            reactWithEve(
                "memory", "success",
                "أكمل اللاعب التسلسل كاملًا بنجاح. المستوى التالي $memoryLevel وطول التسلسل الحالي ${sequence.size}.",
                memoryLevel, 0, sequence.size
            )
            if (sequence.size >= 12) {
                memoryFinished = true; saveResult("memory", "player")
                renderMemory("ذاكرة ممتازة! أكملت 12 إشارة وتفوّقت عليّ.")
            } else {
                sequence.add(random.nextInt(4))
                playSequence()
                renderMemory("صحيح! زدت طول التسلسل. استعد للجولة التالية.")
            }
        }
    }

    // ------------------------------------------------------------------ Reflex duel
    private fun startReflex() {
        beginGameSession()
        game = Game.REFLEX
        reflexGameStarted = false
        reflexReady = false
        reflexFinished = false
        reflexRound = 0
        reflexHits = 0
        reflexTotalMs = 0L
        reflexStartedAt = 0L
        reflexDelayUntil = 0L
        reactWithEve("reflex", "start")
        renderReflex("اضغط ابدأ، انتظر ظهور الإشارة ثم المس زر الاستجابة.")
    }

    private fun renderReflex(message: String) {
        root.removeAllViews()
        header("تحدي سرعة الاستجابة", message)
        root.addView(text("النقاط: $reflexHits / $reflexRound", 21f, cyan, true))
        addWideButton(if (reflexReady) "⚡ الآن!" else "ابدأ / استجب", {
            onReflexTap()
        }, reflexReady)
        root.addView(text("لا تضغط مبكرًا؛ سأحسب زمن رد فعلك بالمللي ثانية.", 14f, muted))
        addWideButton("إعادة التحدي", ::startReflex)
        addWideButton("قائمة الألعاب", ::showMenu)
    }

    private fun onReflexTap() {
        if (game != Game.REFLEX || reflexFinished) return
        if (!reflexGameStarted) {
            reflexGameStarted = true
            scheduleReflexSignal()
            renderReflex("انتظر… لا تضغط حتى تظهر ⚡.")
            return
        }
        if (!reflexReady) {
            // A tap during the random wait is a genuine false start, not another start command.
            reflexFinished = true
            reactWithEve("reflex", "miss")
            saveResult("reflex", "robot")
            renderReflex("ضغطت مبكرًا! نقطة لإيف. خذ وقتك وأعد المحاولة.")
            return
        }
        val reaction = (SystemClock.elapsedRealtime() - reflexStartedAt).coerceAtLeast(0L)
        reflexReady = false
        reflexStartedAt = 0L
        reflexRound++
        reflexTotalMs += reaction
        if (reaction < 450L) {
            reflexHits++
            reactWithEve("reflex", "success")
        } else {
            reactWithEve("reflex", "miss")
        }
        if (reflexRound >= 5) {
            reflexFinished = true
            saveResult("reflex", if (reflexHits >= 4) "player" else if (reflexHits <= 1) "robot" else "draw")
            val average = reflexTotalMs / reflexRound.coerceAtLeast(1)
            renderReflex("متوسط ردّك ${average} ms (آخر جولة ${reaction}ms). نتيجتك $reflexHits/5.")
        } else {
            renderReflex("استجابة ${reaction}ms. جولة أخرى—هل تستطيع أن تكون أسرع؟")
            scheduleReflexSignal()
        }
    }

    private fun scheduleReflexSignal() {
        reflexReady = false
        reflexStartedAt = 0L
        val delay = 900L + random.nextLong(1900L)
        reflexDelayUntil = SystemClock.elapsedRealtime() + delay
        handler.postDelayed({
            if (game != Game.REFLEX || reflexFinished || isFinishing) return@postDelayed
            reflexReady = true
            reflexStartedAt = SystemClock.elapsedRealtime()
            renderReflex("⚡ الآن! المس بسرعة.")
        }, delay)
    }
}
