package com.eve.robot

import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.max

enum class EveRoomType {
    UNKNOWN,
    KITCHEN,
    BEDROOM,
    BATHROOM,
    LIVING_ROOM,
    DINING_AREA,
    OFFICE,
    HALLWAY
}

data class EveRoomHypothesis(
    val room: EveRoomType,
    val confidence: Float,
    val evidence: Set<String>,
    val observedAtMs: Long,
    val explanationArabic: String
)

/**
 * Offline room-type hypothesis from repeated object/scene labels. It is not a geometric map:
 * a kitchen guess from a cup alone is deliberately too weak to name the room.
 */
class EveRoomUnderstanding(
    private val windowMs: Long = 28_000L,
    private val maxSamples: Int = 8
) {
    private data class Sample(
        val timestampMs: Long,
        val scores: Map<EveRoomType, Float>,
        val evidence: Map<EveRoomType, Set<String>>
    )

    private val history = ArrayDeque<Sample>()
    private var lastTimestampMs = 0L

    @Synchronized
    fun observe(timestampMs: Long, candidates: List<Pair<String, Float>>): EveRoomHypothesis {
        if (timestampMs < lastTimestampMs) return snapshotUnknown(timestampMs, "تجاهلت إطارًا قديمًا.")
        lastTimestampMs = timestampMs

        val currentScores = mutableMapOf<EveRoomType, Float>()
        val currentEvidence = mutableMapOf<EveRoomType, MutableSet<String>>()
        for ((rawLabel, rawConfidence) in candidates) {
            val label = normalize(rawLabel)
            val confidence = rawConfidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            if (label.isBlank() || confidence < MIN_LABEL_CONFIDENCE) continue
            val directRoom = directRoomLabel(label)
            if (directRoom != EveRoomType.UNKNOWN) {
                currentScores[directRoom] = max(currentScores[directRoom] ?: 0f, confidence * 0.94f)
                currentEvidence.getOrPut(directRoom) { linkedSetOf() }.add(label)
            }
            for ((room, labels) in ROOM_EVIDENCE) {
                if (label in labels) {
                    val weight = if (label in DISTINCTIVE_EVIDENCE) 1.0f else 0.66f
                    val score = confidence * weight
                    currentScores[room] = max(currentScores[room] ?: 0f, score)
                    currentEvidence.getOrPut(room) { linkedSetOf() }.add(label)
                }
            }
        }

        history.addLast(
            Sample(
                timestampMs,
                currentScores.toMap(),
                currentEvidence.mapValues { it.value.toSet() }
            )
        )
        while (history.size > maxSamples.coerceAtLeast(1)) history.removeFirst()
        while (history.isNotEmpty() && timestampMs - history.first().timestampMs > windowMs) history.removeFirst()

        val roomScores = mutableMapOf<EveRoomType, Float>()
        val roomEvidence = mutableMapOf<EveRoomType, MutableSet<String>>()
        for (sample in history) {
            for ((room, score) in sample.scores) {
                val previous = roomScores[room] ?: 0f
                roomScores[room] = max(previous, score)
                roomEvidence.getOrPut(room) { linkedSetOf() }.addAll(sample.evidence[room].orEmpty())
            }
        }
        val ranked = roomScores.entries.sortedByDescending { (room, score) ->
            score + (roomEvidence[room].orEmpty().size.coerceAtMost(4) * 0.065f)
        }
        val best = ranked.firstOrNull() ?: return snapshotUnknown(timestampMs, "لم أجد أدلة كافية لتحديد نوع الغرفة.")
        val evidence = roomEvidence[best.key].orEmpty()
        val directEvidence = evidence.any(::isDirectRoomLabel)
        val evidenceScore = (best.value + evidence.size.coerceAtMost(4) * 0.065f).coerceIn(0f, 1f)
        val margin = evidenceScore - (ranked.getOrNull(1)?.let { it.value + roomEvidence[it.key].orEmpty().size.coerceAtMost(4) * 0.065f } ?: 0f)

        if (evidenceScore < MIN_ROOM_SCORE || (!directEvidence && evidence.size < 2) || (ranked.size > 1 && margin < MIN_SCORE_MARGIN)) {
            return snapshotUnknown(timestampMs, "الأشياء المرئية لا تكفي للجزم بنوع الغرفة.")
        }

        val room = best.key
        val label = roomArabic(room)
        return EveRoomHypothesis(
            room = room,
            confidence = evidenceScore,
            evidence = evidence.toSet(),
            observedAtMs = timestampMs,
            explanationArabic = "يبدو أنها $label (احتمال ${(evidenceScore * 100).toInt()}٪؛ استنتاج محلي من ${evidence.take(3).joinToString("، ")})."
        )
    }

    @Synchronized
    fun reset() {
        history.clear()
        lastTimestampMs = 0L
    }

    private fun snapshotUnknown(timestampMs: Long, explanation: String) =
        EveRoomHypothesis(EveRoomType.UNKNOWN, 0f, emptySet(), timestampMs, explanation)

    companion object {
        const val MIN_LABEL_CONFIDENCE = 0.20f
        const val MIN_ROOM_SCORE = 0.48f
        const val MIN_SCORE_MARGIN = 0.08f

        private val ROOM_EVIDENCE: Map<EveRoomType, Set<String>> = mapOf(
            EveRoomType.KITCHEN to setOf(
                "refrigerator", "fridge", "oven", "microwave", "toaster", "stove", "dishwasher",
                "kitchen appliance", "cooker", "kitchen sink", "range hood"
            ),
            EveRoomType.BEDROOM to setOf(
                "bed", "pillow", "mattress", "bedside table", "nightstand", "duvet", "bedroom furniture"
            ),
            EveRoomType.BATHROOM to setOf(
                "toilet", "bathtub", "shower", "bathroom sink", "washbasin", "toilet paper", "bathroom fixture"
            ),
            EveRoomType.LIVING_ROOM to setOf(
                "couch", "sofa", "television", "tv", "fireplace", "coffee table", "living room furniture", "armchair"
            ),
            EveRoomType.DINING_AREA to setOf(
                "dining table", "dining room", "dining table furniture", "placemat"
            ),
            EveRoomType.OFFICE to setOf(
                "office", "desk", "computer", "laptop", "monitor", "keyboard", "office chair", "printer"
            ),
            EveRoomType.HALLWAY to setOf(
                "hallway", "corridor", "staircase", "stairs", "foyer", "entryway", "doorway"
            )
        )

        private val DISTINCTIVE_EVIDENCE = setOf(
            "refrigerator", "fridge", "oven", "microwave", "toaster", "bed", "toilet", "bathtub",
            "shower", "couch", "sofa", "television", "tv", "dining table", "computer", "laptop",
            "printer", "staircase", "stairs"
        )

        private val DIRECT_ROOM_LABELS: Map<String, EveRoomType> = mapOf(
            "kitchen" to EveRoomType.KITCHEN,
            "bedroom" to EveRoomType.BEDROOM,
            "bathroom" to EveRoomType.BATHROOM,
            "living room" to EveRoomType.LIVING_ROOM,
            "dining room" to EveRoomType.DINING_AREA,
            "office" to EveRoomType.OFFICE,
            "hallway" to EveRoomType.HALLWAY,
            "corridor" to EveRoomType.HALLWAY
        )

        fun normalize(label: String): String = label.trim()
            .lowercase(Locale.ROOT)
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")

        private fun directRoomLabel(label: String): EveRoomType = DIRECT_ROOM_LABELS[label] ?: EveRoomType.UNKNOWN
        private fun isDirectRoomLabel(label: String): Boolean = label in DIRECT_ROOM_LABELS
        private fun roomArabic(room: EveRoomType): String = when (room) {
            EveRoomType.KITCHEN -> "المطبخ"
            EveRoomType.BEDROOM -> "غرفة النوم"
            EveRoomType.BATHROOM -> "الحمّام"
            EveRoomType.LIVING_ROOM -> "غرفة المعيشة"
            EveRoomType.DINING_AREA -> "منطقة الطعام"
            EveRoomType.OFFICE -> "المكتب"
            EveRoomType.HALLWAY -> "الممر"
            EveRoomType.UNKNOWN -> "نوع غير معروف"
        }
    }
}
