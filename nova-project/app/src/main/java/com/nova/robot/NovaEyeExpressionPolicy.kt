package com.nova.robot

/** Pure expression profiles so eye shape can be tested independently of Android drawing. */
internal enum class NovaEyePalette { AQUA, MINT, ROSE, ICE, REST, VIOLET, LIME }

internal data class NovaEyeProfile(
    val widthFactor: Float,
    val heightFactor: Float,
    val tiltDegrees: Float,
    val palette: NovaEyePalette,
    val heartShaped: Boolean = false,
    val smiling: Boolean = false,
    val brow: Boolean = false,
    val tear: Boolean = false
)

internal object NovaEyeExpressionPolicy {
    fun profile(mood: NovaMood, isLeft: Boolean): NovaEyeProfile = when (mood) {
        NovaMood.SURPRISED -> NovaEyeProfile(0.55f, 0.63f, 0f, NovaEyePalette.ICE)
        NovaMood.SLEEPY -> NovaEyeProfile(0.52f, 0.31f, 0f, NovaEyePalette.REST)
        NovaMood.TIRED -> NovaEyeProfile(0.51f, 0.28f, 0f, NovaEyePalette.REST)
        NovaMood.HAPPY, NovaMood.WARM ->
            NovaEyeProfile(0.49f, 0.43f, 0f, NovaEyePalette.MINT, smiling = true)
        NovaMood.PLAYFUL ->
            NovaEyeProfile(0.50f, 0.50f, if (isLeft) -2f else 2f, NovaEyePalette.MINT, smiling = true)
        NovaMood.LOVE ->
            NovaEyeProfile(0.53f, 0.56f, 0f, NovaEyePalette.ROSE, heartShaped = true)
        NovaMood.ALERT ->
            NovaEyeProfile(0.54f, 0.58f, if (isLeft) -2f else 2f, NovaEyePalette.ICE)
        NovaMood.AFRAID ->
            NovaEyeProfile(0.56f, 0.65f, if (isLeft) -2f else 2f, NovaEyePalette.VIOLET)
        NovaMood.CAUTIOUS ->
            NovaEyeProfile(0.47f, 0.40f, if (isLeft) -5f else 5f, NovaEyePalette.AQUA)
        NovaMood.FRUSTRATED ->
            NovaEyeProfile(0.50f, 0.43f, if (isLeft) -12f else 12f, NovaEyePalette.ROSE, brow = true)
        NovaMood.DISGUSTED ->
            NovaEyeProfile(0.48f, if (isLeft) 0.43f else 0.31f, if (isLeft) -8f else 4f, NovaEyePalette.LIME)
        NovaMood.SAD ->
            NovaEyeProfile(0.49f, 0.42f, if (isLeft) 7f else -7f, NovaEyePalette.VIOLET, tear = isLeft)
        NovaMood.CONFUSED ->
            NovaEyeProfile(if (isLeft) 0.48f else 0.52f, if (isLeft) 0.56f else 0.41f,
                if (isLeft) 5f else -4f, NovaEyePalette.ICE, brow = true)
        NovaMood.CURIOUS ->
            NovaEyeProfile(if (isLeft) 0.48f else 0.51f, if (isLeft) 0.58f else 0.48f,
                if (isLeft) -2f else 2f, NovaEyePalette.AQUA)
        else -> NovaEyeProfile(0.50f, 0.50f, 0f, NovaEyePalette.AQUA)
    }
}
