package com.nova.robot

/** One image-label hypothesis from the local bundled image classifier. */
data class NovaLabelEvidence(val label: String, val confidence: Float)

/**
 * Conservative formatter for coarse single-frame image labels.
 * ML Kit image labels are scene hypotheses, not object boxes; when a face detector finds a person,
 * generic animal labels are suppressed to avoid describing that face as a dog/cat.
 */
object NovaVisionEvidence {
    private val animalTerms = setOf(
        "animal", "animals", "dog", "dogs", "cat", "cats", "puppy", "puppies", "kitten", "kittens",
        "canine", "feline", "mammal", "pet", "pets", "bird", "birds", "horse", "cow", "sheep",
        "goat", "rabbit", "hamster", "rodent", "reptile", "wildlife", "livestock",
        "golden retriever", "labrador", "german shepherd", "poodle", "bulldog", "husky", "beagle",
        "persian cat", "siamese cat", "tabby"
    )
    private val translations = mapOf(
        "chair" to "كرسي",
        "table" to "طاولة",
        "cup" to "كوب",
        "mug" to "كوب",
        "bottle" to "زجاجة",
        "food" to "طعام",
        "fruit" to "فاكهة",
        "banana" to "موز",
        "apple" to "تفاحة",
        "orange" to "برتقال",
        "pizza" to "بيتزا",
        "bread" to "خبز",
        "sandwich" to "شطيرة",
        "laptop" to "حاسوب محمول",
        "computer" to "حاسوب",
        "mobile phone" to "هاتف",
        "cell phone" to "هاتف",
        "phone" to "هاتف",
        "television" to "تلفاز",
        "monitor" to "شاشة",
        "book" to "كتاب",
        "plant" to "نبتة",
        "flower" to "زهرة",
        "car" to "سيارة",
        "bicycle" to "دراجة",
        "person" to "شخص",
        "human" to "شخص",
        "outdoor" to "مشهد خارجي",
        "indoor" to "مشهد داخلي",
        "room" to "غرفة",
        "furniture" to "أثاث",
        "clothing" to "ملابس",
        "shoe" to "حذاء",
        "water" to "ماء",
        "coffee" to "قهوة",
        "tea" to "شاي",
        "sky" to "السماء",
        "sun" to "الشمس",
        "sunlight" to "ضوء الشمس",
        "daylight" to "ضوء النهار",
        "daytime" to "النهار",
        "night" to "الليل",
        "nighttime" to "الليل",
        "cloud" to "سحب",
        "cloudy" to "غائم",
        "rain" to "مطر",
        "rainy" to "ممطر",
        "snow" to "ثلج",
        "snowing" to "تساقط الثلج",
        "weather" to "الطقس",
        "eating" to "تناول الطعام",
        "person eating" to "شخص يتناول الطعام",
        "chewing" to "مضغ",
        "chewing gum" to "مضغ العلكة",
        "gum" to "علكة",
        "exercise" to "تمرين",
        "exercising" to "يمارس الرياضة",
        "running" to "جري",
        "walking" to "مشي",
        "sport" to "رياضة",
        "sports" to "رياضة",
        "fitness" to "لياقة",
        "drinking" to "شرب"
    )

    fun summarize(faceFound: Boolean, labels: List<NovaLabelEvidence>): String {
        val safeLabels = labels.asSequence()
            .filter { it.confidence.isFinite() && it.confidence >= MIN_LABEL_CONFIDENCE }
            .map { it.copy(label = normalize(it.label)) }
            .filter { it.label.isNotBlank() && it.label != "person" && it.label != "human" }
            .filterNot { faceFound && isAnimalLabel(it.label) }
            .distinctBy { it.label }
            .sortedByDescending { it.confidence }
            .take(2)
            .mapNotNull { evidence ->
                translations[evidence.label]?.let { "$it (${(evidence.confidence.coerceIn(0f, 1f) * 100).toInt()}٪)" }
            }
            .toList()

        return when {
            faceFound && safeLabels.isNotEmpty() ->
                "أرى أمامي شخصًا، وقد يكون في محيطي أيضًا: ${safeLabels.joinToString("، ")}. هذه احتمالات بصرية تقريبية، ولا تكفي وحدها لمعرفة هوية الشخص أو شعوره."
            faceFound ->
                "أرى أمامي شخصًا، لكنني لم أتعرف إلى هويته ولا أستطيع تأكيد شعوره من مظهر الوجه وحده."
            safeLabels.isNotEmpty() ->
                "أظن أن في محيطي: ${safeLabels.joinToString("، ")}. هذه تخمينات من لقطة واحدة وليست معرفة مؤكدة."
            else ->
                "لم أتعرف إلى ما أمامي بعد؛ لا أملك دليلًا كافيًا، وسأواصل الملاحظة بدل التخمين."
        }
    }

    private fun isAnimalLabel(label: String): Boolean {
        if (label in animalTerms) return true
        val words = label.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        return words.any { it in animalTerms }
    }

    private fun normalize(label: String): String =
        label.trim().lowercase().replace(Regex("\\s+"), " ")

    const val MIN_LABEL_CONFIDENCE = 0.60f
}
