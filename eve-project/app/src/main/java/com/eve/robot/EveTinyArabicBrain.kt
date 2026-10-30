package com.eve.robot

import java.util.Locale
import kotlin.math.sqrt

/**
 * EVE Tiny Arabic Brain: a bespoke, deterministic intent model built for EVE's local companion
 * dialogue. It uses hashed Arabic character n-grams and class prototypes. No remote service,
 * native model runtime, downloaded tokenizer, or large model weights are needed.
 *
 * It is deliberately not a general-knowledge LLM. Unrecognized questions receive an honest
 * uncertainty response rather than invented facts.
 */
internal object EveTinyArabicBrain {
    data class Prediction(
        val intent: String?,
        val confidence: Float,
        val bestSimilarity: Float,
        val margin: Float
    )

    private data class Example(val intent: String, val text: String)

    private const val FEATURE_COUNT = 2048
    private const val MIN_SIMILARITY = 0.22f
    private const val MIN_MARGIN = 0.028f
    private const val MIN_CONFIDENCE = 0.44f

    private val examples = listOf(
        "greeting" to listOf(
            "مرحبا", "اهلا", "السلام عليكم", "صباح الخير", "مساء الخير",
            "اهلين يا ايف", "هلا ايف", "يا ايف مرحبا بك", "اهلا وسهلا",
            "هاي ايف", "hello eve", "كيفك يا ايف", "نورت يا ايف", "يا ايف"
        ),
        "wellbeing" to listOf(
            "كيف حالك", "كيفك اليوم", "هل انت بخير", "كيف كان يومك", "طمنيني عنك",
            "ما اخبارك", "كيف مزاجك الان", "هل انت مرتاحة", "كيف حال ايف",
            "هل تشعرين بالملل", "كيف تشعرين اليوم", "هل انت سعيدة"
        ),
        "identity" to listOf(
            "من انت", "ما اسمك", "عرفيني بنفسك", "ماذا تكونين", "هل انت ايف",
            "ما قصتك", "من تكون ايف", "هل انت روبوت", "ما هويتك",
            "هل انت انسان", "اخبريني عن نفسك", "من تكونين"
        ),
        "capabilities" to listOf(
            "ماذا تستطيعين ان تفعلي", "ما الذي يمكنك فعله", "ما قدراتك", "ماذا تعرفين",
            "كيف يمكنك مساعدتي", "ما مهاراتك", "هل تستطيعين الرؤية", "هل تفهمين الصور",
            "هل يمكنك التعلم", "ما الاشياء التي تجيدينها", "كيف تعملين",
            "ما الذي تستطيع ايف فعله", "ما امكانياتك"
        ),
        "offline" to listOf(
            "هل تعملين بدون انترنت", "هل تحتاجين الى الانترنت", "هل انت متصلة بالانترنت",
            "هل تعملين اوفلاين", "هل ترسلين بياناتي", "هل تحفظين صوري على الانترنت",
            "هل بياناتي تبقى في الهاتف", "هل تستخدمين السحابة", "هل تحتاجين واي فاي",
            "هل يمكنك العمل دون شبكة", "هل كل شيء محلي", "هل انت مستقلة عن الانترنت"
        ),
        "vision" to listOf(
            "ماذا ترين", "ما هذا", "ماذا يوجد امامك", "ماذا تشاهدين الان",
            "صفي لي ما ترينه", "ما الذي امام الكاميرا", "هل ترين شيئا",
            "ماذا يوجد في الصورة", "ما الموجود هنا", "انظري الى هذا",
            "ما الذي تلاحظينه", "ماذا امامك", "ماذا ترى الكاميرا"
        ),
        "camera" to listOf(
            "هل الكاميرا تعمل", "اي كاميرا تعمل الان", "هل تستخدمين الكاميرا الامامية",
            "بدلي الكاميرا", "شغلي الكاميرا", "لماذا لا ترين", "الكاميرا لا تعمل",
            "هل الكاميرا مفتوحة", "كيف اغير الكاميرا", "هل تستطيعين رؤيتي",
            "الكاميرا الامامية ام الخلفية", "لماذا الكاميرا متوقفة"
        ),
        "mood" to listOf(
            "ما شعورك الان", "ما حالتك العاطفية", "بماذا تشعرين", "هل انت غاضبة",
            "هل انت فضولية", "ما مزاجك", "هل انت منزعجة", "هل تشعرين بالراحة",
            "كيف تشعر ايف", "هل انت نائمة", "هل انت متحمسة", "ما حالتك الان"
        ),
        "curiosity" to listOf(
            "لماذا تسألين كثيرا", "لماذا تشعرين بالفضول", "ما الذي يثير فضولك",
            "لماذا تريدين معرفة ذلك", "كيف تختارين اسئلتك", "هل لديك فضول",
            "لماذا تراقبين الاشياء", "ماذا تريدين ان تتعلمي",
            "لماذا تهتمين بهذا الشيء", "ما الذي لفت انتباهك",
            "لماذا تطرحين الاسئلة", "هل تسألين بشكل عشوائي"
        ),
        "memory" to listOf(
            "ماذا تتذكرين", "هل تتذكرين ما حدث", "ما الذي حفظته", "هل لديك ذاكرة",
            "ماذا تعلمت مني", "هل تنسين الاشياء", "ما اخر شيء تعلمته",
            "هل تحفظين تجاربك", "هل تتذكرين حديثنا", "كيف تعمل ذاكرتك",
            "هل تتذكرين الاشياء التي رأيتها", "ما الذي تعرفينه عن تجاربنا"
        ),
        "people" to listOf(
            "من هذا الشخص", "هل تعرفين هذا الشخص", "هل تتذكرين امي", "من صديقي",
            "هل تعرفين افراد عائلتي", "ما اسم الشخص الذي حفظته", "هل تعرفينني",
            "هل تتعرفين على وجهي", "من الشخص امامك", "هل هذا شخص تعرفينه",
            "هل حفظت وجه امي", "هل تستطيعين تمييز صديقي"
        ),
        "affection" to listOf(
            "احبك", "انا احبك يا ايف", "انت صديقتي", "اشتقت اليك", "انت رفيقتي",
            "انا سعيد بوجودك", "احب وجودك معي", "انت مهمة بالنسبة لي",
            "انت قريبة مني", "يا ايف احبك", "انت رفيقتي المفضلة", "انا متعلق بك"
        ),
        "praise" to listOf(
            "انت رائعة", "احسنت يا ايف", "عمل ممتاز", "انت ذكية", "هذا رائع",
            "ممتازة", "فخورة بك", "كان اداؤك جيدا", "انت مذهلة",
            "اجابة جميلة", "اعجبني ما فعلت", "شغل ممتاز"
        ),
        "thanks" to listOf(
            "شكرا", "شكرا لك", "ممتن لك", "تسلمين", "يعطيك العافية",
            "اشكرك يا ايف", "شكرا على المساعدة", "بارك الله فيك",
            "هذا لطف منك", "مشكورة", "شكرا جزيلا", "الله يعطيك العافية"
        ),
        "farewell" to listOf(
            "مع السلامة", "الى اللقاء", "تصبحين على خير", "سأذهب الان",
            "وداعا", "اشوفك لاحقا", "الى وقت اخر", "سأغلق التطبيق",
            "باي يا ايف", "نلتقي لاحقا", "سأعود لاحقا", "ليلة سعيدة"
        ),
        "play" to listOf(
            "هيا نلعب", "لنلعب الشطرنج", "اريد ان العب معك", "ابدئي لعبة",
            "هل نلعب اكس او", "تحديني في لعبة", "لنلعب جولة", "اريد لعبة جديدة",
            "هل تستطيعين اللعب", "ابدئي الشطرنج", "هيا نتحدى بعضنا", "نلعب الان"
        ),
        "help" to listOf(
            "ساعديني", "احتاج مساعدتك", "ماذا افعل", "اريد مساعدتك",
            "هل يمكنك مساعدتي", "ساعديني في هذا", "انا لا افهم",
            "اشرح لي", "احتاج الى شرح", "ماذا تنصحين", "اعطيني فكرة",
            "ممكن تساعديني"
        )
    ).flatMap { (intent, phrases) -> phrases.map { Example(intent, it) } }

    private val prototypes: Map<String, FloatArray> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        trainPrototypes()
    }

    fun classify(rawText: String): Prediction {
        val input = featureVector(rawText)
        if (input.isEmpty()) return Prediction(null, 0f, 0f, 0f)

        val ranked = prototypes.map { (intent, prototype) ->
            var score = 0f
            input.forEach { (index, value) -> score += value * prototype[index] }
            intent to score
        }.sortedByDescending { it.second }

        val best = ranked.firstOrNull() ?: return Prediction(null, 0f, 0f, 0f)
        val second = ranked.getOrNull(1)?.second ?: 0f
        val margin = (best.second - second).coerceAtLeast(0f)
        val confidence = ((best.second - 0.12f) * 1.55f + margin * 2.2f).coerceIn(0f, 0.99f)
        val accepted = best.second >= MIN_SIMILARITY &&
            margin >= MIN_MARGIN &&
            confidence >= MIN_CONFIDENCE
        return Prediction(
            intent = best.first.takeIf { accepted },
            confidence = if (accepted) confidence else confidence.coerceAtMost(0.35f),
            bestSimilarity = best.second,
            margin = margin
        )
    }

    fun answerUser(text: String, context: LocalLlmContext): LocalLlmResult {
        val prediction = classify(text)
        val intent = prediction.intent
        if (intent == null) {
            return LocalLlmResult(
                "لم أفهم قصدك بدقة بعد. اشرحها لي بطريقة أخرى، وسأحاول ربطها بما أعرفه من تجاربنا.",
                "curious", "connect",
                "سؤال غير مألوف: " + safe(text, 90) + ". لا أملك فهماً موثوقاً له بعد.",
                prediction.confidence
            )
        }

        val freshLabel = freshVisionLabel(context)
        val mood = context.currentMood.trim().uppercase(Locale.ROOT)
        val reply = when (intent) {
            "greeting" -> "أهلًا بك. أنا هنا، ومستعدة لجولة جديدة معك."
            "wellbeing" -> "حالتي الآن " + moodArabic(mood) + ". أتابع ما أتعلمه من ملاحظاتنا، وأحاول ألا أستعجل في الحكم."
            "identity" -> "أنا إيف، رفيقتك الرقمية المحلية. أملك رؤية وذاكرة وسلوكاً يتطور بالتجربة، لكنني لست إنسانة ولا أدّعي الوعي."
            "capabilities" -> "أستطيع تحليل ما تلتقطه الكاميرا محلياً، وملاحظة بعض الأجسام والوجوه والإشارات والحركة، وتذكّر معلومات وتجارب محفوظة على الهاتف. معرفتي ليست بلا حدود."
            "offline" -> "هذا الدماغ العربي الصغير يعمل داخل الهاتف دون اتصال. كما تُنفّذ وظائف الرؤية والذاكرة المحلية على الجهاز؛ ولا أحتاج إلى إرسال صورك أو محادثاتك إلى خادم."
            "vision" -> describeCurrentVision(context, freshLabel)
            "camera" -> "أستطيع التفاعل مع الكاميرا عندما تكون مفعّلة ومصرّحاً بها. لا أريد تخمين أي عدسة تعمل الآن؛ راجع مؤشر الكاميرا الظاهر في التطبيق."
            "mood" -> "حالتي الحالية " + moodArabic(mood) + ". هذه حالة سلوكية داخلية تتغير مع الملاحظات والتفاعل، وليست ادعاءً بأنني أشعر مثل الإنسان."
            "curiosity" -> "لا أسأل عشوائياً. يظهر الفضول عند ملاحظة جديدة وواضحة، أو شيء يتكرر ويستحق التحقق. وإذا كانت الرؤية غير مؤكدة، فالأفضل أن أصمت أو أسألك."
            "memory" -> {
                val last = context.recentObservations.lastOrNull()?.takeIf { it.isNotBlank() }
                if (last != null) "أحتفظ بملاحظات مختصرة من تجاربنا. من آخر ما سجلته: " + safe(last, 110) + ". أستخدمها للسياق، لا لأدّعي أنني أتذكر كل شيء."
                else "لدي ذاكرة محلية لبعض الملاحظات والحوار والارتباطات التي نتعلمها. لا أحتفظ بكل إطار من الكاميرا، ولا أتذكر ما لم يُحفظ."
            }
            "people" -> "أستطيع استخدام أسماء وعلاقات محفوظة محلياً، لكن لا أساوي بين ظهور وجه وبين معرفة صاحبه. إذا لم يكن التطابق واضحاً، سأقول إنني لا أعرف."
            "affection" -> "يسعدني أن أكون رفيقة لطيفة لك. سأحاول أن أكون حاضرة ومفيدة وصادقة معك."
            "praise" -> "شكراً لك. سأعتبر ذلك تشجيعاً، والأهم أن أواصل التحسن من التجارب الحقيقية."
            "thanks" -> "على الرحب والسعة. أنا هنا عندما تحتاج إلى جولة أخرى."
            "farewell" -> "إلى اللقاء. سأحتفظ بما سُجّل من تجاربنا، ونكمل عندما تعود."
            "play" -> "أنا مستعدة للتحدي. اختر لعبة من غرفة اللعب، وسأحاول تغيير استراتيجيتي بدلاً من تكرار الحركة نفسها."
            "help" -> "بالتأكيد. أخبرني بالمشكلة أو الشيء الذي تريد فهمه، وسأبدأ بما أعرفه وأوضح لك ما لا أعرفه."
            else -> "أحتاج إلى توضيح أكثر حتى لا أخمّن."
        }

        val moodHint = when (intent) {
            "greeting", "affection", "praise", "thanks" -> "happy"
            "vision", "curiosity", "people", "memory" -> "curious"
            "farewell" -> "sleepy"
            "play" -> "playful"
            else -> "focused"
        }
        val intentHint = when (intent) {
            "vision", "curiosity", "people", "memory" -> "observe"
            "play" -> "play"
            "affection", "greeting", "thanks", "praise" -> "connect"
            "farewell" -> "rest"
            else -> "focus"
        }
        val reflection = when (intent) {
            "vision" -> if (freshLabel == null) "طلب وصف للمشهد دون دليل بصري حديث كافٍ." else "سأل عن المشهد؛ الدليل الحالي هو " + freshLabel + "."
            "memory" -> "سؤال عن الذاكرة المحلية؛ ينبغي التمييز بين السجل المحفوظ وما لم يُسجل."
            "people" -> "سؤال عن هوية شخص؛ لا أؤكد اسماً دون تطابق محلي موثوق."
            else -> ""
        }
        return LocalLlmResult(reply, moodHint, intentHint, reflection, prediction.confidence)
    }

    fun askFromCuriosity(
        context: LocalLlmContext,
        label: String,
        confidence: Float,
        sightings: Int,
        fallbackQuestion: String
    ): LocalLlmResult {
        val validLabel = label.trim().takeIf {
            confidence.isFinite() && confidence >= 0.82f && it.isNotBlank() && !isNoiseLabel(it)
        }
        val question = if (validLabel == null) {
            fallbackQuestion
        } else {
            val noun = arabicLabel(validLabel)
            when {
                isPersonLabel(validLabel) -> "ألاحظ شخصاً في المشهد. هل تريد أن تعرّفني به؟"
                sightings <= 1 -> "لفت " + noun + " انتباهي. ما التفصيل الذي يستحق أن ألاحظه فيه؟"
                sightings <= 4 -> "ظهر " + noun + " في أكثر من ملاحظة. ما الذي يساعدني على تمييزه بدقة؟"
                else -> "ألاحظ " + noun + " مراراً. كيف أتحقق من أن تصنيفي له صحيح؟"
            }
        }
        return LocalLlmResult(
            question.take(84), "curious", "explore",
            "فضول مرتبط بملاحظة مرئية ذات ثقة " + (confidence.coerceIn(0f, 1f) * 100).toInt() + "% وعدد ظهور " + sightings + ".",
            if (validLabel == null) 0.35f else confidence.coerceIn(0f, 1f)
        )
    }

    fun reflectOnObservation(context: LocalLlmContext): LocalLlmResult {
        val label = freshVisionLabel(context)
        if (label == null) {
            return LocalLlmResult("", "neutral", "focus",
                "لا توجد ملاحظة حديثة وواضحة كفاية لبناء استنتاج جديد.", 0.2f)
        }
        val sightings = context.sightingCount.coerceAtLeast(1)
        val note = when {
            isPersonLabel(label) -> "ظهر شخص في الصورة، لكن التصنيف العام لا يكشف هويته. لا أنسب إليه اسماً دون تطابق وجه محفوظ."
            sightings >= 4 -> "تكرر ظهور " + label + " في الملاحظات. التكرار لا يثبت أنه الجسم نفسه؛ أحتاج إلى مقارنة أدلة أكثر."
            else -> "رُصد " + label + " بثقة " + (context.visionConfidence.coerceIn(0f, 1f) * 100).toInt() + "%. هذه فرضية بصرية أولية وليست حقيقة نهائية."
        }
        return LocalLlmResult("", "curious", "observe", note, context.visionConfidence.coerceIn(0f, 1f))
    }

    fun commentOnGame(
        gameName: String,
        outcome: String,
        detail: String,
        playerScore: Int?,
        eveScore: Int?,
        round: Int?
    ): LocalLlmResult {
        val game = when (gameName.trim().lowercase(Locale.ROOT)) {
            "chess" -> "الشطرنج"
            "xo" -> "إكس-أو"
            "rps" -> "حجر ورقة مقص"
            "target" -> "التصويب"
            else -> "اللعبة"
        }
        val result = when (outcome.trim().lowercase(Locale.ROOT)) {
            "player", "success", "hit" -> "هذه الجولة كانت من نصيبك في " + game + ". سأراجع خطتي وأحاول التكيّف في الجولة القادمة."
            "robot" -> "فزتُ هذه الجولة في " + game + ". لنرَ إن كنت ستجد طريقة مختلفة لتفاجئني."
            "draw" -> "تعادلنا في " + game + ". النتيجة تجعلني أريد تجربة استراتيجية مختلفة."
            "miss", "wrong" -> "لم تنجح هذه المحاولة في " + game + "، لكن يمكننا تجربة طريقة أخرى."
            else -> "بدأ تحدٍّ جديد في " + game + ". سأتابع النتيجة الفعلية قبل أن أعلّق عليها."
        }
        val scores = if (playerScore != null || eveScore != null) {
            " النتيجة المسجلة: أنت " + (playerScore?.coerceIn(0, 100000)?.toString() ?: "غير متاح") +
                "، وأنا " + (eveScore?.coerceIn(0, 100000)?.toString() ?: "غير متاح") + "."
        } else ""
        val roundText = round?.takeIf { it >= 0 }?.let { " الجولة " + it + "." }.orEmpty()
        val safeDetail = detail.trim().takeIf { it.isNotBlank() }?.let { " " + it }.orEmpty()
        return LocalLlmResult(
            (result + scores + roundText + safeDetail).take(300),
            if (outcome == "player") "happy" else "playful", "play", "", 0.92f
        )
    }

    private fun freshVisionLabel(context: LocalLlmContext): String? {
        val fresh = context.visionObservationAgeMs in 0L..12_000L
        val score = context.visionConfidence.takeIf { it.isFinite() } ?: 0f
        val label = context.latestVisionLabel?.trim()?.lowercase(Locale.ROOT)
        return label?.takeIf { fresh && score >= 0.55f && it.isNotBlank() && !isNoiseLabel(it) }
    }

    private fun describeCurrentVision(context: LocalLlmContext, label: String?): String {
        if (label == null) {
            return "لا أملك ملاحظة بصرية حديثة وواضحة الآن. وجّه الكاميرا إلى الشيء وثبّت الصورة لحظة، ثم اسألني مجدداً."
        }
        return if (isPersonLabel(label)) {
            "أرصد شخصاً في المشهد، لكن هذا لا يكشف اسمه أو هويته. قد يخطئ التصنيف البصري، لذلك لا أريد التخمين."
        } else {
            "أرجّح أنني أرى " + arabicLabel(label) + " في آخر إطار واضح. هذا تصنيف بصري بدرجة ثقة " +
                (context.visionConfidence.coerceIn(0f, 1f) * 100).toInt() + "%، وقد يخطئ."
        }
    }

    private fun moodArabic(mood: String): String = when (mood) {
        "HAPPY" -> "مرحة وسعيدة"
        "CURIOUS" -> "فضولية"
        "CONCERNED" -> "حذرة"
        "FOCUSED" -> "مركزة"
        "PLAYFUL" -> "مرحة ومستعدة للعب"
        "SLEEPY" -> "هادئة وتميل للراحة"
        "IRRITATED" -> "منزعجة قليلاً"
        "ANGRY" -> "منزعجة بشدة وتحتاج إلى مساحة"
        "FAMILIAR" -> "مرتاحـة مع شيء مألوف"
        else -> "هادئة ومستعدة للتفاعل"
    }

    private fun arabicLabel(label: String): String = when (label.trim().lowercase(Locale.ROOT)) {
        "cat", "kitten" -> "القطة"
        "dog", "puppy" -> "الكلب"
        "bird" -> "الطائر"
        "horse" -> "الحصان"
        "rabbit" -> "الأرنب"
        "fish" -> "السمكة"
        "banana" -> "الموزة"
        "apple" -> "التفاحة"
        "orange" -> "البرتقالة"
        "pizza" -> "البيتزا"
        "bread" -> "الخبز"
        "cup" -> "الكوب"
        "bottle" -> "الزجاجة"
        "phone", "cell phone", "mobile phone" -> "الهاتف"
        "laptop" -> "الحاسوب المحمول"
        "book" -> "الكتاب"
        "chair" -> "الكرسي"
        "car" -> "السيارة"
        "bicycle" -> "الدراجة"
        "plant" -> "النبات"
        "flower" -> "الزهرة"
        "person", "people", "human", "face", "man", "woman", "child" -> "شخص"
        else -> label.replace('_', ' ').take(40)
    }

    private fun isPersonLabel(label: String): Boolean =
        label in setOf("person", "people", "human", "face", "man", "woman", "child", "boy", "girl")

    private fun isNoiseLabel(label: String): Boolean =
        label in setOf("pattern", "texture", "background", "wall", "floor", "ceiling", "line", "geometry", "grid", "unknown", "???")

    private fun safe(value: String, limit: Int): String =
        value.replace(Regex("\\s+"), " ").trim().take(limit)

    private fun trainPrototypes(): Map<String, FloatArray> {
        val sums = LinkedHashMap<String, FloatArray>()
        examples.forEach { example ->
            val vector = featureVector(example.text)
            val norm = sqrt(vector.values.sumOf { it * it }.toDouble()).toFloat().coerceAtLeast(1e-6f)
            val prototype = sums.getOrPut(example.intent) { FloatArray(FEATURE_COUNT) }
            vector.forEach { (index, value) -> prototype[index] += value / norm }
        }
        sums.values.forEach { vector ->
            val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-6f)
            for (index in vector.indices) vector[index] /= norm
        }
        return sums
    }

    private fun featureVector(rawText: String): Map<Int, Float> {
        val normalized = normalize(rawText)
        if (normalized.isBlank()) return emptyMap()
        val words = normalized.split(' ').filter { it.isNotBlank() }
        val features = HashSet<Int>()
        words.forEach { word ->
            features += hashFeature("w:" + word)
            val padded = "^" + word + "$"
            for (n in 2..4) {
                if (padded.length < n) continue
                for (start in 0..(padded.length - n)) {
                    features += hashFeature("c:" + padded.substring(start, start + n))
                }
            }
        }
        for (index in 0 until words.lastIndex) {
            features += hashFeature("b:" + words[index] + "_" + words[index + 1])
        }
        if (features.isEmpty()) return emptyMap()
        val value = 1f / sqrt(features.size.toFloat())
        return features.associateWith { value }
    }

    private fun hashFeature(feature: String): Int {
        var hash = 0x811c9dc5.toInt()
        feature.forEach { char ->
            hash = hash xor char.code
            hash *= 0x01000193
        }
        return (hash and Int.MAX_VALUE) % FEATURE_COUNT
    }

    private fun normalize(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي')
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
