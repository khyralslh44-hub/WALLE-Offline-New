package com.eve.robot

import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A bounded, on-device temporal interpreter for coarse human activity hypotheses.
 *
 * This deliberately does not claim to identify gum, intent, health, or a person's private state.
 * It needs repeated camera evidence over time and returns UNKNOWN when evidence is insufficient.
 */
enum class EveHumanActivity {
    UNKNOWN,
    EATING_POSSIBLE,
    EATING_TREAT_POSSIBLE,
    DRINKING_POSSIBLE,
    CHEWING_MOTION_POSSIBLE,
    PLAYING_GAME_POSSIBLE,
    DRAWING_OR_COLORING_POSSIBLE,
    EXERCISE_LIKE_MOTION
}

data class EveHumanFrameEvidence(
    val timestampMs: Long,
    val personConfidence: Float,
    val handToMouthConfidence: Float = 0f,
    /** Relative distance between inner lips and facial height, not an emotion label. */
    val mouthOpeningRatio: Float = 0f,
    /** Normalized 0..1 joint-change estimate computed from adjacent pose samples. */
    val jointMotionScore: Float = 0f,
    val leftKneeAngleDegrees: Float? = null,
    val rightKneeAngleDegrees: Float? = null,
    val leftElbowAngleDegrees: Float? = null,
    val rightElbowAngleDegrees: Float? = null,
    /** Normalized hand/palm center from consecutive local hand-landmark detections. */
    val handCenterX: Float? = null,
    val handCenterY: Float? = null,
    val visibleObjectLabels: Set<String> = emptySet()
)

data class EveActivityInterpretation(
    val activity: EveHumanActivity,
    val confidence: Float,
    val evidenceCount: Int,
    val durationMs: Long,
    val explanationArabic: String,
    val nextActionArabic: String,
    val observedAtMs: Long
) {
    val isActionable: Boolean
        get() = activity != EveHumanActivity.UNKNOWN && confidence >= 0.55f
}

class EveActivityUnderstanding(
    private val windowMs: Long = 8_000L,
    private val maxSamples: Int = 12
) {
    private val samples = ArrayDeque<EveHumanFrameEvidence>()
    private var lastStableActivity = EveHumanActivity.UNKNOWN
    private var lastStableVoteCount = 0
    private var lastObservedAtMs = 0L

    @Synchronized
    fun observe(raw: EveHumanFrameEvidence): EveActivityInterpretation {
        val sample = sanitize(raw)
        if (lastObservedAtMs > sample.timestampMs) {
            // Camera timestamps must be monotonic. Ignore stale asynchronous results rather than
            // letting an old frame rewind the activity window.
            return unknown(sample.timestampMs, 0, 0L, "وصل إطار قديم؛ لم أغيّر تقدير النشاط.")
        }
        lastObservedAtMs = sample.timestampMs
        samples.addLast(sample)
        while (samples.isNotEmpty() && sample.timestampMs - samples.first().timestampMs > windowMs) {
            samples.removeFirst()
        }
        while (samples.size > maxSamples) samples.removeFirst()

        val people = samples.filter { it.personConfidence >= MIN_PERSON_CONFIDENCE }
        if (people.size < MIN_PERSON_SAMPLES) {
            resetVote()
            return unknown(
                sample.timestampMs,
                people.size,
                spanMs(people),
                "لا توجد إطارات كافية وواضحة للشخص كي أستنتج نشاطًا.",
            )
        }

        val handMouthEpisodes = countEpisodes(people) { it.handToMouthConfidence >= HAND_NEAR_MOUTH_THRESHOLD }
        val mouthValues = people.map { it.mouthOpeningRatio }
        val mouthRange = (mouthValues.maxOrNull() ?: 0f) - (mouthValues.minOrNull() ?: 0f)
        val mouthCycles = countDirectionReversals(mouthValues, minDelta = 0.012f)
        val objectLabels = people.flatMap { it.visibleObjectLabels }.map(::normalizeLabel).toSet()
        val hasTreat = objectLabels.any(::isTreatLabel)
        val hasFood = objectLabels.any(::isFoodLabel)
        val hasDrink = objectLabels.any(::isDrinkContainerLabel) || objectLabels.any(::isBeverageLabel)
        val drinkKindArabic = drinkKind(objectLabels)
        val hasGameCue = objectLabels.any(::isGameLabel)
        val hasArtCue = objectLabels.any(::isArtLabel)
        val span = spanMs(people)
        val averageJointMotion = people.map { it.jointMotionScore }.average().toFloat()
        val jointChanges = countJointChanges(people)
        val jointReversals = countJointReversals(people)
        val handMotionChanges = countHandMotionChanges(people)
        val handMotionReversals = countHandMotionReversals(people)

        val candidate = when {
            // Treat labels plus repeated hand-to-mouth episodes and mouth motion suggest consumption,
            // but they cannot prove licking or reveal ingredients from a camera image.
            hasTreat && handMouthEpisodes >= 2 && span >= 1_800L &&
                (mouthCycles >= 1 || mouthRange >= 0.025f || handMouthEpisodes >= 3) -> {
                EveActivityInterpretation(
                    EveHumanActivity.EATING_TREAT_POSSIBLE,
                    confidence = confidence(0.55f + min(0.18f, handMouthEpisodes * 0.04f) +
                        if (mouthCycles >= 1) 0.035f else 0f),
                    evidenceCount = people.size,
                    durationMs = span,
                    explanationArabic = "ظهر تصنيف بصري لحلوى أو مثلجات مع تكرار حركة اليد نحو الفم؛ قد يكون الشخص يتناول مصاصة أو مثلجات، لكن لا أستطيع تأكيد اللعق أو نوع الطعام من الصورة وحدها.",
                    nextActionArabic = "هذه فرضية بصرية فقط؛ لا تقاطع الشخص ولا تفترض مكونات الطعام.",
                    observedAtMs = sample.timestampMs
                )
            }
            // Specific game items plus repeated motion support a hypothesis, not proof of the game.
            // A generic phone or screen alone is never enough.
            hasGameCue && people.size >= 5 && span >= 2_000L &&
                ((handMotionChanges >= 3 && handMotionReversals >= 1) ||
                    (jointChanges >= 3 && averageJointMotion >= 0.16f)) -> {
                EveActivityInterpretation(
                    EveHumanActivity.PLAYING_GAME_POSSIBLE,
                    confidence = confidence(0.54f + min(0.16f, handMotionReversals * 0.025f) +
                        min(0.08f, averageJointMotion * 0.08f)),
                    evidenceCount = people.size,
                    durationMs = span,
                    explanationArabic = "ظهرت أداة أو عناصر مرتبطة بالألعاب مع حركة متكررة؛ قد يكون الشخص يلعب، لكن وجود الأداة وحده لا يثبت النشاط أو نوع اللعبة.",
                    nextActionArabic = "لا تقاطع اللعب؛ لا تفترض ما يظهر على الشاشة إلا إذا كان واضحًا أو شرحه الشخص.",
                    observedAtMs = sample.timestampMs
                )
            }
            // Art supplies and sustained hand movement may indicate drawing or colouring.
            hasArtCue && people.size >= 5 && span >= 2_000L &&
                handMotionChanges >= 4 && handMotionReversals >= 2 -> {
                EveActivityInterpretation(
                    EveHumanActivity.DRAWING_OR_COLORING_POSSIBLE,
                    confidence = confidence(0.54f + min(0.18f, handMotionReversals * 0.025f)),
                    evidenceCount = people.size,
                    durationMs = span,
                    explanationArabic = "ظهرت أداة أو مادة مرتبطة بالرسم أو التلوين مع حركة يد متكررة؛ قد يكون الشخص يرسم أو يلوّن، ولا يمكن تأكيد النتيجة من الأداة وحدها.",
                    nextActionArabic = "انتظر مزيدًا من الأدلة ولا تصف الرسم أو محتواه إذا لم يكن واضحًا.",
                    observedAtMs = sample.timestampMs
                )
            }
            // Repeated hand-to-mouth movement plus a nearby food object and visible mouth motion.
            hasFood && handMouthEpisodes >= 2 && (mouthCycles >= 1 || mouthRange >= 0.035f) -> {
                EveActivityInterpretation(
                    EveHumanActivity.EATING_POSSIBLE,
                    confidence = confidence(0.56f + min(0.22f, handMouthEpisodes * 0.045f) +
                        if (mouthCycles >= 2) 0.06f else 0f),
                    evidenceCount = people.size,
                    durationMs = span,
                    explanationArabic = "طعام ظاهر مع تكرار حركة اليد نحو الفم وحركة فم عبر عدة إطارات؛ الأكل احتمال وليس يقينًا.",
                    nextActionArabic = "لا تقاطع الشخص. سجّل الملاحظة بهدوء، ولا تتحدث إلا إذا خاطبك أو طلب المساعدة.",
                    observedAtMs = sample.timestampMs
                )
            }
            // Some face frames do not expose a reliable mouth signal. Repeated, separated
            // hand-to-mouth episodes plus visible food still support a cautious hypothesis,
            // but only after a longer temporal window and without claiming certainty.
            hasFood && handMouthEpisodes >= 3 && span >= 2_500L -> {
                EveActivityInterpretation(
                    EveHumanActivity.EATING_POSSIBLE,
                    confidence = confidence(0.55f + min(0.18f, handMouthEpisodes * 0.04f)),
                    evidenceCount = people.size,
                    durationMs = span,
                    explanationArabic = "ظهر طعام وتكررت حركة اليد باتجاه منطقة الفم عبر الزمن، لكن إشارة فتح الفم غير واضحة؛ الأكل احتمال فقط.",
                    nextActionArabic = "لا تقاطع الشخص. لا تتحدث إلا إذا خاطبك أو طلب المساعدة.",
                    observedAtMs = sample.timestampMs
                )
            }
            // A cup/bottle alone is not enough: require a repeated approach to the mouth.
            hasDrink && handMouthEpisodes >= 1 && (mouthRange >= 0.025f || mouthCycles >= 1) -> {
                EveActivityInterpretation(
                    EveHumanActivity.DRINKING_POSSIBLE,
                    confidence = confidence(0.55f + min(0.18f, handMouthEpisodes * 0.05f) +
                        if (mouthCycles >= 1) 0.04f else 0f),
                    evidenceCount = people.size,
                    durationMs = span,
                    explanationArabic = if (drinkKindArabic != null)
                        "ظهر كوب أو وعاء مع حركة متكررة نحو الفم وتصنيف بصري قد يشير إلى $drinkKindArabic؛ نوع السائل غير مؤكد."
                    else "وعاء شرب محتمل مع اقتراب اليد من الفم وحركة فم؛ الشرب مجرد استنتاج بصري محتمل.",
                    nextActionArabic = "ابقَ هادئًا ولا تقاطع الشخص؛ انتظر تفاعلًا مباشرًا قبل طرح سؤال.",
                    observedAtMs = sample.timestampMs
                )
            }
            // If mouth landmarks are unavailable, require multiple separate approaches;
            // a cup/bottle by itself never implies drinking.
            hasDrink && handMouthEpisodes >= 2 && span >= 2_000L -> {
                EveActivityInterpretation(
                    EveHumanActivity.DRINKING_POSSIBLE,
                    confidence = confidence(0.54f + min(0.17f, handMouthEpisodes * 0.04f)),
                    evidenceCount = people.size,
                    durationMs = span,
                    explanationArabic = if (drinkKindArabic != null)
                        "ظهر وعاء وتصنيف بصري قد يشير إلى $drinkKindArabic مع تكرار حركة اليد نحو الفم؛ الشرب ونوع السائل غير مؤكدين."
                    else "ظهر وعاء شرب محتمل مع تكرار حركة اليد نحو الفم عبر عدة إطارات؛ الشرب احتمال وليس يقينًا.",
                    nextActionArabic = "ابقَ هادئًا ولا تقاطع الشخص؛ انتظر تفاعلًا مباشرًا قبل طرح سؤال.",
                    observedAtMs = sample.timestampMs
                )
            }
            // Mouth movement cannot distinguish chewing gum from food, talking, or other motion.
            people.size >= 5 && span >= 3_000L && mouthCycles >= 2 && mouthRange >= 0.025f -> {
                EveActivityInterpretation(
                    EveHumanActivity.CHEWING_MOTION_POSSIBLE,
                    confidence = confidence(0.54f + min(0.17f, mouthCycles * 0.035f) +
                        if (handMouthEpisodes >= 1) 0.03f else 0f),
                    evidenceCount = people.size,
                    durationMs = span,
                    explanationArabic = "هناك حركة متكررة في منطقة الفم. لا أستطيع تمييز العلكة عن المضغ أو الكلام من هذه الإشارة وحدها.",
                    nextActionArabic = "لا تفترض أنها علكة ولا تسجل ذلك كحقيقة؛ انتظر سياقًا أو سؤالًا مباشرًا.",
                    observedAtMs = sample.timestampMs
                )
            }
            // Exercise requires repeated, multi-frame joint changes, not a single pose or one gesture.
            people.size >= 5 && span >= 3_500L &&
                jointChanges >= 4 && jointReversals >= 2 && averageJointMotion >= 0.13f -> {
                EveActivityInterpretation(
                    EveHumanActivity.EXERCISE_LIKE_MOTION,
                    confidence = confidence(0.54f + min(0.20f, jointChanges * 0.025f) +
                        min(0.08f, averageJointMotion * 0.10f)),
                    evidenceCount = people.size,
                    durationMs = span,
                    explanationArabic = "حركة متكررة لزوايا المفاصل عبر عدة إطارات؛ قد تكون تمرينًا أو حركة متكررة مشابهة.",
                    nextActionArabic = "اترك مساحة للشخص ولا تقاطعه؛ إذا واصل التفاعل معك، اسأله عمّا يريد فعله.",
                    observedAtMs = sample.timestampMs
                )
            }
            // If leg/elbow landmarks are unavailable, a visible person's repeated hand/arm
            // trajectory can only support a weaker "exercise-like motion" hypothesis. It may
            // also be waving, cleaning, or working, so never identify a specific exercise.
            people.size >= 5 && span >= 3_500L &&
                handMotionChanges >= 5 && handMotionReversals >= 3 &&
                averageJointMotion >= 0.13f -> {
                EveActivityInterpretation(
                    EveHumanActivity.EXERCISE_LIKE_MOTION,
                    confidence = confidence(0.53f + min(0.12f, handMotionReversals * 0.018f) +
                        min(0.04f, averageJointMotion * 0.04f)),
                    evidenceCount = people.size,
                    durationMs = span,
                    explanationArabic = "رصدت حركة متكررة لليد أو الذراع عبر الزمن؛ قد تكون تمرينًا، أو تلويحًا، أو نشاطًا آخر، ولا يمكن تحديد النوع بثقة من اليد وحدها.",
                    nextActionArabic = "لا تفترض نوع التمرين ولا تقاطع الشخص؛ انتظر أدلة أوضح أو تفاعلًا مباشرًا.",
                    observedAtMs = sample.timestampMs
                )
            }
            else -> null
        }

        if (candidate == null) {
            resetVote()
            return unknown(
                sample.timestampMs,
                people.size,
                span,
                "لم تتجمع أدلة زمنية كافية لنسبة نشاط محدد إلى الشخص.",
            )
        }

        // Require the same activity to survive more than one inference result. This reduces
        // single-frame false positives and keeps the robot from changing its plan at every frame.
        if (candidate.activity == lastStableActivity) {
            lastStableVoteCount += 1
        } else {
            lastStableActivity = candidate.activity
            lastStableVoteCount = 1
        }
        if (lastStableVoteCount < REQUIRED_STABLE_VOTES) {
            return unknown(
                sample.timestampMs,
                people.size,
                span,
                "أتحقق من تكرار الإشارة قبل اعتماد تقدير النشاط.",
            )
        }
        return candidate.copy(confidence = min(candidate.confidence, 0.82f))
    }

    @Synchronized
    fun reset() {
        samples.clear()
        resetVote()
        lastObservedAtMs = 0L
    }

    private fun resetVote() {
        lastStableActivity = EveHumanActivity.UNKNOWN
        lastStableVoteCount = 0
    }

    private fun countEpisodes(
        values: List<EveHumanFrameEvidence>,
        predicate: (EveHumanFrameEvidence) -> Boolean
    ): Int {
        var count = 0
        var wasActive = false
        var lastStart = Long.MIN_VALUE / 2
        for (value in values) {
            val active = predicate(value)
            if (active && !wasActive && value.timestampMs - lastStart >= MIN_EPISODE_GAP_MS) {
                count++
                lastStart = value.timestampMs
            }
            wasActive = active
        }
        return count
    }

    private fun countDirectionReversals(values: List<Float>, minDelta: Float): Int {
        var priorDirection = 0
        var reversals = 0
        for (i in 1 until values.size) {
            val delta = values[i] - values[i - 1]
            val direction = when {
                delta >= minDelta -> 1
                delta <= -minDelta -> -1
                else -> 0
            }
            if (direction != 0) {
                if (priorDirection != 0 && direction != priorDirection) reversals++
                priorDirection = direction
            }
        }
        return reversals
    }

    private fun countJointChanges(values: List<EveHumanFrameEvidence>): Int {
        var changes = 0
        for (i in 1 until values.size) {
            val a = values[i - 1]
            val b = values[i]
            if (largeAngleChange(a.leftKneeAngleDegrees, b.leftKneeAngleDegrees)) changes++
            if (largeAngleChange(a.rightKneeAngleDegrees, b.rightKneeAngleDegrees)) changes++
            if (largeAngleChange(a.leftElbowAngleDegrees, b.leftElbowAngleDegrees)) changes++
            if (largeAngleChange(a.rightElbowAngleDegrees, b.rightElbowAngleDegrees)) changes++
        }
        return changes
    }

    private fun countHandMotionChanges(values: List<EveHumanFrameEvidence>): Int {
        var changes = 0
        for (i in 1 until values.size) {
            val a = values[i - 1]
            val b = values[i]
            val ax = a.handCenterX ?: continue
            val ay = a.handCenterY ?: continue
            val bx = b.handCenterX ?: continue
            val by = b.handCenterY ?: continue
            if (kotlin.math.hypot((bx - ax).toDouble(), (by - ay).toDouble()) >= MIN_HAND_MOTION_STEP) {
                changes++
            }
        }
        return changes
    }

    private fun countHandMotionReversals(values: List<EveHumanFrameEvidence>): Int {
        val xs = values.mapNotNull { it.handCenterX }
        val ys = values.mapNotNull { it.handCenterY }
        return countDirectionReversals(xs, MIN_HAND_REVERSAL_STEP) +
            countDirectionReversals(ys, MIN_HAND_REVERSAL_STEP)
    }

    private fun countJointReversals(values: List<EveHumanFrameEvidence>): Int {
        val allAngles = listOf(
            values.map { it.leftKneeAngleDegrees },
            values.map { it.rightKneeAngleDegrees },
            values.map { it.leftElbowAngleDegrees },
            values.map { it.rightElbowAngleDegrees }
        )
        return allAngles.sumOf { angles ->
            val valid = angles.mapNotNull { it?.takeIf(Float::isFinite) }
            countDirectionReversals(valid, minDelta = 12f)
        }
    }

    private fun largeAngleChange(a: Float?, b: Float?): Boolean =
        a != null && b != null && a.isFinite() && b.isFinite() && abs(b - a) >= MIN_JOINT_CHANGE_DEGREES

    private fun spanMs(values: List<EveHumanFrameEvidence>): Long =
        if (values.size < 2) 0L else (values.last().timestampMs - values.first().timestampMs).coerceAtLeast(0L)

    private fun sanitize(input: EveHumanFrameEvidence) = input.copy(
        personConfidence = input.personConfidence.finiteUnit(),
        handToMouthConfidence = input.handToMouthConfidence.finiteUnit(),
        mouthOpeningRatio = input.mouthOpeningRatio.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
        jointMotionScore = input.jointMotionScore.finiteUnit(),
        leftKneeAngleDegrees = input.leftKneeAngleDegrees?.takeIf { it.isFinite() }?.coerceIn(0f, 180f),
        rightKneeAngleDegrees = input.rightKneeAngleDegrees?.takeIf { it.isFinite() }?.coerceIn(0f, 180f),
        leftElbowAngleDegrees = input.leftElbowAngleDegrees?.takeIf { it.isFinite() }?.coerceIn(0f, 180f),
        rightElbowAngleDegrees = input.rightElbowAngleDegrees?.takeIf { it.isFinite() }?.coerceIn(0f, 180f),
        handCenterX = input.handCenterX?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        handCenterY = input.handCenterY?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        visibleObjectLabels = input.visibleObjectLabels.map(::normalizeLabel).filter { it.isNotBlank() }.toSet()
    )

    private fun unknown(
        at: Long,
        count: Int,
        duration: Long,
        explanation: String
    ) = EveActivityInterpretation(
        activity = EveHumanActivity.UNKNOWN,
        confidence = 0f,
        evidenceCount = count,
        durationMs = duration,
        explanationArabic = explanation,
        nextActionArabic = "لا تخمّن؛ اجمع أدلة أوضح أو انتظر أن يسألك الشخص.",
        observedAtMs = at
    )

    private fun confidence(value: Float) = value.coerceIn(0f, 0.82f)

    private fun Float.finiteUnit() = takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

    private fun normalizeLabel(value: String) =
        value.trim().lowercase(Locale.ROOT).replace('_', ' ').replace(Regex("\\s+"), " ")

    private fun isFoodLabel(label: String): Boolean {
        val value = normalizeLabel(label)
        return value in FOOD_LABELS || FOOD_LABELS.any { value.contains(it) }
    }

    private fun isDrinkContainerLabel(label: String): Boolean {
        val value = normalizeLabel(label)
        return value in DRINK_LABELS || DRINK_LABELS.any { value.contains(it) }
    }

    private fun isTreatLabel(label: String): Boolean {
        val value = normalizeLabel(label)
        return TREAT_LABELS.any { value == it || value.contains(it) }
    }

    private fun isBeverageLabel(label: String): Boolean {
        val value = normalizeLabel(label)
        return BEVERAGE_LABELS.any { value == it || value.contains(it) }
    }

    private fun drinkKind(labels: Set<String>): String? = when {
        labels.any { normalizeLabel(it).contains("coffee") || normalizeLabel(it).contains("espresso") || normalizeLabel(it).contains("latte") } -> "القهوة"
        labels.any { normalizeLabel(it).contains("water") } -> "الماء"
        labels.any { normalizeLabel(it).contains("tea") } -> "الشاي"
        labels.any { normalizeLabel(it).contains("juice") } -> "العصير"
        else -> null
    }

    private fun isGameLabel(label: String): Boolean {
        val value = normalizeLabel(label)
        return GAME_LABELS.any { value == it || value.contains(it) }
    }

    private fun isArtLabel(label: String): Boolean {
        val value = normalizeLabel(label)
        return ART_LABELS.any { value == it || value.contains(it) }
    }

    companion object {
        private const val MIN_PERSON_CONFIDENCE = 0.55f
        private const val MIN_PERSON_SAMPLES = 3
        private const val REQUIRED_STABLE_VOTES = 2
        private const val HAND_NEAR_MOUTH_THRESHOLD = 0.62f
        private const val MIN_EPISODE_GAP_MS = 450L
        private const val MIN_JOINT_CHANGE_DEGREES = 12f
        private const val MIN_HAND_MOTION_STEP = 0.035f
        private const val MIN_HAND_REVERSAL_STEP = 0.035f
        private val FOOD_LABELS = setOf(
            "food", "fruit", "banana", "apple", "orange", "pizza", "sandwich", "bread",
            "cake", "donut", "doughnut", "carrot", "hot dog", "broccoli", "bowl", "fork",
            "knife", "spoon", "plate", "ice cream", "icecream", "candy", "sweets", "sweet",
            "chocolate", "lollipop", "popsicle", "ice lolly", "ice pop", "frozen dessert",
            "confectionery", "bonbon", "marshmallow", "cookie", "biscuit", "dessert"
        )
        private val TREAT_LABELS = setOf(
            "ice cream", "icecream", "lollipop", "popsicle", "ice lolly", "ice pop",
            "candy", "chocolate", "frozen dessert", "bonbon", "marshmallow"
        )
        private val DRINK_LABELS = setOf("bottle", "cup", "wine glass", "water bottle", "mug", "coffee cup")
        private val BEVERAGE_LABELS = setOf("coffee", "espresso", "water", "tea", "juice", "beverage", "drink", "milk", "latte")
        private val GAME_LABELS = setOf(
            "game controller", "gamepad", "joystick", "video game", "game console", "gaming console",
            "chessboard", "chess board", "board game", "game board", "playing cards", "dice", "toy"
        )
        private val ART_LABELS = setOf(
            "crayon", "colored pencil", "colour pencil", "color pencil", "marker", "felt-tip pen",
            "paintbrush", "paint brush", "pencil", "drawing", "sketchbook", "coloring book",
            "colouring book", "artwork", "canvas", "palette", "watercolor", "watercolour", "paint"
        )
    }
}
