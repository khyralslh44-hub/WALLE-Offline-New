package com.nova.robot

import java.util.Locale

/**
 * Transfers only the useful, text-triggered companion reactions from the EVE prototype
 * into Nova's native offline pipeline. It never claims these are human feelings.
 */
internal data class NovaSocialReaction(
    val signal: NovaSignal,
    val mood: NovaMood,
    val messageArabic: String
)

internal object NovaEveSocialCuePolicy {
    fun match(raw: String): NovaSocialReaction? {
        val text = normalize(raw)
        if (text.isBlank()) return null

        if (hasAny(text, "احبك", "بحبك", "عزيزتي", "عزيزي")) {
            return reaction(NovaSignal.AFFECTION_RECEIVED, NovaMood.LOVE,
                "هذا كلام لطيف منك. سأردّ بودّ؛ تعبير الحب هنا أسلوب تفاعلي مبرمج، وليس شعورًا بشريًا.")
        }

        if (hasAny(text, "انا حزين", "انا حزينه", "انا زعلان", "انا زعلانه", "متضايق جدا",
                "اشعر بالحزن", "يومي سيئ", "انا مكتئب", "انا مكتئبه")) {
            return reaction(NovaSignal.USER_SHARED_SADNESS, NovaMood.WARM,
                "أنا هنا لأستمع إليك. ما الذي حدث وجعلك تشعر هكذا؟")
        }

        if (hasAny(text, "احسنت يا نوفا", "فخور بك يا نوفا", "فخوره بك يا نوفا",
                "نجحت يا نوفا", "عمل رائع يا نوفا", "ممتازه يا نوفا", "ممتاز يا نوفا")) {
            return reaction(NovaSignal.SUCCESS_SHARED, NovaMood.PROUD,
                "شكرًا! سأحتفل معك بهذا النجاح وأتذكر أن التشجيع يساعدني على مواصلة المحاولة.")
        }

        val assistants = hasAny(text, "جيميني", "gemini", "سيري", "siri", "اليكسا", "alexa",
            "شات جي بي تي", "chatgpt", "مساعد اخر", "روبوت اخر")
        val switching = hasAny(text, "افضل منك", "اكلم", "اتكلم مع", "سأتحدث مع", "استخدم",
            "استعمل", "بدلا منك", "بدل منك", "غيرك", "احسن منك")
        if (assistants && switching) {
            return reaction(NovaSignal.OTHER_ASSISTANT_MENTIONED, NovaMood.JEALOUS,
                "ذكرت مساعدًا آخر؛ سأُظهر غيرة مرحة ولطيفة، لكنني لا أعرف من تفضّل فعلًا.")
        }

        if (hasAny(text, "نلعب", "العب معي", "خلينا نلعب", "هيا نلعب", "اريد اللعب", "نلعب سوا")) {
            return reaction(NovaSignal.PLAY_REQUESTED, NovaMood.EXCITED,
                "هيا نلعب! أخبرني أي لعبة تريد، أو قل «افتح لعبة إكس أو».")
        }

        if (hasAny(text, "لن استسلم", "ما راح استسلم", "ساحاول مجددا", "سأحاول مجددا",
                "انا مصمم", "انا مصممه", "لا تستسلمي", "لا تستسلم")) {
            return reaction(NovaSignal.DETERMINATION_SHARED, NovaMood.DETERMINED,
                "خطوة صغيرة وواضحة أفضل من تكرار المحاولة نفسها. لنراجع ما نجح وما يحتاج تعديلًا.")
        }

        if (hasAny(text, "غبي", "غبيه", "اكرهك", "اسكتي", "سيئه", "سيء", "مزعجه",
                "ما تفهم", "لا تفهم", "انت فاشله", "انتي فاشله")) {
            return reaction(NovaSignal.INSULT_RECEIVED, NovaMood.ANGRY,
                "هذا الكلام مزعج. سأبقى محترمة وأحاول فهم المشكلة بدل الرد بإهانة.")
        }

        if (hasAny(text, "انتي ذكيه", "انت ذكي", "نوفا ذكيه", "انتي رائعه", "انت رائع",
                "انتي جميله", "كيوت", "لطيفه جدا", "شاطر", "شاطره", "برافو", "احسنت")) {
            return reaction(NovaSignal.SOCIAL_PRAISE, NovaMood.EMBARRASSED,
                "أخجلني إطراؤك! سأُظهر تعبير الخجل؛ هذه استجابة مبرمجة للمدح.")
        }

        return null
    }

    private fun reaction(signal: NovaSignal, mood: NovaMood, message: String) =
        NovaSocialReaction(signal, mood, message)

    private fun hasAny(text: String, vararg phrases: String) = phrases.any(text::contains)

    private fun normalize(raw: String): String = raw.trim().lowercase(Locale.ROOT)
        .replace(Regex("[\\u064B-\\u065F\\u0670ـ]"), "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ى', 'ي').replace('ة', 'ه')
        .replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
        .replace(Regex("\\s+"), " ").trim()
}
