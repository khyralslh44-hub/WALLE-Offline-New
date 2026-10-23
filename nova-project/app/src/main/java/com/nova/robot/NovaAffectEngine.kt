package com.nova.robot

import kotlin.math.min

/**
 * Lightweight, deterministic affect state for Nova.
 *
 * This is a computational behavior model, not a claim of human-like subjective feelings.
 * Inputs must be grounded in observed events or explicit user feedback. No random actions,
 * network calls, heavy model inference, or per-frame work are performed here.
 */
enum class NovaAffectEvent {
    FRIENDLY_INTERACTION,
    USER_PRAISE,
    USER_CORRECTION,
    REPEATED_FAILURE,
    SUCCESSFUL_TASK,
    UNCERTAIN_OBSERVATION,
    NOVEL_OBSERVATION,
    THREAT_OR_EDGE_RISK,
    RESTFUL_PERIOD,
    USER_RETURNED
}

data class NovaAffectState(
    val curiosity: Float = 0.35f,
    val comfort: Float = 0.55f,
    val trust: Float = 0.35f,
    val frustration: Float = 0.05f,
    val alertness: Float = 0.25f,
    val energy: Float = 0.75f,
    val updatedAtMillis: Long = 0L
) {
    fun bounded(): NovaAffectState = copy(
        curiosity = clamp(curiosity),
        comfort = clamp(comfort),
        trust = clamp(trust),
        frustration = clamp(frustration),
        alertness = clamp(alertness),
        energy = clamp(energy)
    )

    companion object {
        private fun clamp(value: Float) = value.coerceIn(0f, 1f)
    }
}

enum class NovaAffectMood { CALM, CURIOUS, WARM, PLAYFUL, FRUSTRATED, ALERT, TIRED, CAUTIOUS }

/**
 * Small rule-based appraisal/state layer. Neural intent classification can provide a signal,
 * while this state layer integrates repeated events over time and smooths abrupt changes.
 */
class NovaAffectEngine(initial: NovaAffectState = NovaAffectState()) {
    @Volatile private var current = initial.bounded()

    @Synchronized
    fun state(nowMillis: Long): NovaAffectState {
        current = decayTo(current, nowMillis)
        return current
    }

    @Synchronized
    fun observe(event: NovaAffectEvent, nowMillis: Long): NovaAffectState {
        val s = decayTo(current, nowMillis)
        val changed = when (event) {
            NovaAffectEvent.FRIENDLY_INTERACTION -> s.copy(
                comfort = s.comfort + 0.08f, trust = s.trust + 0.025f,
                frustration = s.frustration - 0.05f, energy = s.energy + 0.015f
            )
            NovaAffectEvent.USER_PRAISE -> s.copy(
                comfort = s.comfort + 0.07f, trust = s.trust + 0.04f,
                frustration = s.frustration - 0.08f
            )
            NovaAffectEvent.USER_CORRECTION -> s.copy(
                curiosity = s.curiosity + 0.035f, frustration = s.frustration + 0.04f,
                alertness = s.alertness + 0.025f
            )
            NovaAffectEvent.REPEATED_FAILURE -> s.copy(
                frustration = s.frustration + 0.12f, curiosity = s.curiosity + 0.025f,
                energy = s.energy - 0.035f
            )
            NovaAffectEvent.SUCCESSFUL_TASK -> s.copy(
                comfort = s.comfort + 0.045f, trust = s.trust + 0.025f,
                frustration = s.frustration - 0.09f, energy = s.energy - 0.015f
            )
            NovaAffectEvent.UNCERTAIN_OBSERVATION -> s.copy(
                curiosity = s.curiosity + 0.06f, alertness = s.alertness + 0.025f
            )
            NovaAffectEvent.NOVEL_OBSERVATION -> s.copy(
                curiosity = s.curiosity + 0.13f, energy = s.energy - 0.01f
            )
            NovaAffectEvent.THREAT_OR_EDGE_RISK -> s.copy(
                alertness = s.alertness + 0.35f, comfort = s.comfort - 0.12f,
                curiosity = s.curiosity - 0.08f
            )
            NovaAffectEvent.RESTFUL_PERIOD -> s.copy(
                energy = s.energy + 0.08f, frustration = s.frustration - 0.025f,
                alertness = s.alertness - 0.06f
            )
            NovaAffectEvent.USER_RETURNED -> s.copy(
                comfort = s.comfort + 0.045f, energy = s.energy + 0.025f,
                curiosity = s.curiosity + 0.025f
            )
        }
        current = changed.copy(updatedAtMillis = nowMillis).bounded()
        return current
    }

    @Synchronized
    fun mood(nowMillis: Long): NovaAffectMood {
        val s = state(nowMillis)
        return when {
            s.alertness >= 0.72f -> NovaAffectMood.ALERT
            s.frustration >= 0.68f -> NovaAffectMood.FRUSTRATED
            s.energy <= 0.18f -> NovaAffectMood.TIRED
            s.comfort >= 0.72f && s.energy >= 0.45f -> NovaAffectMood.WARM
            s.curiosity >= 0.68f -> NovaAffectMood.CURIOUS
            s.comfort >= 0.62f && s.curiosity >= 0.48f -> NovaAffectMood.PLAYFUL
            s.comfort <= 0.25f || s.trust <= 0.15f -> NovaAffectMood.CAUTIOUS
            else -> NovaAffectMood.CALM
        }
    }

    /** Compact local serialization suitable for app-private preferences. */
    @Synchronized
    fun snapshot(nowMillis: Long): String {
        val s = state(nowMillis)
        return listOf(s.curiosity, s.comfort, s.trust, s.frustration, s.alertness, s.energy)
            .joinToString(",") { it.toString() } + ",${s.updatedAtMillis}"
    }

    @Synchronized
    fun restore(snapshot: String): Boolean {
        val p = snapshot.split(",")
        if (p.size != 7) return false
        val values = p.take(6).map { it.toFloatOrNull() ?: return false }
        val timestamp = p[6].toLongOrNull() ?: return false
        if (values.any { !it.isFinite() || it < 0f || it > 1f } || timestamp < 0L) return false
        current = NovaAffectState(
            values[0], values[1], values[2], values[3], values[4], values[5], timestamp
        )
        return true
    }

    private fun decayTo(state: NovaAffectState, nowMillis: Long): NovaAffectState {
        if (state.updatedAtMillis <= 0L || nowMillis <= state.updatedAtMillis) return state
        // One small decay step per elapsed 10-minute interval, capped to avoid long startup work.
        val steps = min(72, ((nowMillis - state.updatedAtMillis) / DECAY_INTERVAL_MS).toInt())
        if (steps <= 0) return state
        fun toward(value: Float, baseline: Float, rate: Float): Float {
            var v = value
            repeat(steps) { v += (baseline - v) * rate }
            return v
        }
        return state.copy(
            curiosity = toward(state.curiosity, 0.35f, 0.025f),
            comfort = toward(state.comfort, 0.55f, 0.012f),
            trust = toward(state.trust, 0.35f, 0.002f),
            frustration = toward(state.frustration, 0.05f, 0.08f),
            alertness = toward(state.alertness, 0.25f, 0.07f),
            energy = toward(state.energy, 0.75f, 0.015f),
            updatedAtMillis = nowMillis
        ).bounded()
    }

    companion object {
        private const val DECAY_INTERVAL_MS = 10 * 60 * 1000L
    }
}
