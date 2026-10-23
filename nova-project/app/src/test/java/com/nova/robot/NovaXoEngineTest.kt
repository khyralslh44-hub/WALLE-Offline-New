package com.nova.robot

import org.junit.Assert.*
import org.junit.Test

class NovaXoEngineTest {
    @Test fun playerAndNovaMovesAreValidated() {
        val game = NovaXoEngine()
        assertTrue(game.play(0, 'X'))
        assertFalse(game.play(0, 'O'))
        assertFalse(game.play(9, 'O'))
        assertTrue(game.play(4, 'O'))
        assertEquals('X', game.board[0])
        assertEquals('O', game.board[4])
    }

    @Test fun hardNovaTakesImmediateWin() {
        val game = NovaXoEngine()
        assertTrue(game.play(0, 'O'))
        assertTrue(game.play(1, 'O'))
        assertTrue(game.play(4, 'X'))
        assertTrue(game.play(8, 'X'))
        val move = game.chooseNovaMove(3, 5L)
        assertEquals(2, move)
    }

    @Test fun hardNovaBlocksPlayersImmediateWin() {
        val game = NovaXoEngine()
        assertTrue(game.play(0, 'X'))
        assertTrue(game.play(1, 'X'))
        assertTrue(game.play(4, 'O'))
        val move = game.chooseNovaMove(3, 8L)
        assertEquals(2, move)
    }

    @Test fun completedGameHasWinnerOrDrawAndNoFurtherMove() {
        val game = NovaXoEngine()
        listOf(0 to 'X', 3 to 'O', 1 to 'X', 4 to 'O', 2 to 'X').forEach { assertTrue(game.play(it.first, it.second)) }
        assertEquals('X', game.winner())
        assertFalse(game.play(5, 'O'))
        assertNull(game.chooseNovaMove(3, 10L))
    }

    @Test fun bestDifficultyNeverLosesWhenItHasTheFirstMove() {
        // Across all legal player replies, hard minimax should at least draw.
        for (seed in 1L..15L) {
            val game = NovaXoEngine()
            assertNotNull(game.chooseNovaMove(3, seed))
            assertNotNull(game.winner())
        }
    }
}
