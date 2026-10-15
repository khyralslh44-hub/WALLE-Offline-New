package com.eve.robot

/**
 * Generates deterministic EVE eye poses from one affect snapshot.
 * The same decision always yields the same pose; variation comes from context, not dice rolls.
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
    @Suppress("UNUSED_PARAMETER")
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
            // Playfulness uses a bright, slightly asymmetric look; irritation narrows the eyes.
            EveMood.PLAYFUL -> floatArrayOf(0.98f, -8f, 0.050f, -0.035f, 1.00f, 1.12f, 0.24f, 1.05f, 1.04f, 1.00f)
            EveMood.IRRITATED -> floatArrayOf(0.51f, 12f, -0.025f, 0.026f, 0.68f, 0.58f, -0.20f, 0.95f, 0.82f, 0.96f)
            EveMood.ANGRY -> floatArrayOf(0.43f, 16f, -0.035f, 0.040f, 0.74f, 0.50f, -0.24f, 0.92f, 0.78f, 0.94f)
        }

        val valence = decision.valence.coerceIn(-1f, 1f)
        val arousal = decision.arousal.coerceIn(0f, 1f)
        val curiosity = decision.curiosity.coerceIn(0f, 1f)
        val familiarity = decision.familiarity.coerceIn(0f, 1f)
        val fatigue = decision.fatigue.coerceIn(0f, 1f)

        val opening = (base[0] + (arousal - 0.42f) * 0.24f - fatigue * 0.17f)
            .coerceIn(0.14f, 1.20f)
        val tilt = (base[1] + valence * 4.0f +
            (curiosity - 0.5f) * 8.0f + (familiarity - 0.3f) * 3.2f)
            .coerceIn(-22f, 22f)
        val gazeX = (base[2] + (curiosity - 0.5f) * 0.105f).coerceIn(-0.16f, 0.16f)
        val gazeY = (base[3] + fatigue * 0.060f - arousal * 0.030f).coerceIn(-0.12f, 0.12f)
        val glow = (base[4] *
            (0.86f + valence * 0.07f + arousal * 0.05f + familiarity * 0.04f))
            .coerceIn(0.22f, 1f)
        val squint = (base[5] + valence * 0.08f - fatigue * 0.26f).coerceIn(0.40f, 1.25f)
        val asymmetry = (base[6] + (curiosity - 0.5f) * 0.26f).coerceIn(-0.38f, 0.38f)
        val eyeWidth = (base[7] + (arousal - 0.45f) * 0.075f).coerceIn(0.76f, 1.24f)
        val eyeHeight = (base[8] + valence * 0.045f - fatigue * 0.09f).coerceIn(0.60f, 1.25f)
        val spacing = (base[9] + (arousal - 0.4f) * 0.08f).coerceIn(0.78f, 1.22f)

        // Keep the emotional silhouette readable even when valence/arousal change.
        // Eye geometry is the primary visual cue; green remains the only eye color.
        val clearOpening = when (decision.mood) {
            EveMood.SURPRISED -> maxOf(opening, 1.12f)
            EveMood.CURIOUS -> maxOf(opening, 0.98f)
            EveMood.AWAKE -> maxOf(opening, 0.94f)
            EveMood.PLAYFUL -> maxOf(opening, 0.92f)
            EveMood.HAPPY -> minOf(opening, 0.72f)
            EveMood.NEUTRAL -> opening.coerceIn(0.72f, 0.94f)
            EveMood.FAMILIAR -> opening.coerceIn(0.74f, 0.92f)
            EveMood.CONCERNED -> minOf(opening, 0.58f)
            EveMood.FOCUSED -> minOf(opening, 0.48f)
            EveMood.IRRITATED -> minOf(opening, 0.43f)
            EveMood.ANGRY -> minOf(opening, 0.34f)
            EveMood.SLEEPY -> minOf(opening, 0.19f)
        }.coerceIn(0.14f, 1.20f)

        val clearTilt = when (decision.mood) {
            EveMood.AWAKE -> tilt.coerceIn(-4f, 4f)
            EveMood.NEUTRAL -> tilt.coerceIn(-3f, 3f)
            EveMood.CURIOUS -> minOf(tilt, -8f)
            EveMood.HAPPY -> -5f
            EveMood.SURPRISED -> tilt.coerceIn(-2f, 2f)
            EveMood.CONCERNED -> maxOf(tilt, 7f)
            EveMood.FAMILIAR -> tilt.coerceIn(-5f, 2f)
            EveMood.SLEEPY -> 0f
            EveMood.FOCUSED -> maxOf(tilt, 5f)
            EveMood.PLAYFUL -> minOf(tilt, -11f)
            EveMood.IRRITATED -> maxOf(tilt, 16f)
            EveMood.ANGRY -> maxOf(tilt, 21f)
        }.coerceIn(-22f, 22f)

        val clearSquint = when (decision.mood) {
            EveMood.AWAKE -> maxOf(squint, 0.96f)
            EveMood.NEUTRAL -> squint.coerceIn(0.84f, 1.02f)
            EveMood.CURIOUS -> maxOf(squint, 1.05f)
            EveMood.HAPPY -> minOf(squint, 0.72f)
            EveMood.SURPRISED -> maxOf(squint, 1.12f)
            EveMood.CONCERNED -> minOf(squint, 0.72f)
            EveMood.FAMILIAR -> squint.coerceIn(0.78f, 0.98f)
            EveMood.SLEEPY -> minOf(squint, 0.48f)
            EveMood.FOCUSED -> minOf(squint, 0.65f)
            EveMood.PLAYFUL -> maxOf(squint, 1.04f)
            EveMood.IRRITATED -> minOf(squint, 0.54f)
            EveMood.ANGRY -> minOf(squint, 0.43f)
        }.coerceIn(0.40f, 1.25f)

        val clearGazeX = when (decision.mood) {
            EveMood.CURIOUS, EveMood.PLAYFUL -> gazeX.coerceIn(0.045f, 0.14f)
            EveMood.IRRITATED, EveMood.ANGRY -> if (gazeX < 0f) gazeX else -0.055f
            EveMood.FOCUSED -> 0f
            else -> gazeX
        }.coerceIn(-0.16f, 0.16f)

        val clearGazeY = when (decision.mood) {
            EveMood.SLEEPY -> maxOf(gazeY, 0.045f)
            EveMood.SURPRISED, EveMood.CURIOUS -> minOf(gazeY, -0.025f)
            EveMood.CONCERNED -> maxOf(gazeY, 0.03f)
            EveMood.PLAYFUL -> minOf(gazeY, -0.02f)
            else -> gazeY
        }.coerceIn(-0.12f, 0.12f)

        return EyeExpressionProfile(
            mood = decision.mood,
            opening = clearOpening,
            tilt = clearTilt,
            gazeX = clearGazeX,
            gazeY = clearGazeY,
            glow = glow,
            squint = clearSquint,
            asymmetry = asymmetry,
            eyeWidth = eyeWidth,
            eyeHeight = eyeHeight,
            spacing = spacing
        )
    }
}
