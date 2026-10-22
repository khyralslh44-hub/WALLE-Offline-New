package com.eve.robot

import kotlin.math.abs

/** Offline legal chess: check, mate, stalemate, castling, en-passant, and queen promotion. */
internal class ChessEngine {
    data class Move(val from: Int, val to: Int, val promotion: Char = 'Q')
    data class Position(
        val board: CharArray, val whiteToMove: Boolean,
        val whiteKingSide: Boolean = true, val whiteQueenSide: Boolean = true,
        val blackKingSide: Boolean = true, val blackQueenSide: Boolean = true,
        val enPassant: Int = -1
    ) {
        fun copyPosition() = Position(board.copyOf(), whiteToMove, whiteKingSide, whiteQueenSide, blackKingSide, blackQueenSide, enPassant)
    }
    var position = initialPosition()
        private set
    fun reset() { position = initialPosition() }
    fun legalMoves(from: Int? = null): List<Move> = legalMoves(position, from)

    private fun legalMoves(p: Position, from: Int? = null): List<Move> =
        pseudoMoves(p, p.whiteToMove).filter {
            (from == null || it.from == from) && !inCheck(apply(p, it), p.whiteToMove)
        }

    /**
     * Counts legal move sequences from the current position using the same legality filter used
     * by play(). Kept small and deterministic so regression tests can validate move generation.
     */
    internal fun perft(depth: Int): Long {
        require(depth >= 0) { "depth must be non-negative" }
        return perft(position, depth)
    }

    private fun perft(position: Position, depth: Int): Long {
        if (depth == 0) return 1L
        val sideToMove = position.whiteToMove
        val legal = pseudoMoves(position, sideToMove)
            .filter { !inCheck(apply(position, it), sideToMove) }
        var nodes = 0L
        for (move in legal) nodes += perft(apply(position, move), depth - 1)
        return nodes
    }
    fun play(move: Move): Boolean {
        if (move !in legalMoves(move.from)) return false
        position = apply(position, move)
        return true
    }
    fun isCheck(white: Boolean = position.whiteToMove) = inCheck(position, white)
    fun isCheckmate() = inCheck(position, position.whiteToMove) && legalMoves().isEmpty()
    fun isStalemate() = !inCheck(position, position.whiteToMove) && legalMoves().isEmpty()
    fun moveLabel(m: Move): String = square(m.from) + "–" + square(m.to) + if (m.promotion != 'Q') "=" + m.promotion else ""

    private data class SearchEntry(val depth: Int, val score: Int, val bound: Int)
    private class SearchContext {
        val table = HashMap<Long, SearchEntry>(32_768)
    }

    /**
     * Chooses a move by searching the current board, not by replaying a fixed opening list.
     * Search uses alpha-beta, a transposition table and tactical quiescence. Similar-strength
     * moves are varied by the local game seed so EVE does not repeat the same opening every time.
     * The root is copied before search, so it is safe to calculate away from Android's UI thread.
     */
    fun robotMove(difficulty: Int, variationSeed: Long = 0L): Move? {
        val root = position.copyPosition()
        val moves = legalMoves(root)
        if (moves.isEmpty()) return null

        val level = difficulty.coerceIn(1, 3)
        val depth = when (level) {
            1 -> 2
            2 -> 3
            else -> 4
        }
        val context = SearchContext()
        val ranked = orderMoves(root, moves).map { move ->
            move to -search(
                apply(root, move),
                depth - 1,
                -1_000_000,
                1_000_000,
                1,
                context
            )
        }.sortedByDescending { it.second }

        val best = ranked.first().second
        val tolerance = when (level) {
            1 -> 150
            2 -> 48
            else -> 8
        }
        val comparable = ranked.filter { best - it.second <= tolerance }
        if (comparable.size <= 1) return ranked.first().first
        val normalizedSeed = if (variationSeed == Long.MIN_VALUE) 0L else kotlin.math.abs(variationSeed)
        return comparable[(normalizedSeed % comparable.size.toLong()).toInt()].first
    }

    private fun search(
        p: Position,
        depth: Int,
        alpha0: Int,
        beta0: Int,
        ply: Int,
        context: SearchContext
    ): Int {
        val moves = legalMoves(p)
        if (moves.isEmpty()) return if (inCheck(p, p.whiteToMove)) -100_000 + ply else 0
        if (depth <= 0) return quiescence(p, alpha0, beta0, ply, 0, context)

        var alpha = alpha0
        val originalAlpha = alpha0
        val key = positionKey(p)
        val cached = context.table[key]
        if (cached != null && cached.depth >= depth) {
            when (cached.bound) {
                0 -> return cached.score
                1 -> if (cached.score >= beta0) return cached.score
                2 -> if (cached.score <= alpha0) return cached.score
            }
        }

        var best = -1_000_000
        for (move in orderMoves(p, moves)) {
            val score = -search(
                apply(p, move), depth - 1, -beta0, -alpha, ply + 1, context
            )
            if (score > best) best = score
            if (score > alpha) alpha = score
            if (alpha >= beta0) break
        }

        val bound = when {
            best <= originalAlpha -> 2 // upper bound
            best >= beta0 -> 1       // lower bound
            else -> 0                // exact
        }
        if (context.table.size < 120_000 || cached != null) {
            context.table[key] = SearchEntry(depth, best, bound)
        }
        return best
    }

    /**
     * Extends the search through forcing captures/promotions, preventing the engine from
     * evaluating a position halfway through an obvious exchange. All scores are side-to-move.
     */
    private fun quiescence(
        p: Position,
        alpha0: Int,
        beta: Int,
        ply: Int,
        qDepth: Int,
        context: SearchContext
    ): Int {
        val checked = inCheck(p, p.whiteToMove)
        val legal = legalMoves(p)
        if (legal.isEmpty()) return if (checked) -100_000 + ply else 0

        val standPat = evaluate(p)
        if (qDepth >= 5) return standPat
        var alpha = alpha0
        if (!checked) {
            if (standPat >= beta) return standPat
            if (standPat > alpha) alpha = standPat
        }

        val tactical = if (checked) legal else legal.filter {
            isCapture(p, it) || isPromotion(p, it)
        }
        if (tactical.isEmpty()) return if (checked) -100_000 + ply else alpha

        for (move in orderMoves(p, tactical)) {
            val score = -quiescence(
                apply(p, move), -beta, -alpha, ply + 1, qDepth + 1, context
            )
            if (score >= beta) return score
            if (score > alpha) alpha = score
        }
        return alpha
    }

    /** White-centric positional evaluation, converted to the side-to-move's perspective. */
    private fun evaluate(p: Position): Int {
        val board = p.board
        var score = 0
        var whiteBishops = 0
        var blackBishops = 0
        val whitePawnsByFile = IntArray(8)
        val blackPawnsByFile = IntArray(8)
        var phase = 0

        // Count every pawn before scoring rooks; board iteration order must not affect evaluation.
        for (i in 0..63) {
            when (board[i]) {
                'P' -> whitePawnsByFile[i % 8]++
                'p' -> blackPawnsByFile[i % 8]++
            }
        }

        for (i in 0..63) {
            val piece = board[i]
            if (piece == '.') continue
            val white = piece.isUpperCase()
            val sign = if (white) 1 else -1
            val row = i / 8
            val col = i % 8
            val type = piece.uppercaseChar()
            val material = when (type) {
                'P' -> 100
                'N' -> 320
                'B' -> 335
                'R' -> 500
                'Q' -> 900
                else -> 0
            }
            score += sign * material
            val center = 7 - (abs(3 - row) + abs(3 - col))

            when (type) {
                'P' -> {
                    val advance = if (white) 6 - row else row - 1
                    score += sign * (advance * 7 + center * 2)
                    if (isPassedPawn(board, i, white)) {
                        score += sign * (10 + advance * advance * 3)
                    }
                }
                'N' -> {
                    phase += 1
                    score += sign * (center * 5)
                    val homeRow = if (white) 7 else 0
                    if (row == homeRow && (col == 1 || col == 6)) score -= sign * 10
                }
                'B' -> {
                    phase += 1
                    if (white) whiteBishops++ else blackBishops++
                    score += sign * (center * 3)
                }
                'R' -> {
                    phase += 2
                    val friendlyPawns = if (white) whitePawnsByFile[col] else blackPawnsByFile[col]
                    val enemyPawns = if (white) blackPawnsByFile[col] else whitePawnsByFile[col]
                    // Open files are better than semi-open ones, but both can activate a rook.
                    if (friendlyPawns == 0) score += sign * if (enemyPawns == 0) 14 else 6
                    if (row == (if (white) 1 else 6)) score += sign * 12
                }
                'Q' -> {
                    phase += 4
                    score += sign * (center * 2)
                }
            }
        }

        for (file in 0..7) {
            val whiteCount = whitePawnsByFile[file]
            val blackCount = blackPawnsByFile[file]
            if (whiteCount > 1) score -= (whiteCount - 1) * 14
            if (blackCount > 1) score += (blackCount - 1) * 14
            if (whiteCount > 0 && (file == 0 || whitePawnsByFile[file - 1] == 0) &&
                (file == 7 || whitePawnsByFile[file + 1] == 0)
            ) score -= whiteCount * 11
            if (blackCount > 0 && (file == 0 || blackPawnsByFile[file - 1] == 0) &&
                (file == 7 || blackPawnsByFile[file + 1] == 0)
            ) score += blackCount * 11
        }
        if (whiteBishops >= 2) score += 28
        if (blackBishops >= 2) score -= 28

        if (p.whiteKingSide || p.whiteQueenSide) score += 7
        if (p.blackKingSide || p.blackQueenSide) score -= 7

        val whiteKing = board.indexOf('K')
        val blackKing = board.indexOf('k')
        if (phase > 8) {
            if (whiteKing == 62 || whiteKing == 58) score += 24
            else if (whiteKing in 0..63 && whiteKing / 8 < 6) score -= 12
            if (blackKing == 6 || blackKing == 2) score -= 24
            else if (blackKing in 0..63 && blackKing / 8 > 1) score += 12
        } else {
            if (whiteKing >= 0) {
                val r = whiteKing / 8
                val c = whiteKing % 8
                score += (7 - (abs(3 - r) + abs(3 - c))) * 3
            }
            if (blackKing >= 0) {
                val r = blackKing / 8
                val c = blackKing % 8
                score -= (7 - (abs(3 - r) + abs(3 - c))) * 3
            }
        }
        return if (p.whiteToMove) score else -score
    }

    private fun isPassedPawn(board: CharArray, square: Int, white: Boolean): Boolean {
        val row = square / 8
        val col = square % 8
        for (r in 0..7) {
            val ahead = if (white) r < row else r > row
            if (!ahead) continue
            for (c in maxOf(0, col - 1)..minOf(7, col + 1)) {
                if (board[r * 8 + c] == if (white) 'p' else 'P') return false
            }
        }
        return true
    }

    private fun isCapture(p: Position, move: Move): Boolean {
        if (p.board[move.to] != '.') return true
        return p.board[move.from].uppercaseChar() == 'P' && move.to == p.enPassant
    }

    private fun isPromotion(p: Position, move: Move): Boolean =
        p.board[move.from].uppercaseChar() == 'P' &&
            (move.to / 8 == 0 || move.to / 8 == 7)

    private fun orderMoves(p: Position, moves: List<Move>): List<Move> =
        moves.sortedByDescending { move ->
            val moving = p.board[move.from]
            val target = p.board[move.to]
            val victimValue = when {
                target != '.' -> value(target)
                moving.uppercaseChar() == 'P' && move.to == p.enPassant -> 100
                else -> 0
            }
            var score = if (victimValue > 0) {
                10_000 + victimValue * 10 - value(moving)
            } else 0
            if (isPromotion(p, move)) score += 8_000 + value(move.promotion) - 100
            if (moving.uppercaseChar() == 'K' && abs(move.to % 8 - move.from % 8) == 2) score += 80
            val center = 7 - (abs(3 - move.to / 8) + abs(3 - move.to % 8))
            score + center
        }

    private fun value(piece: Char): Int = when (piece.uppercaseChar()) {
        'P' -> 100
        'N' -> 320
        'B' -> 335
        'R' -> 500
        'Q' -> 900
        'K' -> 20_000
        else -> 0
    }

    private fun positionKey(p: Position): Long {
        // FNV-style hash of the board plus side-to-move, castling rights and en-passant square.
        var hash = -3750763034362895579L
        for (i in 0..63) {
            hash = (hash xor (p.board[i].code.toLong() + i * 17L)) * 1099511628211L
        }
        hash = (hash xor if (p.whiteToMove) 1L else 2L) * 1099511628211L
        val rights = (if (p.whiteKingSide) 1 else 0) or
            (if (p.whiteQueenSide) 2 else 0) or
            (if (p.blackKingSide) 4 else 0) or
            (if (p.blackQueenSide) 8 else 0)
        hash = (hash xor rights.toLong()) * 1099511628211L
        hash = (hash xor (p.enPassant + 1).toLong()) * 1099511628211L
        return hash
    }

    private fun pseudoMoves(p: Position, white: Boolean): List<Move> {
        val out = ArrayList<Move>(48)
        val b = p.board
        for (from in 0..63) {
            val piece = b[from]
            if (piece == '.' || piece.isUpperCase() != white) continue
            val row = from / 8; val col = from % 8
            when (piece.uppercaseChar()) {
                'P' -> {
                    val step = if (white) -1 else 1
                    val start = if (white) 6 else 1
                    val nr = row + step
                    if (inside(nr, col) && b[nr * 8 + col] == '.') {
                        addPawn(out, from, nr * 8 + col, nr)
                        val nr2 = row + step * 2
                        if (row == start && b[nr2 * 8 + col] == '.') out.add(Move(from, nr2 * 8 + col))
                    }
                    for (dc in intArrayOf(-1, 1)) {
                        val nc = col + dc
                        if (!inside(nr, nc)) continue
                        val to = nr * 8 + nc
                        if (b[to] != '.' && b[to].isUpperCase() != white) addPawn(out, from, to, nr)
                        if (to == p.enPassant) out.add(Move(from, to))
                    }
                }
                'N' -> for ((dr, dc) in knightDirs) addStep(p, out, white, from, row + dr, col + dc)
                'B' -> rays(p, out, white, from, row, col, diagonal)
                'R' -> rays(p, out, white, from, row, col, straight)
                'Q' -> rays(p, out, white, from, row, col, directions)
                'K' -> {
                    for ((dr, dc) in directions) addStep(p, out, white, from, row + dr, col + dc)
                    if (white && from == 60 && !inCheck(p, true)) {
                        if (p.whiteKingSide && b[61] == '.' && b[62] == '.' && !attacked(p, 61, false) && !attacked(p, 62, false)) out.add(Move(60, 62))
                        if (p.whiteQueenSide && b[59] == '.' && b[58] == '.' && b[57] == '.' && !attacked(p, 59, false) && !attacked(p, 58, false)) out.add(Move(60, 58))
                    }
                    if (!white && from == 4 && !inCheck(p, false)) {
                        if (p.blackKingSide && b[5] == '.' && b[6] == '.' && !attacked(p, 5, true) && !attacked(p, 6, true)) out.add(Move(4, 6))
                        if (p.blackQueenSide && b[3] == '.' && b[2] == '.' && b[1] == '.' && !attacked(p, 3, true) && !attacked(p, 2, true)) out.add(Move(4, 2))
                    }
                }
            }
        }
        return out
    }
    private fun addPawn(out: MutableList<Move>, from: Int, to: Int, row: Int) { out.add(Move(from, to, if (row == 0 || row == 7) 'Q' else 'Q')) }
    private fun addStep(p: Position, out: MutableList<Move>, white: Boolean, from: Int, row: Int, col: Int) {
        if (!inside(row, col)) return
        val to = row * 8 + col; val target = p.board[to]
        if (target == '.' || target.isUpperCase() != white) out.add(Move(from, to))
    }
    private fun rays(p: Position, out: MutableList<Move>, white: Boolean, from: Int, row: Int, col: Int, dirs: List<Pair<Int, Int>>) {
        for ((dr, dc) in dirs) {
            var r = row + dr; var c = col + dc
            while (inside(r, c)) {
                val target = p.board[r * 8 + c]
                if (target == '.') out.add(Move(from, r * 8 + c))
                else { if (target.isUpperCase() != white) out.add(Move(from, r * 8 + c)); break }
                r += dr; c += dc
            }
        }
    }
    private fun apply(p: Position, m: Move): Position {
        val b = p.board.copyOf()
        val piece = b[m.from]; val fr = m.from / 8; val tr = m.to / 8
        var ep = -1
        if (piece.uppercaseChar() == 'P' && m.to == p.enPassant && b[m.to] == '.') {
            val captured = m.to + if (piece.isUpperCase()) 8 else -8
            if (captured in 0..63) b[captured] = '.'
        }
        b[m.to] = if (piece.uppercaseChar() == 'P' && (tr == 0 || tr == 7)) { if (piece.isUpperCase()) m.promotion.uppercaseChar() else m.promotion.lowercaseChar() } else piece
        b[m.from] = '.'
        if (piece.uppercaseChar() == 'P' && abs(tr - fr) == 2) ep = (m.from + m.to) / 2
        var wk = p.whiteKingSide; var wq = p.whiteQueenSide; var bk = p.blackKingSide; var bq = p.blackQueenSide
        if (piece == 'K') {
            wk = false; wq = false
            if (m.from == 60 && m.to == 62) { b[61] = b[63]; b[63] = '.' }
            if (m.from == 60 && m.to == 58) { b[59] = b[56]; b[56] = '.' }
        }
        if (piece == 'k') {
            bk = false; bq = false
            if (m.from == 4 && m.to == 6) { b[5] = b[7]; b[7] = '.' }
            if (m.from == 4 && m.to == 2) { b[3] = b[0]; b[0] = '.' }
        }
        if (m.from == 63 || m.to == 63) wk = false
        if (m.from == 56 || m.to == 56) wq = false
        if (m.from == 7 || m.to == 7) bk = false
        if (m.from == 0 || m.to == 0) bq = false
        return Position(b, !p.whiteToMove, wk, wq, bk, bq, ep)
    }
    private fun inCheck(p: Position, white: Boolean): Boolean {
        val king = p.board.indexOf(if (white) 'K' else 'k')
        return king < 0 || attacked(p, king, !white)
    }
    private fun attacked(p: Position, square: Int, byWhite: Boolean): Boolean {
        val b = p.board; val row = square / 8; val col = square % 8
        val pawnRow = row + if (byWhite) 1 else -1
        if (pawnRow in 0..7) for (dc in intArrayOf(-1, 1)) {
            val c = col + dc
            if (c in 0..7 && b[pawnRow * 8 + c] == if (byWhite) 'P' else 'p') return true
        }
        for ((dr, dc) in knightDirs) {
            val r = row + dr; val c = col + dc
            if (inside(r, c) && b[r * 8 + c] == if (byWhite) 'N' else 'n') return true
        }
        for ((dr, dc) in directions) {
            var r = row + dr; var c = col + dc; var distance = 1
            while (inside(r, c)) {
                val piece = b[r * 8 + c]
                if (piece != '.') {
                    if (piece.isUpperCase() == byWhite) {
                        val type = piece.uppercaseChar()
                        if (distance == 1 && type == 'K') return true
                        if (type == 'Q' || ((dr == 0 || dc == 0) && type == 'R') || ((dr != 0 && dc != 0) && type == 'B')) return true
                    }
                    break
                }
                r += dr; c += dc; distance++
            }
        }
        return false
    }
    private fun inside(r: Int, c: Int) = r in 0..7 && c in 0..7
    private fun square(i: Int) = "" + ('a'.code + i % 8).toChar() + (8 - i / 8)

    companion object {
        private val knightDirs = listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
        private val straight = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        private val diagonal = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
        private val directions = straight + diagonal
        private fun initialPosition(): Position {
            val b = CharArray(64) { '.' }; val back = "rnbqkbnr"
            for (c in 0..7) { b[c] = back[c]; b[8+c] = 'p'; b[48+c] = 'P'; b[56+c] = back[c].uppercaseChar() }
            return Position(b, true)
        }
    }
}
