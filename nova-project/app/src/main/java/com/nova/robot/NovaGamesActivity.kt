package com.nova.robot

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.util.concurrent.Executors

/** Fully local game room: Nova is the chess and tic-tac-toe opponent. */
class NovaGamesActivity : ComponentActivity() {
    private enum class Mode { MENU, XO, CHESS }
    private lateinit var root: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val chessWorker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("nova_games", MODE_PRIVATE) }
    private var mode = Mode.MENU
    private var session = 0L
    private var difficulty = 2
    private var xo = NovaXoEngine()
    private var xoThinking = false
    private var xoOver = false
    private var chess = ChessEngine()
    private var chessSelected: Int? = null
    private var chessThinking = false
    private var chessOver = false
    private var lastChessMove: ChessEngine.Move? = null

    private val ink = 0xFFEAF6FF.toInt()
    private val muted = 0xFF9CB8CF.toInt()
    private val cyan = 0xFF63E9FF.toInt()
    private val bg = 0xFF071321.toInt()
    private val panel = 0xFF10243A.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(20))
            setBackgroundColor(bg)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        when (intent.getStringExtra(EXTRA_GAME)) {
            "chess" -> startChess()
            "xo" -> startXo()
            else -> showMenu()
        }
    }

    override fun onDestroy() {
        session++
        handler.removeCallbacksAndMessages(null)
        chessWorker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Compatibility with older Android navigation")
    override fun onBackPressed() {
        if (mode == Mode.MENU) finish() else showMenu()
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

    private fun label(value: String, size: Float = 16f, color: Int = ink, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(7), dp(8), dp(7))
        }

    private fun gameButton(value: String, action: () -> Unit, accent: Boolean = false): Button =
        Button(this).apply {
            text = value
            textSize = 15f
            isAllCaps = false
            setTextColor(if (accent) bg else ink)
            setBackgroundColor(if (accent) cyan else panel)
            minHeight = dp(48)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { action() }
        }

    private fun addWideButton(value: String, action: () -> Unit, accent: Boolean = false) {
        root.addView(gameButton(value, action, accent), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(5)
            bottomMargin = dp(5)
        })
    }

    private fun heading(title: String, subtitle: String) {
        root.addView(label("N O V A  •  OFFLINE GAMES", 11f, cyan, true))
        root.addView(label(title, 25f, ink, true), LinearLayout.LayoutParams(-1, -2))
        root.addView(label(subtitle, 14f, muted), LinearLayout.LayoutParams(-1, -2))
    }

    private fun showMenu() {
        session++
        mode = Mode.MENU
        handler.removeCallbacksAndMessages(null)
        xoThinking = false
        chessThinking = false
        root.removeAllViews()
        heading("اختر لعبتنا", "الخصم هو نوفا نفسها. قواعد اللعب والردود تعمل دون إنترنت.")
        addWideButton("♟  الشطرنج ضد نوفا — أنت الأبيض", ::startChess, true)
        addWideButton("⭕  إكس أو ضد نوفا — أنت ✕ ونوفا ○", ::startXo)
        root.addView(label("نوفا تحسب نقلاتها محليًا. اختر الصعوبة وابدأ جولة جديدة في أي وقت.", 14f, muted))
        addWideButton("عودة إلى نوفا", { finish() })
    }

    private fun startXo() {
        session++
        mode = Mode.XO
        xo = NovaXoEngine()
        xoThinking = false
        xoOver = false
        difficulty = prefs.getInt("xo_difficulty", 2).coerceIn(1, 3)
        renderXo("أنت ✕، وأنا نوفا ○. ابدأ بحركتك.")
    }

    private fun renderXo(message: String) {
        if (isFinishing || mode != Mode.XO) return
        root.removeAllViews()
        heading("إكس أو ضد نوفا", message)
        val board = xo.board
        val grid = GridLayout(this).apply { columnCount = 3 }
        for (square in 0..8) {
            val mark = board[square]
            val cell = gameButton(if (mark == ' ') "·" else mark.toString(), { playHumanXo(square) }, mark != ' ')
            cell.textSize = 34f
            cell.setBackgroundColor(when (mark) { 'X' -> 0xFF124B63.toInt(); 'O' -> 0xFF24345B.toInt(); else -> panel })
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0; height = dp(88)
                columnSpec = GridLayout.spec(square % 3, 1f)
                rowSpec = GridLayout.spec(square / 3)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, -2))
        root.addView(label(
            "صعوبة نوفا: " + difficulty + "/3 • فوزك " + prefs.getInt("xo_player", 0) +
                " • فوز نوفا " + prefs.getInt("xo_nova", 0) + " • تعادل " + prefs.getInt("xo_draw", 0),
            13f, muted
        ))
        addWideButton(if (xoThinking) "نوفا تحسب الحركة…" else "تغيير الصعوبة: " + difficulty + "/3", {
            if (!xoThinking && !xoOver) {
                difficulty = difficulty % 3 + 1
                prefs.edit().putInt("xo_difficulty", difficulty).apply()
                renderXo("غيّرت مستوى التحدي. دورك.")
            }
        })
        addWideButton("جولة جديدة", ::startXo)
        addWideButton("قائمة الألعاب", ::showMenu)
    }

    private fun playHumanXo(square: Int) {
        if (mode != Mode.XO || xoOver || xoThinking || !xo.play(square, 'X')) return
        if (finishXoIfNeeded()) return
        xoThinking = true
        renderXo("نوفا تراجع الخيارات وتختار حركتها…")
        val currentSession = session
        handler.postDelayed({
            if (currentSession != session || mode != Mode.XO || xoOver || isFinishing) return@postDelayed
            val move = xo.chooseNovaMove(difficulty, System.nanoTime())
            if (move != null) xo.play(move, 'O')
            xoThinking = false
            if (!finishXoIfNeeded()) renderXo("لعبت نوفا حركتها. دورك الآن.")
        }, 240L)
    }

    private fun finishXoIfNeeded(): Boolean {
        val winner = xo.winner()
        val resultMessage = when {
            winner == 'X' -> {
                prefs.edit().putInt("xo_player", prefs.getInt("xo_player", 0) + 1).apply()
                "فزت على نوفا! أحسنت."
            }
            winner == 'O' -> {
                prefs.edit().putInt("xo_nova", prefs.getInt("xo_nova", 0) + 1).apply()
                "هذه الجولة لنوفا. جرّب مستوى آخر أو أعد اللعب!"
            }
            xo.isDraw() -> {
                prefs.edit().putInt("xo_draw", prefs.getInt("xo_draw", 0) + 1).apply()
                "تعادل! لا يوجد فائز هذه المرة."
            }
            else -> return false
        }
        xoOver = true
        xoThinking = false
        renderXo(resultMessage)
        return true
    }

    private fun startChess() {
        session++
        mode = Mode.CHESS
        chess = ChessEngine()
        chessSelected = null
        chessThinking = false
        chessOver = false
        lastChessMove = null
        difficulty = prefs.getInt("chess_difficulty", 2).coerceIn(1, 3)
        renderChess("أنت الأبيض، وأنا نوفا الأسود. اختر قطعة بيضاء ثم مربعًا مميزًا.")
    }

    private fun renderChess(message: String) {
        if (isFinishing || mode != Mode.CHESS) return
        root.removeAllViews()
        heading("الشطرنج ضد نوفا", message)
        val board = chess.position.board
        val grid = GridLayout(this).apply { columnCount = 8 }
        val glyph = mapOf(
            'K' to "♔", 'Q' to "♕", 'R' to "♖", 'B' to "♗", 'N' to "♘", 'P' to "♙",
            'k' to "♚", 'q' to "♛", 'r' to "♜", 'b' to "♝", 'n' to "♞", 'p' to "♟", '.' to ""
        )
        val destinations = chessSelected?.let { from -> chess.legalMoves(from).map { it.to }.toSet() } ?: emptySet()
        val cellDp = (((resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt() - 40) / 8).coerceIn(32, 48)
        for (square in 0..63) {
            val piece = board[square]
            val selected = chessSelected == square
            val possible = square in destinations
            val lastFrom = lastChessMove?.from == square
            val lastTo = lastChessMove?.to == square
            val cell = gameButton(glyph[piece] ?: "", { selectChess(square) }, selected || possible)
            cell.textSize = 24f
            cell.setPadding(0, 0, 0, 0)
            cell.setTextColor(if (piece.isUpperCase() && piece != '.') 0xFFFFFFFF.toInt() else 0xFF111820.toInt())
            cell.setBackgroundColor(when {
                selected -> 0xFF00A8C4.toInt()
                possible -> 0xFF45816D.toInt()
                lastTo -> 0xFF8EAC59.toInt()
                lastFrom -> 0xFF587C6A.toInt()
                (square / 8 + square % 8) % 2 == 0 -> 0xFF244357.toInt()
                else -> 0xFFC6D8DE.toInt()
            })
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0; height = dp(cellDp)
                columnSpec = GridLayout.spec(square % 8, 1f)
                rowSpec = GridLayout.spec(square / 8)
                setMargins(dp(1), dp(1), dp(1), dp(1))
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, -2))
        val chessState = when {
            chessOver && chess.position.whiteToMove -> "فازت نوفا"
            chessOver -> "فزت على نوفا"
            chessThinking -> "نوفا تفكّر في الرد"
            chess.isCheck() -> "كش — ابحث عن رد قانوني"
            chess.position.whiteToMove -> "دورك"
            else -> "دور نوفا"
        }
        root.addView(label("الحالة: " + chessState + " • الصعوبة " + difficulty + "/3 • الأبيض أنت والأسود نوفا", 13f, cyan, true))
        addWideButton(if (chessThinking) "نوفا تحسب النقلات…" else "تغيير الصعوبة: " + difficulty + "/3", {
            if (!chessThinking && !chessOver) {
                difficulty = difficulty % 3 + 1
                prefs.edit().putInt("chess_difficulty", difficulty).apply()
                renderChess("غيّرت مستوى التحدي. أراجع الوضع الحالي.")
            }
        })
        addWideButton("مباراة جديدة", ::startChess)
        addWideButton("قائمة الألعاب", ::showMenu)
    }

    private fun selectChess(square: Int) {
        if (mode != Mode.CHESS || chessOver || chessThinking || !chess.position.whiteToMove || square !in 0..63) return
        val board = chess.position.board
        val from = chessSelected
        if (from == null) {
            if (board[square].isUpperCase() && board[square] != '.') {
                chessSelected = square
                renderChess("اخترت " + squareName(square) + ". المس مربعًا أخضر لإتمام النقلة.")
            } else renderChess("اختر قطعة بيضاء أولًا.")
            return
        }
        if (from == square) {
            chessSelected = null
            renderChess("ألغيت اختيار القطعة.")
            return
        }
        if (board[square].isUpperCase() && board[square] != '.') {
            chessSelected = square
            renderChess("اخترت " + squareName(square) + ". المس مربعًا أخضر لإتمام النقلة.")
            return
        }
        val choices = chess.legalMoves(from).filter { it.to == square }
        when {
            choices.isEmpty() -> renderChess("هذه النقلة غير قانونية؛ اختر مربعًا أخضر.")
            choices.size > 1 -> {
                val options = arrayOf("ملكة", "قلعة", "فيل", "حصان")
                val promotions = charArrayOf('Q', 'R', 'B', 'N')
                AlertDialog.Builder(this).setTitle("اختر قطعة الترقية")
                    .setItems(options) { _, index ->
                        choices.firstOrNull { it.promotion == promotions[index] }?.let(::playHumanChessMove)
                    }.setOnCancelListener { renderChess("اختر قطعة الترقية أو تابع اللعب.") }.show()
            }
            else -> playHumanChessMove(choices.first())
        }
    }

    private fun playHumanChessMove(move: ChessEngine.Move) {
        if (!chess.play(move)) {
            renderChess("لم تُنفّذ النقلة لأنها غير قانونية.")
            return
        }
        lastChessMove = move
        chessSelected = null
        if (finishChessIfNeeded()) return
        chessThinking = true
        renderChess("لعبت " + chess.moveLabel(move) + ". نوفا تحسب الرد القانوني…")
        val engine = chess
        val currentSession = session
        val level = difficulty
        chessWorker.execute {
            val response = runCatching { engine.robotMove(level, System.nanoTime()) }.getOrNull()
                ?: engine.legalMoves().firstOrNull()
            handler.post {
                if (currentSession != session || mode != Mode.CHESS || chessOver || isFinishing || engine !== chess) return@post
                if (response == null || !engine.play(response)) {
                    chessThinking = false
                    if (!finishChessIfNeeded()) renderChess("لم تجد نوفا نقلة قانونية أخرى.")
                    return@post
                }
                lastChessMove = response
                chessThinking = false
                if (!finishChessIfNeeded()) {
                    val note = if (engine.isCheck()) "لعبت نوفا " + engine.moveLabel(response) + "؛ كش عليك! دورك."
                        else "لعبت نوفا " + engine.moveLabel(response) + ". دورك الآن."
                    renderChess(note)
                }
            }
        }
    }

    private fun finishChessIfNeeded(): Boolean {
        when {
            chess.isCheckmate() -> {
                chessOver = true
                val playerWon = !chess.position.whiteToMove
                val key = if (playerWon) "chess_player" else "chess_nova"
                prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
                renderChess(if (playerWon) "كش مات! فزت على نوفا. أحسنت اللعب." else "كش مات! فازت نوفا هذه الجولة.")
                return true
            }
            chess.isStalemate() -> {
                chessOver = true
                prefs.edit().putInt("chess_draw", prefs.getInt("chess_draw", 0) + 1).apply()
                renderChess("تعادل بالخنق؛ لا توجد نقلة قانونية ولا ملك في كش.")
                return true
            }
            else -> return false
        }
    }

    private fun squareName(square: Int) = "" + ('a'.code + square % 8).toChar() + (8 - square / 8)

    companion object {
        const val EXTRA_GAME = "nova_game_mode"
    }
}
