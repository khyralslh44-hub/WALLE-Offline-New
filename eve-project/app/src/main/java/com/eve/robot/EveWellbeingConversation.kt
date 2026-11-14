package com.eve.robot

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject

/**
 * Gate for spontaneous wellbeing dialogue. It accepts evidence from a future validated local
 * appearance model; it does not infer illness from a face or turn ordinary camera frames into
 * medical evidence. Consent and cooldown are stored locally per enrolled person.
 */
data class EveWellbeingEvidence(
    val personId: String,
    val personName: String,
    val observationArabic: String,
    val confidence: Float,
    val imageQuality: Float,
    val stableFrames: Int,
    val consented: Boolean,
    val capturedAtElapsedMs: Long = SystemClock.elapsedRealtime()
)

class EveWellbeingConversationPolicy(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "eve_wellbeing_conversation", Context.MODE_PRIVATE
    )

    @Synchronized
    fun shouldStart(
        evidence: EveWellbeingEvidence,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        nowWallMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!evidence.consented || evidence.personId.isBlank() ||
            evidence.observationArabic.isBlank() ||
            !evidence.confidence.isFinite() || evidence.confidence < MIN_CONFIDENCE ||
            !evidence.imageQuality.isFinite() || evidence.imageQuality < MIN_IMAGE_QUALITY ||
            evidence.stableFrames < MIN_STABLE_FRAMES ||
            nowElapsedMs - evidence.capturedAtElapsedMs !in 0L..MAX_EVIDENCE_AGE_MS
        ) return false
        val lastWallMs = prefs.getLong("last_${safeKey(evidence.personId)}", 0L)
        // Wall-clock storage survives phone restarts; elapsedRealtime is used only for fresh-frame age.
        if (lastWallMs > 0L) {
            val age = nowWallMs - lastWallMs
            if (age < 0L || age < PERSON_COOLDOWN_MS) return false
        }
        return true
    }

    @Synchronized
    fun markStarted(
        evidence: EveWellbeingEvidence,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        nowWallMs: Long = System.currentTimeMillis()
    ) {
        require(shouldStart(evidence, nowElapsedMs, nowWallMs)) {
            "Wellbeing dialogue evidence is not eligible."
        }
        prefs.edit().putLong("last_${safeKey(evidence.personId)}", nowWallMs).apply()
    }

    fun prompt(evidence: EveWellbeingEvidence): String = buildString {
        append("أنت إيف، رفيقة روبوتية ذات شخصية دافئة ومستقلة. ")
        append("لاحظت للتو ملاحظة بصرية موثوقة ومحدودة عن شخص تعرفه: ")
        append(evidence.observationArabic.take(180)).append(". ")
        append("ابدئي حديثًا قصيرًا وعفويًا للاطمئنان، بصياغة جديدة تناسب السياق ولا تستخدمي جملة محفوظة. ")
        append("لا تشخّصي مرضًا، ولا تستنتجي فقر الدم أو أي حالة من الوجه. ")
        append("عبّري عن الملاحظة بصيغة احتمالية واسألي إن كان الشخص بخير أو يشعر بتعب. ")
        append("لا تكرري السؤال إذا سبق أن سألتِه مؤخرًا، ولا تخيفيه. ")
        append("اكتبي جملة أو جملتين بالعربية فقط، دون ذكر درجات الثقة أو أسماء النماذج.")
    }

    private fun safeKey(value: String): String =
        value.hashCode().toUInt().toString(16)

    companion object {
        const val MIN_CONFIDENCE = 0.88f
        const val MIN_IMAGE_QUALITY = 0.90f
        const val MIN_STABLE_FRAMES = 3
        const val MAX_EVIDENCE_AGE_MS = 8_000L
        const val PERSON_COOLDOWN_MS = 6 * 60 * 60 * 1000L
    }
}
