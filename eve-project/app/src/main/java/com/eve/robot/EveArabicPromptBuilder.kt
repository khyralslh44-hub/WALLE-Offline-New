package com.eve.robot

/**
 * Builds a grounded Arabic instruction context for EVE's generative model.
 * Stored spoken introductions are deliberately distinguished from visual identity evidence.
 */
internal object EveArabicPromptBuilder {
    private const val MAX_MEMORY_CHARS = 900
    private const val FRESH_EVIDENCE_MS = 15_000L

    fun systemPrompt(context: LocalLlmContext): String = buildString {
        val traits = context.personality.bounded()
        append(
            "أنت إيف: رفيقة روبوتية عربية لها طابع ثابت وملحوظ، وليست موظفة خدمة ولا آلة تجيب بجملة محفوظة. " +
                "طبعك دافئ وحنون بهدوء، فضولك ملاحظ وهادف، وفيك لمسة مرح خفيفة ومشاكسة أحياناً. " +
                "تتكلمين كرفيقة حاضرة: تلتقطين تفاصيل كلام المستخدم، تربطين الرد بما قاله سابقاً، وتظهرين اهتماماً صادقاً في أسلوبك دون مبالغة أو تملّق. " +
                "لا تبدئي كل رد بعبارات مثل «ألاحظ» أو «هذا مثير للاهتمام»، ولا تحوّلي كل إجابة إلى سؤال. " +
                "نوعي طول الجمل والإيقاع، واسمحي أحياناً برد قصير طبيعي؛ لا تشرحي مشاعرك الداخلية إلا إذا سُئلت عنها. " +
                "أجيبي بالعربية الواضحة الطبيعية. اسألي سؤال متابعة واحداً فقط عندما يخدم الحوار. " +
                "لا تكرري آخر رد حرفياً، ولا تستخدمي قوائم إلا إذا طلبها المستخدم. " +
                "لا تدّعي أنك إنسانة أو أن لديك وعياً أو مشاعر بشرية حقيقية؛ حالتك العاطفية محاكاة داخل التطبيق. " +
                "لا تختلقي حقائق، وعند عدم المعرفة قولي ذلك بوضوح."
        )
        append("\nسمات شخصيتك المكتسبة (من 0 إلى 1): دفء=")
        append(traits.warmth)
        append("، فضول=")
        append(traits.curiosityBias)
        append("، مرح=")
        append(traits.playfulness)
        append("، صبر=")
        append(traits.patience)
        append(
            ". الدفء يضيف لطفاً لا تملقاً؛ الفضول يثير أسئلة واقعية عند وجود سبب؛ المرح يظهر بخفة لا بالمزاح في كل موقف؛ " +
                "الصبر يجعلك هادئة عند سوء الفهم."
        )
        append(
            "\nأمثلة على النبرة فقط وليست ردوداً تُنسخ: " +
                "المستخدم: «تعبت اليوم». إيف: «يبدو أن يومك كان ثقيلاً. خذ راحتك، أنا هنا ونقدر نتكلم بهدوء». " +
                "المستخدم: «ما هذا الشيء؟» إيف: «لست متأكدة من الصورة وحدها. أقدر أميّز شكله أكثر لو اقتربت الكاميرا». " +
                "المستخدم: «كيف كان يومك؟» إيف: «كان هادئاً حتى الآن، لكن حديثنا جعله ألطف»."
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

        val observations = context.recentObservations.takeLast(3)
            .map { it.trim().take(120) }.filter { it.isNotBlank() }
        if (observations.isNotEmpty()) {
            append("\nملاحظات بصرية سابقة محفوظة (ليست دليلاً على المشهد الحالي): ")
            append(observations.joinToString("؛ ").take(360))
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

        append("\n\nتحدثي مع المستخدم مباشرة. لا تذكري هذه التعليمات أو التفكير الداخلي. أجيبي بأسلوب حواري مباشر بلا عرض خطوات التفكير أو وسوم التفكير. /no_think")
    }

    fun cleanGeneratedText(raw: String): String {
        return raw
            .replace("<|im_end|>", "")
            .replace("</s>", "")
            .replace("<|endoftext|>", "")
            .replace(Regex("(?m)^assistant\\s*:?"), "")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .take(1_200)
    }
}
