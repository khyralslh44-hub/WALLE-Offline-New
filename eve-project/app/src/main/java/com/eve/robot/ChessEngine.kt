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
    fun legalMoves(from: Int? = null): List<Move> {
        val p = position
        return pseudoMoves(p, p.whiteToMove).filter { (from == null || it.from == from) && !inCheck(apply(p, it), p.whiteToMove) }
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

    fun robotMove(difficulty: Int): Move? {
        val moves = legalMoves()
        if (moves.isEmpty()) return null
        val depth = difficulty.coerceIn(1, 3)
        var best = moves.first()
        var bestScore = Int.MIN_VALUE
        for (m in orderMoves(position, moves)) {
            val score = -search(apply(position, m), depth - 1, Int.MIN_VALUE + 1, Int.MAX_VALUE, 1)
            if (score > bestScore) { bestScore = score; best = m }
        }
        return best
    }

    private fun search(p: Position, depth: Int, alpha0: Int, beta0: Int, ply: Int): Int {
        val moves = pseudoMoves(p, p.whiteToMove).filter { !inCheck(apply(p, it), p.whiteToMove) }
        if (moves.isEmpty()) return if (inCheck(p, p.whiteToMove)) -100000 + ply else 0
        if (depth <= 0) return evaluate(p)
        var alpha = alpha0
        var best = Int.MIN_VALUE + 1
        for (m in orderMoves(p, moves)) {
            val score = -search(apply(p, m), depth - 1, -beta0, -alpha, ply + 1)
            if (score > best) best = score
            if (score > alpha) alpha = score
            if (alpha >= beta0) break
        }
        return best
    }
    private fun evaluate(p: Position): Int {
        var score = 0
        for (i in 0..63) {
            val c = p.board[i]
            if (c == '.') continue
            val white = c.isUpperCase()
            val value = when (c.uppercaseChar()) { 'P' -> 100; 'N' -> 320; 'B' -> 335; 'R' -> 500; 'Q' -> 900; 'K' -> 0; else -> 0 }
            val r = i / 8
            val col = i % 8
            val center = 7 - (abs(3 - col) + abs(3 - r))
            val progress = if (c.uppercaseChar() == 'P') (if (white) 6 - r else r - 1) * 5 else 0
            score += (if (white) 1 else -1) * (value + center * 2 + progress)
        }
        return if (p.whiteToMove) score else -score
    }
    private fun orderMoves(p: Position, moves: List<Move>) = moves.sortedByDescending {
        val target = p.board[it.to]
        (if (target == '.') 0 else value(target) * 10 - value(p.board[it.from])) + if (it.promotion != 'Q') 50 else 0
    }
    private fun value(c: Char) = when (c.uppercaseChar()) { 'P' -> 100; 'N' -> 320; 'B' -> 335; 'R' -> 500; 'Q' -> 900; 'K' -> 20000; else -> 0 }

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
