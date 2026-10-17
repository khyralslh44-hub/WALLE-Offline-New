package com.eve.robot

import kotlin.math.abs

/** Converts model labels and short motion history into deliberate, debounced hand events. */
class HandGestureInterpreter(
    private val confidenceThreshold: Float = 0.68f,
    private val stableWindow: Int = 3,
    private val stableVotes: Int = 2
) {
    private data class Sample(
        val label: String,
        val confidence: Float,
        val x: Float?,
        val span: Float?,
        val timeMs: Long
    )

    private val recent = ArrayList<Sample>()
    private val openPalmXs = ArrayList<Pair<Long, Float>>()
    private var stableLabel: String? = null
    private var lastEmittedLabel: String? = null
    private var emptyFrames = 0
    private var lastFistSpan: Float? = null
    private var lastFistTime = 0L
    private var punchEmittedForFist = false
    private var waveEmittedForPalm = false

    @Synchronized
    fun observe(
        categoryName: String?,
        confidence: Float,
        handCenterX: Float?,
        handSpan: Float?,
        nowMs: Long
    ): String? {
        val label = normalize(categoryName)
        val valid = label != null && confidence.isFinite() &&
            confidence >= confidenceThreshold && nowMs >= 0L

        if (!valid) {
            emptyFrames++
            recent.clear()
            if (emptyFrames >= 2) {
                stableLabel = null
                lastEmittedLabel = null
                openPalmXs.clear()
                waveEmittedForPalm = false
                punchEmittedForFist = false
                lastFistSpan = null
                lastFistTime = 0L
            }
            return null
        }

        emptyFrames = 0
        val safeLabel = requireNotNull(label)
        recent += Sample(
            safeLabel,
            confidence.coerceIn(0f, 1f),
            handCenterX?.takeIf { it.isFinite() && it in 0f..1f },
            handSpan?.takeIf { it.isFinite() && it in 0f..1f },
            nowMs
        )
        while (recent.size > stableWindow) recent.removeAt(0)
        val winningLabel = recent.groupingBy { it.label }.eachCount()
            .maxByOrNull { it.value }
            ?.takeIf { it.value >= stableVotes }
            ?.key ?: return null
        val winningSample = recent.lastOrNull { it.label == winningLabel } ?: return null
        if (stableLabel != winningLabel) {
            stableLabel = winningLabel
            lastEmittedLabel = null
            if (winningLabel != "Open_Palm") {
                openPalmXs.clear()
                waveEmittedForPalm = false
            }
            if (winningLabel != "Closed_Fist") {
                lastFistSpan = null
                lastFistTime = 0L
                punchEmittedForFist = false
            }
        }

        if (winningLabel == "Open_Palm" && winningSample.x != null) {
            openPalmXs += nowMs to winningSample.x
            while (openPalmXs.isNotEmpty() && nowMs - openPalmXs.first().first > 2600L) {
                openPalmXs.removeAt(0)
            }
            if (!waveEmittedForPalm && isWave()) {
                waveEmittedForPalm = true
                lastEmittedLabel = "wave"
                return "wave"
            }
        }

        if (winningLabel == "Closed_Fist" && winningSample.span != null) {
            // After the forward-motion cue, suppress further "fist" events for this held pose.
            // A new non-fist stable label resets this latch in the stable-label transition above.
            if (punchEmittedForFist) return null
            val previousSpan = lastFistSpan
            val quickApproach = previousSpan != null &&
                nowMs - lastFistTime in 1L..850L &&
                winningSample.span - previousSpan >= 0.045f &&
                winningSample.span >= previousSpan * 1.38f
            lastFistSpan = winningSample.span
            lastFistTime = nowMs
            if (quickApproach && !punchEmittedForFist) {
                punchEmittedForFist = true
                lastEmittedLabel = "punch"
                return "punch"
            }
        }

        if (lastEmittedLabel == winningLabel) return null
        lastEmittedLabel = winningLabel
        return when (winningLabel) {
            "Closed_Fist" -> "fist"
            "Open_Palm" -> "open_palm"
            "Victory" -> "victory"
            "Thumb_Up" -> "thumb_up"
            "Thumb_Down" -> "thumb_down"
            "Pointing_Up" -> "pointing_up"
            "ILoveYou" -> "i_love_you"
            else -> null
        }
    }

    @Synchronized
    fun reset() {
        recent.clear()
        openPalmXs.clear()
        stableLabel = null
        lastEmittedLabel = null
        emptyFrames = 0
        lastFistSpan = null
        lastFistTime = 0L
        punchEmittedForFist = false
        waveEmittedForPalm = false
    }

    private fun isWave(): Boolean {
        if (openPalmXs.size < 5) return false
        val xs = openPalmXs.map { it.second }
        val range = (xs.maxOrNull() ?: 0f) - (xs.minOrNull() ?: 0f)
        if (range < 0.19f) return false
        var previousDirection = 0
        var reversals = 0
        for (index in 1 until xs.size) {
            val delta = xs[index] - xs[index - 1]
            val direction = when {
                delta >= 0.055f -> 1
                delta <= -0.055f -> -1
                else -> 0
            }
            if (direction != 0) {
                if (previousDirection != 0 && direction != previousDirection) reversals++
                previousDirection = direction
            }
        }
        return reversals >= 2
    }

    private fun normalize(name: String?): String? {
        val value = name?.trim() ?: return null
        return when (value) {
            "Closed_Fist", "Open_Palm", "Victory", "Thumb_Up", "Thumb_Down", "Pointing_Up", "ILoveYou" -> value
            else -> null
        }
    }
}

/** Unit-testable mapping for the standard MediaPipe Gesture Recognizer categories. */
object HandGestureMapping {
    fun signal(categoryName: String?, confidence: Float): String? {
        if (categoryName.isNullOrBlank() || !confidence.isFinite() || confidence < 0.68f) return null
        return when (categoryName.trim()) {
            "Closed_Fist" -> "fist"
            "Open_Palm" -> "open_palm"
            "Victory" -> "victory"
            "Thumb_Up" -> "thumb_up"
            "Thumb_Down" -> "thumb_down"
            "Pointing_Up" -> "pointing_up"
            "ILoveYou" -> "i_love_you"
            else -> null
        }
    }
}
