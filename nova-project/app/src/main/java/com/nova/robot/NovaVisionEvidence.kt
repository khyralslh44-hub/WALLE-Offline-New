package com.nova.robot

/** One image-label hypothesis from the local bundled image classifier. */
data class NovaLabelEvidence(val label: String, val confidence: Float)

/**
 * Conservative formatter for coarse single-frame image labels.
 * ML Kit image labels are scene hypotheses, not object boxes; when a face detector finds a person,
 * generic animal labels are suppressed to avoid describing that face as a dog/cat.
 */
object NovaVisionEvidence {
    private val animalTerms = setOf("animal", "dog", "cat", "puppy", "kitten", "canine", "feline", "mammal")
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
            .filterNot { faceFound && it.label in animalTerms }
            .distinctBy { it.label }
            .sortedByDescending { it.confidence }
            .take(2)
            .mapNotNull { evidence ->
                translations[evidence.label]?.let { "$it (${(evidence.confidence * 100).toInt()}٪)" }
            }
            .toList()

        return when {
            faceFound && safeLabels.isNotEmpty() ->
                "رصدت وجه شخص أمام الكاميرا، وقد أرى أيضًا: ${safeLabels.joinToString("، ")}. هذا وصف بصري تقريبي، ولا أتعرّف على هوية الشخص."
            faceFound ->
                "رصدت وجه شخص أمام الكاميرا. لا أستطيع تحديد هويته أو شعوره من هذا الكشف وحده."
            safeLabels.isNotEmpty() ->
                "قد يكون في المشهد: ${safeLabels.joinToString("، ")}. إنها تخمينات من صورة واحدة وليست تأكيدًا."
            else ->
                "أرى مشهدًا، لكن لم أجد دليلًا بصريًا كافيًا لتسمية ما فيه. سأبقى غير متأكدة بدل التخمين."
        }
    }

    private fun normalize(label: String): String =
        label.trim().lowercase().replace(Regex("\\s+"), " ")

    const val MIN_LABEL_CONFIDENCE = 0.60f
}
