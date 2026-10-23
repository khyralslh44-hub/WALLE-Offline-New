package com.nova.robot

/**
 * Deterministic policy layer joining visual evidence and Nova's persistent affect state.
 * It chooses a face/behavior response without fabricating an observation.
 */
internal object NovaBehaviorPlanner {
    fun chooseFaceMood(
        expressionMood: NovaMood?,
        affectMood: NovaAffectMood,
        hasFace: Boolean
    ): NovaMood {
        if (expressionMood != null) return expressionMood
        return when (affectMood) {
            NovaAffectMood.ALERT -> NovaMood.ALERT
            NovaAffectMood.CAUTIOUS -> NovaMood.CAUTIOUS
            NovaAffectMood.WARM -> NovaMood.WARM
            NovaAffectMood.PLAYFUL -> NovaMood.PLAYFUL
            NovaAffectMood.FRUSTRATED -> NovaMood.FRUSTRATED
            NovaAffectMood.TIRED -> NovaMood.TIRED
            NovaAffectMood.CURIOUS -> NovaMood.CURIOUS
            NovaAffectMood.CALM -> if (hasFace) NovaMood.CURIOUS else NovaMood.THINKING
        }
    }
}
