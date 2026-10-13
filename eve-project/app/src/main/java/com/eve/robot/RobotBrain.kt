package com.eve.robot

import android.content.Context
import kotlin.math.min

enum class EveMood {
    AWAKE, NEUTRAL, CURIOUS, HAPPY, SURPRISED, CONCERNED, FAMILIAR, SLEEPY, FOCUSED
}

data class EveDecision(
    val mood: EveMood,
    val reason: String,
    val playSound: Boolean = true
)

/** Rule-based local behavior engine. This is adaptive behavior, not human-like consciousness. */
class RobotBrain {
    fun observe(label: String, confidence: Float, sightings: Int): EveDecision {
        val item = label.trim().lowercase()
        if (item.isBlank() || confidence < 0.45f) {
            return EveDecision(EveMood.CONCERNED, "uncertain visual label", false)
        }
        if (sightings >= 3) return EveDecision(EveMood.FAMILIAR, "familiar item: $item")
        val mood = when {
            listOf("food", "fruit", "banana", "apple", "pizza", "bread", "meal").any(item::contains) -> EveMood.HAPPY
            listOf("animal", "cat", "dog", "bird", "pet", "puppy", "kitten").any(item::contains) -> EveMood.CURIOUS
            listOf("person", "people", "human", "face").any(item::contains) -> EveMood.AWAKE
            confidence >= 0.80f -> EveMood.SURPRISED
            else -> EveMood.CURIOUS
        }
        return EveDecision(mood, "new observation: $item", confidence >= 0.72f)
    }

    fun touched(longPress: Boolean): EveDecision =
        if (longPress) EveDecision(EveMood.HAPPY, "long touch")
        else EveDecision(EveMood.CURIOUS, "touch")

    fun idle(inactiveMs: Long, next: Int): EveDecision = when {
        inactiveMs >= 40_000L -> EveDecision(EveMood.SLEEPY, "resting", false)
        inactiveMs < 5_000L -> EveDecision(EveMood.AWAKE, "recently active", false)
        next % 4 == 0 -> EveDecision(EveMood.FAMILIAR, "quiet friendly look", false)
        next % 3 == 0 -> EveDecision(EveMood.NEUTRAL, "quiet idle look", false)
        else -> EveDecision(EveMood.CURIOUS, "curious idle look", false)
    }
}

/** Persistent on-device counters. Only labels/counts/timestamps are saved, never camera frames. */
class EveMemory(context: Context) {
    private val prefs = context.getSharedPreferences("eve_local_memory", Context.MODE_PRIVATE)

    @Synchronized
    fun remember(label: String): Int {
        val key = label.trim().lowercase().take(80)
        if (key.isBlank()) return 0
        val next = (prefs.getInt("count:$key", 0) + 1).coerceAtMost(100_000)
        prefs.edit()
            .putInt("count:$key", next)
            .putLong("seen:$key", System.currentTimeMillis())
            .apply()
        return next
    }

    fun sightings(label: String): Int = prefs.getInt("count:${label.trim().lowercase().take(80)}", 0)
}
