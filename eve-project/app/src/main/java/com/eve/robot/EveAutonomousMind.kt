package com.eve.robot

/**
 * Deterministic local goal planner. It does not claim consciousness: it keeps a compact goal,
 * explicit steps, success criteria, and feedback so EVE can act on evidence rather than timers
 * or random mood changes. Device actions remain outside this class and are policy-checked.
 */
enum class EveMindGoal {
    OBSERVE_SCENE,
    VERIFY_UNCERTAIN_EVIDENCE,
    REFLECT_ON_NEW_EVIDENCE,
    FOCUS_ON_HUMAN_ACTIVITY,
    CONNECT_WITH_RECOGNIZED_PERSON,
    EXPLORE_CURRENT_VIEW,
    WAIT_QUIETLY
}

enum class EveMindAction {
    WAIT_FOR_FRESH_FRAME,
    REVIEW_RECENT_EVIDENCE,
    REQUEST_LOCAL_REFLECTION,
    MAINTAIN_QUIET_ATTENTION,
    CONTINUE_EXPLORATION,
    SWITCH_TO_WORLD_CAMERA
}

data class EveMindContext(
    val nowMs: Long,
    val inactiveMs: Long,
    val sceneAgeMs: Long,
    val sceneSummary: String,
    val latestLabel: String,
    val labelConfidence: Float,
    val faceSeenRecently: Boolean,
    val familiarPersonRecognized: Boolean,
    val activity: EveHumanActivity,
    val activityConfidence: Float,
    val explorationEnabled: Boolean,
    val localLanguageReady: Boolean,
    val lastReflectionAtMs: Long = 0L,
    /** True when the front/selfie camera is active and the rear camera can provide a wider scene. */
    val cameraIsFront: Boolean = false,
    /** Computed by the activity after checking permission, inactivity, and switching cooldown. */
    val cameraSwitchAllowed: Boolean = false
)

data class EveMindStep(
    val action: EveMindAction,
    val reasonArabic: String,
    val successCriterionArabic: String,
    val priority: Float
)

data class EveMindPlan(
    val goal: EveMindGoal,
    val rationaleArabic: String,
    val steps: List<EveMindStep>,
    val confidence: Float,
    val createdAtMs: Long,
    val expiresAtMs: Long
) {
    init {
        require(steps.isNotEmpty() && steps.size <= 3)
        require(confidence in 0f..1f)
    }
}

data class EveMindFeedback(
    val goal: EveMindGoal,
    val action: EveMindAction,
    val succeeded: Boolean,
    val note: String,
    val atMs: Long
)

/**
 * A bounded, repeat-aware planner:
 * - stale evidence leads to observation, not invented descriptions;
 * - human activity influences attention but does not trigger intrusive speech;
 * - the LLM may reflect on fresh evidence, while the planner keeps its own safety/goal rules;
 * - exploration is a user-configurable mode; it never flips camera lenses by itself.
 */
class EveAutonomousMind(
    private val maxFeedback: Int = 16,
    private val planCooldownMs: Long = 4_000L
) {
    private var lastPlan: EveMindPlan? = null
    private var lastPlanSignature = ""
    private var lastPlanAtMs = 0L
    private val feedback = ArrayDeque<EveMindFeedback>()
    private val reflectedEvidence = LinkedHashSet<String>()

    @Synchronized
    fun plan(context: EveMindContext): EveMindPlan {
        val now = context.nowMs.coerceAtLeast(0L)
        val confidence = context.labelConfidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val activityConfidence = context.activityConfidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val sceneFresh = context.sceneAgeMs in 0L..SCENE_FRESH_MS && context.sceneSummary.isNotBlank()
        val labelFresh = context.sceneAgeMs in 0L..SCENE_FRESH_MS &&
            context.latestLabel.isNotBlank() && confidence >= MIN_REFLECTION_CONFIDENCE
        val evidenceKey = (
            context.latestLabel.trim().lowercase() + "|" + context.sceneSummary.trim().take(180)
        ).take(180)
        val alreadyReflected = evidenceKey in reflectedEvidence
        val reflectionCooldownDone = context.lastReflectionAtMs <= 0L ||
            now - context.lastReflectionAtMs >= REFLECTION_COOLDOWN_MS
        val sameEvidenceReflectionFailedRecently = feedback.asReversed().firstOrNull {
            it.goal == EveMindGoal.REFLECT_ON_NEW_EVIDENCE &&
                it.action == EveMindAction.REQUEST_LOCAL_REFLECTION &&
                !it.succeeded && it.note == evidenceKey
        }?.let { now - it.atMs in 0L..REFLECTION_FAILURE_BACKOFF_MS } == true

        val candidate = when {
            context.explorationEnabled && context.cameraIsFront && context.cameraSwitchAllowed &&
                !context.faceSeenRecently && context.inactiveMs >= AUTO_CAMERA_SWITCH_IDLE_MS &&
                hasRecentFailureStreak(EveMindGoal.EXPLORE_CURRENT_VIEW, EveMindAction.SWITCH_TO_WORLD_CAMERA, now) ->
                makePlan(
                    EveMindGoal.WAIT_QUIETLY,
                    "تعلمت من محاولات سابقة أن تبديل الكاميرا لم ينجح مرارًا؛ أوقف التكرار الآن وأنتظر تغيرًا أو تدخلًا بدل إعادة الخطأ نفسه.",
                    listOf(step(
                        EveMindAction.MAINTAIN_QUIET_ATTENTION,
                        "تجنب تكرار فعل فشل عدة مرات",
                        "تصل أدلة جديدة أو يعاد تفعيل المحاولة بعد فترة تهدئة",
                        0.86f
                    )),
                    0.86f, now
                )
            context.familiarPersonRecognized -> makePlan(
                EveMindGoal.CONNECT_WITH_RECOGNIZED_PERSON,
                "تعرّفت إيف على مرجع وجه محفوظ بدرجة مطابقة كافية؛ الأولوية لتحية هادئة واحدة.",
                listOf(step(EveMindAction.MAINTAIN_QUIET_ATTENTION, "الشخص المعروف حاضر", "لا تكرر التحية أثناء فترة التهدئة", 0.86f)),
                0.86f, now
            )
            context.activity != EveHumanActivity.UNKNOWN && activityConfidence >= MIN_ACTIVITY_CONFIDENCE -> makePlan(
                EveMindGoal.FOCUS_ON_HUMAN_ACTIVITY,
                "هناك استنتاج نشاط متعدد الإطارات؛ أراقب تغير الأدلة ولا أحوّل الاحتمال إلى حقيقة.",
                listOf(step(EveMindAction.REVIEW_RECENT_EVIDENCE, "مراجعة نشاط مرئي متكرر", "تتجدد الأدلة أو تنتهي صلاحيتها دون مقاطعة الشخص", 0.82f)),
                activityConfidence, now
            )
            context.explorationEnabled && context.cameraIsFront && context.cameraSwitchAllowed &&
                !context.faceSeenRecently && context.inactiveMs >= AUTO_CAMERA_SWITCH_IDLE_MS ->
                makePlan(
                    EveMindGoal.EXPLORE_CURRENT_VIEW,
                    "لا يوجد شخص حديث أمام الكاميرا الأمامية؛ بعد فترة هدوء أختار الكاميرا الخلفية لمعاينة المكان. لن أبدّل العدسة أثناء رؤية شخص.",
                    listOf(
                        step(
                            EveMindAction.SWITCH_TO_WORLD_CAMERA,
                            "الانتقال مرة واحدة إلى منظور المكان",
                            "تعمل الكاميرا الخلفية وتصل أدلة مشهد حديثة؛ وإلا أعود إلى الانتظار",
                            0.78f
                        )
                    ),
                    0.78f, now
                )
            context.sceneAgeMs < 0L || !sceneFresh -> makePlan(
                EveMindGoal.OBSERVE_SCENE,
                "المشهد غير متاح أو قديم؛ الخطوة الصحيحة هي انتظار إطار حديث لا تخمين ما يحدث.",
                listOf(step(EveMindAction.WAIT_FOR_FRESH_FRAME, "الدليل البصري غير حديث", "يصل مشهد موثوق أو تظل النتيجة غير معروفة", 0.94f)),
                0.94f, now
            )
            context.explorationEnabled && labelFresh && sameEvidenceReflectionFailedRecently ->
                makePlan(
                    EveMindGoal.VERIFY_UNCERTAIN_EVIDENCE,
                    "فشلت محاولة التحليل المحلي السابقة للدليل نفسه؛ لا أكرر الطلب بلا نهاية. أنتظر تغير المشهد أو دليلًا أحدث قبل إعادة المحاولة.",
                    listOf(step(
                        EveMindAction.REVIEW_RECENT_EVIDENCE,
                        "مقارنة الدليل الحالي بنتيجة المحاولة السابقة",
                        "تصل ملاحظة مختلفة أو يصبح الدليل قديمًا؛ لا إعادة توليد على الدليل نفسه الآن",
                        0.78f
                    )),
                    maxOf(0.55f, confidence * 0.80f), now
                )
            context.explorationEnabled && labelFresh && !alreadyReflected &&
                context.localLanguageReady && reflectionCooldownDone -> makePlan(
                EveMindGoal.REFLECT_ON_NEW_EVIDENCE,
                "ظهر دليل مرئي حديث يستحق المقارنة بالذاكرة؛ أطلب انعكاسًا محليًا محدودًا ثم أتحقق من حداثته.",
                listOf(
                    step(EveMindAction.REVIEW_RECENT_EVIDENCE, "تلخيص ما يظهر الآن", "لا تُستخدم تسمية أقدم من مهلة المشهد", 0.82f),
                    step(EveMindAction.REQUEST_LOCAL_REFLECTION, "مقارنة المشهد بذاكرة محلية قصيرة", "أي استنتاج يظل مرتبطًا بالدليل وثقته", 0.76f)
                ),
                confidence, now
            )
            context.explorationEnabled -> makePlan(
                EveMindGoal.EXPLORE_CURRENT_VIEW,
                "وضع الاستكشاف مفعّل؛ أستمر في فحص الإطار الحالي ومقارنة المشاهد الجديدة دون تبديل الكاميرا تلقائيًا.",
                listOf(step(EveMindAction.CONTINUE_EXPLORATION, "تحليل محلي للمشهد الحالي", "تحديث ملخص المشهد عند وصول دليل جديد", 0.74f)),
                if (sceneFresh) 0.74f else 0.52f, now
            )
            context.sceneSummary.isNotBlank() && !labelFresh && context.inactiveMs >= IDLE_REFLECTION_MS ->
                makePlan(
                    EveMindGoal.VERIFY_UNCERTAIN_EVIDENCE,
                    "ملخص المشهد موجود لكن دقته أو حداثته لا تكفي لادعاء قوي؛ أحتفظ بعدم اليقين.",
                    listOf(step(EveMindAction.REVIEW_RECENT_EVIDENCE, "فحص ثقة الدليل وحداثته", "تؤكد إطارات لاحقة التسمية أو تُرفض", 0.70f)),
                    0.70f, now
                )
            context.faceSeenRecently -> makePlan(
                EveMindGoal.OBSERVE_SCENE,
                "هناك شخص في مجال الرؤية؛ أراقب بهدوء ولا أفترض هويته ما لم تنجح مطابقة مرجع محفوظ.",
                listOf(step(EveMindAction.MAINTAIN_QUIET_ATTENTION, "وجود شخص لا يعني معرفة هويته", "تبقى التسمية عامة ما لم يوجد تطابق موثوق", 0.68f)),
                0.68f, now
            )
            context.inactiveMs >= IDLE_REFLECTION_MS -> makePlan(
                EveMindGoal.WAIT_QUIETLY,
                "لا توجد أدلة جديدة تستحق استنتاجًا؛ أتجنب اختراع نشاط أو تكرار صوت، وأنتظر تغير المشهد.",
                listOf(step(EveMindAction.MAINTAIN_QUIET_ATTENTION, "الهدوء أفضل من السلوك العشوائي", "يتغير الهدف عند وصول دليل جديد", 0.60f)),
                0.60f, now
            )
            else -> makePlan(
                EveMindGoal.OBSERVE_SCENE,
                "أجمع أدلة المشهد الحالي قبل اختيار هدف آخر.",
                listOf(step(EveMindAction.WAIT_FOR_FRESH_FRAME, "جمع أدلة قبل الاستنتاج", "يصل إطار حديث", 0.58f)),
                0.58f, now
            )
        }

        // Outcome history alters future confidence/priority; successful strategies earn a small
        // preference, while failed strategies lose weight. The effect is bounded and requires
        // more than one observed outcome, so one noisy event cannot teach a permanent rule.
        val learnedSteps = candidate.steps.map { item ->
            val outcomes = feedback.asReversed().asSequence()
                .filter { it.goal == candidate.goal && it.action == item.action }
                .take(8).toList()
            val learnedPriority = if (outcomes.size >= MIN_OUTCOMES_FOR_ADAPTATION) {
                val rate = (outcomes.count { it.succeeded } + 1f) / (outcomes.size + 2f)
                item.priority + (rate - 0.5f) * MAX_PRIORITY_LEARNING_SHIFT
            } else item.priority
            item.copy(priority = learnedPriority.coerceIn(0f, 1f))
        }
        val firstAction = candidate.steps.first().action
        val candidateOutcomes = feedback.asReversed().asSequence()
            .filter { it.goal == candidate.goal && it.action == firstAction }
            .take(8).toList()
        val learnedConfidence = if (candidateOutcomes.size >= MIN_OUTCOMES_FOR_ADAPTATION) {
            val rate = (candidateOutcomes.count { it.succeeded } + 1f) / (candidateOutcomes.size + 2f)
            candidate.confidence + (rate - 0.5f) * MAX_CONFIDENCE_LEARNING_SHIFT
        } else candidate.confidence
        val learnedCandidate = candidate.copy(
            steps = learnedSteps,
            confidence = learnedConfidence.coerceIn(0f, 1f)
        )

        val signature = "${learnedCandidate.goal}|${learnedCandidate.steps.firstOrNull()?.action}|${evidenceKey.hashCode()}"
        val previous = lastPlan
        if (previous != null && now - lastPlanAtMs < planCooldownMs &&
            signature == lastPlanSignature && previous.expiresAtMs > now
        ) return previous

        lastPlan = learnedCandidate
        lastPlanSignature = signature
        lastPlanAtMs = now
        return learnedCandidate
    }

    /** True only for a recent consecutive run of failed experiences for the exact same action. */
    private fun hasRecentFailureStreak(goal: EveMindGoal, action: EveMindAction, nowMs: Long): Boolean {
        val outcomes = feedback.asReversed().asSequence()
            .filter { it.goal == goal && it.action == action }
            .take(3).toList()
        if (outcomes.size < FAILURE_STREAK_MIN) return false
        val latest = outcomes.first()
        if (latest.succeeded || latest.atMs < 0L) return false
        if (nowMs - latest.atMs !in 0L..FAILURE_MEMORY_WINDOW_MS) return false
        return outcomes.take(FAILURE_STREAK_MIN).all { !it.succeeded }
    }

    @Synchronized
    fun recordFeedback(item: EveMindFeedback) {
        feedback.addLast(item.copy(note = item.note.trim().take(180)))
        while (feedback.size > maxFeedback.coerceAtLeast(1)) feedback.removeFirst()
        if (item.succeeded && item.goal == EveMindGoal.REFLECT_ON_NEW_EVIDENCE) {
            reflectedEvidence.add(item.note.trim().take(180))
            while (reflectedEvidence.size > MAX_REFLECTED_KEYS) {
                val iterator = reflectedEvidence.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

    @Synchronized
    fun recentFeedback(): List<EveMindFeedback> = feedback.toList()

    /**
     * Compact, versioned state for private on-device persistence. Only bounded decision feedback
     * and evidence keys are stored; camera frames, face embeddings, and raw audio are never saved.
     */
    @Synchronized
    fun exportMemory(): String = buildString {
        append(MEMORY_HEADER).append('\n')
        feedback.forEach { item ->
            append("F\t")
                .append(item.goal.name).append('\t')
                .append(item.action.name).append('\t')
                .append(item.succeeded).append('\t')
                .append(item.atMs.coerceAtLeast(0L)).append('\t')
                .append(encodeMemoryField(item.note.take(180)))
                .append('\n')
        }
        // Persist the bounded set of already-reflected evidence across app restarts.
        reflectedEvidence.forEach { key ->
            append("E\t").append(encodeMemoryField(key.take(180))).append('\n')
        }
    }.take(MAX_MEMORY_CHARS)

    /**
     * Restores a saved local history after process/activity recreation. Malformed or future-version
     * data is ignored safely, and all restored fields are bounded before entering planner state.
     */
    @Synchronized
    fun restoreMemory(serialized: String): Boolean {
        if (serialized.length > MAX_MEMORY_CHARS ||
            serialized.lineSequence().firstOrNull() != MEMORY_HEADER
        ) return false

        feedback.clear()
        reflectedEvidence.clear()
        lastPlan = null
        lastPlanSignature = ""
        lastPlanAtMs = 0L

        serialized.lineSequence().drop(1).take(MAX_MEMORY_RECORDS).forEach { line ->
            if (line.startsWith("E\t")) {
                val key = runCatching { decodeMemoryField(line.substring(2)) }
                    .getOrNull()?.trim()?.take(180)
                if (!key.isNullOrBlank() && reflectedEvidence.size < MAX_REFLECTED_KEYS) {
                    reflectedEvidence.add(key)
                }
                return@forEach
            }
            if (!line.startsWith("F\t")) return@forEach
            val fields = line.split('\t', limit = 6)
            if (fields.size != 6) return@forEach
            val goal = runCatching { EveMindGoal.valueOf(fields[1]) }.getOrNull() ?: return@forEach
            val action = runCatching { EveMindAction.valueOf(fields[2]) }.getOrNull() ?: return@forEach
            val succeeded = fields[3].toBooleanStrictOrNull() ?: return@forEach
            val atMs = fields[4].toLongOrNull()?.takeIf { it >= 0L } ?: return@forEach
            val note = runCatching { decodeMemoryField(fields[5]) }.getOrNull()
                ?.trim()?.take(180) ?: return@forEach
            recordFeedback(EveMindFeedback(goal, action, succeeded, note, atMs))
        }
        return true
    }

    private fun encodeMemoryField(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private fun decodeMemoryField(value: String): String =
        java.net.URLDecoder.decode(value, "UTF-8")

    private fun makePlan(
        goal: EveMindGoal,
        rationale: String,
        steps: List<EveMindStep>,
        confidence: Float,
        now: Long
    ) = EveMindPlan(
        goal = goal,
        rationaleArabic = rationale,
        steps = steps,
        confidence = confidence.coerceIn(0f, 1f),
        createdAtMs = now,
        expiresAtMs = now + PLAN_TTL_MS
    )

    private fun step(action: EveMindAction, reason: String, success: String, priority: Float) =
        EveMindStep(action, reason, success, priority.coerceIn(0f, 1f))

    companion object {
        const val SCENE_FRESH_MS = 15_000L
        const val REFLECTION_COOLDOWN_MS = 90_000L
        const val MIN_REFLECTION_CONFIDENCE = 0.84f
        const val MIN_ACTIVITY_CONFIDENCE = 0.55f
        const val IDLE_REFLECTION_MS = 18_000L
        const val AUTO_CAMERA_SWITCH_IDLE_MS = 24_000L
        const val PLAN_TTL_MS = 22_000L
        const val REFLECTION_FAILURE_BACKOFF_MS = 180_000L
        const val MAX_REFLECTED_KEYS = 32
        private const val FAILURE_STREAK_MIN = 2
        private const val FAILURE_MEMORY_WINDOW_MS = 8 * 60_000L
        private const val MIN_OUTCOMES_FOR_ADAPTATION = 2
        private const val MAX_PRIORITY_LEARNING_SHIFT = 0.12f
        private const val MAX_CONFIDENCE_LEARNING_SHIFT = 0.10f
        private const val MEMORY_HEADER = "EVE_MIND_MEMORY_V1"
        private const val MAX_MEMORY_RECORDS = 64
        private const val MAX_MEMORY_CHARS = 24_000
    }
}
