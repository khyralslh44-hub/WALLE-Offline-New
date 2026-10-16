package com.eve.robot

import android.graphics.Color
import android.graphics.Typeface
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
    private var statusText = "سأحاول التكيّف مع أسلوبك بدل تكرار الحركة نفسها."
    private val prefs by lazy { getSharedPreferences("eve_games", MODE_PRIVATE) }

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

    // RPS adapts by choosing a winning response to the player's most common move.
    private val playerRpsHistory = mutableListOf<Int>()
    private var rpsScoreHuman = 0
    private var rpsScoreEve = 0
    private var rpsRound = 0

    // EMO-inspired Paint Shot: local, harmless paint-pulse game.
    private var paintShotScore = 0
    private var paintShotRound = 0
    private var paintShotTargetSide = -1
    private var paintShotFinished = false
    private var paintShotMessage = "ارتدِ النظارة الخيالية، وجّه إيف إلى اليسار أو اليمين، ثم أطلق طلقة طلاء."

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
    private var reflexReady = false
    private var reflexFinished = false
    private var reflexStartedAt = 0L
    private var reflexDelayUntil = 0L
    private var reflexRound = 0
    private var reflexHits = 0

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
        showMenu()
    }

    override fun onStart() {
        super.onStart()
        EveNotificationScheduler.onForeground(this)
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        EveNotificationScheduler.onBackground(this)
        super.onStop()
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
        root.addView(text("E V E   •   ساحة التحدّي", 12f, cyan, true))
        root.addView(text(title, 26f, ivory, true))
        root.addView(text(subtitle, 14f, muted))
        addSpace(10)
    }

    private fun addWideButton(label: String, action: () -> Unit, accent: Boolean = false) {
        root.addView(button(label, action, accent), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })
    }

    private fun showMenu() {
        game = Game.MENU
        handler.removeCallbacksAndMessages(null)
        root.removeAllViews()
        header("اختر لعبتنا", "كل الألعاب تعمل دون إنترنت. أحتفظ بنتائجك على هذا الهاتف.")
        addWideButton("♟  الشطرنج — أنا الأسود وأنت الأبيض", ::startChess, true)
        addWideButton("⭕  إكس-أو — أحاول ألّا أترك لك فرصة", ::startXo)
        addWideButton("✊  حجر • ورقة • مقص — أتعلّم من اختياراتك", ::startRps)
        addWideButton("🎨  Paint Shot — لعبة طلقات الطلاء", ::startPaintShot, true)
        addWideButton("🎯  إصابة الهدف — هل ردّ فعلك أسرع؟", ::startTarget)
        addWideButton("◈  ذاكرة الأنماط — كرّر تسلسل الإضاءة", ::startMemory)
        addWideButton("⚡  اختبار سرعة الاستجابة", ::startReflex)
        addSpace(12)
        root.addView(text("شخصيتي في اللعب", 18f, cyan, true))
        root.addView(text(
            "أتذكّر الفوز والخسارة. أستطيع رفع الصعوبة تدريجيًا، وأعود إلى آخر تحدٍّ برسالة مناسبة. " +
                "هذه شخصية برمجية محلية، وليست وعيًا حقيقيًا.", 14f, muted
        ))
        addSpace(12)
        addWideButton("عودة إلى وجه إيف", { finish() })
    }

    private fun saveResult(name: String, result: String) {
        EveNotificationScheduler.recordGameResult(this, name, result)
        val ratingKey = "rating:" + name
        val old = prefs.getInt(ratingKey, 1000)
        val update = when (result) { "player" -> old + 18; "robot" -> old - 16; else -> old + 2 }
        prefs.edit().putInt(ratingKey, update.coerceIn(700, 2000)).apply()
        difficulty = when {
            prefs.getInt("games:" + name + ":player", 0) >= 3 -> 3
            prefs.getInt("games:" + name + ":robot", 0) >= 3 -> 2
            else -> 1
        }
    }

    private fun addResultLine(gameName: String, result: String, line: String) {
        if (result.isNotBlank()) saveResult(gameName, result)
        statusText = line
    }

    // ------------------------------------------------------------------ Tic-tac-toe
    private fun startXo() {
        game = Game.XO
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
        root.addView(text("سجلّك: فوز ${prefs.getInt("games:xo:player", 0)} • خسارة ${prefs.getInt("games:xo:robot", 0)} • تعادل ${prefs.getInt("games:xo:draw", 0)}", 14f, muted))
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
        val humanWin = winner(xo)
        if (humanWin == 'X') {
            xoFinished = true; saveResult("xo", "player"); renderXo("فزت! هذه الجولة لك. سأغيّر خطتي."); return
        }
        if (xo.none { it == ' ' }) {
            xoFinished = true; saveResult("xo", "draw"); renderXo("تعادلنا. لم تمنحني فرصة سهلة."); return
        }
        var best = -100
        var bestMoves = mutableListOf<Int>()
        for (i in xo.indices) if (xo[i] == ' ') {
            xo[i] = 'O'
            val score = xoScore(xo, false, 0)
            xo[i] = ' '
            when {
                score > best -> { best = score; bestMoves = mutableListOf(i) }
                score == best -> bestMoves.add(i)
            }
        }
        val chosen = if (bestMoves.isNotEmpty()) bestMoves[random.nextInt(bestMoves.size)] else xo.indices.first { xo[it] == ' ' }
        xo[chosen] = 'O'
        when {
            winner(xo) == 'O' -> { xoFinished = true; saveResult("xo", "robot"); renderXo("هذه الجولة لي. سأحاول الحفاظ على الفوز."); }
            xo.none { it == ' ' } -> { xoFinished = true; saveResult("xo", "draw"); renderXo("تعادل مرة أخرى. التحدي أصعب مما توقعت."); }
            else -> renderXo(listOf("راقب الوسط… هل ستغيّر طريقتك؟", "أحاول توقع خطوتك التالية.", "هذه الحركة كانت محسوبة. دورك.")[random.nextInt(3)])
        }
    }

    // ------------------------------------------------------------------ Rock / Paper / Scissors
    private fun startRps() {
        game = Game.RPS
        playerRpsHistory.clear()
        rpsScoreHuman = 0; rpsScoreEve = 0; rpsRound = 0
        renderRps("لن أكشف استراتيجيتي قبل أن أرى اختياراتك.")
    }

    private fun renderRps(message: String) {
        root.removeAllViews()
        header("حجر • ورقة • مقص", message)
        root.addView(text("أنت $rpsScoreHuman   —   إيف $rpsScoreEve", 21f, cyan, true))
        listOf("✊ حجر" to 0, "✋ ورقة" to 1, "✌ مقص" to 2).forEach { (label, move) ->
            addWideButton(label, { playRps(move) }, move == 1)
        }
        addSpace(8)
        root.addView(text("الجولات: $rpsRound • نمط اللعب يتكيّف مع أكثر حركة تكررها.", 13f, muted))
        addWideButton("تصفير المباراة", ::startRps)
        addWideButton("قائمة الألعاب", ::showMenu)
    }

    private fun playRps(player: Int) {
        if (game != Game.RPS) return
        val frequencies = (0..2).map { move -> playerRpsHistory.count { it == move } }
        val common = frequencies.maxOrNull() ?: 0
        val mostUsed = (0..2).filter { frequencies[it] == common }
        val robot = if (playerRpsHistory.size >= 3 && mostUsed.size == 1) (mostUsed[0] + 1) % 3
                    else random.nextInt(3)
        playerRpsHistory.add(player)
        rpsRound++
        val labels = listOf("حجر", "ورقة", "مقص")
        val result = when {
            player == robot -> "draw"
            (player + 1) % 3 == robot -> "robot"
            else -> "player"
        }
        when (result) { "player" -> rpsScoreHuman++; "robot" -> rpsScoreEve++ }
        saveResult("rps", result)
        val note = when (result) {
            "player" -> "فزت بهذه الجولة. سأعيد تحليل اختياراتك."
            "robot" -> if (playerRpsHistory.size >= 4) "توقعت حركتك الأكثر تكرارًا!" else "هذه الجولة لي. كانت قراءة أولية."
            else -> "تعادلنا. سأحتاج إشارة أخرى لأقرأ نمطك."
        }
        renderRps("اخترت ${labels[player]}؛ اخترت ${labels[robot]}. $note")
    }

    // ------------------------------------------------------------------ Chess
    private fun startChess() {
        game = Game.CHESS
        chess.reset()
        chessSelected = null
        chessFinished = false
        chessThinking = false
        renderChess("أنت الأبيض، وأنا الأسود. المس قطعة بيضاء ثم وجهتها.")
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
            val shade = if ((i / 8 + i % 8) % 2 == 0) Color.rgb(29, 54, 61) else Color.rgb(172, 207, 207)
            val cell = button(unicode[board[i]] ?: "", { selectChess(i) }, selected || possible)
            cell.textSize = 27f
            cell.setTextColor(if (board[i] != '.' && board[i].isUpperCase()) Color.WHITE else Color.BLACK)
            cell.setBackgroundColor(if (selected) cyan else if (possible) Color.rgb(55, 100, 94) else shade)
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0; height = dp(43)
                columnSpec = GridLayout.spec(i % 8, 1f)
                rowSpec = GridLayout.spec(i / 8)
                setMargins(dp(1), dp(1), dp(1), dp(1))
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, -2))
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
        val from = chessSelected
        if (from == null || chess.position.board[square].isUpperCase().not() && chess.position.board[square] != '.') {
            if (chess.position.board[square] != '.' && chess.position.board[square].isUpperCase()) {
                chessSelected = square
                renderChess("اخترت ${squareName(square)}. المس مربعًا أخضر للحركة.")
            }
            return
        }
        if (from == square) { chessSelected = null; renderChess("حُدّد الاختيار من جديد."); return }
        val move = chess.legalMoves(from).firstOrNull { it.to == square }
        if (move == null) {
            if (chess.position.board[square] != '.' && chess.position.board[square].isUpperCase()) {
                chessSelected = square; renderChess("اختر قطعة بيضاء ثم حركة قانونية.")
            } else renderChess("هذه الحركة غير قانونية؛ جرّب مربعًا آخر.")
            return
        }
        chess.play(move)
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
                handler.postDelayed({
                    if (game != Game.CHESS || chessFinished || isFinishing) return@postDelayed
                    val robotMove = chess.robotMove(difficulty)
                    if (robotMove == null) { chessFinished = true; saveResult("chess", if (chess.isCheck()) "player" else "draw"); renderChess("انتهت المباراة."); return@postDelayed }
                    chess.play(robotMove)
                    chessThinking = false
                    when {
                        chess.isCheckmate() -> { chessFinished = true; saveResult("chess", "robot"); renderChess("كش مات. فزت هذه المرة؛ لن أنسى هذه الخطة.") }
                        chess.isStalemate() -> { chessFinished = true; saveResult("chess", "draw"); renderChess("تعادل بالخنق. لا توجد حركة قانونية.") }
                        else -> renderChess("لعبت ${chess.moveLabel(robotMove)}. ${if (chess.isCheck()) "كش عليك!" else "دورك."}")
                    }
                }, 180L)
            }
        }
    }

    private fun squareName(i: Int) = "" + ('a'.code + i % 8).toChar() + (8 - i / 8)

    // ------------------------------------------------------------------ EMO-inspired Paint Shot
    // EMO uses glasses, a gun/palm-cannon gesture and "bang!".
    // EVE's phone implementation is a safe, fully offline touch-aiming paint game.
    private fun startPaintShot() {
        game = Game.PAINT_SHOT
        paintShotScore = 0
        paintShotRound = 0
        paintShotFinished = false
        paintShotTargetSide = if (random.nextBoolean()) -1 else 1
        paintShotMessage = "ارتدِ النظارة الخيالية. اختر جهة الهدف ثم اضغط «بانغ!» لإطلاق طلاء."
        renderPaintShot()
    }

    private fun renderPaintShot() {
        root.removeAllViews()
        header("🎨 Paint Shot", "لعبة طلاء آمنة تعمل دون إنترنت — الجولة " + (paintShotRound + 1) + " من 10")
        root.addView(text("👓  وضع النظارة مفعّل", 15f, cyan, true))
        root.addView(text("النقاط: $paintShotScore / 10", 22f, ivory, true))
        addSpace(6)
        val arena = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(16), dp(4), dp(16))
            setBackgroundColor(panel)
        }
        val leftTarget = button(if (paintShotTargetSide == -1) "🎯\nهدف الطلاء" else "⬅\nاليسار", {
            aimPaintShot(-1)
        }, paintShotTargetSide == -1).apply { textSize = 17f }
        val rightTarget = button(if (paintShotTargetSide == 1) "🎯\nهدف الطلاء" else "اليمين\n➡", {
            aimPaintShot(1)
        }, paintShotTargetSide == 1).apply { textSize = 17f }
        arena.addView(leftTarget, LinearLayout.LayoutParams(0, dp(118), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        arena.addView(rightTarget, LinearLayout.LayoutParams(0, dp(118), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        root.addView(arena, LinearLayout.LayoutParams(-1, -2))
        root.addView(text(paintShotMessage, 15f, muted))
        addSpace(6)
        addWideButton("💥  بانغ! — أطلق طلقة طلاء", ::firePaintShot, true)
        addWideButton("جولة جديدة", ::startPaintShot)
        addWideButton("العودة إلى ألعاب إيف", ::showMenu)
    }

    private fun aimPaintShot(side: Int) {
        if (game != Game.PAINT_SHOT || paintShotFinished) return
        paintShotTargetSide = side
        paintShotMessage = if (side < 0) "إيف تصوّب نحو اليسار… قل «بانغ!» أو اضغط زر الإطلاق."
            else "إيف تصوّب نحو اليمين… قل «بانغ!» أو اضغط زر الإطلاق."
        renderPaintShot()
    }

    private fun firePaintShot() {
        if (game != Game.PAINT_SHOT || paintShotFinished) return
        val hit = random.nextInt(100) < 78
        paintShotRound++
        if (hit) paintShotScore++
        paintShotMessage = when {
            hit -> if (paintShotTargetSide < 0) "💦 بااانغ! لطخة طلاء على الهدف الأيسر! إيف سعيدة 😄"
                else "💦 بااانغ! لطخة طلاء على الهدف الأيمن! إيف سعيدة 😄"
            else -> "💨 فشلت الطلقة! تحرّك الهدف قبل وصول الطلاء. حاول مرة أخرى."
        }
        android.util.Log.i("EVE_PAINT_SHOT", "hit=$hit side=$paintShotTargetSide round=$paintShotRound score=$paintShotScore")
        if (paintShotRound >= 10) {
            paintShotFinished = true
            val result = when { paintShotScore >= 7 -> "player"; paintShotScore <= 3 -> "robot"; else -> "draw" }
            saveResult("paint_shot", result)
            paintShotMessage += "\nانتهت الجولة: $paintShotScore من 10."
        } else {
            paintShotTargetSide = if (random.nextBoolean()) -1 else 1
        }
        renderPaintShot()
    }

    // ------------------------------------------------------------------ Target practice
    private fun startTarget() {
        targetMoveTask?.let(handler::removeCallbacks)
        game = Game.TARGET
        targetScore = 0
        targetRound = 0
        targetFinished = false
        targetCell = random.nextInt(9)
        statusText = "اضرب الدائرة التركوازية قبل أن تنتقل إلى مربع آخر."
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
        val now = SystemClock.elapsedRealtime()
        val hit = cellIndex == targetCell
        targetMoveTask?.let(handler::removeCallbacks)
        targetRound++
        if (hit) {
            targetScore++
            statusText = listOf(
                "إصابة دقيقة!", "سريع! بدأت أتوقع حركتك.", "هذه نقطة لك."
            )[targetScore % 3]
        } else {
            statusText = "لم تصب الهدف في الوقت المناسب. راقب مكانه الجديد."
        }
        if (targetRound >= 10) {
            targetFinished = true
            targetMoveTask = null
            val result = when {
                targetScore >= 8 -> "player"
                targetScore <= 3 -> "robot"
                else -> "draw"
            }
            saveResult("target", result)
            statusText += " انتهت الجولة: $targetScore من 10."
        } else {
            if (hit || cellIndex == targetCell || now > targetDeadline) {
                val previous = targetCell
                val next = random.nextInt(8)
                targetCell = if (next >= previous) next + 1 else next
            }
            renderTarget()
            scheduleTargetMove()
            return
        }
        renderTarget()
    }

    // ------------------------------------------------------------------ Pattern memory
    private fun startMemory() {
        game = Game.MEMORY
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
        game = Game.REFLEX
        reflexReady = false; reflexFinished = false; reflexRound = 0; reflexHits = 0
        reflexStartedAt = 0L; reflexDelayUntil = 0L
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
        if (reflexStartedAt == 0L) {
            reflexRound = 0; reflexHits = 0
            scheduleReflexSignal()
            renderReflex("انتظر… لا تضغط حتى تظهر ⚡.")
            return
        }
        if (!reflexReady) {
            reflexFinished = true
            saveResult("reflex", "robot")
            renderReflex("ضغطت مبكرًا! نقطة لإيف. خذ وقتك وأعد المحاولة.")
            return
        }
        val reaction = SystemClock.elapsedRealtime() - reflexStartedAt
        reflexReady = false; reflexStartedAt = 0L
        reflexRound++
        if (reaction < 450L) reflexHits++
        if (reflexRound >= 5) {
            reflexFinished = true
            saveResult("reflex", if (reflexHits >= 4) "player" else if (reflexHits <= 1) "robot" else "draw")
            renderReflex("متوسط ردّك ${reaction}ms. نتيجتك $reflexHits/5.")
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
