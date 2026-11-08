package com.eve.robot

import java.util.Locale

/**
 * Compact Arabic text normalization authored for EVE.
 *
 * This is a linguistic front-end, not a pretrained model or a full Arabic stemmer.
 * It keeps the original word and adds conservative clitic/inflection variants so
 * dialect and spelling differences can reach the same small intent vocabulary.
 */
internal object EveArabicTextUnderstanding {
    private val dialectAliases = mapOf(
        "شلون" to "كيف",
        "شلونك" to "كيفك",
        "شخبارك" to "كيف حالك",
        "شنو" to "ما",
        "شن" to "ما",
        "شنية" to "ما",
        "قداش" to "كم",
        "قداشك" to "كم",
        "ايش" to "ما",
        "وش" to "ما",
        "شو" to "ما",
        "تشوفين" to "ترين",
        "تشوف" to "ترى",
        "تسمعين" to "تسمعين",
        "تتكلمين" to "تتحدثين",
        "تحكين" to "تتحدثين",
        "ليه" to "لماذا",
        "علاش" to "لماذا",
        "ليش" to "لماذا",
        "ازاي" to "كيف",
        "كيفاش" to "كيف",
        "وين" to "اين",
        "فين" to "اين",
        "امتى" to "متى",
        "متى" to "متى",
        "هسه" to "الان",
        "توا" to "الان",
        "الحين" to "الان",
        "دلوقتي" to "الان",
        "هلق" to "الان",
        "دحين" to "الان",
        "ابغى" to "اريد",
        "نبي" to "اريد",
        "نبو" to "نريد",
        "تبي" to "تريد",
        "تبو" to "تريدون",
        "ابي" to "اريد",
        "بغيت" to "اريد",
        "عايز" to "اريد",
        "عاوزه" to "اريد",
        "عاوز" to "اريد",
        "بدي" to "اريد",
        "بدّي" to "اريد",
        "مو" to "ليس",
        "مش" to "ليس",
        "مب" to "ليس",
        "زين" to "جيد",
        "باهي" to "جيد",
        "باهية" to "جيدة",
        "كويس" to "جيد",
        "هلبة" to "كثير",
        "هكي" to "هكذا",
        "هكيكا" to "هكذا",
        "تمام" to "جيد",
        "كويس" to "جيد",
        "حلو" to "جميل",
        "طفشان" to "مللت",
        "زهقان" to "مللت",
        "مبسوط" to "سعيد",
        "فرحان" to "سعيد",
        "زعلان" to "حزين",
        "متضايق" to "حزين",
        "مضايق" to "حزين",
        "تعبان" to "متعب",
        "نعسان" to "متعب",
        "مرهق" to "متعب",
        "مدايق" to "حزين",
        "مبسوطه" to "سعيده",
        "فرحانه" to "سعيده",
        "زعلانه" to "حزينه"
    )

    private val removablePrefixes = listOf("وال", "بال", "كال", "فال", "لل", "ال", "و", "ف", "ب", "ك", "ل", "س")
    private val removableSuffixes = listOf(
        "كما", "هما", "كن", "كم", "هن", "هم", "ها", "نا", "ني",
        "هما", "ية", "يات", "ات", "ون", "ين", "ان", "وا", "ه", "ك", "ي"
    )

    fun normalize(raw: String): String {
        val cleaned = raw.lowercase(Locale.ROOT)
            .replace(Regex("[\\u064B-\\u065F\\u0670ـ]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
            .replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي')
            .replace('ة', 'ه').replace('ی', 'ي').replace('ک', 'ك')
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return ""
        return cleaned.split(' ').flatMap { token ->
            val alias = dialectAliases[token]
            if (alias == null) listOf(token) else alias.split(' ')
        }.joinToString(" ")
    }

    /**
     * Returns the observed token plus at most a few conservative Arabic clitic variants.
     * Variants are used for feature matching only; the user's original text is preserved.
     */
    fun variants(rawToken: String): Set<String> {
        val token = normalize(rawToken).takeIf { it.isNotBlank() } ?: return emptySet()
        val result = linkedSetOf(token)
        var current = token

        // Remove one attached conjunction/preposition/article at a time.
        for (prefix in removablePrefixes) {
            if (current.startsWith(prefix) && current.length - prefix.length >= 3) {
                result += current.removePrefix(prefix)
                break
            }
        }

        // Arabic object/possessive pronouns and common inflection endings.
        for (suffix in removableSuffixes) {
            if (current.endsWith(suffix) && current.length - suffix.length >= 3) {
                result += current.removeSuffix(suffix)
                break
            }
        }

        // A light verb-person normalization: ت/ي/ن/ا are common imperfect prefixes.
        val candidates = result.toList()
        for (candidate in candidates) {
            if (candidate.length >= 5 && candidate.first() in setOf('ا', 'ت', 'ي', 'ن')) {
                val stem = candidate.drop(1)
                if (stem.length >= 4) result += stem
            }
        }
        return result
    }

    fun tokens(raw: String): List<String> =
        normalize(raw).split(' ').filter { it.isNotBlank() }

    fun featureTokens(raw: String): Set<String> =
        tokens(raw).flatMapTo(linkedSetOf()) { variants(it) }
}
