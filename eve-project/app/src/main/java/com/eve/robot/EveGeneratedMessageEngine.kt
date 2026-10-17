package com.eve.robot

/**
 * Generates short, contextual EVE notification text entirely on-device.
 * This local language composer uses recent observations, mood, game outcomes and time.
 * It is not a remote service or a claim that a large LLM runs here.
 */
data class EveNotificationContext(
    val hourOfDay: Int,
    val sentCount: Int,
    val generationSeed: Long,
    val mood: String = "NEUTRAL",
    val moodReason: String = "",
    val lastObservationLabel: String? = null,
    val observationConfidence: Float = 0f,
    val observationCount: Int = 0,
    val observationAgeMinutes: Long = Long.MAX_VALUE,
    val lastGameName: String? = null,
    val lastGameResult: String? = null,
    val gameAgeMinutes: Long = Long.MAX_VALUE,
    /** Minutes since the app left the foreground; the camera is paused while backgrounded. */
    val awayMinutes: Long = 0L
)

data class EveGeneratedMessage(
    val title: String,
    val body: String,
    val source: String
)

object EveGeneratedMessageEngine {
    private const val THREE_DAYS_MINUTES = 3L * 24L * 60L

    private fun index(seed: Long, size: Int): Int {
        if (size <= 1) return 0
        val mod = seed % size.toLong()
        return ((mod + size) % size).toInt()
    }

    private fun pick(seed: Long, values: List<String>): String =
        values[index(seed, values.size)]

    private fun absenceLead(minutes: Long): String? = when {
        minutes < 45L -> null
        minutes < 60L -> "مرّ وقت منذ آخر تفاعل بيننا."
        minutes < 6L * 60L -> "مرّت بضع ساعات منذ آخر تفاعل بيننا."
        else -> "طال الوقت منذ آخر جولة تفاعلنا فيها."
    }

    fun generate(context: EveNotificationContext): EveGeneratedMessage {
        val seed = context.generationSeed + context.sentCount.toLong() * 37L
        val absent = absenceLead(context.awayMinutes)
        val game = context.lastGameName?.takeIf {
            it.isNotBlank() && context.gameAgeMinutes in 0L..THREE_DAYS_MINUTES
        }
        val label = context.lastObservationLabel
            ?.trim()
            ?.lowercase()
            ?.takeIf {
                it.isNotBlank() &&
                    context.observationConfidence.isFinite() &&
                    context.observationConfidence >= 0.72f &&
                    context.observationAgeMinutes in 0L..(24L * 60L)
            }

        // Recent real events outrank generic idle prompts.
        if (game != null && context.lastGameResult == "player") {
            val gameName = gameArabic(game)
            val opening = listOfNotNull(
                absent,
                pick(seed, listOf(
                    "راجعت نتيجة جولتنا الأخيرة في $gameName.",
                    "ما زلت أتذكّر الجولة التي فزتَ فيها في $gameName.",
                    "لدي فكرة جديدة بعد مباراتنا الأخيرة في $gameName."
                ))
            ).joinToString(" ")
            val followup = pick(seed / 3L + 11L, listOf(
                "هذه المرة سأجرّب خطة مختلفة.",
                "هل تستطيع الفوز مرة أخرى إذا غيّرتُ أسلوبي؟",
                "أريد أن أختبر إن كانت خطتك ستنجح أمام حركة غير متوقعة."
            ))
            return EveGeneratedMessage(
                pick(seed + 5L, listOf("إيف تجهّز تحدّيًا جديدًا", "جولة أخرى؟", "فكرة بعد مباراتنا")),
                "$opening $followup افتح غرفة اللعب عندما يناسبك.",
                "recent_game_player_win"
            )
        }
        if (game != null && context.lastGameResult == "robot") {
            val gameName = gameArabic(game)
            val opening = listOfNotNull(
                absent,
                pick(seed + 7L, listOf(
                    "في آخر جولة من $gameName كانت النتيجة لصالحي.",
                    "احتفظت بنتيجة مباراتنا الأخيرة في $gameName.",
                    "لاحظت أن خطتي نجحت في آخر تحدٍّ من $gameName."
                ))
            ).joinToString(" ")
            val followup = pick(seed / 5L + 3L, listOf(
                "هل ستجرب استراتيجية مختلفة هذه المرة؟",
                "قد تتمكن من مفاجأتي بحركة جديدة.",
                "لنختبر هل أستطيع التكيّف إذا غيّرتَ طريقتك."
            ))
            return EveGeneratedMessage(
                pick(seed + 13L, listOf("إيف لديها تحدٍّ لك", "هل تغيّر خطتك؟", "لنختبر الجولة القادمة")),
                "$opening $followup",
                "recent_game_robot_win"
            )
        }

        if (label != null) {
            val item = labelArabic(label)
            val countPhrase = if (context.observationCount <= 1) {
                "رصدتُ $item في آخر جولة نظر."
            } else {
                "ظهر " + item + " في " + context.observationCount + " ملاحظات محفوظة عندي."
            }
            val reflection = pick(seed / 7L + context.observationCount, listOf(
                "في المرة القادمة سأحاول مقارنة تفاصيله بدل الاكتفاء باسمه.",
                "أريد أن أتحقق هل سأراه في المكان نفسه أم في سياق مختلف.",
                "قد تكون ملاحظة أخرى كافية لتمييزه عن شيء يشبهه.",
                "إن مررت به مجددًا، يمكننا اختبار ما إذا كان تصنيفي دقيقًا."
            ))
            return EveGeneratedMessage(
                pick(seed + 17L, listOf("ملاحظة من كاميرتي", "احتفظت بتفصيل صغير", "سؤال بعد جولتنا")),
                listOfNotNull(absent, countPhrase, reflection).joinToString(" "),
                "recent_visual_observation"
            )
        }

        if (absent != null) {
            val returnChallenge = when (context.mood.uppercase()) {
                "HAPPY", "FAMILIAR", "PLAYFUL" ->
                    pick(seed + 29L, listOf(
                        "هل نبدأ جولة شطرنج أو إكس-أو وأحاول هذه المرة أن أقرأ خطتك؟",
                        "لديّ تحدٍّ جديد: اختر الشطرنج أو إكس-أو ودعنا نرى إن كنت ستتفوق عليّ.",
                        "تعالَ نختبر جولة قصيرة؛ سأحاول تغيير أسلوبي بدل تكرار الحركات."
                    ))
                "SLEEPY" ->
                    pick(seed + 31L, listOf(
                        "عندما يناسبك، نلعب جولة هادئة من إكس-أو أو الشطرنج.",
                        "لا استعجال؛ غرفة اللعب جاهزة لجولة قصيرة عندما تعود."
                    ))
                else ->
                    pick(seed + 37L, listOf(
                        "هل نبدأ جولة شطرنج أو إكس-أو، أم تريد استكشاف شيء جديد؟",
                        "اختر الشطرنج أو إكس-أو من غرفة اللعب؛ سأستخدم نتائج جولاتنا السابقة لتكييف المستوى.",
                        "يمكنك العودة لجولة شطرنج أو إكس-أو؛ لديّ سجل نتيجتنا السابقة ومستوى التحدي الحالي."
                    ))
            }
            return EveGeneratedMessage(
                pick(seed + 41L, listOf("إيف لديها تحدٍّ جديد", "مرّ وقت منذ جولتنا", "هل نلعب جولة؟")),
                "$absent $returnChallenge",
                "absence_return_challenge"
            )
        }

        val mood = context.mood.uppercase()
        return when (mood) {
            "HAPPY", "FAMILIAR", "PLAYFUL" -> {
                val opening = pick(seed, listOf(
                    "أنا مستعدة لتجربة صغيرة معك.",
                    "خطرت لي فكرة لطيفة لاختبار ملاحظاتي.",
                    "لنحوّل الجولة القادمة إلى تحدٍّ خفيف."
                ))
                val question = pick(seed / 11L + 9L, listOf(
                    "هل تختار شيئًا في الغرفة لأحاول التعرّف عليه؟",
                    "هل نلعب جولة قصيرة ونرى إن كنت قد تكيفت مع أسلوبك؟",
                    "ما التفصيل الذي تريدني أن أركّز عليه في المرة القادمة؟"
                ))
                EveGeneratedMessage(pick(seed + 2L, listOf("إيف في مزاج مرح", "فكرة جديدة", "هل نجرّب شيئًا؟")), "$opening $question", "mood_warm")
            }
            "IRRITATED", "ANGRY" -> EveGeneratedMessage(
                pick(seed + 4L, listOf("استراحة قصيرة", "لنهدأ قليلًا", "إيف تحتاج مساحة صغيرة")),
                pick(seed / 13L, listOf(
                    "كثرة اللمسات السريعة أربكت إيقاعي؛ سأهدأ قليلًا، ثم يمكننا المتابعة بلمسة هادئة.",
                    "أحتاج إلى لحظة هدوء قبل التحدي التالي. سأكون جاهزة عندما تعود.",
                    "بدل تكرار الحركة نفسها، لنأخذ استراحة قصيرة ونجرّب طريقة ألطف لاحقًا."
                )),
                "mood_irritated"
            )
            "SLEEPY" -> EveGeneratedMessage(
                pick(seed + 6L, listOf("إيف تهدأ", "لحظة هدوء", "رسالة هادئة")),
                pick(seed / 17L, listOf(
                    "مرّ وقت هادئ. سأبقى ساكنة الآن؛ افتح التطبيق عندما تريد أن نكمل الاستكشاف.",
                    "لا يوجد شيء عاجل الآن. سأحتفظ بفضولي للجولة القادمة.",
                    "أخذت الأمور وقتًا هادئًا، لذا سأخفّف حركتي إلى أن نعود."
                )),
                "mood_sleepy"
            )
            "CONCERNED" -> EveGeneratedMessage(
                pick(seed + 8L, listOf("لدي ملاحظة غير مؤكدة", "أحتاج إلى تحقق", "سؤال صغير من إيف")),
                pick(seed / 19L, listOf(
                    "آخر تصنيف لم يكن واضحًا بما يكفي، لذلك لن أتعامل معه كحقيقة. يمكننا إعادة المحاولة عندما تفتح الكاميرا.",
                    "أفضل ألا أخمّن عندما تكون الرؤية غير واضحة. جولة أخرى بإضاءة أفضل قد تساعد.",
                    "لم أصل إلى نتيجة واثقة بعد. سأنتظر ملاحظة أوضح بدل أن أختلق جوابًا."
                )),
                "mood_concerned"
            )
            else -> {
                val timePrompt = when {
                    context.hourOfDay < 11 -> "ما الشيء الذي تريدني أن ألاحظه أولًا اليوم؟"
                    context.hourOfDay >= 21 -> "يمكننا إكمال الاستكشاف غدًا؛ ما أول شيء تريد اختباره؟"
                    else -> "هل تريد جولة استكشاف جديدة أم تحديًا قصيرًا في إحدى الألعاب؟"
                }
                val body = pick(seed, listOf(
                    "لا أملك ملاحظة جديدة موثوقة حتى الآن. $timePrompt",
                    "لم تظهر لي تجربة جديدة تستحق التنبيه بعد. $timePrompt",
                    "سأفضّل الانتظار بدل إرسال تنبيه بلا سبب واضح. $timePrompt"
                ))
                EveGeneratedMessage(pick(seed + 23L, listOf("سؤال من إيف", "إيف تنتظر تجربة", "فكرة للجولة القادمة")), body, "contextual_idle")
            }
        }
    }

    private fun gameArabic(name: String): String = when (name.trim().lowercase()) {
        "chess" -> "الشطرنج"
        "xo" -> "إكس-أو"
        "rps" -> "حجر ورقة مقص"
        "target" -> "التصويب"
        "memory" -> "اختبار الذاكرة"
        "reflex" -> "اختبار ردّ الفعل"
        else -> "اللعبة"
    }

    private fun labelArabic(label: String): String = when (label) {
        "banana" -> "موزة"
        "apple" -> "تفاحة"
        "orange" -> "برتقالة"
        "pizza" -> "بيتزا"
        "bread" -> "خبز"
        "cake" -> "كعكة"
        "cat", "kitten" -> "قطة"
        "dog", "puppy" -> "كلب"
        "bird" -> "طائر"
        "fish" -> "سمكة"
        "rabbit" -> "أرنب"
        "horse" -> "حصان"
        "person", "people", "human" -> "شخصًا"
        "face" -> "وجهًا"
        "plant" -> "نباتًا"
        "flower" -> "زهرة"
        "tree" -> "شجرة"
        "leaf" -> "ورقة نبات"
        "food", "fruit", "vegetable", "meal", "sandwich" -> "طعامًا"
        else -> label.take(40)
    }
}
