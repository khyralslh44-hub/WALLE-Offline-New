package com.eve.robot

import java.io.File
import java.util.Locale
import java.util.Properties

/**
 * Low-cost self-supervised experience memory.
 *
 * It autonomously learns which coarse activity/context patterns recur across time, without asking
 * the user to label every observation. It stores only counts and timestamps, never images, faces,
 * names, or raw camera frames. It does not silently turn its own predictions into ground-truth
 * labels or fine-tune the visual/language neural networks.
 */
data class EveExperienceLearningResult(
    val contextKey: String,
    val recurrenceCount: Int,
    val recordedNewOccurrence: Boolean,
    val familiar: Boolean,
    val summaryArabic: String
)

class EveAutonomousExperienceMemory(private val stateFile: File) {
    private val counts = linkedMapOf<String, Int>()
    private val lastRecordedAt = linkedMapOf<String, Long>()

    init {
        load()
    }

    /**
     * Repeated observations are learned locally when several frames support a plausible hypothesis.
     * A cooldown prevents adjacent camera frames from being counted as separate experiences.
     */
    @Synchronized
    fun observe(
        activity: EveHumanActivity,
        confidence: Float,
        evidenceCount: Int,
        durationMs: Long,
        visibleLabels: Set<String>,
        nowEpochMs: Long
    ): EveExperienceLearningResult? {
        if (activity == EveHumanActivity.UNKNOWN ||
            !confidence.isFinite() ||
            confidence < MIN_CONFIDENCE ||
            evidenceCount < MIN_EVIDENCE_FRAMES ||
            durationMs < MIN_DURATION_MS ||
            nowEpochMs <= 0L
        ) return null

        val context = contextFamily(visibleLabels)
        val key = "${activity.name.lowercase(Locale.ROOT)}.${context}"
        val previousAt = lastRecordedAt[key] ?: 0L
        val elapsed = if (previousAt > 0L && nowEpochMs >= previousAt) nowEpochMs - previousAt else Long.MAX_VALUE
        val recordNew = previousAt == 0L || elapsed >= OCCURRENCE_COOLDOWN_MS
        if (recordNew) {
            counts[key] = ((counts[key] ?: 0) + 1).coerceAtMost(MAX_OCCURRENCES)
            lastRecordedAt[key] = nowEpochMs
            persist()
        }

        val count = counts[key] ?: 0
        val familiar = count >= FAMILIAR_AFTER
        val note = when {
            familiar -> "تعلّمت محليًا أن نمط «${activityArabic(activity)}» مع سياق ${contextArabic(context)} تكرر ${count} مرات على الأقل؛ هذا تعلّم للتكرار وليس إثباتًا أن الاستنتاج صحيح."
            count >= 2 -> "بدأت ألاحظ تكرار هذا السياق محليًا؛ ما زلت أتعامل مع النشاط كاحتمال بصري."
            else -> ""
        }
        return EveExperienceLearningResult(key, count, recordNew, familiar, note)
    }

    @Synchronized
    fun occurrenceCount(activity: EveHumanActivity, visibleLabels: Set<String>): Int {
        val key = "${activity.name.lowercase(Locale.ROOT)}.${contextFamily(visibleLabels)}"
        return counts[key] ?: 0
    }

    private fun contextFamily(labels: Set<String>): String {
        val normalized = labels.map { it.lowercase(Locale.ROOT).replace('_', ' ').replace('-', ' ') }
        fun hasAny(vararg terms: String) = normalized.any { value -> terms.any(value::contains) }
        return when {
            hasAny("food", "meal", "sandwich", "pizza", "fruit", "snack", "cookie", "cake", "ice cream", "candy", "apple", "banana") -> "food"
            hasAny("drink", "cup", "bottle", "mug", "water", "coffee", "tea", "juice") -> "drink"
            hasAny("game", "controller", "joystick", "chess", "playing card", "video game") -> "game"
            hasAny("pencil", "crayon", "marker", "paint", "drawing", "art", "paper", "coloring") -> "art"
            hasAny("dumbbell", "barbell", "treadmill", "sports ball", "basketball", "bicycle", "gym") -> "exercise"
            hasAny("cell phone", "mobile phone", "smartphone", "laptop", "computer", "tablet", "keyboard", "screen") -> "device"
            else -> "general"
        }
    }

    private fun activityArabic(activity: EveHumanActivity): String = when (activity) {
        EveHumanActivity.EATING_POSSIBLE -> "تناول الطعام"
        EveHumanActivity.EATING_TREAT_POSSIBLE -> "تناول حلوى أو مثلجات"
        EveHumanActivity.DRINKING_POSSIBLE -> "الشرب"
        EveHumanActivity.CHEWING_MOTION_POSSIBLE -> "حركة مضغ محتملة"
        EveHumanActivity.PLAYING_GAME_POSSIBLE -> "اللعب"
        EveHumanActivity.DRAWING_OR_COLORING_POSSIBLE -> "الرسم أو التلوين"
        EveHumanActivity.EXERCISE_LIKE_MOTION -> "حركة شبيهة بالتمرين"
        EveHumanActivity.UNKNOWN -> "نشاط غير معروف"
    }

    private fun contextArabic(context: String): String = when (context) {
        "food" -> "الطعام"
        "drink" -> "الشرب"
        "game" -> "الألعاب"
        "art" -> "الرسم"
        "exercise" -> "الرياضة"
        "device" -> "الأجهزة"
        else -> "المشهد العام"
    }

    private fun load() {
        if (!stateFile.isFile) return
        runCatching {
            val properties = Properties()
            stateFile.inputStream().use(properties::load)
            for ((key, value) in properties) {
                val property = key.toString()
                val parsed = value.toString()
                when {
                    property.startsWith("count.") -> parsed.toIntOrNull()?.takeIf { it in 1..MAX_OCCURRENCES }?.let {
                        counts[property.removePrefix("count.")] = it
                    }
                    property.startsWith("last.") -> parsed.toLongOrNull()?.takeIf { it > 0L }?.let {
                        lastRecordedAt[property.removePrefix("last.")] = it
                    }
                }
            }
        }
    }

    private fun persist() {
        stateFile.parentFile?.mkdirs()
        val properties = Properties()
        counts.forEach { (key, value) -> properties.setProperty("count.$key", value.toString()) }
        lastRecordedAt.forEach { (key, value) -> properties.setProperty("last.$key", value.toString()) }
        val temp = File(stateFile.parentFile ?: File("."), stateFile.name + ".tmp")
        temp.outputStream().use { properties.store(it, "EVE local recurring experience counts; no images or identities") }
        if (!temp.renameTo(stateFile)) {
            stateFile.outputStream().use { properties.store(it, "EVE local recurring experience counts; no images or identities") }
            temp.delete()
        }
    }

    companion object {
        private const val MIN_CONFIDENCE = 0.64f
        private const val MIN_EVIDENCE_FRAMES = 5
        private const val MIN_DURATION_MS = 1_800L
        private const val OCCURRENCE_COOLDOWN_MS = 30L * 60L * 1000L
        private const val FAMILIAR_AFTER = 3
        private const val MAX_OCCURRENCES = 10_000
    }
}
