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
    fun openingMoveGenerationMatchesStandardPerftCounts() {
        val chess = ChessEngine()
        assertEquals("perft depth 1", 20L, chess.perft(1))
        assertEquals("perft depth 2", 400L, chess.perft(2))
        assertEquals("perft depth 3", 8_902L, chess.perft(3))
        assertEquals("perft must not mutate the live game", 20, chess.legalMoves().size)
        assertTrue(chess.position.whiteToMove)
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
    fun legalCaptureRemovesTheCapturedPieceAndLeavesMoverOnDestination() {
        val chess = ChessEngine()
        assertTrue(chess.play(ChessEngine.Move(from = 52, to = 36))) // e2-e4
        assertTrue(chess.play(ChessEngine.Move(from = 11, to = 27))) // d7-d5
        assertTrue(chess.play(ChessEngine.Move(from = 36, to = 27))) // e4xd5

        assertEquals('P', chess.position.board[27])
        assertEquals('.', chess.position.board[36])
        assertTrue(chess.position.whiteToMove.not())
    }

    @Test
    fun castlingMovesTheKingAndRookTogetherAfterThePathIsCleared() {
        val chess = ChessEngine()
        assertTrue(chess.play(ChessEngine.Move(52, 36))) // e2-e4
        assertTrue(chess.play(ChessEngine.Move(12, 28))) // e7-e5
        assertTrue(chess.play(ChessEngine.Move(62, 45))) // Ng1-f3
        assertTrue(chess.play(ChessEngine.Move(1, 18)))  // Nb8-c6
        assertTrue(chess.play(ChessEngine.Move(61, 34))) // Bf1-c4
        assertTrue(chess.play(ChessEngine.Move(6, 21)))  // Ng8-f6
        assertTrue(chess.play(ChessEngine.Move(60, 62))) // O-O

        assertEquals('K', chess.position.board[62])
        assertEquals('R', chess.position.board[61])
        assertEquals('.', chess.position.board[60])
        assertEquals('.', chess.position.board[63])
        assertFalse(chess.position.whiteKingSide)
    }

    @Test
    fun enPassantCaptureRemovesThePawnFromThePassedSquare() {
        val chess = ChessEngine()
        assertTrue(chess.play(ChessEngine.Move(51, 35))) // d2-d4
        assertTrue(chess.play(ChessEngine.Move(8, 16)))  // a7-a6
        assertTrue(chess.play(ChessEngine.Move(35, 27))) // d4-d5
        assertTrue(chess.play(ChessEngine.Move(12, 28))) // e7-e5
        assertTrue(chess.play(ChessEngine.Move(27, 20))) // d5xe6 e.p.

        assertEquals('P', chess.position.board[20])
        assertEquals('.', chess.position.board[28])
        assertEquals('.', chess.position.board[27])
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
