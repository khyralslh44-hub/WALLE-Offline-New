package com.eve.robot

/**
 * Small, deterministic Arabic command layer placed after offline ASR and before EVE's mind.
 * It contains no speech model and makes no network calls. Only explicit phrase matches
 * become actions; unknown or ambiguous utterances are left for the normal conversation path.
 */
internal object ArabicCommandEar {
    enum class Intent { WAKE, LOOK, QUIET, GREET, PRESENCE, CALM, STOP_EXPLORING }

    data class Match(val intent: Intent, val canonicalPhrase: String, val confidence: Float)

    fun normalize(input: String): String = input.trim()
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ى', 'ي').replace('ة', 'ه')
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val phrases: Map<Intent, List<String>> = linkedMapOf(
        Intent.WAKE to listOf("يا ايف", "ايف"),
        Intent.LOOK to listOf("انظري", "انظري حولك", "ماذا ترين", "شوفي", "شاهدي"),
        Intent.QUIET to listOf("توقفي عن الكلام", "اسكتي", "اصمتي", "لا تتكلمي"),
        Intent.GREET to listOf("صباح الخير", "مساء الخير", "مرحبا ايف", "اهلا ايف"),
        Intent.PRESENCE to listOf("هل انت هنا", "ايف هل تسمعينني", "هل تسمعينني"),
        Intent.CALM to listOf("اهدئي", "خذي نفسا", "استرخي"),
        Intent.STOP_EXPLORING to listOf("اوقفي الاستكشاف", "توقفي عن الاستكشاف")
    )

    fun classify(raw: String): Match? {
        val text = normalize(raw)
        if (text.isBlank()) return null
        for ((intent, variants) in phrases) {
            val phrase = variants.firstOrNull { text == it } ?: continue
            return Match(intent, phrase, 1.0f)
        }
        return null
    }
}
