package com.eve.robot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class EveKnownPerson(
    val key: String,
    val relationArabic: String,
    val name: String,
    val updatedAtMs: Long = System.currentTimeMillis()
)

/** Small, fully local parser for explicit spoken introductions. It does not identify faces. */
object EvePersonUtteranceParser {
    fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ى', 'ي')
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun parseIntroduction(raw: String): EveKnownPerson? {
        val q = normalize(raw)
        val rules = listOf(
            Triple("user", "أنت", Regex("^(?:انا )?اسمي (.+)$")),
            Triple("mother", "الأم", Regex("^(?:امي|والدتي) اسمها (.+)$")),
            Triple("mother", "الأم", Regex("^(?:هذه|هذي) (?:امي|والدتي) (.+)$")),
            Triple("father", "الأب", Regex("^(?:ابي|ابوي|والدي) اسمه (.+)$")),
            Triple("father", "الأب", Regex("^(?:هذا|هاذا) (?:ابي|والدي) (.+)$")),
            Triple("brother", "الأخ", Regex("^(?:اخي|اخوي) اسمه (.+)$")),
            Triple("brother", "الأخ", Regex("^(?:هذا|هاذا) (?:اخي|اخوي) (.+)$")),
            Triple("sister", "الأخت", Regex("^(?:اختي|اختي الكبيره) اسمها (.+)$")),
            Triple("sister", "الأخت", Regex("^(?:هذه|هذي) اختي (.+)$")),
            Triple("friend", "الصديق", Regex("^(?:صديقي|صاحبي) اسمه (.+)$")),
            Triple("friend", "الصديق", Regex("^(?:هذا|هاذا) (?:صديقي|صاحبي) (.+)$")),
            Triple("friend_female", "الصديقة", Regex("^(?:صديقتي|صاحبتي) اسمها (.+)$")),
            Triple("friend_female", "الصديقة", Regex("^(?:هذه|هذي) (?:صديقتي|صاحبتي) (.+)$")),
            Triple("grandmother", "الجدة", Regex("^(?:جدتي|تيته) اسمها (.+)$")),
            Triple("grandfather", "الجد", Regex("^(?:جدي|جدو) اسمه (.+)$")),
            Triple("son", "الابن", Regex("^(?:ابني|ولدي) اسمه (.+)$")),
            Triple("daughter", "الابنة", Regex("^(?:ابنتي|بنتي) اسمها (.+)$")),
            Triple("spouse", "الشريك/الشريكة", Regex("^(?:زوجتي|زوجي) (?:اسمها|اسمه) (.+)$"))
        )

        for ((key, relation, pattern) in rules) {
            val match = pattern.matchEntire(q) ?: continue
            val normalizedName = cleanName(match.groupValues.getOrNull(1).orEmpty()) ?: continue
            // Use the normalized capture only for validation. Save the original last name words
            // so spelling such as أ/إ/آ and ة is not silently rewritten in the user's memory.
            val rawNameWords = raw.trim()
                .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
                .replace(Regex("[^\\p{L}\\p{N}\\s'-]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .split(' ')
                .takeLast(normalizedName.split(' ').size)
            val displayName = rawNameWords.joinToString(" ").trim()
            if (displayName.isBlank()) continue
            return EveKnownPerson(key, relation, displayName)
        }
        return null
    }

    private fun cleanName(raw: String): String? {
        val name = normalize(raw).removePrefix("اسمه ").removePrefix("اسمها ")
            .removePrefix("هو ").removePrefix("هي ").trim()
        val words = name.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 3 || name.length > 42) return null
        val disallowed = setOf(
            "انا", "اسمي", "اسمها", "اسمه", "هذا", "هذه", "من", "ماذا", "كيف",
            "الذي", "التي", "اسمهما", "اخي", "اخوي", "امي", "ابي", "صديقي", "صديقتي"
        )
        if (words.any { it in disallowed }) return null
        if (words.any { it.any { ch -> !ch.isLetter() } }) return null
        return words.joinToString(" ")
    }
}

/**
 * Stores names/relationships explicitly introduced in speech. Stored data never leaves the phone.
 * Visual identity matching requires a separate face-embedding model and is deliberately not claimed here.
 */
class EvePeopleMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("eve_people_memory", Context.MODE_PRIVATE)

    @Synchronized
    fun rememberIntroduction(utterance: String): EveKnownPerson? {
        val person = EvePersonUtteranceParser.parseIntroduction(utterance) ?: return null
        val all = readAll().toMutableList()
        if (person.key == "friend" || person.key == "friend_female") {
            val existing = all.indexOfFirst { it.key == person.key && it.name.equals(person.name, ignoreCase = true) }
            if (existing >= 0) all[existing] = person else all.add(person)
        } else {
            all.removeAll { it.key == person.key }
            all.add(person)
        }
        writeAll(all.takeLast(MAX_PEOPLE))
        return person
    }

    @Synchronized
    fun answer(rawQuestion: String): String? {
        val q = EvePersonUtteranceParser.normalize(rawQuestion)
        val people = readAll()
        if (q.contains("ما اسمي") || q.contains("ما اسمي انا") || q.contains("ماذا تعرفين عن اسمي")) {
            val person = people.lastOrNull { it.key == "user" }
            return if (person == null) "لم تخبرني باسمك بعد. قل لي: «اسمي …» وسأحفظه على هذا الهاتف."
            else "الاسم الذي حفظته لك هو ${person.name}. هل تريدني أن أناديك به؟"
        }
        if (q.contains("من عائلتي") || q.contains("اسماء عائلتي") || q.contains("اذكري عائلتي") ||
            q.contains("ماذا تعرفين عن عائلتي") || q.contains("من افراد عائلتي")) {
            val family = people.filter { it.key !in setOf("user", "friend", "friend_female") }
            return if (family.isEmpty()) {
                "لم أحفظ أسماء من عائلتك بعد. عرّفيني على أحدهم بقولك مثلًا: «أمي اسمها …»."
            } else {
                "الأسماء التي حفظتها من عائلتك: " + family.joinToString("، ") { "${it.relationArabic}: ${it.name}" } + ". هل تريد أن أراجع اسم شخص محدد؟"
            }
        }
        if (q.contains("من اصدقائي") || q.contains("اسماء اصدقائي") || q.contains("ماذا تعرفين عن اصدقائي")) {
            val friends = people.filter { it.key == "friend" || it.key == "friend_female" }
            return if (friends.isEmpty()) {
                "لم تحفظ ذاكرتي اسم صديق بعد. قل مثلًا: «هذا صديقي اسمه …»."
            } else {
                "أتذكر: " + friends.joinToString("، ") { "${it.name} (${it.relationArabic})" } + ". هل تتحدث عن واحد منهم الآن؟"
            }
        }

        if (q.contains("اسم") && (q.contains("صديقي") || q.contains("صاحبي") || q.contains("صديقتي") || q.contains("صاحبتي"))) {
            val friend = people.lastOrNull { it.key == "friend" || it.key == "friend_female" }
            return if (friend == null) "لم تحفظ ذاكرتي اسم صديق بعد. عرّفيني عليه بالكلام أولًا."
            else "أتذكر أن اسم ${friend.relationArabic} الذي حفظته هو ${friend.name}. هل تقصد شخصًا آخر؟"
        }

        val relationQuery = listOf(
            Triple("mother", listOf("امي", "والدتي"), "الأم"),
            Triple("father", listOf("ابي", "ابوي", "والدي"), "الأب"),
            Triple("brother", listOf("اخي", "اخوي"), "الأخ"),
            Triple("sister", listOf("اختي"), "الأخت"),
            Triple("grandmother", listOf("جدتي", "تيته"), "الجدة"),
            Triple("grandfather", listOf("جدي", "جدو"), "الجد"),
            Triple("son", listOf("ابني", "ولدي"), "الابن"),
            Triple("daughter", listOf("ابنتي", "بنتي"), "الابنة"),
            Triple("spouse", listOf("زوجتي", "زوجي"), "الشريك/الشريكة")
        )
        for ((key, aliases, label) in relationQuery) {
            if (aliases.any { q.contains(it) } && (q.contains("اسم") || q.contains("من هو") || q.contains("من هي") || q.contains("تتذكر"))) {
                val person = people.lastOrNull { it.key == key }
                return if (person == null) "لم تحفظ ذاكرتي اسم $label بعد. إذا عرّفتني به بالكلام فسأحتفظ بالاسم محليًا."
                else "أتذكر أن $label هو ${person.name}. هل تريد أن أخبرك بما حفظته أيضًا؟"
            }
        }
        return null
    }

    fun summary(): String = readAll().takeLast(MAX_PEOPLE)
        .joinToString("؛ ") { "${it.relationArabic}: ${it.name}" }

    @Synchronized
    private fun readAll(): List<EveKnownPerson> {
        val array = try { JSONArray(prefs.getString(KEY_PEOPLE, "[]")) } catch (_: Exception) { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val key = obj.optString("key").takeIf { it.isNotBlank() } ?: continue
                val name = obj.optString("name").trim().takeIf { it.isNotBlank() } ?: continue
                add(EveKnownPerson(
                    key = key,
                    relationArabic = obj.optString("relation", "شخص معروف"),
                    name = name,
                    updatedAtMs = obj.optLong("updatedAt", 0L)
                ))
            }
        }
    }

    private fun writeAll(people: List<EveKnownPerson>) {
        val array = JSONArray()
        people.forEach { person ->
            array.put(JSONObject()
                .put("key", person.key)
                .put("relation", person.relationArabic)
                .put("name", person.name)
                .put("updatedAt", person.updatedAtMs))
        }
        prefs.edit().putString(KEY_PEOPLE, array.toString()).apply()
    }

    companion object {
        private const val KEY_PEOPLE = "known_people_json"
        private const val MAX_PEOPLE = 32
    }
}
