package com.eve.robot

import java.util.Locale
import kotlin.math.sqrt

/**
 * EVE Tiny Arabic Brain: a bespoke, deterministic intent model built for EVE's local companion
 * dialogue. It uses hashed Arabic character n-grams and class prototypes. No remote service,
 * native model runtime, downloaded tokenizer, or large model weights are needed.
 *
 * It is deliberately not a general-knowledge LLM. Unrecognized questions receive an honest
 * uncertainty response rather than invented facts.
 */
internal object EveTinyArabicBrain {
    data class Prediction(
        val intent: String?,
        val confidence: Float,
        val bestSimilarity: Float,
        val margin: Float
    )

    private data class Example(val intent: String, val text: String)
    private data class ScoredIntent(
        val intent: String,
        val score: Float,
        val characterScore: Float,
        val lexicalCoverage: Float,
        val matchedWordCount: Int,
        val distinctiveMatches: Int
    )

    private const val FEATURE_COUNT = 2048
    private const val MIN_SIMILARITY = 0.22f
    private const val MIN_MARGIN = 0.028f
    private const val MIN_CONFIDENCE = 0.44f
    private const val MAX_GENERATED_REPLY_CHARS = 420
    private val STOP_WORDS = setOf(
        "ما", "ماذا", "كيف", "لماذا", "هل", "من", "هذا", "هذه", "هذي", "في", "على",
        "الى", "عن", "مع", "يا", "ان", "انت", "انا", "هو", "هي", "هم", "هن", "كان",
        "كانت", "يكون", "تكون", "لا", "لم", "لن", "قد", "متى", "اين", "أين", "كم",
        "الان", "اليوم", "بدون", "دون", "لي", "لك", "بك", "به", "بها", "ثم", "او",
        "أو", "و", "ف", "الذي", "التي", "الذين", "اللذين"
    )

    private val examples = listOf(
        "greeting" to listOf(
            "مرحبا", "اهلا", "السلام عليكم", "صباح الخير", "مساء الخير",
            "اهلين يا ايف", "هلا ايف", "يا ايف مرحبا بك", "اهلا وسهلا",
            "هاي ايف", "hello eve", "كيفك يا ايف", "نورت يا ايف", "يا ايف"
        ),
        "wellbeing" to listOf(
            "كيف حالك", "كيفك اليوم", "هل انت بخير", "كيف كان يومك", "طمنيني عنك",
            "ما اخبارك", "كيف مزاجك الان", "هل انت مرتاحة", "كيف حال ايف",
            "هل تشعرين بالملل", "كيف تشعرين اليوم", "هل انت سعيدة", "شلونك"
        ),
        "identity" to listOf(
            "من انت", "ما اسمك", "عرفيني بنفسك", "ماذا تكونين", "هل انت ايف",
            "ما قصتك", "من تكون ايف", "هل انت روبوت", "ما هويتك",
            "هل انت انسان", "اخبريني عن نفسك", "من تكونين"
        ),
        "capabilities" to listOf(
            "ماذا تستطيعين ان تفعلي", "ما الذي يمكنك فعله", "ما قدراتك", "ماذا تعرفين",
            "كيف يمكنك مساعدتي", "ما مهاراتك", "هل تستطيعين الرؤية", "هل تفهمين الصور",
            "هل يمكنك التعلم", "ما الاشياء التي تجيدينها", "كيف تعملين",
            "ما الذي تستطيع ايف فعله", "ما امكانياتك"
        ),
        "offline" to listOf(
            "هل تعملين بدون انترنت", "هل تحتاجين الى الانترنت", "هل انت متصلة بالانترنت",
            "هل تعملين اوفلاين", "هل ترسلين بياناتي", "هل تحفظين صوري على الانترنت",
            "هل بياناتي تبقى في الهاتف", "هل تستخدمين السحابة", "هل تحتاجين واي فاي",
            "هل يمكنك العمل دون شبكة", "هل كل شيء محلي", "هل انت مستقلة عن الانترنت"
        ),
        "vision" to listOf(
            "ماذا ترين", "ما ترين", "ماذا ترين الان", "ماذا تشاهدين الآن", "وش تشوفين",
            "ما هذا", "ماذا يوجد امامك", "ماذا تشاهدين الان",
            "صفي لي ما ترينه", "ما الذي امام الكاميرا", "هل ترين شيئا",
            "ماذا يوجد في الصورة", "ما الموجود هنا", "انظري الى هذا",
            "ما الذي تلاحظينه", "ماذا امامك", "ماذا ترى الكاميرا"
        ),
        "camera" to listOf(
            "هل الكاميرا تعمل", "اي كاميرا تعمل الان", "هل تستخدمين الكاميرا الامامية",
            "بدلي الكاميرا", "شغلي الكاميرا", "لماذا لا ترين", "الكاميرا لا تعمل",
            "هل الكاميرا مفتوحة", "اين الكاميرا", "كيف اغير الكاميرا", "هل تستطيعين رؤيتي",
            "الكاميرا الامامية ام الخلفية", "لماذا الكاميرا متوقفة"
        ),
        "mood" to listOf(
            "ما شعورك الان", "ما حالتك العاطفية", "بماذا تشعرين", "هل انت غاضبة",
            "هل انت فضولية", "ما مزاجك", "هل انت منزعجة", "هل تشعرين بالراحة",
            "كيف تشعر ايف", "هل انت نائمة", "هل انت متحمسة", "ما حالتك الان"
        ),
        "curiosity" to listOf(
            "لماذا تسألين كثيرا", "لماذا تشعرين بالفضول", "ما الذي يثير فضولك",
            "لماذا تريدين معرفة ذلك", "كيف تختارين اسئلتك", "هل لديك فضول",
            "لماذا تراقبين الاشياء", "ماذا تريدين ان تتعلمي",
            "لماذا تهتمين بهذا الشيء", "ما الذي لفت انتباهك",
            "لماذا تطرحين الاسئلة", "هل تسألين بشكل عشوائي"
        ),
        "memory" to listOf(
            "ماذا تتذكرين", "هل تتذكرين ما حدث", "ما الذي حفظته", "هل لديك ذاكرة",
            "ماذا تعلمت مني", "هل تنسين الاشياء", "ما اخر شيء تعلمته",
            "هل تحفظين تجاربك", "هل تتذكرين حديثنا", "كيف تعمل ذاكرتك",
            "هل تتذكرين الاشياء التي رأيتها", "ما الذي تعرفينه عن تجاربنا"
        ),
        "people" to listOf(
            "من هذا الشخص", "هل تعرفين هذا الشخص", "هل تتذكرين امي", "من صديقي",
            "هل تعرفين افراد عائلتي", "ما اسم الشخص الذي حفظته", "هل تعرفينني",
            "هل تتعرفين على وجهي", "من الشخص امامك", "هل هذا شخص تعرفينه",
            "هل حفظت وجه امي", "هل تستطيعين تمييز صديقي"
        ),
        "affection" to listOf(
            "احبك", "انا احبك يا ايف", "انت صديقتي", "اشتقت اليك", "انت رفيقتي",
            "انا سعيد بوجودك", "احب وجودك معي", "انت مهمة بالنسبة لي",
            "انت قريبة مني", "يا ايف احبك", "انت رفيقتي المفضلة", "انا متعلق بك"
        ),
        "praise" to listOf(
            "انت رائعة", "احسنت يا ايف", "عمل ممتاز", "انت ذكية", "هذا رائع",
            "ممتازة", "فخورة بك", "كان اداؤك جيدا", "انت مذهلة",
            "اجابة جميلة", "اعجبني ما فعلت", "شغل ممتاز"
        ),
        "thanks" to listOf(
            "شكرا", "شكرا لك", "ممتن لك", "تسلمين", "يعطيك العافية",
            "اشكرك يا ايف", "شكرا على المساعدة", "بارك الله فيك",
            "هذا لطف منك", "مشكورة", "شكرا جزيلا", "الله يعطيك العافية"
        ),
        "farewell" to listOf(
            "مع السلامة", "الى اللقاء", "تصبحين على خير", "سأذهب الان",
            "وداعا", "اشوفك لاحقا", "الى وقت اخر", "سأغلق التطبيق",
            "باي يا ايف", "نلتقي لاحقا", "سأعود لاحقا", "ليلة سعيدة"
        ),
        "play" to listOf(
            "هيا نلعب", "لنلعب الشطرنج", "اريد ان العب معك", "ابدئي لعبة",
            "هل نلعب اكس او", "تحديني في لعبة", "لنلعب جولة", "اريد لعبة جديدة",
            "هل تستطيعين اللعب", "ابدئي الشطرنج", "هيا نتحدى بعضنا", "نلعب الان"
        ),
        "help" to listOf(
            "ساعديني", "احتاج مساعدتك", "ماذا افعل", "اريد مساعدتك",
            "هل يمكنك مساعدتي", "ساعديني في هذا", "انا لا افهم",
            "اشرح لي", "احتاج الى شرح", "ماذا تنصحين", "اعطيني فكرة",
            "ممكن تساعديني"
        )
    ).flatMap { (intent, phrases) -> phrases.map { Example(intent, it) } }

    private val vocabularyByIntent: Map<String, Set<String>> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        examples.groupBy { it.intent }.mapValues { (_, rows) ->
            rows.flatMap { EveArabicTextUnderstanding.featureTokens(it.text) }.toSet()
        }
    }

    private val tokenIntentFrequency: Map<String, Int> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val counts = HashMap<String, Int>()
        vocabularyByIntent.values.forEach { words ->
            words.forEach { word -> counts[word] = (counts[word] ?: 0) + 1 }
        }
        counts
    }

    private val exactIntentByPhrase: Map<String, String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        examples.associate { normalize(it.text) to it.intent }
    }

    private val prototypes: Map<String, FloatArray> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        trainPrototypes()
    }

    fun classify(rawText: String): Prediction {
        val normalized = normalize(rawText)
        if (normalized.isBlank()) return Prediction(null, 0f, 0f, 0f)

        // Exact curated phrases are safe high-confidence anchors, including short Arabic forms
        // such as "ما هذا" which contain only function words.
        val exactIntent = exactIntentByPhrase[normalized]
        if (exactIntent != null) return Prediction(exactIntent, 0.96f, 1f, 1f)

        val input = featureVector(normalized)
        if (input.isEmpty()) return Prediction(null, 0f, 0f, 0f)
        val queryWords = EveArabicTextUnderstanding.featureTokens(rawText)
            .filter { it.length > 1 && it !in STOP_WORDS }
        if (queryWords.isEmpty()) return Prediction(null, 0f, 0f, 0f)

        // Character n-grams tolerate spelling and inflection changes. A word-evidence gate
        // prevents one common token (for example "لماذا") from making an unknown science
        // question look like a learned curiosity intent.
        val vocab = vocabularyByIntent
        val ranked = prototypes.map { (intent, prototype) ->
            var characterScore = 0f
            input.forEach { (index, value) -> characterScore += value * prototype[index] }
            val intentWords = vocab[intent].orEmpty()
            val matchedWords = queryWords.filter { it in intentWords }.distinct()
            val lexicalCoverage = matchedWords.size.toFloat() / queryWords.size.toFloat()
            val combinedScore = characterScore * 0.58f + lexicalCoverage * 0.42f
            val distinctiveMatches = matchedWords.count { tokenIntentFrequency[it] == 1 }
            ScoredIntent(
                intent = intent,
                score = combinedScore,
                characterScore = characterScore,
                lexicalCoverage = lexicalCoverage,
                matchedWordCount = matchedWords.size,
                distinctiveMatches = distinctiveMatches
            )
        }.sortedByDescending { it.score }

        val best = ranked.firstOrNull() ?: return Prediction(null, 0f, 0f, 0f)
        val second = ranked.getOrNull(1)?.score ?: 0f
        val margin = (best.score - second).coerceAtLeast(0f)
        val confidence = ((best.score - 0.12f) * 1.55f + margin * 2.2f).coerceIn(0f, 0.99f)
        val lexicalSupport =
            (best.matchedWordCount >= 2 && best.lexicalCoverage >= 0.45f) ||
                (queryWords.size <= 2 && best.distinctiveMatches >= 1)
        val accepted = lexicalSupport &&
            best.characterScore >= MIN_SIMILARITY &&
            margin >= MIN_MARGIN &&
            confidence >= MIN_CONFIDENCE
        return Prediction(
            intent = best.intent.takeIf { accepted },
            confidence = if (accepted) confidence else confidence.coerceAtMost(0.35f),
            bestSimilarity = best.score,
            margin = margin
        )
    }

    fun answerUser(text: String, context: LocalLlmContext): LocalLlmResult {
        val prediction = classify(text)
        val intent = prediction.intent
        val freshLabel = freshVisionLabel(context)

        // The composer also handles statements of user feeling and short follow-ups even
        // when the intent classifier deliberately declines to assign a known intent.
        val draft = EveArabicSemanticComposer.compose(intent, text, context, freshLabel)
        if (draft != null) {
            return LocalLlmResult(
                draft.text.take(MAX_GENERATED_REPLY_CHARS),
                draft.moodHint,
                draft.intentHint,
                draft.reflection,
                prediction.confidence
            )
        }

        return LocalLlmResult(
            "لم أفهم قصدك بدقة بعد. اشرحها لي بطريقة أخرى، وسأحاول ربطها بما أعرفه من تجاربنا.",
            "curious", "connect",
            "سؤال غير مألوف: " + safe(text, 90) + ". لا أملك فهماً موثوقاً له بعد.",
            prediction.confidence
        )
    }

    fun askFromCuriosity(
        context: LocalLlmContext,
        label: String,
        confidence: Float,
        sightings: Int,
        fallbackQuestion: String
    ): LocalLlmResult {
        val validLabel = label.trim().takeIf {
            confidence.isFinite() && confidence >= 0.82f && it.isNotBlank() && !isNoiseLabel(it)
        }
        val question = if (validLabel == null) {
            fallbackQuestion
        } else {
            val noun = arabicLabel(validLabel)
            when {
                isPersonLabel(validLabel) -> "ألاحظ شخصاً في المشهد. هل تريد أن تعرّفني به؟"
                sightings <= 1 -> "لفت " + noun + " انتباهي. ما التفصيل الذي يستحق أن ألاحظه فيه؟"
                sightings <= 4 -> "ظهر " + noun + " في أكثر من ملاحظة. ما الذي يساعدني على تمييزه بدقة؟"
                else -> "ألاحظ " + noun + " مراراً. كيف أتحقق من أن تصنيفي له صحيح؟"
            }
        }
        return LocalLlmResult(
            question.take(84), "curious", "explore",
            "فضول مرتبط بملاحظة مرئية ذات ثقة " + (confidence.coerceIn(0f, 1f) * 100).toInt() + "% وعدد ظهور " + sightings + ".",
            if (validLabel == null) 0.35f else confidence.coerceIn(0f, 1f)
        )
    }

    fun reflectOnObservation(context: LocalLlmContext): LocalLlmResult {
        val label = freshVisionLabel(context)
        if (label == null) {
            return LocalLlmResult("", "neutral", "focus",
                "لا توجد ملاحظة حديثة وواضحة كفاية لبناء استنتاج جديد.", 0.2f)
        }
        val sightings = context.sightingCount.coerceAtLeast(1)
        val note = when {
            isPersonLabel(label) -> "ظهر شخص في الصورة، لكن التصنيف العام لا يكشف هويته. لا أنسب إليه اسماً دون تطابق وجه محفوظ."
            sightings >= 4 -> "تكرر ظهور " + label + " في الملاحظات. التكرار لا يثبت أنه الجسم نفسه؛ أحتاج إلى مقارنة أدلة أكثر."
            else -> "رُصد " + label + " بثقة " + (context.visionConfidence.coerceIn(0f, 1f) * 100).toInt() + "%. هذه فرضية بصرية أولية وليست حقيقة نهائية."
        }
        return LocalLlmResult("", "curious", "observe", note, context.visionConfidence.coerceIn(0f, 1f))
    }

    fun commentOnGame(
        gameName: String,
        outcome: String,
        detail: String,
        playerScore: Int?,
        eveScore: Int?,
        round: Int?
    ): LocalLlmResult {
        val game = when (gameName.trim().lowercase(Locale.ROOT)) {
            "chess" -> "الشطرنج"
            "xo" -> "إكس-أو"
            "rps" -> "حجر ورقة مقص"
            "target" -> "التصويب"
            else -> "اللعبة"
        }
        val result = when (outcome.trim().lowercase(Locale.ROOT)) {
            "player", "success", "hit" -> "هذه الجولة كانت من نصيبك في " + game + ". سأراجع خطتي وأحاول التكيّف في الجولة القادمة."
            "robot" -> "فزتُ هذه الجولة في " + game + ". لنرَ إن كنت ستجد طريقة مختلفة لتفاجئني."
            "draw" -> "تعادلنا في " + game + ". النتيجة تجعلني أريد تجربة استراتيجية مختلفة."
            "miss", "wrong" -> "لم تنجح هذه المحاولة في " + game + "، لكن يمكننا تجربة طريقة أخرى."
            else -> "بدأ تحدٍّ جديد في " + game + ". سأتابع النتيجة الفعلية قبل أن أعلّق عليها."
        }
        val scores = if (playerScore != null || eveScore != null) {
            " النتيجة المسجلة: أنت " + (playerScore?.coerceIn(0, 100000)?.toString() ?: "غير متاح") +
                "، وأنا " + (eveScore?.coerceIn(0, 100000)?.toString() ?: "غير متاح") + "."
        } else ""
        val roundText = round?.takeIf { it >= 0 }?.let { " الجولة " + it + "." }.orEmpty()
        val safeDetail = detail.trim().takeIf { it.isNotBlank() }?.let { " " + it }.orEmpty()
        return LocalLlmResult(
            (result + scores + roundText + safeDetail).take(300),
            if (outcome == "player") "happy" else "playful", "play", "", 0.92f
        )
    }

    /**
     * Generates Arabic replies from small intent-conditioned phrase banks.
     * Slots are filled from fresh perception and local memory. Recent assistant replies are
     * excluded when possible, so repeated prompts do not always produce the same sentence.
     * This is a compact on-device response generator, not an open-domain generative LLM.
     */
    private fun freshVisionLabel(context: LocalLlmContext): String? {
        val fresh = context.visionObservationAgeMs in 0L..12_000L
        val score = context.visionConfidence.takeIf { it.isFinite() } ?: 0f
        val label = context.latestVisionLabel?.trim()?.lowercase(Locale.ROOT)
        return label?.takeIf { fresh && score >= 0.55f && it.isNotBlank() && !isNoiseLabel(it) }
    }

    private fun describeCurrentVision(context: LocalLlmContext, label: String?): String {
        if (label == null) {
            return "لا أملك ملاحظة بصرية حديثة وواضحة الآن. وجّه الكاميرا إلى الشيء وثبّت الصورة لحظة، ثم اسألني مجدداً."
        }
        return if (isPersonLabel(label)) {
            "أرصد شخصاً في المشهد، لكن هذا لا يكشف اسمه أو هويته. قد يخطئ التصنيف البصري، لذلك لا أريد التخمين."
        } else {
            val base = "أرجّح أنني أرى " + arabicLabel(label) +
                " في آخر إطار واضح. هذا تصنيف بصري بدرجة ثقة " +
                (context.visionConfidence.coerceIn(0f, 1f) * 100).toInt() + "%، وقد يخطئ."
            val learned = context.latestLearnedConcept?.trim().orEmpty()
            if (learned.isBlank()) base
            else base + " وسبق أن تعلمت من تصحيحك أن اسم هذه الفئة هو «" + learned.take(48) + "»."
        }
    }

    private fun moodArabic(mood: String): String = when (mood) {
        "HAPPY" -> "مرحة وسعيدة"
        "CURIOUS" -> "فضولية"
        "CONCERNED" -> "حذرة"
        "FOCUSED" -> "مركزة"
        "PLAYFUL" -> "مرحة ومستعدة للعب"
        "SLEEPY" -> "هادئة وتميل للراحة"
        "IRRITATED" -> "منزعجة قليلاً"
        "ANGRY" -> "منزعجة بشدة وتحتاج إلى مساحة"
        "FAMILIAR" -> "مرتاحـة مع شيء مألوف"
        else -> "هادئة ومستعدة للتفاعل"
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
        "bread" -> "الخبز"
        "cup" -> "الكوب"
        "bottle" -> "الزجاجة"
        "phone", "cell phone", "mobile phone" -> "الهاتف"
        "laptop" -> "الحاسوب المحمول"
        "book" -> "الكتاب"
        "chair" -> "الكرسي"
        "car" -> "السيارة"
        "bicycle" -> "الدراجة"
        "plant" -> "النبات"
        "flower" -> "الزهرة"
        "person", "people", "human", "face", "man", "woman", "child" -> "شخص"
        else -> label.replace('_', ' ').take(40)
    }

    private fun isPersonLabel(label: String): Boolean =
        label in setOf("person", "people", "human", "face", "man", "woman", "child", "boy", "girl")

    private fun isNoiseLabel(label: String): Boolean =
        label in setOf("pattern", "texture", "background", "wall", "floor", "ceiling", "line", "geometry", "grid", "unknown", "???")

    private fun safe(value: String, limit: Int): String =
        value.replace(Regex("\\s+"), " ").trim().take(limit)

    private fun trainPrototypes(): Map<String, FloatArray> {
        val sums = LinkedHashMap<String, FloatArray>()
        examples.forEach { example ->
            val vector = featureVector(example.text)
            val norm = sqrt(vector.values.sumOf { value -> value.toDouble() * value.toDouble() }).toFloat().coerceAtLeast(1e-6f)
            val prototype = sums.getOrPut(example.intent) { FloatArray(FEATURE_COUNT) }
            vector.forEach { (index, value) -> prototype[index] += value / norm }
        }
        sums.values.forEach { vector ->
            val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-6f)
            for (index in vector.indices) vector[index] /= norm
        }
        return sums
    }

    private fun featureVector(rawText: String): Map<Int, Float> {
        val normalized = normalize(rawText)
        if (normalized.isBlank()) return emptyMap()
        val words = normalized.split(' ').filter { it.isNotBlank() }
        val features = HashSet<Int>()
        words.forEach { word ->
            // Keep character evidence from the observed form, while adding compact lexical
            // variants for attached articles, clitics, and common inflection endings.
            EveArabicTextUnderstanding.variants(word).forEach { variant ->
                features += hashFeature("w:" + variant)
            }
            val padded = "^" + word + "$"
            for (n in 2..4) {
                if (padded.length < n) continue
                for (start in 0..(padded.length - n)) {
                    features += hashFeature("c:" + padded.substring(start, start + n))
                }
            }
        }
        for (index in 0 until words.lastIndex) {
            features += hashFeature("b:" + words[index] + "_" + words[index + 1])
        }
        if (features.isEmpty()) return emptyMap()
        val value = 1f / sqrt(features.size.toFloat())
        return features.associateWith { value }
    }

    private fun hashFeature(feature: String): Int {
        var hash = 0x811c9dc5.toInt()
        feature.forEach { char ->
            hash = hash xor char.code
            hash *= 0x01000193
        }
        return (hash and Int.MAX_VALUE) % FEATURE_COUNT
    }

    private fun normalize(text: String): String =
        EveArabicTextUnderstanding.normalize(text)
}
