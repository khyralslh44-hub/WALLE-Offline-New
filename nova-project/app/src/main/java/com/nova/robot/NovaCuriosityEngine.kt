package com.nova.robot

/**
 * Small deterministic curiosity policy. It asks only about a newly seen, unmatched face or an
 * explicitly uncertain visual result, and rate-limits each kind of question to avoid repetition.
 */
internal class NovaCuriosityEngine(
    private val cooldownMillis: Long = 90_000L
) {
    private var lastQuestionKey: String? = null
    private var lastQuestionAtMillis: Long = Long.MIN_VALUE

    fun questionFor(summary: String, nowMillis: Long): String? {
        val keyAndQuestion = when {
            summary.contains("وجهًا جديدًا بالنسبة إلى ذاكرتي") ||
                summary.contains("لم تسجل نوفا هوية بصرية لهذا الشخص") ->
                "new-person" to "هل تريد أن تعرّفني بهذا الشخص؟ لن أحفظ مرجعًا للوجه دون موافقة."
            summary.contains("لا أستطيع مطابقته بثقة") ||
                summary.contains("لم أستطع مطابقته بثقة") ->
                "uncertain-person" to "لست واثقة من هوية هذا الشخص؛ هل تريد أن تتركه دون اسم أم تعرّفني به؟"
            summary.contains("لست متأكدة") ||
                summary.contains("لا أملك دليلًا كافيًا") ||
                summary.contains("لم أتعرف إلى ما أمامي") ||
                summary.contains("لم أجد دليلًا بصريًا كافيًا") ->
                "uncertain-scene" to "لم أتمكن من تحديد المشهد بثقة. هل تخبرني ما الذي تراه؟"
            else -> return null
        }
        val (key, question) = keyAndQuestion
        if (key == lastQuestionKey && nowMillis >= lastQuestionAtMillis &&
            nowMillis - lastQuestionAtMillis < cooldownMillis
        ) return null
        lastQuestionKey = key
        lastQuestionAtMillis = nowMillis
        return question
    }

    fun reset() {
        lastQuestionKey = null
        lastQuestionAtMillis = Long.MIN_VALUE
    }
}
