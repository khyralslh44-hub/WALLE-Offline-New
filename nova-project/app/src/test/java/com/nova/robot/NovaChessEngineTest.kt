package com.nova.robot

import org.junit.Assert.*
import org.junit.Test

class NovaChessEngineTest {
    @Test fun startingPositionHasTwentyLegalMoves() {
        val chess = ChessEngine()
        assertTrue(chess.position.whiteToMove)
        assertEquals(20, chess.legalMoves().size)
        assertEquals(400L, chess.perft(2))
    }

    @Test fun rejectsIllegalMoveAndKeepsPositionUnchanged() {
        val chess = ChessEngine()
        val before = chess.position.board.copyOf()
        assertFalse(chess.play(ChessEngine.Move(52, 28)))
        assertTrue(before.contentEquals(chess.position.board))
    }

    @Test fun novaCanReplyWithALegalMoveOffline() {
        val chess = ChessEngine()
        assertTrue(chess.play(ChessEngine.Move(52, 36)))
        val reply = chess.robotMove(1, 42L)
        assertNotNull(reply)
        assertTrue(chess.play(requireNotNull(reply)))
        assertTrue(chess.position.whiteToMove)
    }

    @Test fun detectsCheckmate() {
        val chess = ChessEngine()
        assertTrue(chess.play(ChessEngine.Move(53, 45)))
        assertTrue(chess.play(ChessEngine.Move(12, 28)))
        assertTrue(chess.play(ChessEngine.Move(54, 38)))
        assertTrue(chess.play(ChessEngine.Move(3, 39)))
        assertTrue(chess.isCheckmate())
    }
}
