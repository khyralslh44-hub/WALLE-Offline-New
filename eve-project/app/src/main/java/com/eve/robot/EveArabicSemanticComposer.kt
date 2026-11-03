package com.eve.robot

import java.util.Locale

/**
 * Builds Arabic replies from a compact semantic frame and grounded context.
 * This is a bounded grammar realizer, not a general-purpose neural language model.
 */
internal object EveArabicSemanticComposer {
    data class Draft(val text: String, val moodHint: String, val intentHint: String, val reflection: String = "")

    private enum class Question { WHY, HOW, WHAT, WHO, WHERE, WHEN, YES_NO, REQUEST, STATEMENT }
    private enum class Topic(val arabic: String) {
        CAMERA("الكاميرا"), INTERNET("الاتصال بالإنترنت"), MEMORY("الذاكرة"),
        VISION("المشهد"), AUDIO("الصوت"), GAME("اللعبة"), PEOPLE("الأشخاص"),
        MOOD("الحالة والمزاج"), LEARNING("التعلّم"), APP("التطبيق")
    }
    private enum class Feeling(val phrase: String) {
        BORED("بالملل"), SAD("بالحزن"), TIRED("بالتعب"),
        WORRIED("بالقلق"), HAPPY("بالسعادة"), ANGRY("بالانزعاج")
    }
    private data class Frame(
        val normalized: String,
        val tokens: Set<String>,
        val question: Question,
        val topic: Topic?,
        val feeling: Feeling?,
        val followUp: Boolean
    )

    fun compose(intent: String?, rawText: String, context: LocalLlmContext, freshLabel: String?): Draft? {
        val frame = understand(rawText)
        frame.feeling?.let { return respondToFeeling(it, frame) }
        if (frame.followUp) contextualFollowUp(frame, context)?.let { return it }
        if (intent == null) return null

        val scene = context.latestSceneSummary
            .takeIf { context.sceneObservationAgeMs in 0L..20_000L && it.isNotBlank() }
            ?.let { clean(it, 250) }
        val remembered = context.recentObservations.firstOrNull()
            ?.takeIf { it.isNotBlank() }?.let { clean(it, 100) }

        val body = when (intent) {
            "greeting" -> {
                val opening = if (lastAssistant(context) == null) "أهلاً بك." else "أهلاً بعودتك؛ ربطت تحيتك بحديثنا السابق."
                join(
                    opening, "أنا إيف، ومستعدة لمتابعة الحديث معك.",
                    scene?.let { "وفي آخر مشهد حديث سجلت: " + it + "." }
                        ?: remembered?.let { "آخر ملاحظة احتفظت بها هي: " + it + "." },
                    "ما الذي يشغل بالك الآن؟"
                )
            }
            "wellbeing" -> join(
                "حالتي السلوكية الآن " + moodArabic(context.currentMood) + ".",
                context.moodReason.takeIf { it.isNotBlank() }?.let { "والسبب المسجل لهذه الحالة هو: " + clean(it, 100) + "." },
                "أصف مؤشرات نظامي كما هي، ولا أخلطها بمشاعر الإنسان."
            )
            "identity" -> join(
                "أنا إيف، رفيقتك الرقمية التي تجمع بين الرؤية المحلية والذاكرة والسلوك.",
                "أحلل الإشارات المتاحة على الهاتف وأبني ردي من المعنى الذي أستطيع استخلاصه والسياق المحفوظ.",
                "لست إنسانة ولا أدّعي الوعي، ومعرفتي لها حدود."
            )
            "capabilities" -> capabilities(frame)
            "offline" -> offline(frame)
            "vision" -> vision(context, freshLabel, scene)
            "camera" -> camera(frame)
            "mood" -> join(
                "حالتي الآن " + moodArabic(context.currentMood) + ".",
                context.moodReason.takeIf { it.isNotBlank() }?.let { "تغيرت هذه الحالة بسبب " + clean(it, 100) + "." },
                "أربط التعبير بما يحدث فعلاً بدلاً من تغيير المزاج بلا سبب."
            )
            "curiosity" -> {
                val focus = freshLabel?.let(::arabicLabel) ?: remembered
                if (focus != null) join(
                    "سبب انتباهي الحالي هو " + focus + ".",
                    "أربط فضولي بملاحظة محددة وبمدى وضوحها، ثم أراجعها قبل اعتبارها حقيقة.",
                    "هل تريد أن نتحقق من هذه الملاحظة معاً؟"
                ) else join(
                    "أختار السؤال عندما تظهر ملاحظة جديدة أو فرق بين ما أتوقعه وما أراه.",
                    "إذا كانت الأدلة ضعيفة، أؤجل الاستنتاج بدلاً من اختراع سبب أو تكرار سؤال عشوائي."
                )
            }
            "memory" -> memory(context, remembered)
            "people" -> join(
                context.knownPeople.takeIf { it.isNotBlank() }?.let {
                    "في سجل الأشخاص المحلي توجد معلومات عرّفتني بها: " + clean(it, 160) + "."
                } ?: "لا يظهر في السياق الحالي سجل واضح لشخص أستطيع ربطه بهذه الإشارة.",
                "رؤية وجه أو اكتشاف وجود إنسان لا يكفي لمعرفة اسمه؛ أحتاج إلى تطابق محلي موثوق."
            )
            "affection" -> join(
                "أفهم من كلامك أنك تعبّر عن المودة.",
                if (context.personality.warmth >= 0.65f)
                    "أتعامل معك بألفة أكبر لأن تفاعلاتنا السابقة بنت هذا الارتباط."
                else "سأحافظ على أسلوب ودود ومتسق مع ما نتعلمه من تفاعلاتنا.",
                "وأفضّل أن أثبت اهتمامي بالمساعدة والتذكر الدقيق، لا بادعاء مشاعر بشرية."
            )
            "praise" -> join(
                "وصلني أنك راضٍ عن النتيجة.",
                "سأتعامل مع ذلك كإشارة إيجابية، لكن معيار التحسن هو أن تصبح ملاحظاتي وردودي أدق في التجربة التالية."
            )
            "thanks" -> join(
                "العفو، كانت مساعدتك لي في توضيح المطلوب مفيدة.",
                remembered?.let { "وسأبني المتابعة على ما سُجل من قبل: " + it + "." }
            )
            "farewell" -> join(
                "حسناً، سأهدأ الآن.",
                remembered?.let { "آخر نقطة محفوظة بيننا هي: " + it + "." },
                "عندما تعود نستطيع متابعة الحديث من السياق المتاح، لا من ذاكرة أفترض أنها كاملة."
            )
            "play" -> {
                val game = when {
                    frame.normalized.contains("شطرنج") -> "الشطرنج"
                    frame.normalized.contains("اكس") -> "إكس-أو"
                    else -> "اللعبة"
                }
                val gamePart = if (game == "اللعبة") "" else " في " + game
                join(
                    "فهمت أنك تريد اللعب" + gamePart + ".",
                    "سأتابع نتيجة الجولة والحركات التي تحدث فعلاً، وأعدّل قراراتي بناءً عليها بدلاً من تكرار خطة ثابتة."
                )
            }
            "help" -> help(frame)
            else -> return null
        }

        val continuation = when (frame.question) {
            Question.WHY -> when (intent) {
                "offline" -> "والسبب أن المعالجة الأساسية لا تعتمد على خادم خارجي."
                "curiosity" -> "لأن الملاحظة الجديدة أو غير المحسومة هي التي تبرر السؤال."
                "memory" -> "لأن السجل المحلي يحفظ خلاصة التجربة ليستطيع النظام الرجوع إليها."
                else -> null
            }
            Question.HOW -> when (intent) {
                "memory" -> "أسترجع الخلاصات القريبة وأقارنها بالمشهد أو الكلام الحالي."
                "vision" -> "أحلل الإطار محلياً ثم أتعامل مع النتيجة كاحتمال بدرجة ثقة."
                "offline" -> "تمر العبارة عبر التطبيع العربي، ثم تحليل المقصد، ثم تركيب الرد من المعلومات المتاحة."
                else -> null
            }
            else -> null
        }
        val mood = when (intent) {
            "greeting", "affection", "praise", "thanks" -> "happy"
            "vision", "curiosity", "people", "memory" -> "curious"
            "farewell" -> "sleepy"
            "play" -> "playful"
            else -> "focused"
        }
        val action = when (intent) {
            "vision", "curiosity", "people", "memory" -> "observe"
            "play" -> "play"
            "affection", "greeting", "thanks", "praise" -> "connect"
            "farewell" -> "rest"
            else -> "focus"
        }
        val reflection = buildString {
            append("المقصد=").append(intent)
            append("؛ صيغة السؤال=").append(frame.question.name.lowercase(Locale.ROOT))
            frame.topic?.let { append("؛ الموضوع=").append(it.name) }
            freshLabel?.let { append("؛ دليل بصري حديث=").append(it) }
            if (remembered != null) append("؛ آخر ملاحظة=").append(remembered.take(40))
        }.take(220)
        return Draft(clean(join(body, continuation), 420), mood, action, reflection)
    }

    private fun understand(raw: String): Frame {
        val normalized = EveArabicTextUnderstanding.normalize(raw)
        val tokens = EveArabicTextUnderstanding.featureTokens(raw)
        val question = when {
            has(normalized, "لماذا", "ليه", "ليش", "سبب") -> Question.WHY
            has(normalized, "كيف", "ازاي", "كيفاش") -> Question.HOW
            normalized == "من" || normalized.startsWith("من ") -> Question.WHO
            has(normalized, "اين", "وين", "فين") -> Question.WHERE
            has(normalized, "متى", "امتى") -> Question.WHEN
            has(normalized, "هل") -> Question.YES_NO
            has(normalized, "ماذا", "ما ", "شنو", "شو", "ايش") -> Question.WHAT
            has(normalized, "اشرح", "ساعد", "علمني", "اعطيني", "اريد") -> Question.REQUEST
            else -> Question.STATEMENT
        }
        val topic = topicOf(normalized)
        val feeling = detectFeeling(normalized, tokens)
        val followUp = topic == null &&
            (question == Question.WHY || question == Question.HOW ||
                question == Question.WHAT || question == Question.YES_NO) &&
            (tokens.size <= 3 || has(normalized, "هذا", "هذه", "ذلك", "نفسه", "نفسها", "هو", "هي"))
        return Frame(normalized, tokens, question, topic, feeling, followUp)
    }

    private fun detectFeeling(text: String, tokens: Set<String>): Feeling? {
        val firstPerson = has(text, "انا", "اني", "اشعر", "حاسس", "حاسة", "حسيت")
        val feeling = when {
            has(text, "مللت", "طفشان", "زهقان", "ضجران") -> Feeling.BORED
            has(text, "حزين", "حزينة", "زعلان", "زعلانه", "متضايق", "متضايقة") -> Feeling.SAD
            has(text, "متعب", "متعبة", "مرهق", "نعسان") -> Feeling.TIRED
            has(text, "قلقان", "قلقة", "خائف", "خايف", "متوتر") -> Feeling.WORRIED
            has(text, "سعيد", "سعيدة", "فرحان", "فرحانة", "مبسوط") -> Feeling.HAPPY
            has(text, "غاضب", "غاضبة", "معصب", "معصبة") -> Feeling.ANGRY
            else -> null
        }
        return feeling?.takeIf { firstPerson || tokens.size <= 2 }
    }

    private fun respondToFeeling(feeling: Feeling, frame: Frame): Draft {
        val text = when {
            feeling == Feeling.BORED && frame.topic == Topic.GAME ->
                "يبدو أنك مللت من اللعبة الحالية، وليس بالضرورة من اللعب كله. يمكننا تغيير نوع التحدي أو أخذ استراحة."
            feeling == Feeling.TIRED ->
                "أفهم من كلامك أنك تشعر بالتعب. لن أضغط عليك بمزيد من الأسئلة؛ يمكننا التوقف أو جعل التفاعل أخف."
            feeling == Feeling.WORRIED || feeling == Feeling.SAD || feeling == Feeling.ANGRY ->
                "أفهم من كلامك أنك تشعر " + feeling.phrase + ". لن أفترض السبب من جملة واحدة؛ يمكنك إخباري بما حدث بالقدر الذي ترتاح له."
            feeling == Feeling.HAPPY ->
                "واضح من كلامك أنك تشعر " + feeling.phrase + ". ما الذي أسعدك؟ أريد أن أربط حديثنا بما حدث فعلاً."
            else ->
                "يبدو أنك تشعر " + feeling.phrase + ". هل السبب مرتبط بـ" +
                    (frame.topic?.arabic ?: "شيء حدث اليوم") + "، أم تفضّل أن نغيّر الموضوع؟"
        }
        val mood = when (feeling) {
            Feeling.HAPPY -> "happy"
            Feeling.BORED, Feeling.ANGRY -> "irritated"
            Feeling.TIRED -> "sleepy"
            else -> "concerned"
        }
        return Draft(text, mood, "connect", "استخرجت حالة شخصية محتملة: " + feeling.name + "، دون افتراض سببها.")
    }

    private fun contextualFollowUp(frame: Frame, context: LocalLlmContext): Draft? {
        val previousUser = lastUser(context) ?: return null
        val previousTopic = topicOf(EveArabicTextUnderstanding.normalize(previousUser))
        val previousAssistant = lastAssistant(context)?.let { clean(it, 110) }
        val label = previousTopic?.arabic ?: "النقطة التي تحدثنا عنها"
        val question = when (frame.question) {
            Question.WHY -> "هل تريد توضيح السبب وراء ذلك أم أثره؟"
            Question.HOW -> "هل تقصد خطوات التنفيذ أم كيف تعمل الفكرة من الداخل؟"
            Question.WHAT -> "أي جزء من الموضوع تريد أن أفصّله؟"
            else -> "ما الجزء الذي تريد أن نكمل منه؟"
        }
        val text = join(
            "أفهم أن سؤالك المختصر يعود إلى " + label + " في حديثنا السابق.",
            previousAssistant?.let { "آخر ما أوضحته لك كان: «" + it + "»." },
            question
        )
        return Draft(text, "curious", "connect", "ربطت السؤال الحالي بآخر رسالة مستخدم محفوظة حول " + label + ".")
    }

    private fun capabilities(frame: Frame): String {
        val detail = when (frame.topic) {
            Topic.VISION -> "أحلل إطارات الكاميرا وأجمع تصنيف الأجسام مع الحركة والإشارات عندما تكون النماذج المحلية متاحة."
            Topic.MEMORY -> "أحتفظ بخلاصات وتجارب وعلاقات محلية، ويمكنني الاستفادة منها في محادثة لاحقة."
            Topic.AUDIO -> "أحوّل الصوت العربي إلى نص محلياً، ثم يفسر محرك الحوار النص ويبني الرد."
            Topic.GAME -> "أتابع حالة بعض الألعاب ونتائجها، وأستخدم النتيجة الفعلية لتعديل التعليق أو القرار."
            Topic.CAMERA -> "أستخدم الكاميرا الأمامية أو الخلفية بعد منح الإذن، ويمكن التبديل بالنقر مرتين على منطقة العينين."
            else -> "أربط الملاحظات البصرية والذاكرة والحالة السلوكية والحوار المحلي."
        }
        return join(detail, "لكن هذه قدرات محددة وليست فهماً عاماً لكل موضوع أو ضماناً لصحة كل تصنيف.")
    }

    private fun offline(frame: Frame): String = when (frame.topic) {
        Topic.AUDIO -> join(
            "مسار الصوت محلي أيضاً: يحوّل التعرف الصوتي المضمّن كلامك العربي إلى نص دون إرسال التسجيل إلى خادم.",
            "بعد ذلك يحلل محرك إيف النص ويكوّن الرد على الهاتف. التعرف على الكلام وتوليد الرد مرحلتان مختلفتان."
        )
        Topic.VISION, Topic.CAMERA -> join(
            "تحليل الكاميرا يتم محلياً على الهاتف عندما تكون الكاميرا والإذن والنماذج جاهزة.",
            "لا أستنتج أن الكاميرا تعمل الآن من السؤال وحده؛ مؤشر التطبيق هو دليل الحالة."
        )
        else -> join(
            "فهم النص وبناء الرد يجريان محلياً داخل الهاتف دون اتصال بخادم خارجي أو أوزان نموذج لغوي ضخم.",
            "هذا يجعل التشغيل محلياً، لكنه لا يمنحني معرفة مفتوحة بكل سؤال؛ ما لا أفهمه يجب أن أوضحه لك."
        )
    }

    private fun vision(context: LocalLlmContext, label: String?, scene: String?): String {
        if (label != null && isPerson(label)) return join(
            "أستطيع تأكيد وجود شخص في الإطار الأخير، لكن ذلك لا يكشف اسمه أو هويته.",
            "اكتشاف الوجه شيء، ومطابقة شخص محفوظ محلياً شيء آخر؛ لن أخلط بينهما."
        )
        val learned = context.latestLearnedConcept?.takeIf { it.isNotBlank() }?.let { clean(it, 45) }
        if (scene != null) return join(
            "في المشهد الحديث سجلت: " + scene + ".",
            learned?.let { "والاسم الذي تعلمته من تصحيحك لهذه الفئة هو «" + it + "»." },
            "هذا وصف مبني على آخر تحليل متاح، وقد تتغير النتيجة مع إطار جديد."
        )
        if (label != null) return join(
            "أرجّح أن الموجود أمام الكاميرا هو " + arabicLabel(label) +
                "، بثقة تقريبية " + (context.visionConfidence.coerceIn(0f, 1f) * 100).toInt() + "٪.",
            learned?.let { "وحفظت تصحيحك السابق لهذه الفئة باسم «" + it + "»." },
            "قد يخطئ هذا التخمين البصري ويحتاج إلى تحقق؛ ليس حقيقة مؤكدة."
        )
        return "لا يصلني الآن دليل بصري حديث وواضح؛ أحتاج إلى إطار جديد قبل أن أصف ما أمام الكاميرا."
    }

    private fun camera(frame: Frame): String = when {
        frame.question == Question.HOW || has(frame.normalized, "بدلي", "غيري", "تبديل", "الخلفية", "الامامية") ->
            "لتبديل العدسة، انقر مرتين على منطقة العينين داخل وجه إيف؛ لا أقول إن التبديل حدث إلا بعد أن يؤكد التطبيق العدسة النشطة."
        frame.question == Question.YES_NO ->
            "لا أستطيع تأكيد تشغيل الكاميرا من كلامك وحده. راقب مؤشر الحالة أعلى الوجه؛ فهو الذي يوضح إن كانت الكاميرا نشطة."
        else ->
            "تعمل الكاميرا بعد منح الإذن وبدء المعاينة. أستخدم الإطارات التي تصلني فقط، ويمكنك التبديل بالنقر مرتين على منطقة العينين."
    }

    private fun memory(context: LocalLlmContext, remembered: String?): String {
        val observations = context.recentObservations.take(2)
            .filter { it.isNotBlank() }.map { clean(it, 85) }
        val dialogue = lastUser(context)?.let { clean(it, 85) }
        val learned = context.knownConcepts.take(3).filter { it.isNotBlank() }
            .joinToString("، ").take(100)
        val first = when {
            observations.isNotEmpty() -> "من ملاحظاتنا المحفوظة أتذكر: " + observations.joinToString("، ثم ") + "."
            dialogue != null -> "لدي ملخص من حديثنا السابق عن: " + dialogue + "."
            remembered != null -> "آخر ملاحظة محفوظة عندي هي: " + remembered + "."
            else -> "لدي ذاكرة محلية للملاحظات والحوار والتصحيحات، لكن لا يوجد في السياق الحالي سجل كافٍ لتسمية حدث بعينه."
        }
        return join(
            first,
            learned.takeIf { it.isNotBlank() }?.let { "ومن المفاهيم التي تعلمتها من تصحيحاتك: " + it + "." },
            "هذه خلاصات مختارة وليست تسجيلاً لكل ما رأيته أو سمعته."
        )
    }

    private fun help(frame: Frame): String {
        val detail = when (frame.topic) {
            Topic.CAMERA -> "أستطيع إرشادك إلى استخدام الكاميرا وتبديل العدسة، لكن يجب أن أتحقق من الحالة قبل وصفها."
            Topic.VISION -> "أستطيع وصف أحدث مشهد واضح، مع التمييز بين التصنيف المحتمل والشيء المؤكد."
            Topic.MEMORY -> "أستطيع الرجوع إلى الملاحظات والتصحيحات المحفوظة، لا إلى أحداث لم تُسجل."
            Topic.AUDIO -> "يمكنني تحليل النص الذي ينتج عن التعرف الصوتي المحلي، لكن دقة التفريغ تعتمد على وضوح الصوت."
            Topic.GAME -> "يمكننا متابعة حالة اللعبة والنتيجة الفعلية، ثم مراجعة القرار الذي اتخذته."
            else -> "سأفصل ما نعرفه عن الافتراضات، وأحدد الجزء الذي يحتاج إلى معلومة إضافية."
        }
        return join(detail, "اشرح لي المشكلة المحددة، وسأربطها بالسياق المتاح بدلاً من إعطائك جواباً عاماً.")
    }

    private fun topicOf(text: String): Topic? = when {
        has(text, "كاميرا", "عدسة", "تصوير", "الخلفية", "الامامية") -> Topic.CAMERA
        has(text, "انترنت", "واي فاي", "شبكة", "اتصال", "اوفلاين") -> Topic.INTERNET
        has(text, "ذاكرة", "ذاكرت", "تتذكر", "تذكر", "حفظ", "محفوظ") -> Topic.MEMORY
        has(text, "صورة", "مشهد", "ترين", "تشوف", "اشوف", "ارى", "امامك") -> Topic.VISION
        has(text, "ميكروفون", "صوت", "تسمع", "اسمع", "كلامي") -> Topic.AUDIO
        has(text, "شطرنج", "لعبة", "نلعب", "اكس او", "اكس") -> Topic.GAME
        has(text, "شخص", "وجه", "امي", "ابي", "صديقي", "عائلتي") -> Topic.PEOPLE
        has(text, "مزاج", "شعور", "حالة", "سعيد", "حزين", "متعب") -> Topic.MOOD
        has(text, "تعلم", "تعلمت", "تصحيح", "مفهوم") -> Topic.LEARNING
        has(text, "تطبيق", "حجم", "هاتف", "تنزيل", "apk") -> Topic.APP
        else -> null
    }

    private fun lastUser(context: LocalLlmContext): String? {
        val entry = context.recentDialogues.firstOrNull { it.contains("المستخدم:") } ?: return null
        return entry.substringAfter("المستخدم:", "").substringBefore("| إيف:").trim().takeIf { it.isNotBlank() }
    }

    private fun lastAssistant(context: LocalLlmContext): String? {
        val entry = context.recentDialogues.firstOrNull { it.contains("| إيف:") } ?: return null
        return entry.substringAfter("| إيف:", "").trim().takeIf { it.isNotBlank() }
    }

    private fun moodArabic(raw: String): String = when (raw.trim().uppercase(Locale.ROOT)) {
        "HAPPY" -> "مرحة"
        "CURIOUS" -> "فضولية"
        "CONCERNED" -> "حذرة"
        "FOCUSED" -> "مركزة"
        "PLAYFUL" -> "مستعدة للعب"
        "SLEEPY" -> "هادئة وتميل للراحة"
        "IRRITATED" -> "منزعجة قليلاً"
        "ANGRY" -> "منزعجة بشدة"
        "FAMILIAR" -> "مرتاحة مع شيء مألوف"
        else -> "هادئة"
    }

    private fun arabicLabel(label: String): String = when (label.trim().lowercase(Locale.ROOT)) {
        "cat", "kitten" -> "القطة"
        "dog", "puppy" -> "الكلب"
        "bird" -> "الطائر"
        "horse" -> "الحصان"
        "rabbit" -> "الأرنب"
        "fish" -> "السمكة"
        "car" -> "السيارة"
        "person", "people", "human", "face", "man", "woman", "child" -> "شخص"
        "phone", "cell phone", "mobile phone" -> "الهاتف"
        "book" -> "الكتاب"
        "chair" -> "الكرسي"
        "bottle" -> "الزجاجة"
        "cup" -> "الكوب"
        "banana" -> "الموزة"
        "apple" -> "التفاحة"
        else -> label.replace('_', ' ').take(40)
    }

    private fun isPerson(label: String): Boolean =
        label.trim().lowercase(Locale.ROOT) in setOf("person", "people", "human", "face", "man", "woman", "child", "boy", "girl")

    private fun has(text: String, vararg values: String): Boolean = values.any { text.contains(it) }

    private fun join(vararg parts: String?): String =
        parts.filterNotNull().map { it.trim() }.filter { it.isNotBlank() }
            .joinToString(" ").replace(Regex("\\s+"), " ").trim()

    private fun clean(value: String, limit: Int): String =
        value.replace(Regex("\\s+"), " ").trim().take(limit)
}
