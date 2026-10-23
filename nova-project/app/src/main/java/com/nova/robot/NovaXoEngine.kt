package com.nova.robot

import kotlin.random.Random

/** Offline tic-tac-toe rules and a minimax opponent used by Nova. X is the human; O is Nova. */
internal class NovaXoEngine {
    private val cells = CharArray(9) { ' ' }
    val board: CharArray get() = cells.copyOf()

    fun reset() { cells.fill(' ') }

    fun legalMoves(): List<Int> = cells.indices.filter { cells[it] == ' ' }

    fun play(square: Int, mark: Char): Boolean {
        if (square !in cells.indices || mark !in setOf('X', 'O') || cells[square] != ' ' ||
            winner() != null || legalMoves().isEmpty()
        ) return false
        cells[square] = mark
        return true
    }

    fun winner(): Char? {
        for (line in WIN_LINES) {
            val mark = cells[line[0]]
            if (mark != ' ' && mark == cells[line[1]] && mark == cells[line[2]]) return mark
        }
        return null
    }

    fun isDraw(): Boolean = winner() == null && cells.none { it == ' ' }

    /** Difficulty 1 is casual, 2 is usually strategic with occasional mistakes, 3 uses minimax. */
    fun chooseNovaMove(difficulty: Int, seed: Long = System.nanoTime()): Int? {
        val moves = legalMoves()
        if (moves.isEmpty() || winner() != null) return null
        val random = Random(seed)
        val level = difficulty.coerceIn(1, 3)
        if (level == 1) return moves[random.nextInt(moves.size)]
        if (level == 2 && random.nextInt(5) == 0) return moves[random.nextInt(moves.size)]

        var bestScore = Int.MIN_VALUE
        val bestMoves = mutableListOf<Int>()
        for (move in moves) {
            cells[move] = 'O'
            val score = minimax(maximizeNova = false, depth = 1)
            cells[move] = ' '
            when {
                score > bestScore -> { bestScore = score; bestMoves.clear(); bestMoves.add(move) }
                score == bestScore -> bestMoves.add(move)
            }
        }
        return bestMoves[random.nextInt(bestMoves.size)]
    }

    private fun minimax(maximizeNova: Boolean, depth: Int): Int {
        when (winner()) {
            'O' -> return 10 - depth
            'X' -> return depth - 10
        }
        if (cells.none { it == ' ' }) return 0
        if (maximizeNova) {
            var best = Int.MIN_VALUE
            for (i in cells.indices) if (cells[i] == ' ') {
                cells[i] = 'O'
                best = maxOf(best, minimax(false, depth + 1))
                cells[i] = ' '
            }
            return best
        }
        var best = Int.MAX_VALUE
        for (i in cells.indices) if (cells[i] == ' ') {
            cells[i] = 'X'
            best = minOf(best, minimax(true, depth + 1))
            cells[i] = ' '
        }
        return best
    }

    private companion object {
        val WIN_LINES = arrayOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)
        )
    }
}
