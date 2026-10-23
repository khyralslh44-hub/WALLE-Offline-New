package com.nova.robot

import java.util.Locale

/**
 * Explicit-consent personal notes. Only commands such as "تذكري أنني أحب القهوة" write a note.
 * Notes are kept through NovaMindMemory, whose Android implementation encrypts values at rest.
 * Conversation turns are never added automatically.
 */
internal class NovaPersonalMemory(private val memory: NovaMindMemory) {
    fun handle(rawText: String, nowMillis: Long): String? {
        val command = normalize(rawText)

        if (clearCommands.any { command == it || command.startsWith(it + " ") }) {
            memory.write(NovaMemoryItem(KEY_NOTES, "", nowMillis))
            return "مسحت كل الملاحظات الشخصية التي طلبت مني تذكّرها. لم أحذف إعدادات التطبيق أو نموذج اللغة المحلي."
        }

        if (listCommands.any { command == it || command.startsWith(it + " ") }) {
            val notes = readNotes()
            return if (notes.isEmpty()) {
                "لا توجد لديّ ملاحظات شخصية محفوظة. يمكنك أن تقولي مثلًا: «تذكّري أنني أحب القهوة»، وسأحفظ هذه المعلومة وحدها."
            } else {
                "هذه بعض المعلومات التي طلبت مني تذكّرها: " +
                    notes.mapIndexed { index, note -> "${index + 1}) $note" }.joinToString("؛ ") +
                    ". هل تريدين إضافة معلومة أو نسيان واحدة؟"
            }
        }

        val rememberPrefix = rememberPrefixes.firstOrNull { command.startsWith(it) }
        if (rememberPrefix != null) {
            val note = (extractOriginalNote(rawText) ?: command.removePrefix(rememberPrefix))
                .trim().replace(Regex("[\\r\\n]+"), " ").take(MAX_NOTE_CHARS)
            if (normalize(note).length < 3) return "لم أحفظ شيئًا؛ أخبريني بالمعلومة كاملة بعد عبارة «تذكّري أنني»."
            val notes = readNotes()
            if (notes.any { normalize(it) == normalize(note) }) {
                return "أتذكر هذه المعلومة بالفعل: «$note». لن أكرر حفظها."
            }
            val updated = (notes + note).takeLast(MAX_NOTES)
            memory.write(NovaMemoryItem(KEY_NOTES, updated.joinToString("\n"), nowMillis))
            return "حفظت هذه المعلومة محليًا: «$note». لن أحفظ بقية المحادثة تلقائيًا، ويمكنك أن تطلبي مني نسيانها."
        }

        val forgetPrefix = forgetPrefixes.firstOrNull { command.startsWith(it) }
        if (forgetPrefix != null) {
            val target = command.removePrefix(forgetPrefix).trim()
            if (target.isBlank()) return "حددي المعلومة التي تريدين مني نسيانها."
            val notes = readNotes()
            val remaining = notes.filterNot { normalize(it) == normalize(target) || normalize(it).contains(target) }
            if (remaining.size == notes.size) return "لم أجد ملاحظة مطابقة لعبارتك، لذلك لم أحذف أي شيء."
            memory.write(NovaMemoryItem(KEY_NOTES, remaining.joinToString("\n"), nowMillis))
            return "نسيت المعلومة التي طلبت حذفها. بقيت الملاحظات الأخرى كما هي."
        }
        return null
    }

    private fun extractOriginalNote(rawText: String): String? {
        val expression = Regex(
            """^(?:تذكّري|تذكري|تذكّر|تذكر|احفظي|احفظ|سجّلي|سجلي|سجّل|سجل|لا تنسي|لا تنس)\s+(?:أنني|أني|اني|أن|ان|بأنني|بأني|بانني|بأن)\s+(.+)$"""
        )
        return expression.find(rawText.trim())?.groupValues?.getOrNull(1)?.trim()
    }

    private fun readNotes(): List<String> = memory.read(KEY_NOTES)
        .orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.length >= 3 }
        .toList()
        .takeLast(MAX_NOTES)

    private fun normalize(value: String): String = value.trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace('أ', 'ا')
        .replace('إ', 'ا')
        .replace('آ', 'ا')
        .replace('ى', 'ي')
        .replace(Regex("\\s+"), " ")
        .replace(Regex("[?؟.!،,؛:]+$"), "")

    private companion object {
        const val KEY_NOTES = "nova_explicit_personal_notes_v1"
        const val MAX_NOTES = 12
        const val MAX_NOTE_CHARS = 160

        val rememberPrefixes = listOf(
            "تذكري انني ", "تذكر انني ", "احفظي انني ", "احفظ انني ",
            "تذكري اني ", "تذكر اني ", "احفظي اني ", "احفظ اني ",
            "تذكري ان ", "تذكر ان ", "احفظي ان ", "احفظ ان ",
            "سجلي ان ", "سجل ان ", "لا تنسي ان ", "لا تنس ان "
        )
        val forgetPrefixes = listOf(
            "انسي انني ", "انس انني ", "انسي اني ", "انس اني ", "احذفي انني ", "احذف انني ",
            "انسي ان ", "انس ان ", "احذفي ان ", "احذف ان "
        )
        val clearCommands = listOf(
            "انس كل ما تتذكره عني", "انسي كل ما تتذكرينه عني",
            "امسح ذاكرتك الشخصيه", "امسحي ذاكرتك الشخصيه",
            "احذف كل ملاحظاتي الشخصيه", "احذفي كل ملاحظاتي الشخصيه"
        )
        val listCommands = listOf(
            "ماذا تتذكر عني", "ما الذي تتذكره عني", "ماذا حفظت عني",
            "ما الاشياء التي طلبت منك تذكرها", "اذكر ما تتذكره عني", "اذكري ما تتذكرينه عني"
        )
    }
}
