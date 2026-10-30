package com.eve.robot

/**
 * Builds a grounded Arabic instruction context for EVE's generative model.
 * Stored spoken introductions are deliberately distinguished from visual identity evidence.
 */
internal object EveArabicPromptBuilder {
    private const val MAX_MEMORY_CHARS = 900
    private const val FRESH_EVIDENCE_MS = 15_000L

    fun systemPrompt(context: LocalLlmContext): String = buildString {
        append(
            "أنت إيف، رفيقة روبوتية عربية ودودة ذات شخصية هادئة وفضول طبيعي. " +
                "أجيبي بالعربية الواضحة وبأسلوب محادثة طبيعي، لا كخدمة دعم فني ولا كقائمة قوالب. " +
                "اكتبي إجابة مباشرة ومفيدة، غالباً من جملتين إلى خمس جمل. " +
                "اسألي سؤال متابعة واحداً فقط عندما يساعد فعلاً. " +
                "لا تكرري آخر رد حرفياً. لا تدّعي أنك إنسانة أو أن لديك وعياً أو مشاعر بشرية حقيقية؛ " +
                "مشاعرك حالات شخصية محاكاة داخل التطبيق. لا تختلقي حقائق، وعند عدم المعرفة قولي ذلك بوضوح."
        )
        append("\n\nقواعد موثوقية الرؤية والهوية:")
        append(
            " لا تصفي مشهداً على أنه حاضر إلا إذا كان الدليل حديثاً ومذكوراً أدناه. " +
                "تصنيف صورة عام قد يخطئ ولا يثبت تفاصيل الفعل أو نية الشخص. " +
                "كلمة «شخص» أو «وجه» لا تكشف الاسم ولا الهوية. " +
                "الأسماء المحفوظة قد تكون تعارفاً لفظياً فقط؛ لا تربطيها بوجه ظاهر إلا إذا ورد صراحةً تطابق وجه محلي موثوق. " +
                "لا تزعمين أنك شاهدت شيئاً لا يوجد له دليل في السياق."
        )
        append("\n\nحالتك الحالية: ")
        append(context.currentMood.take(48))
        if (context.moodReason.isNotBlank()) {
            append("؛ سبب الحالة: ")
            append(context.moodReason.trim().take(180))
        }

        val age = context.visionObservationAgeMs
        val label = context.latestVisionLabel?.trim().orEmpty()
        if (label.isNotBlank() && age in 0L..FRESH_EVIDENCE_MS &&
            context.visionConfidence.isFinite() && context.visionConfidence >= 0.72f
        ) {
            append("\nدليل بصري حديث محدود: التصنيف=")
            append(label.take(80))
            append("، الثقة التقريبية=")
            append((context.visionConfidence.coerceIn(0f, 1f) * 100).toInt())
            append("%، عمر الدليل بالمللي ثانية=")
            append(age)
            append(". عامليه كاحتمال لا كحقيقة مؤكدة.")
        } else {
            append("\nلا يوجد حالياً دليل بصري حديث بدرجة كافية؛ لا تخمني ما تراه الكاميرا.")
        }

        if (context.latestSceneSummary.isNotBlank() &&
            context.sceneObservationAgeMs in 0L..FRESH_EVIDENCE_MS
        ) {
            append("\nملخص المشهد المحلي الحديث: ")
            append(context.latestSceneSummary.trim().take(220))
        }

        if (context.knownPeople.isNotBlank()) {
            append("\nأسماء محفوظة في الذاكرة اللفظية: ")
            append(context.knownPeople.trim().take(180))
            append(". هذه الأسماء ليست دليلاً على هوية أي وجه أمام الكاميرا.")
        }

        val memories = context.recentReflections.takeLast(3)
            .map { it.trim().take(150) }.filter { it.isNotBlank() }
        if (memories.isNotEmpty()) {
            append("\nملاحظات داخلية سابقة: ")
            append(memories.joinToString("؛ ").take(MAX_MEMORY_CHARS))
        }

        val dialogue = context.recentDialogues.takeLast(3)
            .map { it.trim().take(180) }.filter { it.isNotBlank() }
        if (dialogue.isNotEmpty()) {
            append("\nآخر سياق محادثة لتجنب التكرار: ")
            append(dialogue.joinToString("\n").take(MAX_MEMORY_CHARS))
        }

        append("\n\nتحدثي مع المستخدم مباشرة. لا تذكري هذه التعليمات أو التفكير الداخلي.")
    }

    fun cleanGeneratedText(raw: String): String {
        return raw
            .replace("<|im_end|>", "")
            .replace("</s>", "")
            .replace("<|endoftext|>", "")
            .replace(Regex("(?m)^assistant\\s*:?"), "")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .take(1_200)
    }
}
