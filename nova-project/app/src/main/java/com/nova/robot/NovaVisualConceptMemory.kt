package com.nova.robot

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * Local memory for user-named visible concepts. It stores names and classifier labels only,
 * never camera frames. A generic label such as "car" cannot prove a specific brand such as Tesla.
 */
internal class NovaVisualConceptMemory(private val memory: NovaMindMemory) {
    private data class Concept(
        val name: String,
        val labels: Set<String>,
        val sightings: Int,
        val lastSightAt: Long
    )

    @Synchronized
    fun handleCommand(
        rawText: String,
        currentLabels: List<NovaLabelEvidence>,
        labelsAreFresh: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): String? {
        val command = normalize(rawText).trim().trimEnd('.', '!', '?', '؟')
        if (command in CLEAR_COMMANDS) {
            memory.write(NovaMemoryItem(KEY, "", nowMillis))
            return "مسحت محليًا أسماء الأشياء التي علّمتِني إياها."
        }
        if (command in LIST_COMMANDS) {
            val all = readAll()
            return if (all.isEmpty()) {
                "لم أحفظ أسماء أشياء بعد. وجّهي الكاميرا إلى الشيء ثم اكتبي: «علّمي هذا الشيء باسم سيارة»."
            } else {
                "الأشياء التي علّمتِني أسماءها محليًا: " +
                    all.joinToString("؛ ") { c ->
                        "«" + c.name + "» (" +
                            (if (c.sightings >= REQUIRED_SIGHTINGS) "تعرّف متكرر" else "ما زلت أتحقق") +
                            ")"
                    } + ". الذاكرة تقريبية وليست ضمانًا للتعرف الصحيح."
            }
        }

        val name = parseName(command) ?: return null
        if (!labelsAreFresh) {
            return "لم أحفظ الاسم بعد؛ شغّلي الكاميرا وانتظري مشهدًا واضحًا، ثم أعيدي: «علّمي هذا الشيء باسم " + name + "»."
        }
        val current = signature(currentLabels)
        if (current.isEmpty()) {
            return "لم أحفظ الاسم؛ التصنيفات المحلية الحالية لا تعطيني دليلًا بصريًا كافيًا."
        }

        val concepts = readAll().toMutableList()
        val index = concepts.indexOfFirst { normalize(it.name) == normalize(name) }
        val old = concepts.getOrNull(index)
        val updated = Concept(
            name = name,
            labels = (old?.labels.orEmpty() + current).take(MAX_LABELS),
            sightings = old?.sightings ?: 0,
            lastSightAt = old?.lastSightAt ?: 0L
        )
        if (index >= 0) concepts[index] = updated else concepts.add(updated)
        writeAll(concepts.takeLast(MAX_CONCEPTS), nowMillis)

        return if (hasSpecificNameToken(name) && !specificNameAppearsIn(name, current)) {
            "حفظت تعليمك عن «" + name + "» كمرجع محلي أولي، لكن التصنيفات الحالية لا تميّز الاسم المحدد بعد. «سيارة» وحدها لا تثبت أنها «تسلا». لن أخلط بينهما؛ سأنتظر علامة بصرية مميزة."
        } else {
            "حفظت «" + name + "» وملصقات الرؤية المحلية المرتبطة بالمشهد. سأقارنها في ملاحظات لاحقة، ولن أعتبر التعرف مستقرًا إلا بعد تطابق الدليل في " + REQUIRED_SIGHTINGS + " ملاحظات متباعدة."
        }
    }

    /** Counts spaced matching observations, not raw frames; repeated frames are not independent proof. */
    @Synchronized
    fun observe(labels: List<NovaLabelEvidence>, nowMillis: Long = System.currentTimeMillis()): String? {
        val observed = signature(labels)
        if (observed.isEmpty()) return null
        val candidate = readAll().mapNotNull { c ->
            matchScore(c, observed)?.let { c to it }
        }.maxByOrNull { it.second }?.first ?: return null

        var current = candidate
        if (candidate.lastSightAt == 0L ||
            (nowMillis >= candidate.lastSightAt && nowMillis - candidate.lastSightAt >= MIN_SIGHTING_GAP)
        ) {
            current = candidate.copy(
                sightings = (candidate.sightings + 1).coerceAtMost(REQUIRED_SIGHTINGS),
                lastSightAt = nowMillis
            )
            writeAll(readAll().map { if (normalize(it.name) == normalize(candidate.name)) current else it }, nowMillis)
        }
        if (current.sightings < REQUIRED_SIGHTINGS) return null
        return "أتعرف إلى «" + current.name + "» من ملاحظات بصرية محلية متكررة. هذا تعرّف تقريبي؛ قد أخطئ إذا تشابهت الأجسام أو تغيّرت زاوية الرؤية."
    }

    @Synchronized fun count(): Int = readAll().size

    @Synchronized
    fun clear(nowMillis: Long = System.currentTimeMillis()) {
        memory.write(NovaMemoryItem(KEY, "", nowMillis))
    }

    private fun matchScore(concept: Concept, observed: Set<String>): Int? {
        val overlap = concept.labels.intersect(observed)
        if (overlap.isEmpty()) return null
        val nameTokens = tokens(concept.name)
        val categories = nameTokens.filter { it in CATEGORY_TOKENS }
        val specific = nameTokens.filter { it !in CATEGORY_TOKENS && it !in STOP_WORDS }
        val observedTokens = observed.flatMap { tokens(it) }.toSet() + observed

        // Brand/model terms must be present in model evidence, not inferred from a generic class.
        if (specific.isNotEmpty() && specific.none { token -> aliases(token).any { it in observedTokens } }) return null
        // A background label alone must not match a named category such as a car or phone.
        if (categories.isNotEmpty() && categories.none { token -> aliases(token).any { it in observedTokens } }) return null
        return overlap.size * 10 + if (specific.isNotEmpty()) 5 else 0
    }

    private fun specificNameAppearsIn(name: String, labels: Set<String>): Boolean {
        val specific = tokens(name).filter { it !in CATEGORY_TOKENS && it !in STOP_WORDS }
        val seen = labels.flatMap { tokens(it) }.toSet() + labels
        return specific.isEmpty() || specific.any { token -> aliases(token).any { it in seen } }
    }

    private fun hasSpecificNameToken(name: String): Boolean =
        tokens(name).any { it !in CATEGORY_TOKENS && it !in STOP_WORDS }

    private fun signature(labels: List<NovaLabelEvidence>): Set<String> =
        labels.asSequence()
            .filter { it.confidence.isFinite() && it.confidence >= MIN_CONFIDENCE }
            .map { normalizeLabel(it.label) }
            .filter { it.length >= 2 }
            .distinct()
            .take(MAX_LABELS)
            .toSet()

    private fun parseName(command: String): String? {
        val prefixes = listOf(
            "علمي هذا الشيء باسم ",
            "علميني هذا الشيء باسم ",
            "احفظي هذا الشيء باسم ",
            "سجلي هذا الشيء باسم ",
            "علمي الشيء باسم "
        )
        val prefix = prefixes.firstOrNull { command.startsWith(it) } ?: return null
        return command.removePrefix(prefix).trim()
            .replace(Regex("[|,\\r\\n]"), " ")
            .replace(Regex("\\s+"), " ")
            .take(MAX_NAME_LENGTH)
            .takeIf { it.length in 2..MAX_NAME_LENGTH }
    }

    private fun readAll(): List<Concept> = memory.read(KEY).orEmpty().lineSequence().mapNotNull { row ->
        val fields = row.split("|", limit = 4)
        if (fields.size != 4) return@mapNotNull null
        val name = decode(fields[0]) ?: return@mapNotNull null
        val labels = fields[1].split(",").mapNotNull(::decode).toSet()
        val sightings = fields[2].toIntOrNull() ?: 0
        val lastSightAt = fields[3].toLongOrNull() ?: 0L
        if (name.isBlank() || labels.isEmpty()) null else Concept(name, labels, sightings, lastSightAt)
    }.toList().takeLast(MAX_CONCEPTS)

    private fun writeAll(concepts: List<Concept>, nowMillis: Long) {
        val payload = concepts.joinToString("\n") { c ->
            listOf(
                encode(c.name),
                c.labels.joinToString(",") { encode(it) },
                c.sightings.toString(),
                c.lastSightAt.toString()
            ).joinToString("|")
        }
        memory.write(NovaMemoryItem(KEY, payload, nowMillis))
    }

    private fun normalizeLabel(raw: String): String = raw.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun normalize(raw: String): String = raw.trim().lowercase(Locale.ROOT)
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ى', 'ي').replace('ـ', ' ')
        .replace(Regex("\\s+"), " ")

    private fun tokens(raw: String): Set<String> = normalize(raw)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() }
        .map { if (it.length > 3 && it.startsWith("ال")) it.removePrefix("ال") else it }
        .toSet()

    private fun aliases(raw: String): Set<String> {
        val token = if (raw.length > 3 && raw.startsWith("ال")) raw.removePrefix("ال") else raw
        return setOf(token) + when (token) {
            "سيارة", "سيارات", "car", "cars", "vehicle", "vehicles", "automobile" ->
                setOf("car", "cars", "vehicle", "vehicles", "automobile", "auto")
            "تسلا", "تيسلا" -> setOf("tesla")
            "شخص", "انسان", "person", "human" -> setOf("person", "human", "people")
            "كلب", "dog" -> setOf("dog", "dogs", "canine")
            "قطة", "قط", "cat" -> setOf("cat", "cats", "feline")
            "هاتف", "جوال", "phone", "mobile", "cell" -> setOf("phone", "mobile", "mobile phone", "cell phone", "cellphone")
            "دراجة", "bicycle", "bike" -> setOf("bicycle", "bike")
            "كتاب", "book" -> setOf("book", "books")
            "زجاجة", "قارورة", "bottle" -> setOf("bottle", "bottles")
            "كوب", "cup" -> setOf("cup", "cups", "mug")
            "كرسي", "chair" -> setOf("chair", "chairs", "seat")
            "ازرق", "زرقاء", "blue" -> setOf("blue")
            "كهربائية", "كهربائي", "electric", "electrical" -> setOf("electric", "electrical")
            else -> emptySet()
        }
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? =
        runCatching { String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8) }.getOrNull()

    private companion object {
        const val KEY = "nova_visual_concepts_v1"
        const val MAX_CONCEPTS = 24
        const val MAX_LABELS = 6
        const val MAX_NAME_LENGTH = 48
        const val MIN_CONFIDENCE = 0.50f
        const val MIN_SIGHTING_GAP = 2_500L
        const val REQUIRED_SIGHTINGS = 3
        val CATEGORY_TOKENS = setOf(
            "سيارة", "سيارات", "car", "cars", "vehicle", "vehicles", "automobile",
            "شخص", "انسان", "person", "human", "dog", "كلب", "cat", "قطة", "قط",
            "هاتف", "جوال", "phone", "mobile", "cell", "دراجة", "bicycle", "bike",
            "كتاب", "book", "زجاجة", "قارورة", "bottle", "كوب", "cup", "كرسي", "chair"
        )
        val STOP_WORDS = setOf("هذا", "هذه", "شيء", "الشيء", "الذي", "التي", "اراه", "اسم", "باسم")
        val LIST_COMMANDS = setOf(
            "ماذا تعلمت عن الاشياء", "ما الاشياء التي تتذكرينها", "اذكري الاشياء التي علمتك اياها",
            "اذكري اسماء الاشياء المحفوظة", "ما اسماء الاشياء التي علمتك اياها"
        )
        val CLEAR_COMMANDS = setOf(
            "امسحي ذاكرة الاشياء", "امسحي الاشياء المحفوظة", "احذفي ذاكرة الاشياء التي علمتك اياها"
        )
    }
}
