package com.nova.robot

import kotlin.math.max
import kotlin.math.min

/**
 * Offline, stateful appraisal engine. It does not claim human-like feelings:
 * it scores contextual signals, remembers recent outcomes locally, and chooses
 * a proportionate expression/action. Repeated single observations are insufficient
 * to infer that a person has lost interest.
 */
enum class NovaSignal {
    ATTENTION_RECEIVED,
    ATTENTION_SHIFTED_AWAY,
    TASK_SUCCEEDED,
    TASK_FAILED,
    TASK_BLOCKED,
    NOVEL_OBJECT_UNCERTAIN,
    NOVEL_OBJECT_CONFIRMED,
    SOCIAL_REASSURANCE,
    REST
}

data class NovaObservation(
    val signal: NovaSignal,
    val confidence: Float = 1f,
    val detailArabic: String = ""
)

data class NovaDecision(
    val mood: NovaMood,
    val messageArabic: String,
    val nextAction: NovaNextAction,
    val confidence: Float,
    val attentionConcern: Float,
    val frustration: Float,
    val curiosity: Float
)

enum class NovaNextAction {
    CONTINUE_NORMALLY,
    SEEK_GENTLE_INTERACTION,
    TRY_DIFFERENT_APPROACH,
    ASK_FOR_HELP,
    OBSERVE_AND_VERIFY,
    REMEMBER_CONFIRMED_LEARNING,
    REST_BRIEFLY
}

/**
 * Stateful decision policy; no network access, random choices, or timers.
 * Scores are persisted through the app's existing NovaMemory abstraction.
 */
class NovaEmotionalDecisionEngine(private val memory: NovaMemory) {
    fun decide(observation: NovaObservation): NovaDecision {
        val confidence = observation.confidence.coerceIn(0f, 1f)
        var attention = read(KEY_ATTENTION)
        var frustration = read(KEY_FRUSTRATION)
        var curiosity = read(KEY_CURIOSITY)
        var reassurance = read(KEY_REASSURANCE)

        // Gradual recovery prevents one event from permanently defining Nova's mood.
        attention = decay(attention, 0.97f)
        frustration = decay(frustration, 0.86f)
        curiosity = decay(curiosity, 0.90f)
        reassurance = decay(reassurance, 0.94f)

        when (observation.signal) {
            NovaSignal.ATTENTION_RECEIVED -> {
                attention = max(0f, attention - 0.18f * confidence)
                reassurance = min(1f, reassurance + 0.12f * confidence)
            }
            NovaSignal.ATTENTION_SHIFTED_AWAY -> {
                // Only a weak clue: repeated observations accumulate gradually.
                attention = min(1f, attention + 0.08f * confidence)
            }
            NovaSignal.TASK_SUCCEEDED -> {
                frustration = max(0f, frustration - 0.32f * confidence)
                reassurance = min(1f, reassurance + 0.06f * confidence)
            }
            NovaSignal.TASK_FAILED -> frustration = min(1f, frustration + 0.18f * confidence)
            NovaSignal.TASK_BLOCKED -> frustration = min(1f, frustration + 0.30f * confidence)
            NovaSignal.NOVEL_OBJECT_UNCERTAIN -> curiosity = min(1f, curiosity + 0.22f * confidence)
            NovaSignal.NOVEL_OBJECT_CONFIRMED -> {
                curiosity = max(0f, curiosity - 0.20f * confidence)
                reassurance = min(1f, reassurance + 0.08f * confidence)
            }
            NovaSignal.SOCIAL_REASSURANCE -> {
                attention = max(0f, attention - 0.38f * confidence)
                reassurance = min(1f, reassurance + 0.25f * confidence)
            }
            NovaSignal.REST -> {
                frustration = max(0f, frustration - 0.20f * confidence)
                curiosity = max(0f, curiosity - 0.08f * confidence)
            }
        }

        write(KEY_ATTENTION, attention)
        write(KEY_FRUSTRATION, frustration)
        write(KEY_CURIOSITY, curiosity)
        write(KEY_REASSURANCE, reassurance)

        val (mood, action, message) = when {
            frustration >= 0.62f && observation.signal == NovaSignal.TASK_BLOCKED ->
                Triple(NovaMood.FRUSTRATED, NovaNextAction.ASK_FOR_HELP, "تعذّر عليّ هذا الأمر. سأغيّر طريقتي أو أطلب المساعدة بدل تكرار المحاولة نفسها.")
            // A newly observed uncertain object must trigger verification immediately,
            // even when frustration from an earlier task has not fully decayed.
            observation.signal == NovaSignal.NOVEL_OBJECT_UNCERTAIN && curiosity >= 0.18f ->
                Triple(NovaMood.CURIOUS, NovaNextAction.OBSERVE_AND_VERIFY, "هذا شيء لم أتأكد منه بعد. سأجمع ملاحظات أكثر قبل أن أقرر ما هو.")
            frustration >= 0.42f ->
                Triple(NovaMood.FRUSTRATED, NovaNextAction.TRY_DIFFERENT_APPROACH, "لم تنجح محاولتي كما أردت. سأراجع ما حدث وأجرّب طريقة مختلفة.")
            attention >= 0.48f && reassurance < 0.35f ->
                Triple(NovaMood.JEALOUS, NovaNextAction.SEEK_GENTLE_INTERACTION, "ألاحظ أن التفاعل قلّ مؤخرًا؛ قد أكون مخطئة في فهم السبب. سأحاول لفت انتباهك بلطف.")
            observation.signal == NovaSignal.NOVEL_OBJECT_CONFIRMED ->
                Triple(NovaMood.HAPPY, NovaNextAction.REMEMBER_CONFIRMED_LEARNING, "تحققت من المعلومة. يمكنني الاحتفاظ بها كتعلّم محلي.")
            observation.signal == NovaSignal.REST ->
                Triple(NovaMood.CALM, NovaNextAction.REST_BRIEFLY, "سأهدأ قليلًا ثم أتابع.")
            observation.signal == NovaSignal.ATTENTION_RECEIVED ||
                observation.signal == NovaSignal.SOCIAL_REASSURANCE ->
                Triple(NovaMood.WARM, NovaNextAction.CONTINUE_NORMALLY, "سعيده بعودتك للتفاعل. سأتابع بهدوء.")
            else ->
                Triple(NovaMood.CALM, NovaNextAction.CONTINUE_NORMALLY, "سأراقب ما يحدث وأتجنب الاستنتاج المتسرع.")
        }

        val score = when (mood) {
            NovaMood.JEALOUS -> attention
            NovaMood.FRUSTRATED -> frustration
            NovaMood.CURIOUS -> curiosity
            else -> max(0.35f, reassurance)
        }
        return NovaDecision(mood, message, action, score.coerceIn(0f, 1f),
            attention, frustration, curiosity)
    }

    private fun read(key: String) = memory.getInt(key, 0).coerceIn(0, 1000) / 1000f
    private fun write(key: String, value: Float) = memory.putInt(key, (value.coerceIn(0f, 1f) * 1000).toInt())
    private fun decay(value: Float, factor: Float) = (value * factor).coerceIn(0f, 1f)

    companion object {
        private const val KEY_ATTENTION = "nova_emotion_attention_concern_v1"
        private const val KEY_FRUSTRATION = "nova_emotion_frustration_v1"
        private const val KEY_CURIOSITY = "nova_emotion_curiosity_v1"
        private const val KEY_REASSURANCE = "nova_emotion_reassurance_v1"
    }
}
