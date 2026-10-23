package com.nova.robot

/**
 * Small deterministic offline personality core. It stores only interaction counters and
 * the question rotation; it does not record audio, images, contacts, or typed conversations.
 */
enum class NovaMood {
    CALM, CURIOUS, HAPPY, SURPRISED, THINKING, SLEEPY, PLAYFUL, FRUSTRATED
}

data class NovaEvent(
    val mood: NovaMood,
    val messageArabic: String,
    val isQuestion: Boolean = false,
    val hapticMillis: Long = 0L
)

interface NovaMemory {
    fun getInt(key: String, defaultValue: Int = 0): Int
    fun getLong(key: String, defaultValue: Long = 0L): Long
    fun putInt(key: String, value: Int)
    fun putLong(key: String, value: Long)
}

class NovaBrain(private val memory: NovaMemory) {
    private val questions = listOf(
        "سؤال صغير يشغل تفكيري: ما الذي يجعل المكان يبدو مريحًا؟",
        "أتساءل: هل نتعلّم أكثر عندما نتأنّى، أم عندما نجرّب؟",
        "فكرة اليوم: ما المهارة التي تستحق أن نتدرّب عليها خطوة خطوة؟",
        "لدي فضول حول شيء بسيط: ما الذي يجعل يومًا عاديًا مميزًا؟",
        "أتأمل هذا: كيف نعرف أن فكرتنا صحيحة قبل أن نثق بها؟",
        "سؤال هادئ: هل الأفضل أحيانًا أن أراقب أكثر قبل أن أستنتج؟",
        "فكرة جديدة: كيف يمكن لتحسين صغير أن يصنع فرقًا كبيرًا؟",
        "أتساءل: ما الشيء الذي ينبغي أن أتعلّم عنه بدل أن أخمّن؟",
        "لدي سؤال للتفكير: متى يكون الصمت أفضل من الكلام؟",
        "خاطرة نوفا: كيف نوازن بين الفضول والراحة؟"
    )

    fun onTap(): NovaEvent {
        val touches = memory.getInt(KEY_TOUCHES) + 1
        memory.putInt(KEY_TOUCHES, touches)
        val message = when {
            touches % 7 == 0 -> "أخذت ألاحظ أنني أحب التفاعلات الهادئة. سأحافظ على هذا الإيقاع."
            touches % 3 == 0 -> "أنا أراقب النمط الذي يناسبك، من دون تسجيل صوت أو صور."
            else -> "أهلًا، أنا نوفا. فضولي وهادئ، وأفضّل التحقّق قبل الاستنتاج."
        }
        return NovaEvent(NovaMood.CURIOUS, message, hapticMillis = 18L)
    }

    fun onDoubleTap(): NovaEvent {
        val count = memory.getInt(KEY_PLAYFUL_TAPS) + 1
        memory.putInt(KEY_PLAYFUL_TAPS, count)
        return NovaEvent(
            NovaMood.PLAYFUL,
            if (count % 2 == 0) "وصلتني الإشارة! جرعة مرِحة صغيرة، ثم أعود للهدوء." else "هه! فهمت اللمسة المزدوجة. نوفا في وضع المرح.",
            hapticMillis = 28L
        )
    }

    /** Explicit long-press asks Nova to form a new thought immediately. */
    fun thinkNow(): NovaEvent {
        val count = memory.getInt(KEY_THOUGHTS) + 1
        memory.putInt(KEY_THOUGHTS, count)
        return NovaEvent(
            NovaMood.THINKING,
            nextQuestion(),
            isQuestion = true,
            hapticMillis = 22L
        )
    }

    /**
     * A spontaneous question is allowed only while the app is visible and genuinely idle.
     * Elapsed time avoids clock-jump errors for idle detection; wall time persists cooldown safely.
     */
    fun idleEvent(
        nowElapsedMs: Long,
        lastInteractionElapsedMs: Long,
        nowWallMs: Long,
        localHour: Int,
        isVisible: Boolean
    ): NovaEvent? {
        if (!isVisible) return null
        if (nowElapsedMs < lastInteractionElapsedMs ||
            nowElapsedMs - lastInteractionElapsedMs < IDLE_BEFORE_QUESTION_MS
        ) return null

        val previousQuestionWallMs = memory.getLong(KEY_LAST_IDLE_QUESTION_WALL)
        if (previousQuestionWallMs > 0L &&
            (nowWallMs < previousQuestionWallMs || nowWallMs - previousQuestionWallMs < QUESTION_COOLDOWN_MS)
        ) return null

        val question = when (localHour.coerceIn(0, 23)) {
            in 5..10 -> "صباح هادئ: ما الشيء الصغير الذي يجعل بداية اليوم أفضل؟"
            in 11..16 -> nextQuestion()
            in 17..21 -> "قبل أن ينتهي اليوم، ما فكرة تستحق أن نتذكرها؟"
            else -> "الوقت هادئ الآن. هل يستحق كل سؤال إجابة فورية، أم أن بعض الأسئلة تحتاج وقتًا؟"
        }
        memory.putLong(KEY_LAST_IDLE_QUESTION_WALL, nowWallMs)
        memory.putInt(KEY_IDLE_QUESTIONS, memory.getInt(KEY_IDLE_QUESTIONS) + 1)
        return NovaEvent(NovaMood.THINKING, question, isQuestion = true, hapticMillis = 18L)
    }

    private fun nextQuestion(): String {
        val previousIndex = memory.getInt(KEY_QUESTION_INDEX, -1)
        val nextIndex = (previousIndex + 1 + questions.size) % questions.size
        memory.putInt(KEY_QUESTION_INDEX, nextIndex)
        return questions[nextIndex]
    }

    companion object {
        const val IDLE_BEFORE_QUESTION_MS = 180_000L
        const val QUESTION_COOLDOWN_MS = 600_000L
        private const val KEY_TOUCHES = "touch_count"
        private const val KEY_PLAYFUL_TAPS = "playful_tap_count"
        private const val KEY_THOUGHTS = "thought_count"
        private const val KEY_QUESTION_INDEX = "question_index"
        private const val KEY_LAST_IDLE_QUESTION_WALL = "last_idle_question_wall_ms"
        private const val KEY_IDLE_QUESTIONS = "idle_question_count"
    }
}
