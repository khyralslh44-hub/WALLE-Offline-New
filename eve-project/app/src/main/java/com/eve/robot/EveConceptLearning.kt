package com.eve.robot

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * EVE's own concept-learning loop.
 * Vision labels are hypotheses. A spoken correction starts a candidate; separated observations
 * are required before EVE keeps it as a locally confirmed concept. Images/audio are never saved.
 */
data class EveConcept(
    val visualLabel: String,
    val learnedName: String,
    val confirmed: Boolean = false,
    val evidenceCount: Int = 0,
    val firstEvidenceAtMs: Long = 0L,
    val lastEvidenceAtMs: Long = 0L,
    val peakConfidence: Float = 0f
)

interface EveConceptStore {
    fun readAll(): List<EveConcept>
    fun writeAll(concepts: List<EveConcept>)
}

private class SharedPreferencesEveConceptStore(context: Context) : EveConceptStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences("eve_concept_memory_v1", Context.MODE_PRIVATE)

    override fun readAll(): List<EveConcept> {
        val raw = preferences.getString(KEY_CONCEPTS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val label = item.optString("label").trim()
                    val name = item.optString("name").trim()
                    if (label.isBlank() || name.isBlank()) continue
                    add(
                        EveConcept(
                            visualLabel = label,
                            learnedName = name,
                            confirmed = item.optBoolean("confirmed", false),
                            evidenceCount = item.optInt("count", 0).coerceAtLeast(0),
                            firstEvidenceAtMs = item.optLong("first", 0L).coerceAtLeast(0L),
                            lastEvidenceAtMs = item.optLong("last", 0L).coerceAtLeast(0L),
                            peakConfidence = item.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun writeAll(concepts: List<EveConcept>) {
        val array = JSONArray()
        concepts.takeLast(MAX_CONCEPTS).forEach { concept ->
            array.put(
                JSONObject()
                    .put("label", concept.visualLabel)
                    .put("name", concept.learnedName)
                    .put("confirmed", concept.confirmed)
                    .put("count", concept.evidenceCount)
                    .put("first", concept.firstEvidenceAtMs)
                    .put("last", concept.lastEvidenceAtMs)
                    .put("confidence", concept.peakConfidence.toDouble())
            )
        }
        preferences.edit().putString(KEY_CONCEPTS, array.toString()).apply()
    }

    companion object {
        private const val KEY_CONCEPTS = "concepts"
        private const val MAX_CONCEPTS = 80
    }
}

enum class EveConceptLearningStatus {
    WAITING_FOR_VERIFICATION, VERIFICATION_PROGRESS, CONFIRMED, ALREADY_CONFIRMED, REJECTED
}

data class EveConceptLearningResult(
    val status: EveConceptLearningStatus,
    val visualLabel: String,
    val learnedName: String,
    val evidenceCount: Int = 0,
    val messageArabic: String
)

class EveConceptLearning(private val store: EveConceptStore) {
    constructor(context: Context) : this(SharedPreferencesEveConceptStore(context))

    private val concepts = LinkedHashMap<String, EveConcept>()

    init {
        store.readAll().takeLast(MAX_CONCEPTS).forEach { concept ->
            val key = normalize(concept.visualLabel)
            if (key.isNotBlank() && !isPersonCategory(key)) concepts[key] = concept
        }
    }

    /** Tie a user correction to a fresh, confidently observed label. */
    @Synchronized
    fun teachCurrentObservation(
        visualLabel: String?,
        visualConfidence: Float,
        observationAgeMs: Long,
        utterance: String,
        allowBareAnswer: Boolean,
        nowMs: Long
    ): EveConceptLearningResult? {
        val learnedName = parseTeaching(utterance, allowBareAnswer) ?: return null
        val label = visualLabel?.trim().orEmpty()
        val key = normalize(label)
        if (isPersonCategory(key)) {
            return EveConceptLearningResult(
                EveConceptLearningStatus.REJECTED, label, learnedName,
                messageArabic = "هذا تصنيف عام لشخص أو وجه، ولا يكفي لتحديد هوية إنسان. أحتفظ بتعرّف الأشخاص في ذاكرة الوجوه المنفصلة ولا أربط الاسم بتصنيف عام."
            )
        }
        if (key.isBlank() || !visualConfidence.isFinite() ||
            visualConfidence < MIN_TEACH_CONFIDENCE ||
            observationAgeMs !in 0L..MAX_TEACH_AGE_MS
        ) {
            return EveConceptLearningResult(
                EveConceptLearningStatus.REJECTED, label, learnedName,
                messageArabic = "سمعت الاسم، لكن لا توجد لديّ ملاحظة بصرية حديثة وواضحة بما يكفي لربطه بهذا الشيء. سأنتظر رؤية أوضح قبل حفظه."
            )
        }

        val old = concepts[key]
        if (old?.confirmed == true && old.learnedName.equals(learnedName, ignoreCase = true)) {
            return EveConceptLearningResult(
                EveConceptLearningStatus.ALREADY_CONFIRMED, label, old.learnedName,
                old.evidenceCount, "هذه المعلومة محفوظة لديّ بالفعل: " + label + " هو " + old.learnedName + "."
            )
        }

        concepts[key] = EveConcept(
            visualLabel = label,
            learnedName = learnedName,
            confirmed = false,
            evidenceCount = 0,
            firstEvidenceAtMs = 0L,
            lastEvidenceAtMs = 0L,
            peakConfidence = visualConfidence.coerceIn(0f, 1f)
        )
        persist()
        return EveConceptLearningResult(
            EveConceptLearningStatus.WAITING_FOR_VERIFICATION, label, learnedName, 0,
            "فهمت. سأتعامل مع «" + learnedName + "» كتعريف مبدئي لـ «" + label +
                "»، ولن أعتمده حتى أراه بوضوح في ملاحظات منفصلة."
        )
    }

    /** Count time-separated confirmations. Repeated callbacks for the same frame do not count. */
    @Synchronized
    fun observe(visualLabel: String, confidence: Float, nowMs: Long): EveConceptLearningResult? {
        val key = normalize(visualLabel)
        if (key.isBlank() || isPersonCategory(key) || !confidence.isFinite() ||
            confidence < MIN_VERIFY_CONFIDENCE
        ) return null
        val current = concepts[key] ?: return null
        if (current.confirmed) return null

        val now = nowMs.coerceAtLeast(0L)
        val previousAt = current.lastEvidenceAtMs
        if (previousAt > 0L && now - previousAt in 0L until MIN_EVIDENCE_GAP_MS) {
            return EveConceptLearningResult(
                EveConceptLearningStatus.VERIFICATION_PROGRESS,
                current.visualLabel, current.learnedName, current.evidenceCount,
                "ما زلت أتحقق من «" + current.learnedName + "»؛ أنتظر ملاحظة مستقلة أخرى."
            )
        }

        val gapExpired = previousAt > 0L &&
            (now < previousAt || now - previousAt > MAX_EVIDENCE_GAP_MS)
        val firstAt = if (current.evidenceCount == 0 || gapExpired) now else current.firstEvidenceAtMs
        val count = if (current.evidenceCount == 0 || gapExpired) 1 else current.evidenceCount + 1
        val peak = maxOf(current.peakConfidence, confidence.coerceIn(0f, 1f))
        val span = (now - firstAt).coerceAtLeast(0L)
        val verified = count >= REQUIRED_EVIDENCE_COUNT && span >= REQUIRED_EVIDENCE_SPAN_MS &&
            peak >= MIN_VERIFY_CONFIDENCE
        val updated = current.copy(
            confirmed = verified,
            evidenceCount = count,
            firstEvidenceAtMs = firstAt,
            lastEvidenceAtMs = now,
            peakConfidence = peak
        )
        concepts[key] = updated
        persist()

        return EveConceptLearningResult(
            if (verified) EveConceptLearningStatus.CONFIRMED else EveConceptLearningStatus.VERIFICATION_PROGRESS,
            updated.visualLabel, updated.learnedName, count,
            if (verified) {
                "تكرر لديّ تصنيف «" + updated.visualLabel + "» في ملاحظات منفصلة، فحفظت ارتباطه المبدئي باسم «" +
                    updated.learnedName + "». ويمكنك تصحيحي إذا كان الربط غير صحيح."
            } else {
                "أتحقق من «" + updated.learnedName + "»: رصدت " + count +
                    " ملاحظات منفصلة، وأنتظر اكتمال مدة التحقق."
            }
        )
    }

    @Synchronized
    fun confirmedNameFor(visualLabel: String?): String? {
        val key = normalize(visualLabel.orEmpty())
        return concepts[key]?.takeIf { it.confirmed && !isPersonCategory(key) }?.learnedName
    }

    @Synchronized
    fun isConfirmed(visualLabel: String): Boolean {
        val key = normalize(visualLabel)
        return !isPersonCategory(key) && concepts[key]?.confirmed == true
    }

    @Synchronized
    fun recentConfirmedConcepts(limit: Int = 5): List<String> =
        concepts.values.asSequence()
            .filter { it.confirmed && !isPersonCategory(normalize(it.visualLabel)) }
            .sortedByDescending { it.lastEvidenceAtMs }
            .take(limit.coerceIn(0, 20))
            .map { it.visualLabel + " = " + it.learnedName + " (تحقق بصري متكرر)" }
            .toList()

    private fun persist() {
        store.writeAll(concepts.values.toList().takeLast(MAX_CONCEPTS))
    }

    private fun parseTeaching(raw: String, allowBareAnswer: Boolean): String? {
        val normalized = normalize(raw)
        if (normalized.isBlank()) return null
        val prefixes = listOf(
            "هذا هو ", "هذه هي ", "هذي هي ", "هذا ", "هذه ", "هذي ",
            "انها ", "انه ", "يسمى ", "تسمى ", "اسمها ", "اسمه ", "يعني "
        )
        val explicitCandidate = prefixes.firstNotNullOfOrNull { prefix ->
            normalized.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()
        }
        val candidate = explicitCandidate
            ?: if (allowBareAnswer && normalized.split(' ').size in 1..2) normalized
            else return null

        val words = candidate.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 4 || candidate.length !in 2..48) return null
        val rejected = setOf(
            "شيء", "شي", "هذا", "هذه", "هذي", "هو", "هي", "نعم", "لا",
            "ما", "ماذا", "كيف", "لا اعرف", "مش عارف", "غير معروف"
        )
        if (candidate in rejected || words.any { it in setOf("ما", "ماذا", "كيف", "لماذا") }) return null
        return candidate
    }

    private fun isPersonCategory(normalizedLabel: String): Boolean =
        normalizedLabel in PERSON_CATEGORIES

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ى', 'ي')
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    companion object {
        const val MIN_TEACH_CONFIDENCE = 0.80f
        const val MIN_VERIFY_CONFIDENCE = 0.82f
        const val REQUIRED_EVIDENCE_COUNT = 3
        const val MIN_EVIDENCE_GAP_MS = 2_800L
        const val REQUIRED_EVIDENCE_SPAN_MS = 8_000L
        const val MAX_EVIDENCE_GAP_MS = 15 * 60_000L
        const val MAX_TEACH_AGE_MS = 90_000L
        private const val MAX_CONCEPTS = 80
        private val PERSON_CATEGORIES = setOf(
            "person", "people", "human", "face", "man", "woman", "child", "boy", "girl",
            "adult", "male", "female", "baby", "crowd", "portrait", "headshot",
            "شخص", "اشخاص", "انسان", "بشر", "وجه", "رجل", "امراة", "طفل", "طفلة",
            "ولد", "بنت", "ناس", "حشد", "صورة شخصية"
        )
    }
}
