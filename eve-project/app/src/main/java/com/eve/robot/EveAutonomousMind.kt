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
    CONTINUE_EXPLORATION
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
    val lastReflectionAtMs: Long = 0L
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

        val candidate = when {
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
            context.sceneAgeMs < 0L || !sceneFresh -> makePlan(
                EveMindGoal.OBSERVE_SCENE,
                "المشهد غير متاح أو قديم؛ الخطوة الصحيحة هي انتظار إطار حديث لا تخمين ما يحدث.",
                listOf(step(EveMindAction.WAIT_FOR_FRESH_FRAME, "الدليل البصري غير حديث", "يصل مشهد موثوق أو تظل النتيجة غير معروفة", 0.94f)),
                0.94f, now
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

        val signature = "${candidate.goal}|${candidate.steps.firstOrNull()?.action}|${evidenceKey.hashCode()}"
        val previous = lastPlan
        if (previous != null && now - lastPlanAtMs < planCooldownMs &&
            signature == lastPlanSignature && previous.expiresAtMs > now
        ) return previous

        lastPlan = candidate
        lastPlanSignature = signature
        lastPlanAtMs = now
        return candidate
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
        const val PLAN_TTL_MS = 22_000L
        const val MAX_REFLECTED_KEYS = 32
    }
}
