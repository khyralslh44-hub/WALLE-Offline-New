package com.eve.robot

import java.util.Locale

/**
 * Offline, goal-driven curiosity based on a current high-confidence label, local recurrence,
 * and learned curiosity trait. Cooldowns keep a still camera view from creating repeated prompts.
 * This is a character-behaviour simulation, not consciousness or a general-purpose LLM.
 */
data class EveCuriosityThought(val label: String, val text: String, val interest: Float, val reason: String)

object EveCuriosityEngine {
    private const val MIN_CONFIDENCE = 0.82f
    private const val MIN_CURIOSITY = 0.48f
    private const val QUIET_GAP_MS = 42_000L
    private const val SAME_LABEL_GAP_MS = 45L * 60L * 1000L

    fun consider(
        label: String, confidence: Float, sightings: Int, curiosityBias: Float,
        lastAnyQuestionAtMs: Long, lastLabelQuestionAtMs: Long,
        previousQuestionCount: Int, nowMs: Long
    ): EveCuriosityThought? {
        val key = label.trim().lowercase(Locale.ROOT)
        if (key.isBlank() || key in NOISE_LABELS) return null
        if (!confidence.isFinite() || confidence < MIN_CONFIDENCE) return null
        if (!curiosityBias.isFinite() || curiosityBias < MIN_CURIOSITY) return null
        if (lastAnyQuestionAtMs > 0L && nowMs - lastAnyQuestionAtMs in 0L until QUIET_GAP_MS) return null
        if (lastLabelQuestionAtMs > 0L && nowMs - lastLabelQuestionAtMs in 0L until SAME_LABEL_GAP_MS) return null
        if (!isWorthExploring(key)) return null

        val count = sightings.coerceAtLeast(1)
        val interest = (
            0.48f + (confidence.coerceIn(0f, 1f) - MIN_CONFIDENCE) * 0.9f +
                curiosityBias.coerceIn(0f, 1f) * 0.22f +
                if (count <= 1) 0.16f else if (count >= 5) 0.04f else 0.10f
            ).coerceIn(0f, 1f)
        val noun = arabicLabel(key)
        val text = when {
            isPerson(key) ->
                "لاحظت شخصًا في المشهد، لكن التصنيف لا يكشف هويته. أتساءل: هل سأفهم المشهد أكثر إذا رأيته من زاوية أخرى؟"
            count >= 5 ->
                "ألاحظ " + noun + " مرة أخرى. هل هو الشيء نفسه فعلًا، أم أنني تعرّفت فقط إلى الفئة ذاتها؟"
            isAnimal(key) ->
                if (curiosityBias >= 0.78f)
                    "لفت " + noun + " انتباهي. أتساءل: ما التفصيل الذي يساعدني على تمييزه عن شيء يشبهه؟"
                else
                    "أرى " + noun + ". هل تعرف عنه شيئًا يساعدني على فهم ما أراه بصورة أدق؟"
            isFood(key) ->
                "أرى " + noun + ". أتساءل إن كنت سأتعرف إليه إذا تغيّرت زاوية النظر. هل نختبر ذلك؟"
            isPlant(key) ->
                "لاحظت " + noun + ". ما الذي يمكنني مراقبته لأميّزه عن نبات يشبهه؟ يمكننا النظر إليه من زاوية أخرى."
            else -> when ((previousQuestionCount + count) % 3) {
                0 -> "لفت " + noun + " انتباهي. أتساءل إن كنت سأتعرف إليه مجددًا من زاوية مختلفة."
                1 -> "أرى " + noun + ". ما الملاحظة التي تساعدني على التأكد من أن تصنيفي صحيح؟"
                else -> "هذا التصنيف جديد في هذه الجولة: " + noun + ". هل نجرّب رؤيته من زاوية أخرى لنتحقق؟"
            }
        }
        return EveCuriosityThought(
            label = key, text = text, interest = interest,
            reason = if (count <= 1) "novel high-confidence observation"
                else if (count >= 5) "recurring category; checking whether it is the same item"
                else "repeated observation with sufficient confidence"
        )
    }

    private fun isWorthExploring(label: String) =
        isAnimal(label) || isFood(label) || isPlant(label) || isPerson(label) || label in INTERESTING_OBJECTS
    private fun isAnimal(label: String) = listOf("animal", "cat", "dog", "bird", "pet", "puppy", "kitten", "horse", "rabbit", "fish", "hamster", "turtle", "butterfly").any(label::contains)
    private fun isFood(label: String) = listOf("food", "fruit", "banana", "apple", "pizza", "bread", "meal", "sandwich", "vegetable", "cake", "orange", "strawberry", "egg", "cookie").any(label::contains)
    private fun isPlant(label: String) = listOf("plant", "flower", "tree", "leaf", "grass", "garden", "cactus").any(label::contains)
    private fun isPerson(label: String) = listOf("person", "people", "human", "face", "man", "woman", "child", "boy", "girl").any(label::contains)

    private val INTERESTING_OBJECTS = setOf(
        "phone", "mobile phone", "cell phone", "smartphone", "laptop", "computer", "keyboard",
        "mouse", "monitor", "tv", "tv monitor", "book", "cup", "bottle", "car", "bicycle",
        "motorcycle", "bus", "truck", "train", "airplane", "boat", "chair", "couch",
        "dining table", "table", "bed", "toilet", "potted plant", "toy", "clock", "camera",
        "headphones", "remote", "remote control", "microwave", "oven", "toaster", "sink",
        "refrigerator", "vase", "scissors", "teddy bear", "hair drier", "toothbrush",
        "shoe", "umbrella", "bag", "backpack", "glasses", "watch", "suitcase", "handbag",
        "sports ball", "skateboard", "tennis racket", "baseball bat", "baseball glove",
        "traffic light", "stop sign", "bench", "fire hydrant", "parking meter"
    )
    private val NOISE_LABELS = setOf(
        "pattern", "texture", "background", "wall", "floor", "ceiling", "line", "geometry",
        "grid", "tile", "color", "colour", "design", "lighting", "shadow", "unknown", "???"
    )
    private fun arabicLabel(label: String): String = when (label) {
        "animal" -> "حيوان"; "cat", "kitten" -> "قطة"; "dog", "puppy" -> "كلب"
        "bird" -> "طائر"; "pet" -> "حيوان أليف"; "horse" -> "حصان"; "rabbit" -> "أرنب"
        "fish" -> "سمكة"; "hamster" -> "هامستر"; "turtle" -> "سلحفاة"; "butterfly" -> "فراشة"
        "food", "meal" -> "طعام"; "fruit" -> "فاكهة"; "banana" -> "موزة"; "apple" -> "تفاحة"
        "pizza" -> "بيتزا"; "bread" -> "خبز"; "sandwich" -> "شطيرة"; "vegetable" -> "خضار"
        "cake" -> "كعكة"; "orange" -> "برتقالة"; "strawberry" -> "فراولة"; "egg" -> "بيضة"
        "plant" -> "نبات"; "flower" -> "زهرة"; "tree" -> "شجرة"; "leaf" -> "ورقة نبات"
        "grass" -> "عشب"; "garden" -> "حديقة"; "cactus" -> "صبار"
        "phone", "mobile phone", "cell phone", "smartphone" -> "هاتف"
        "laptop" -> "حاسوب محمول"; "computer" -> "حاسوب"; "keyboard" -> "لوحة مفاتيح"
        "book" -> "كتاب"; "cup" -> "كوب"; "bottle" -> "زجاجة"; "car" -> "سيارة"
        "bicycle" -> "دراجة"; "toy" -> "لعبة"; "clock" -> "ساعة"; "camera" -> "كاميرا"
        "headphones" -> "سماعات"; "remote control" -> "جهاز تحكم"; "shoe" -> "حذاء"
        "umbrella" -> "مظلة"; "bag", "backpack" -> "حقيبة"; "glasses" -> "نظارة"; "watch" -> "ساعة يد"
        else -> label
    }
}

data class EveOfflineAnswer(val text: String, val topic: String, val mood: EveMood = EveMood.CURIOUS)

object OfflineEveResponder {
    private const val FRESH_OBSERVATION_MS = 12_000L
    fun isQuestion(raw: String): Boolean {
        val q = normalize(raw)
        return q.endsWith("?") || QUESTION_MARKERS.any(q::contains)
    }

    fun answer(rawQuestion: String, latestLabel: String?, latestConfidence: Float, observationAgeMs: Long): EveOfflineAnswer? {
        val q = normalize(rawQuestion)
        if (q.isBlank() || !isQuestion(q)) return null
        if (contains(q, "ماذا ترين", "ما ترين", "ماذا تشاهدين", "ماذا يوجد امامك", "ما الذي امامك",
                "صفي ما ترين", "وش تشوفين", "ايش تشوفين", "ما هذا", "وش هذا", "ايش هذا",
                "what is this", "what do you see", "describe what you see", "what is in front of you")) {
            val label = latestLabel?.trim()?.lowercase(Locale.ROOT)?.takeIf {
                observationAgeMs in 0L..FRESH_OBSERVATION_MS && latestConfidence.isFinite() &&
                    latestConfidence >= 0.52f && it !in setOf(
                    "pattern", "texture", "background", "wall", "floor", "ceiling", "line",
                    "geometry", "grid", "tile", "color", "colour", "design", "lighting", "shadow",
                    "unknown", "???"
                )
            }
            return if (label == null) EveOfflineAnswer(
                "لا أملك تصنيفًا بصريًا موثوقًا الآن. سأنتظر مشهدًا أوضح بدل أن أخمّن.",
                "uncertain_vision", EveMood.FOCUSED
            ) else EveOfflineAnswer(
                "أرى " + arabicVisualName(label) + " وفق التصنيف المحلي. قد يخطئ النموذج، ولا تكفي هذه النتيجة وحدها لمعرفة التفاصيل أو الهوية.",
                "current_visual_label", EveMood.CURIOUS
            )
        }
        if (contains(q, "ما اسمك", "من انت", "من انتي", "من تكونين", "who are you", "what is your name"))
            return EveOfflineAnswer("أنا إيف، شخصية روبوتية على هذا الهاتف. أستخدم الرؤية والكلام المحليين وأعبّر عن حالتي بعينيّ؛ هذا سلوك محاكى وليس وعيًا بشريًا.", "identity", EveMood.HAPPY)
        if (contains(q, "هل تعملين بدون انترنت", "هل تعملين دون انترنت", "هل تعملين بدون اتصال",
                "هل تعملين دون اتصال", "هل انت متصله بالانترنت", "do you work offline", "can you work offline", "are you online"))
            return EveOfflineAnswer("تعمل الرؤية وبعض الإجابات المحفوظة محليًا دون إنترنت. معرفتي محدودة بما هو مضمّن في التطبيق، ولا أستطيع البحث عن معلومات جديدة وأنا غير متصلة.", "offline_capability", EveMood.FOCUSED)
        if (contains(q, "ماذا تستطيعين", "ماذا يمكنك فعله", "ما قدراتك", "ما الذي تستطيعين فعله", "what can you do", "what are your abilities"))
            return EveOfflineAnswer("أستطيع تصنيف بعض الأشياء، ورصد وجه وتقدير بعض تعبيراته، والتفاعل مع إشارات يد مدعومة. قد أخطئ، ولا أتعرف إلى هوية شخص من تصنيف عام.", "local_capabilities", EveMood.FOCUSED)
        if (contains(q, "كيف حالك", "هل انت سعيده", "هل انت سعيدة", "are you happy", "how are you"))
            return EveOfflineAnswer("حالتي الآن فضولية ومستعدة للاستكشاف. هذا وصف لحالة الشخصية داخل التطبيق، وليس ادعاءً بأن لدي مشاعر بشرية حقيقية.", "current_character_state", EveMood.PLAYFUL)
        if (contains(q, "لماذا", "why") && contains(q, "تسألين", "فضول", "curious"))
            return EveOfflineAnswer("أسأل عندما ألاحظ تصنيفًا واضحًا يستحق الاستكشاف، ويؤثر فضولي وذاكرتي المحلية في اختيار ما أتابعه. إذا لم تكن لدي أدلة كافية فسأقول ذلك.", "curiosity_policy", EveMood.CURIOUS)
        return EveOfflineAnswer("لا أملك إجابة محلية موثوقة عن هذا السؤال الآن. لن أختلق معلومة؛ أستطيع الإجابة عمّا أراه بوضوح أو عمّا أعرفه عن قدراتي دون إنترنت.", "unknown_offline_question", EveMood.CONCERNED)
    }

    private val QUESTION_MARKERS = listOf("ما ", "ماهذا", "ماذا", "كيف", "لماذا", "هل ", "من ", "اين", "أين", "متى", "كم ", "وش ", "ايش ", "what", "how", "why", "where", "when", "who", "which", "can you")
    private fun contains(text: String, vararg options: String) = options.any(text::contains)
    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه')
        .replace(Regex("[^\\p{L}\\p{N}?\\s]"), " ").replace(Regex("\\s+"), " ").trim()
    private fun arabicVisualName(label: String) = when (label) {
        "animal" -> "حيوان"; "cat", "kitten" -> "قطة"; "dog", "puppy" -> "كلب"
        "bird" -> "طائر"; "pet" -> "حيوان أليف"; "horse" -> "حصان"; "rabbit" -> "أرنب"
        "fish" -> "سمكة"; "plant" -> "نبات"; "flower" -> "زهرة"; "tree" -> "شجرة"
        "fruit" -> "فاكهة"; "banana" -> "موزة"; "apple" -> "تفاحة"; "food" -> "طعام"
        "pizza" -> "بيتزا"; "bread" -> "خبز"; "sandwich" -> "شطيرة"; "person", "people", "human" -> "شخصًا"
        "face" -> "وجهًا"; "man" -> "رجلًا"; "woman" -> "امرأة"; "child", "boy", "girl" -> "طفلًا"
        "laptop" -> "حاسوبًا محمولًا"; "computer" -> "حاسوبًا"
        "phone", "mobile phone", "cell phone", "smartphone" -> "هاتفًا"
        "mouse" -> "فأرة حاسوب"
        "keyboard" -> "لوحة مفاتيح"
        "monitor", "tv", "tv monitor" -> "شاشة"
        "book" -> "كتابًا"; "cup" -> "كوبًا"; "bottle" -> "زجاجةً"
        "car" -> "سيارةً"; "bicycle" -> "دراجةً"; "motorcycle" -> "دراجة نارية"
        "bus" -> "حافلة"; "truck" -> "شاحنة"; "train" -> "قطار"; "airplane" -> "طائرة"; "boat" -> "قارب"
        "chair" -> "كرسي"; "couch" -> "أريكة"; "dining table", "table" -> "طاولة"
        "bed" -> "سرير"; "toilet" -> "مرحاض"; "potted plant" -> "نبات في أصيص"
        "microwave" -> "ميكروويف"; "oven" -> "فرن"; "toaster" -> "محمصة خبز"; "sink" -> "حوض"
        "refrigerator" -> "ثلاجة"; "vase" -> "مزهرية"; "scissors" -> "مقص"
        "teddy bear" -> "دب محشو"; "hair drier" -> "مجفف شعر"; "toothbrush" -> "فرشاة أسنان"
        "suitcase" -> "حقيبة سفر"; "handbag" -> "حقيبة يد"; "sports ball" -> "كرة"
        "skateboard" -> "لوح تزلج"; "tennis racket" -> "مضرب تنس"
        "baseball bat" -> "مضرب بيسبول"; "baseball glove" -> "قفاز بيسبول"
        "traffic light" -> "إشارة مرور"; "stop sign" -> "علامة توقف"; "bench" -> "مقعد"
        "fire hydrant" -> "صنبور إطفاء"; "parking meter" -> "عداد موقف سيارات"
        "spoon" -> "ملعقة"; "fork" -> "شوكة"; "knife" -> "سكين"; "bowl" -> "وعاء"
        "broccoli" -> "بروكلي"; "carrot" -> "جزرة"; "hot dog" -> "هوت دوغ"; "donut" -> "دونات"
        "cake" -> "كعكة"; "pizza" -> "بيتزا"; "orange" -> "برتقالة"; "apple" -> "تفاحة"; "banana" -> "موزة"
        "dog" -> "كلب"; "cat" -> "قطة"; "bird" -> "طائر"; "horse" -> "حصان"; "cow" -> "بقرة"
        "sheep" -> "خروف"; "elephant" -> "فيل"; "bear" -> "دب"; "zebra" -> "حمار وحشي"; "giraffe" -> "زرافة"
        else -> label
    }
}
