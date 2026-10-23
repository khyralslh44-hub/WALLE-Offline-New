package com.eve.robot

/**
 * Keeps private perception/reflection notes out of the on-screen dialogue.
 *
 * This is a presentation filter only: it does not delete evidence, memory, or model reflections.
 * User-directed answers and natural, contextual curiosity prompts remain visible.
 */
internal object EveVisibleMessagePolicy {
    private val internalReflectionPatterns = listOf(
        Regex("لا أستطيع تأكيد.{0,35}(شعور|هوي|تعبير)", RegexOption.IGNORE_CASE),
        Regex("لم أتعرف إلى هويته", RegexOption.IGNORE_CASE),
        Regex("لم أتعرف على هويته", RegexOption.IGNORE_CASE),
        Regex("لا أعرف اسم صاحبه", RegexOption.IGNORE_CASE),
        Regex("لا أستطيع تسميته بدقة", RegexOption.IGNORE_CASE),
        Regex("ألاحظ تعبيراً على الوجه.{0,45}(لا أستطيع|لكنني)", RegexOption.IGNORE_CASE),
        Regex("أنا أراقب النمط الذي يناسبك، من دون تسجيل", RegexOption.IGNORE_CASE),
        Regex("المزاج الداخلي الحالي|ملاحظات التحليل الداخلي|تفكير داخلي", RegexOption.IGNORE_CASE)
    )

    fun shouldDisplay(message: String): Boolean {
        val normalized = message.trim()
        if (normalized.isEmpty()) return false
        return internalReflectionPatterns.none { it.containsMatchIn(normalized) }
    }
}
