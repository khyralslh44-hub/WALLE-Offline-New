package com.nova.robot

/**
 * Offline, evidence-led curiosity and relationship dialogue.
 * A detected face is never treated as a friend. Personal dialogue is emitted only when
 * the upstream local people-memory summary explicitly identifies a known person.
 */
internal class NovaCuriosityEngine(
    private val memory: NovaMindMemory? = null,
    private val cooldownMillis: Long = 90_000L,
    private val minimumGapMillis: Long = 35_000L
) {
    private data class Cue(val key: String, val lines: List<String>)
    private var lastKey: String? = null
    private var lastAt = Long.MIN_VALUE
    private var lastAnyAt = Long.MIN_VALUE
    private val useCounts = mutableMapOf<String, Int>()

    init { restoreState() }

    fun questionFor(summary: String, nowMillis: Long): String? {
        if (nowMillis < 0L) return null
        val cue = findCue(summary) ?: return null
        if (lastAnyAt != Long.MIN_VALUE &&
            (nowMillis < lastAnyAt || nowMillis - lastAnyAt < minimumGapMillis)) return null
        if (cue.key == lastKey && lastAt != Long.MIN_VALUE &&
            (nowMillis < lastAt || nowMillis - lastAt < cooldownMillis)) return null
        val index = useCounts[cue.key] ?: 0
        val line = cue.lines[index % cue.lines.size]
        useCounts[cue.key] = index + 1
        lastKey = cue.key
        lastAt = nowMillis
        lastAnyAt = nowMillis
        persistState(nowMillis)
        return line
    }

    private fun findCue(summary: String): Cue? {
        // Unknown or uncertain people: no unsolicited personal questions.
        if (summary.contains("وجهًا جديدًا بالنسبة إلى ذاكرتي") ||
            summary.contains("لم تسجل نوفا هوية بصرية لهذا الشخص") ||
            summary.contains("لا أستطيع مطابقته بثقة") ||
            summary.contains("لم أستطع مطابقته بثقة") ||
            summary.contains("شخص غريب") || summary.contains("شخص غير معروف")) return null

        // Relationship talk requires an explicit successful local-memory match.
        val knownRelation = listOf("صديق معروف", "صديقة معروفة", "شخص معروف في ذاكرتي",
            "من العائلة", "أمي", "أبي", "أختي", "أخي", "صديقي", "صديقتي")
            .firstOrNull { summary.contains(it) }
        if (knownRelation != null && (summary.contains("تعرّفت") ||
                summary.contains("تعرفت") || summary.contains("مطابقة موثوقة") ||
                summary.contains("تطابق موثوق") || summary.contains("معروف في ذاكرتي"))) {
            val name = Regex("(?:الاسم|اسمه|اسمها)\\s*[:：]?\\s*([\\p{L}][\\p{L}\\s'-]{0,35})")
                .find(summary)?.groupValues?.getOrNull(1)?.trim()
            val greeting = if (!name.isNullOrBlank()) "أهلًا يا $name!" else "أهلًا، اشتقت لوجودك!"
            return Cue("known-person-$knownRelation", listOf(
                "$greeting يسعدني أن أراك. كيف كان يومك؟",
                "$greeting عندي شيء صغير لفت انتباهي اليوم، وأحب أن أحكيه لك.",
                "$greeting وجودك مألوف ومريح لي. ما أخبارك؟"
            ))
        }

        if (summary.contains("تعبير الوجه أقرب إلى")) return Cue("expression", listOf(
            "لفتني التعبير الذي أراه، لكنني قد أقرأ الملامح خطأ.",
            "أحاول فهم الملامح دون أن أفترض ما يشعر به الشخص."
        ))
        val expressions = listOf("الغضب", "الاشمئزاز", "الخوف", "الفرح", "الحياد", "الحزن", "الدهشة")
        expressions.firstOrNull { summary.contains("«$it»") }?.let {
            return Cue("expression-$it", listOf(
                "أظن أن الملامح أقرب إلى $it، لكنني قد أكون مخطئة.",
                "مال تقديري إلى $it؛ التعبير وحده لا يخبرني بكل ما يشعر به الشخص."
            ))
        }
        if (summary.contains("قد يكون الشخص يأكل أو يشرب")) return Cue("eating", listOf(
            "ألاحظ ما يشبه الطعام في المشهد؛ هل فهمت ما أراه بشكل صحيح؟",
            "هذا المشهد لفت انتباهي. قد أكون فسّرته خطأ، ما الذي يحدث فعلًا؟"
        ))
        if (summary.contains("حركة متكررة في الأطراف") ||
            summary.contains("يمشي أو يتحرك بنشاط") || summary.contains("يمارس الرياضة")) {
            return Cue("movement", listOf(
                "الحركة لفتت انتباهي! لم أحدد النشاط بعد.",
                "أراقب الحركة، لكنني لا أريد تخمين نوع النشاط قبل أن تتضح لي الصورة."
            ))
        }
        if (summary.contains("لست متأكدة") || summary.contains("لا أملك دليلًا كافيًا") ||
            summary.contains("لم أتعرف إلى ما أمامي") || summary.contains("لم أجد دليلًا بصريًا كافيًا")) {
            return Cue("uncertain-scene", listOf(
                "لم تتضح لي الصورة بعد، ولا أريد أن أخمّن.",
                "هناك تفصيل لم أفهمه بعد؛ سأراقب أكثر قبل أن أستنتج."
            ))
        }
        if (!summary.contains("في محيطي")) return null
        return when {
            listOf("زهرة", "زهور").any(summary::contains) -> Cue("flower", listOf(
                "لفتتني الزهرة في المشهد. أتساءل ما نوعها.",
                "أظن أنني أرى زهرة؛ قد أحتاج إلى ملاحظة أوضح لأعرف نوعها."
            ))
            listOf("نبتة", "نبات").any(summary::contains) -> Cue("plant", listOf(
                "لاحظت نبتة في محيطي؛ أحب ملاحظة كيف تتغير الأشياء مع الوقت.",
                "هناك ما يشبه نباتًا هنا. سأراقب تفاصيله أكثر."
            ))
            listOf("طعام", "فاكهة", "موز", "تفاحة", "بيتزا", "قهوة", "شاي", "شطيرة").any(summary::contains) ->
                Cue("food", listOf("يبدو أن هناك طعامًا أو شرابًا في المشهد؛ هل أصبت؟",
                    "هذا الجزء من المشهد لفت انتباهي، لكن تصنيفي قد لا يكون دقيقًا."))
            listOf("كتاب").any(summary::contains) -> Cue("book", listOf(
                "أظن أنني أرى كتابًا. لا أستطيع معرفة محتواه من الصورة وحدها.",
                "كتاب في محيطي! أتساءل عمّا يتحدث."
            ))
            listOf("هاتف", "حاسوب", "شاشة").any(summary::contains) -> Cue("device", listOf(
                "ألاحظ ما يشبه جهازًا إلكترونيًا؛ لا أستطيع معرفة محتوى شاشته من هذا الوصف وحده.",
                "هذا الجهاز أثار فضولي. سأحاول ملاحظة تفاصيل أكثر."
            ))
            listOf("مطر", "ثلج", "السماء", "ضوء الشمس", "النهار", "الليل").any(summary::contains) ->
                Cue("weather-light", listOf("أحاول فهم الضوء والطقس في المشهد، وقد أحتاج إلى دليل أوضح.",
                    "المشهد يوحي بحالة من الطقس أو الإضاءة، لكنني لا أريد الجزم دون دليل."))
            listOf("كوب", "كأس", "زجاجة").any(summary::contains) -> Cue("cup", listOf(
                "شيء يشبه الكوب لفت انتباهي. هل فهمت المشهد بشكل صحيح؟",
                "ألاحظ إناءً في المشهد، لكنني لا أعرف ما بداخله."
            ))
            listOf("كرسي", "أريكة", "سرير", "طاولة", "مكتب", "غرفة").any(summary::contains) ->
                Cue("place", listOf("بدأت ألاحظ بعض ملامح المكان.",
                    "أحاول تكوين فكرة عن المكان دون استنتاج ما لا أراه."))
            else -> null
        }
    }

    fun reset() {
        lastKey = null
        lastAt = Long.MIN_VALUE
        lastAnyAt = Long.MIN_VALUE
        useCounts.clear()
        persistState(System.currentTimeMillis())
    }

    private fun persistState(nowMillis: Long) {
        val counts = useCounts.entries.joinToString(";") { "${it.key}=${it.value}" }
        val snapshot = listOf(lastKey.orEmpty(), lastAt.toString(), lastAnyAt.toString(), counts).joinToString("|")
        memory?.write(NovaMemoryItem(KEY_CURIOUS_STATE, snapshot, nowMillis.coerceAtLeast(0L)))
    }

    private fun restoreState() {
        val snapshot = memory?.read(KEY_CURIOUS_STATE) ?: return
        val pieces = snapshot.split('|', limit = 4)
        if (pieces.size != 4) return
        lastKey = pieces[0].ifBlank { null }
        lastAt = pieces[1].toLongOrNull() ?: Long.MIN_VALUE
        lastAnyAt = pieces[2].toLongOrNull() ?: Long.MIN_VALUE
        pieces[3].split(";").forEach { entry ->
            val parts = entry.split("=", limit = 2)
            if (parts.size == 2) {
                val count = parts[1].toIntOrNull()
                if (parts[0].isNotBlank() && count != null && count >= 0) useCounts[parts[0]] = count
            }
        }
    }

    private companion object { const val KEY_CURIOUS_STATE = "nova_curiosity_state_v1" }
}
