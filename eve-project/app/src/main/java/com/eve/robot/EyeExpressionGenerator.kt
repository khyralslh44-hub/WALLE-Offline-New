package com.eve.robot

import kotlin.random.Random

/**
 * Generates smooth, parameterized EVE eye poses from the same affect snapshot consumed by voice.
 * This is an offline procedural expression generator, not a library of fixed images.
 */
data class EyeExpressionProfile(
    val mood: EveMood,
    val opening: Float,
    val tilt: Float,
    val gazeX: Float,
    val gazeY: Float,
    val glow: Float,
    val squint: Float,
    val asymmetry: Float,
    val eyeWidth: Float,
    val eyeHeight: Float,
    val spacing: Float
)

object EyeExpressionGenerator {
    fun generate(decision: EveDecision, seed: Long): EyeExpressionProfile {
        val base = when (decision.mood) {
            EveMood.AWAKE -> floatArrayOf(1.00f, 0f, 0f, -0.025f, 0.95f, 1.00f, 0.02f, 1.00f, 1.00f, 1.00f)
            EveMood.NEUTRAL -> floatArrayOf(0.82f, 0f, 0f, 0f, 0.72f, 0.94f, 0.00f, 1.00f, 0.94f, 1.00f)
            EveMood.CURIOUS -> floatArrayOf(1.04f, -6f, 0.045f, -0.025f, 0.98f, 1.10f, 0.17f, 1.04f, 1.08f, 0.97f)
            EveMood.HAPPY -> floatArrayOf(0.70f, -5f, 0.015f, -0.01f, 0.92f, 0.72f, 0.11f, 1.02f, 0.82f, 0.98f)
            EveMood.SURPRISED -> floatArrayOf(1.15f, 0f, 0f, -0.045f, 1.00f, 1.16f, 0.015f, 1.08f, 1.15f, 1.08f)
            EveMood.CONCERNED -> floatArrayOf(0.64f, 4f, -0.025f, 0.035f, 0.57f, 0.77f, -0.15f, 0.96f, 0.90f, 0.95f)
            EveMood.FAMILIAR -> floatArrayOf(0.84f, -2f, 0.02f, 0.008f, 0.88f, 0.88f, 0.12f, 0.99f, 0.91f, 1.03f)
            EveMood.SLEEPY -> floatArrayOf(0.25f, 0f, 0f, 0.055f, 0.42f, 0.53f, 0.00f, 1.02f, 0.80f, 1.00f)
            EveMood.FOCUSED -> floatArrayOf(0.54f, 2f, 0.035f, 0f, 0.66f, 0.66f, -0.10f, 0.97f, 0.84f, 0.94f)
        }
        val random = Random(seed)
        fun vary(index: Int, range: Float, low: Float, high: Float): Float =
            (base[index] + (random.nextFloat() * 2f - 1f) * range).coerceIn(low, high)

        val valence = decision.valence.coerceIn(-1f, 1f)
        val arousal = decision.arousal.coerceIn(0f, 1f)
        val curiosity = decision.curiosity.coerceIn(0f, 1f)
        val familiarity = decision.familiarity.coerceIn(0f, 1f)
        val fatigue = decision.fatigue.coerceIn(0f, 1f)

        val opening = (vary(0, 0.055f, 0.14f, 1.20f) +
            (arousal - 0.42f) * 0.24f - fatigue * 0.17f +
            (random.nextFloat() - 0.5f) * 0.14f).coerceIn(0.14f, 1.20f)
        val tilt = (vary(1, 2.4f, -18f, 18f) + valence * 4.0f +
            (curiosity - 0.5f) * 8.0f + (familiarity - 0.3f) * 3.2f +
            (random.nextFloat() - 0.5f) * 4.5f).coerceIn(-22f, 22f)
        val gazeX = (vary(2, 0.025f, -0.12f, 0.12f) +
            (curiosity - 0.5f) * 0.105f + (random.nextFloat() - 0.5f) * 0.055f)
            .coerceIn(-0.16f, 0.16f)
        val gazeY = (vary(3, 0.020f, -0.10f, 0.10f) +
            fatigue * 0.060f - arousal * 0.030f + (random.nextFloat() - 0.5f) * 0.045f)
            .coerceIn(-0.12f, 0.12f)
        val glow = (vary(4, 0.07f, 0.22f, 1f) *
            (0.86f + valence * 0.07f + arousal * 0.05f + familiarity * 0.04f))
            .coerceIn(0.22f, 1f)
        val squint = (vary(5, 0.075f, 0.42f, 1.24f) +
            valence * 0.08f - fatigue * 0.26f + (random.nextFloat() - 0.5f) * 0.13f)
            .coerceIn(0.40f, 1.25f)
        val asymmetry = (vary(6, 0.055f, -0.30f, 0.30f) +
            (curiosity - 0.5f) * 0.26f + (random.nextFloat() - 0.5f) * 0.26f)
            .coerceIn(-0.38f, 0.38f)
        val eyeWidth = (vary(7, 0.045f, 0.80f, 1.22f) +
            (arousal - 0.45f) * 0.075f + (random.nextFloat() - 0.5f) * 0.07f)
            .coerceIn(0.76f, 1.24f)
        val eyeHeight = (vary(8, 0.055f, 0.62f, 1.24f) +
            valence * 0.045f - fatigue * 0.09f + (random.nextFloat() - 0.5f) * 0.07f)
            .coerceIn(0.60f, 1.25f)
        val spacing = (vary(9, 0.035f, 0.80f, 1.20f) +
            (arousal - 0.4f) * 0.08f + (random.nextFloat() - 0.5f) * 0.065f)
            .coerceIn(0.78f, 1.22f)

        return EyeExpressionProfile(
            mood = decision.mood,
            opening = opening,
            tilt = tilt,
            gazeX = gazeX,
            gazeY = gazeY,
            glow = glow,
            squint = squint,
            asymmetry = asymmetry,
            eyeWidth = eyeWidth,
            eyeHeight = eyeHeight,
            spacing = spacing
        )
    }
}
