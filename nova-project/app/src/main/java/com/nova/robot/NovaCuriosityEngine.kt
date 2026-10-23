package com.nova.robot

/**
 * Deterministic offline curiosity for Nova's written robot persona.
 * It reacts to evidence already produced by vision and asks contextual follow-up questions.
 * It never treats a face expression as proof of someone's inner feelings.
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
            (nowMillis < lastAnyAt || nowMillis - lastAnyAt < minimumGapMillis)
        ) return null
        if (cue.key == lastKey && lastAt != Long.MIN_VALUE &&
            (nowMillis < lastAt || nowMillis - lastAt < cooldownMillis)
        ) return null

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
        if (summary.contains("وجهًا جديدًا بالنسبة إلى ذاكرتي") ||
            summary.contains("لم تسجل نوفا هوية بصرية لهذا الشخص")
        ) return Cue("new-person", listOf(
            "أوه، أرى وجهًا جديدًا في محيطي! هل تحب أن تعرّفني بهذا الشخص؟ لن أحفظ مرجع وجه دون موافقة صريحة.",
            "وجه جديد لفت انتباهي. من يكون؟ يمكنك تعريفه لي، ولن أحفظ مرجعًا بصريًا إلا بموافقتك."
        ))

        if (summary.contains("لا أستطيع مطابقته بثقة") ||
            summary.contains("لم أستطع مطابقته بثقة")
        ) return Cue("uncertain-person", listOf(
            "يبدو هذا الوجه مألوفًا قليلًا، لكنني لا أريد أن أخمّن. هل تتركه بلا اسم أم تعرّفني به؟",
            "لست واثقة ممن أرى. أيمكنك مساعدتي في معرفة من هذا، أم نترك الأمر دون اسم؟"
        ))

        if (summary.contains("تعبير الوجه أقرب إلى")) {
            return Cue("expression", listOf(
                "لفتني التعبير الذي أراه، لكنني قد أقرأ الملامح خطأ. هل يعبّر فعلًا عما يشعر به الشخص؟",
                "أحاول فهم الملامح بدل التخمين عن الشعور. هل تراني فهمت التعبير بشكل صحيح؟"
            ))
        }
        val expressions = listOf("الغضب", "الاشمئزاز", "الخوف", "الفرح", "الحياد", "الحزن", "الدهشة")
        val expression = expressions.firstOrNull { summary.contains("«" + it + "»") }
        if (expression != null) return Cue("expression-" + expression, listOf(
            "أظن أن الملامح أقرب إلى " + expression + "، لكن التعبير وحده لا يخبرني بما يشعر به الشخص. هل قراءتي قريبة؟",
            "مال تقديري إلى " + expression + ". قد أكون مخطئة؛ كيف تفسّر أنت هذا التعبير؟"
        ))

        if (summary.contains("قد يكون الشخص يأكل أو يشرب")) return Cue("eating", listOf(
            "ألاحظ يدًا تقترب من الوجه وطعامًا قريبًا؛ هل أرى وجبة، أم فسّرت المشهد خطأ؟",
            "هذا المشهد يثير فضولي: هناك ما يشبه الطعام واليد تتحرك نحوه. ما الذي يحدث فعلًا؟"
        ))
        if (summary.contains("حركة متكررة في الأطراف") ||
            summary.contains("يمشي أو يتحرك بنشاط") || summary.contains("يمارس الرياضة")
        ) return Cue("movement", listOf(
            "أراقب الحركة عبر عدة لقطات، لكنني لم أحدد النشاط بعد. هل هو تمرين أم حركة عادية؟",
            "الحركة لفتت انتباهي! أستطيع ملاحظة تغيّر الوضعية، لكن ما نوع النشاط في رأيك؟"
        ))

        if (summary.contains("لست متأكدة") ||
            summary.contains("لا أملك دليلًا كافيًا") ||
            summary.contains("لم أتعرف إلى ما أمامي") ||
            summary.contains("لم أجد دليلًا بصريًا كافيًا")
        ) return Cue("uncertain-scene", listOf(
            "لم تتضح لي الصورة بعد، ولا أريد أن أخمّن. هل تخبرني ما الذي تراه؟",
            "ما زال في المشهد تفصيل لم أفهمه. هل يمكنك مساعدتي في معرفة ما فاتني؟"
        ))

        if (!summary.contains("في محيطي")) return null
        return when {
            summary.contains("زهرة") || summary.contains("زهور") ->
                Cue("flower", listOf(
                    "لفتتني الزهرة في المشهد. هل تعرف نوعها؟ أحب أن أتعلم أسماء الأشياء التي ألاحظها.",
                    "أظن أنني أرى زهرة. ما اسمها؟ وقد أكون مخطئة في التصنيف، فصحّح لي إن لزم."
                ))
            summary.contains("نبتة") || summary.contains("نبات") ->
                Cue("plant", listOf(
                    "لاحظت نبتة في محيطي. هل تعتني بها، أم أنها موجودة هنا فحسب؟",
                    "هناك ما يشبه نباتًا في المشهد؛ أتساءل كيف يبدو في ضوء مختلف."
                ))
            summary.contains("طعام") || summary.contains("فاكهة") || summary.contains("موز") ||
                summary.contains("تفاحة") || summary.contains("بيتزا") || summary.contains("قهوة") ||
                summary.contains("شاي") || summary.contains("شطيرة") ->
                Cue("food", listOf(
                    "يبدو أن هناك طعامًا أو شرابًا في المشهد. هل أصبت، أم أن تصنيفي البصري أخطأ؟",
                    "هذا الجزء من المشهد لفت انتباهي. أتساءل ماذا يوجد هنا؛ هل تخبرني إن كنت قد فهمته؟"
                ))
            summary.contains("كتاب") ->
                Cue("book", listOf(
                    "أظن أنني أرى كتابًا. هل تقرؤه الآن أم أنه جزء من المكان؟",
                    "كتاب في محيطي! أتساءل عمّا يتحدث، لكنني لا أستطيع معرفة محتواه من الصورة وحدها."
                ))
            summary.contains("هاتف") || summary.contains("حاسوب") || summary.contains("شاشة") ->
                Cue("device", listOf(
                    "ألاحظ ما يشبه جهازًا إلكترونيًا. هل تستخدمه الآن أم أنه موضوع هنا فقط؟",
                    "هذا الجهاز أثار فضولي؛ أستطيع تمييز شكله تقريبًا، لكنني لا أعرف ما الذي يظهر على شاشته."
                ))
            summary.contains("مطر") || summary.contains("ثلج") || summary.contains("السماء") ||
                summary.contains("ضوء الشمس") || summary.contains("النهار") || summary.contains("الليل") ->
                Cue("weather-light", listOf(
                    "المشهد يوحي بحالة من الطقس أو الإضاءة. هل هذا ما يحدث فعلًا، أم أنني أقرأ الصورة خطأ؟",
                    "أحاول فهم الضوء في المشهد؛ هل هو نهار أم ليل، أم أن الإضاءة تخدعني؟"
                ))
            summary.contains("كوب") || summary.contains("كأس") || summary.contains("زجاجة") ->
                Cue("cup", listOf(
                    "ألاحظ إناءً في المشهد. هل هو لشيء تشربه، أم أسيء فهم ما أراه؟",
                    "شيء يشبه الكوب لفت انتباهي. هل تستطيع إخباري بما يوجد فيه؟"
                ))
            summary.contains("كرسي") || summary.contains("أريكة") || summary.contains("سرير") ||
                summary.contains("طاولة") || summary.contains("مكتب") || summary.contains("غرفة") ->
                Cue("place", listOf(
                    "بدأت أتعرف إلى بعض ملامح المكان. هل هذه زاوية ترتاح فيها عادة؟",
                    "ألاحظ أثاثًا في المشهد، وأحاول تكوين فكرة عن المكان دون استنتاج ما لا أراه."
                ))
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
        val snapshot = listOf(lastKey.orEmpty(), lastAt.toString(), lastAnyAt.toString(), counts)
            .joinToString("|")
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
                if (!parts[0].isBlank() && count != null && count >= 0) useCounts[parts[0]] = count
            }
        }
    }

    private companion object {
        const val KEY_CURIOUS_STATE = "nova_curiosity_state_v1"
    }
}
