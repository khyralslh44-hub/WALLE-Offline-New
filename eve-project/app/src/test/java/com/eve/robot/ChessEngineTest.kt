package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessEngineTest {
    @Test
    fun initialPositionHasTwentyLegalMovesAndCorrectTurn() {
        val chess = ChessEngine()
        assertTrue(chess.position.whiteToMove)
        assertEquals(20, chess.legalMoves().size)
    }

    @Test
    fun illegalMoveDoesNotChangeBoardOrTurn() {
        val chess = ChessEngine()
        val before = chess.position.board.copyOf()
        assertFalse(chess.play(ChessEngine.Move(from = 52, to = 28))) // e2 to e5 is blocked/illegal.
        assertTrue(before.contentEquals(chess.position.board))
        assertTrue(chess.position.whiteToMove)
    }

    @Test
    fun legalMoveAndRobotReplyKeepTurnsAndMovesValid() {
        val chess = ChessEngine()
        assertTrue(chess.play(ChessEngine.Move(from = 52, to = 36))) // e2-e4
        assertFalse(chess.position.whiteToMove)
        assertEquals(20, chess.legalMoves().size)
        val reply = chess.robotMove(difficulty = 1)
        assertNotNull(reply)
        assertTrue(chess.play(requireNotNull(reply)))
        assertTrue(chess.position.whiteToMove)
    }

    @Test
    fun foolsMateIsRecognizedAsCheckmate() {
        val chess = ChessEngine()
        assertTrue(chess.play(ChessEngine.Move(53, 45))) // f2-f3
        assertTrue(chess.play(ChessEngine.Move(12, 28))) // e7-e5
        assertTrue(chess.play(ChessEngine.Move(54, 38))) // g2-g4
        assertTrue(chess.play(ChessEngine.Move(3, 39)))  // Qd8-h4#
        assertTrue(chess.isCheck())
        assertTrue(chess.isCheckmate())
        assertTrue(chess.legalMoves().isEmpty())
    }
}
