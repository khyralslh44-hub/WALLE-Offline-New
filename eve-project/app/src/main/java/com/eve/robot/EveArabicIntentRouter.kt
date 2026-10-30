package com.eve.robot

import java.util.Locale

/**
 * Routes natural Arabic utterances to the conditioning intent used by EveLM.
 * The intent router is intentionally separate from the neural decoder: the GRU generates
 * the actual words, while this layer supplies a small, explainable dialogue context.
 */
internal object EveArabicIntentRouter {
    data class Route(
        val intent: String,
        val slots: Map<String, String>,
        val moodHint: String,
        val intentHint: String,
        val confidence: Float
    )

    fun route(text: String, context: LocalLlmContext): Route {
        val q = normalize(text)
        val slots = linkedMapOf<String, String>()
        val mood = context.currentMood.uppercase(Locale.ROOT)

        fun has(pattern: String): Boolean = Regex(pattern).containsMatchIn(q)
        fun capture(pattern: String): String? =
            Regex(pattern).find(q)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

        val intent = when {
            has("^(مرحبا|اهلا|هلا|هاي|السلام عليكم|صباح الخير|مساء الخير|يا هلا)") -> "greet"
            has("(احبك|بحبك|اشتقت لك|اشتقت اليك)") -> "love"
            has("(غبيه|اسكتي|اكرهك|انت سيئه|انتي مزعجه|انت مزعجه)") -> "insult"
            has("(شكرا|شكرا لك|ممتن|تسلمين|يعطيك العافيه|اشكرك)") -> "thanks"
            has("(انتي جميله|انت جميله|انتي ذكيه|انت ذكي|انتي رائعه|احسنتي|شاطره|مدهشه)") -> "praise"
            has("(انا حزين|انا زعلان|متضايق|لست بخير|اشعر بالوحده|قلق|مكتئب|مضغوط)") -> "feel_neg"
            has("(انا سعيد|انا مبسوط|فرحان|يومي رائع|كل شيء تمام|انا بخير)") -> "feel_pos"
            has("(انا تعبان|انا متعب|مرهق|نعسان|اريد ان انام|منهك)") -> "tired"
            has("(كيف حالك|شلونك|كيفك|اخبارك|كيف الحال|كيف تشعرين)") -> "how"
            has("(ماذا تفعلين|ماذا تفعلين الان|بماذا تفكرين|ايش تسوين)") -> "doing"
            has("(ماذا ترين|ماذا ترى|ماذا تشاهدين|ما الذي تريه|وصفي لي ما حولك|ماذا يوجد امامك|ما هذا)") -> "see"
            has("(ما اسمك|من انت|عرفيني بنفسك|من تكونين|ما هي ايف)") -> "who"
            has("(ماذا تعرفين عني|هل تتذكرين|ماذا تذكرين|ما الذي تتذكرينه)") -> "memory"
            has("(هيا نلعب|هل نلعب|اريد ان العب|العبي معي|لنلعب|هل تريدين اللعب|شطرنج)") -> "play"
            has("(غني لي|دندني|اريد موسيقى|شغلي لحنا|هل تغنين)") -> "music"
            has("(اشعر بالملل|انا ممل|لا يوجد شيء افعله|مللت|زهقان)") -> "bored"
            has("(احكي لي نكته|قولي نكته|اضحكيني|هل تعرفين نكته)") -> "joke"
            has("(مع السلامه|الى اللقاء|تصبحين على خير|وداعا|اراك لاحقا|سلام)") -> "bye"
            has("(كيف الجو|هل الجو بارد|هل تمطر|كيف الطقس|الطقس اليوم)") -> "weather"
            has("(كم الساعه|ما الوقت الان|كم الوقت)") -> "time"
            has("(ساعديني|احتاج مساعده|هل تستطيعين مساعدتي|اريد مساعده|اشرح لي)") -> "help"
            has("(لدي مفاجاه|عندي خبر|تخيلي ماذا حدث|لدي شيء اخبرك به)") -> "surprise"
            has("(لماذا انت|ما بك|لماذا تبدين هكذا|ما الذي بك)") -> "why"
            has("(لا احب|اكره|لا يعجبني|ما احب) .+") -> "dislike"
            has("(احب|بحب|يعجبني) .+") -> "like"
            has("(اسمي|انا اسمي|يمكنك مناداتي) .+") -> "name"
            has("(اخي|اختي|امي|ابي|ابوي|جدي|جدتي|صديقي|صديقتي|زوجتي|زوجي|ابني|بنتي)") -> "family"
            else -> "other"
        }

        when (intent) {
            "name" -> {
                capture("(?:اسمي|انا اسمي|يمكنك مناداتي)\\s+(.+)")?.let { slots["name"] = it }
            }
            "like", "dislike" -> {
                capture("(?:احب|بحب|يعجبني|لا احب|اكره|لا يعجبني|ما احب)\\s+(.+)")?.let {
                    if (it !in setOf("ك", "كثيرا")) slots["like"] = it.take(60)
                }
            }
            "family" -> {
                val relation = listOf("اخي", "اختي", "امي", "ابي", "ابوي", "جدي", "جدتي", "صديقي", "صديقتي", "زوجتي", "زوجي", "ابني", "بنتي")
                    .firstOrNull { q.contains(it) }
                val name = relation?.let { capture(Regex.escape(it) + "\\s+(\\S+)") }
                if (relation != null && name != null) slots["rel"] = relation + " " + name
                else if (relation != null) slots["rel"] = relation
            }
            else -> Unit
        }

        val visibleLabel = context.latestVisionLabel
            ?.takeIf { context.visionObservationAgeMs in 0L..15_000L && context.visionConfidence >= 0.55f }
        if (visibleLabel != null) slots["thing"] = arabicLabel(visibleLabel)

        val moodHint = when (intent) {
            "greet", "love", "praise", "thanks", "feel_pos" -> "happy"
            "feel_neg", "insult" -> "concerned"
            "tired", "bye" -> "sleepy"
            "play", "joke", "music" -> "playful"
            "see", "doing", "memory", "surprise", "why" -> "curious"
            else -> when (mood) {
                "ANGRY" -> "angry"
                "IRRITATED" -> "annoyed"
                "SLEEPY" -> "sleepy"
                "PLAYFUL" -> "playful"
                "HAPPY" -> "joy"
                "CURIOUS" -> "curious"
                else -> "calm"
            }
        }
        val intentHint = when (intent) {
            "see", "doing", "memory", "surprise", "why" -> "observe"
            "play", "joke", "music" -> "play"
            "love", "greet", "praise", "thanks", "family" -> "connect"
            "bye", "tired" -> "rest"
            else -> "focus"
        }
        val confidence = if (intent == "other") 0.48f else 0.84f
        return Route(intent, slots, moodHint, intentHint, confidence)
    }

    fun normalize(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace(Regex("[\\u064B-\\u065F\\u0670ـ]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه')
            .replace('ؤ', 'و').replace('ئ', 'ي')
            .replace(Regex("[^\\p{L}\\p{N}\\s<>]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
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
        "cup" -> "الكوب"
        "bottle" -> "الزجاجة"
        "phone", "cell phone", "mobile phone" -> "الهاتف"
        "laptop" -> "الحاسوب المحمول"
        "book" -> "الكتاب"
        "chair" -> "الكرسي"
        "car" -> "السيارة"
        "bicycle" -> "الدراجة"
        "person", "people", "human", "face", "man", "woman", "child" -> "شخص"
        else -> label.replace('_', ' ').take(40)
    }
}
