package com.eve.robot

/** Hand-sign mapping and opponent strategy used by the offline camera RPS game. */
enum class RpsMove(val arabicName: String, val emoji: String) {
    ROCK("حجر", "✊"),
    PAPER("ورقة", "✋"),
    SCISSORS("مقص", "✌")
}

enum class RpsOutcome { PLAYER, EVE, DRAW }

object RpsGestureLogic {
    /** MediaPipe Gesture Recognizer model labels. Confidence filtering happens before mapping. */
    fun fromGesture(categoryName: String?, confidence: Float): RpsMove? {
        if (categoryName.isNullOrBlank() || !confidence.isFinite() || confidence < 0.68f) return null
        return when (categoryName.trim()) {
            "Closed_Fist" -> RpsMove.ROCK
            "Open_Palm" -> RpsMove.PAPER
            "Victory" -> RpsMove.SCISSORS
            else -> null
        }
    }

    fun outcome(player: RpsMove, eve: RpsMove): RpsOutcome {
        if (player == eve) return RpsOutcome.DRAW
        val playerWins = (player == RpsMove.ROCK && eve == RpsMove.SCISSORS) ||
            (player == RpsMove.PAPER && eve == RpsMove.ROCK) ||
            (player == RpsMove.SCISSORS && eve == RpsMove.PAPER)
        return if (playerWins) RpsOutcome.PLAYER else RpsOutcome.EVE
    }

    /**
     * Mostly non-deterministic play, with occasional pattern reading after four rounds.
     * strategyRoll is 0..99; rolls 0..34 use a counter to the most frequent prior move,
     * while the rest choose freely so EVE does not mechanically counter every time.
     * randomMoveIndex is 0..2 and breaks ties without biasing the result logic.
     */
    fun chooseEveMove(
        history: List<RpsMove>,
        strategyRoll: Int,
        randomMoveIndex: Int
    ): RpsMove {
        val safeRandom = ((randomMoveIndex % RpsMove.values().size) + RpsMove.values().size) %
            RpsMove.values().size
        if (history.size < 4 || strategyRoll !in 0..34) {
            return RpsMove.values()[safeRandom]
        }
        val counts = RpsMove.values().associateWith { move -> history.count { it == move } }
        val maxCount = counts.values.maxOrNull() ?: 0
        val mostUsed = counts.filterValues { it == maxCount }.keys.toList()
        if (mostUsed.size != 1) return RpsMove.values()[safeRandom]
        return when (mostUsed.single()) {
            RpsMove.ROCK -> RpsMove.PAPER
            RpsMove.PAPER -> RpsMove.SCISSORS
            RpsMove.SCISSORS -> RpsMove.ROCK
        }
    }
}
