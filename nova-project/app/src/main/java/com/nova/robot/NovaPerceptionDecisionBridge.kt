package com.nova.robot

/**
 * Adapter for the camera/perception and task layers.
 *
 * Call only from actual local perception results, not from every camera frame.
 * Presence transitions are deduplicated so a person visible across many frames
 * does not artificially accumulate "lost attention" or reassurance scores.
 */
class NovaPerceptionDecisionBridge(private val brain: NovaBrain) {
    private var lastPersonVisible: Boolean? = null

    fun onPersonPresence(visible: Boolean, confidence: Float): NovaEvent? {
        val c = confidence.coerceIn(0f, 1f)
        if (c < MIN_CONFIDENCE) return null
        if (lastPersonVisible == visible) return null
        lastPersonVisible = visible
        val signal = if (visible) NovaSignal.ATTENTION_RECEIVED else NovaSignal.ATTENTION_SHIFTED_AWAY
        return brain.observeAndDecide(NovaObservation(signal, c)).first
    }

    /** Submit when the vision model cannot confidently identify a newly seen object. */
    fun onUnknownObject(confidence: Float): NovaEvent? {
        if (confidence.coerceIn(0f, 1f) < MIN_CONFIDENCE) return null
        return brain.observeAndDecide(
            NovaObservation(NovaSignal.NOVEL_OBJECT_UNCERTAIN, confidence)
        ).first
    }

    /** Submit only after a local verifier or trusted correction confirms the object. */
    fun onObjectVerified(confidence: Float): NovaEvent =
        brain.observeAndDecide(
            NovaObservation(NovaSignal.NOVEL_OBJECT_CONFIRMED, confidence)
        ).first

    fun onTaskSucceeded(confidence: Float = 1f): NovaEvent =
        brain.observeAndDecide(NovaObservation(NovaSignal.TASK_SUCCEEDED, confidence)).first

    fun onTaskFailed(confidence: Float = 1f): NovaEvent =
        brain.observeAndDecide(NovaObservation(NovaSignal.TASK_FAILED, confidence)).first

    fun onTaskBlocked(confidence: Float = 1f): Pair<NovaEvent, NovaNextAction> =
        brain.observeAndDecide(NovaObservation(NovaSignal.TASK_BLOCKED, confidence))

    fun onSocialReassurance(confidence: Float = 1f): NovaEvent =
        brain.observeAndDecide(NovaObservation(NovaSignal.SOCIAL_REASSURANCE, confidence)).first

    companion object {
        private const val MIN_CONFIDENCE = 0.60f
    }
}
