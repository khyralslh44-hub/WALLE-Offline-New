package com.eve.robot

import android.content.Context
import kotlin.math.abs
import kotlin.math.pow

enum class EveMood {
    AWAKE, NEUTRAL, CURIOUS, HAPPY, SURPRISED, CONCERNED, FAMILIAR, SLEEPY, FOCUSED,
    PLAYFUL, IRRITATED, ANGRY
}

/** A short-lived goal, separate from mood: the same feeling can lead to different actions. */
enum class EveIntent {
    EXPLORE, CONNECT, PLAY, REST, FOCUS, REGULATE
}

/**
 * Stable personality traits tune EVE's behavior. They are deliberately bounded and deterministic.
 */
data class EvePersonality(
    val curiosityBias: Float = 0.72f,
    val warmth: Float = 0.76f,
    val playfulness: Float = 0.62f,
    val patience: Float = 0.68f
) {
    fun bounded() = copy(
        curiosityBias = curiosityBias.coerceIn(0.15f, 0.95f),
        warmth = warmth.coerceIn(0.20f, 0.98f),
        playfulness = playfulness.coerceIn(0.10f, 0.92f),
        patience = patience.coerceIn(0.20f, 0.98f)
    )
}

/**
 * A local affect snapshot shared by vision, touch, idle behavior, facial expression and sound.
 * Values are continuous; moods are only readable summaries of the richer state.
 */
data class EveDecision(
    val mood: EveMood,
    val reason: String,
    val playSound: Boolean = false,
    val valence: Float = 0.12f,
    val arousal: Float = 0.32f,
    val curiosity: Float = 0.55f,
    val familiarity: Float = 0.05f,
    val fatigue: Float = 0f,
    val frustration: Float = 0f,
    val playfulness: Float = 0.58f,
    val intent: EveIntent? = null,
    /** Context-weighted social affect drives; these are simulated state values, not feelings. */
    val jealousy: Float = 0f,
    val pride: Float = 0f,
    val reassurance: Float = 0f
)

/**
 * Stateful local affect engine. It reacts to confidence, semantic category, repeated sightings,
 * touch and inactivity. This is a lightweight emotional-behavior simulation, not consciousness.
 */
class RobotBrain(personality: EvePersonality = EvePersonality()) {
    @Volatile private var personality = personality.bounded()

    /** Apply learned, persistent traits without resetting the current emotional state. */
    @Synchronized
    fun updatePersonality(next: EvePersonality) {
        personality = next.bounded()
    }

    /**
     * Merge a validated local-LLM suggestion into the bounded affect state.
     * The model cannot invoke device actions or overwrite hard behavior rules; it only supplies
     * a small mood/goal hint which is range-checked here before reaching the face renderer.
     */
    @Synchronized
    fun integrateLanguageInsight(
        moodHint: String?,
        intentHint: String?,
        confidence: Float,
        note: String,
        source: String = "context"
    ): EveDecision {
        val certainty = confidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val hint = moodHint?.trim()?.lowercase()?.takeIf { certainty >= 0.45f }
        val intent = when (intentHint?.trim()?.lowercase()) {
            "observe", "focus" -> EveIntent.FOCUS
            "explore" -> EveIntent.EXPLORE
            "connect", "respond" -> EveIntent.CONNECT
            "play" -> EveIntent.PLAY
            "rest" -> EveIntent.REST
            "regulate" -> EveIntent.REGULATE
            else -> null
        }

        // Language output nudges the same affect variables used by perception/touch/idle.
        // These increments are intentionally small; the LLM is advisory, not the authority.
        when (hint) {
            "happy" -> {
                valence = (valence + 0.045f * certainty * personality.warmth).coerceIn(-1f, 1f)
                arousal = (arousal + 0.025f * certainty).coerceIn(0f, 1f)
            }
            "curious" -> curiosity = (curiosity + 0.055f * certainty).coerceAtMost(1f)
            "concerned" -> {
                valence = (valence - 0.025f * certainty).coerceAtLeast(-1f)
                curiosity = (curiosity + 0.020f * certainty).coerceAtMost(1f)
            }
            "focused" -> arousal = (arousal + 0.020f * certainty).coerceIn(0f, 1f)
            "playful" -> {
                valence = (valence + 0.025f * certainty).coerceIn(-1f, 1f)
                curiosity = (curiosity + 0.020f * certainty).coerceAtMost(1f)
            }
            "sleepy" -> fatigue = (fatigue + 0.025f * certainty).coerceAtMost(1f)
            "irritated" -> frustration = (frustration + 0.020f * certainty).coerceAtMost(1f)
            "jealous", "jealousy" -> {
                jealousy = (jealousy + 0.70f * certainty).coerceAtMost(1f)
                pride = (pride - 0.18f * certainty).coerceAtLeast(0f)
                reassurance = (reassurance - 0.12f * certainty).coerceAtLeast(0f)
                valence = (valence - 0.06f * certainty).coerceAtLeast(-1f)
            }
            "proud", "pride" -> {
                pride = (pride + 0.72f * certainty).coerceAtMost(1f)
                jealousy = (jealousy - 0.20f * certainty).coerceAtLeast(0f)
                valence = (valence + 0.08f * certainty).coerceAtMost(1f)
            }
            "reassured", "secure", "safe" -> {
                reassurance = (reassurance + 0.70f * certainty).coerceAtMost(1f)
                jealousy = (jealousy - 0.24f * certainty).coerceAtLeast(0f)
                frustration = (frustration - 0.22f * certainty).coerceAtLeast(0f)
                valence = (valence + 0.08f * certainty).coerceAtMost(1f)
                arousal = (arousal * 0.65f + 0.20f).coerceIn(0.12f, 0.70f)
            }
            "neutral" -> Unit
        }

        val hintedMood = when (hint) {
            "neutral" -> EveMood.NEUTRAL
            "curious" -> EveMood.CURIOUS
            "happy" -> EveMood.HAPPY
            "concerned" -> EveMood.CONCERNED
            "focused" -> EveMood.FOCUSED
            "playful" -> EveMood.PLAYFUL
            "sleepy" -> EveMood.SLEEPY
            "irritated" -> EveMood.IRRITATED
            "jealous", "jealousy" -> if (jealousy >= 0.48f) EveMood.IRRITATED else EveMood.CURIOUS
            "proud", "pride" -> EveMood.PLAYFUL
            "reassured", "secure", "safe" -> EveMood.FAMILIAR
            else -> currentMood
        }
        val safeMood = when {
            frustration >= 0.84f -> EveMood.ANGRY
            frustration >= 0.62f && hintedMood !in setOf(EveMood.SLEEPY, EveMood.NEUTRAL) -> EveMood.IRRITATED
            else -> hintedMood
        }
        val safeNote = note.trim().replace(Regex("\\s+"), " ").take(110)
        val safeSource = source.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(24)
        return snapshot(
            safeMood,
            "local LLM ${safeSource.ifBlank { "context" }} insight confidence=${"%.2f".format(java.util.Locale.ROOT, certainty)}" +
                if (safeNote.isNotBlank()) ": $safeNote" else "",
            false,
            intent
        )
    }

    private var valence = 0.12f       // negative = wary, positive = warm
    private var arousal = 0.32f       // calm <-> alert
    private var curiosity = 0.58f
    private var familiarity = 0.05f
    private var fatigue = 0f
    private var frustration = 0f
    private var jealousy = 0f
    private var pride = 0f
    private var reassurance = 0f
    private var lastTouchAtMs = 0L
    private var rapidTouchCount = 0
    private var lastHumanMood: EveMood? = null
    private var lastModelEmotion: String? = null
    private var currentMood = EveMood.NEUTRAL
    private var lastIdleEvaluationQuietMs = 0L
    private var idleIntentStartedAtQuietMs = 0L
    private var lastIdleIntent: EveIntent? = null
    private var repeatedIdleIntentCount = 0

    private enum class Kind { FOOD, ANIMAL, PERSON, PLANT, OTHER }

    /**
     * Game feedback updates the same local affect state used by camera/touch behavior.
     * This is a playful character response, not a claim that EVE feels human emotions.
     */
    @Synchronized
    fun gameOutcome(gameName: String, outcome: String): EveDecision {
        val safeGame = gameName.trim().lowercase()
            .filter { it.isLetterOrDigit() || it == '_' }
            .take(24).ifBlank { "game" }
        return when (outcome.trim().lowercase()) {
            "player", "success", "hit" -> {
                valence = (valence + 0.16f + personality.warmth * 0.04f).coerceAtMost(0.95f)
                arousal = 0.66f
                curiosity = (curiosity + 0.04f).coerceAtMost(1f)
                frustration = (frustration - 0.10f).coerceAtLeast(0f)
                snapshot(EveMood.HAPPY, "happy game result: player succeeds in $safeGame", false)
            }
            "robot" -> {
                valence = (valence + 0.07f * personality.playfulness).coerceAtMost(0.85f)
                arousal = 0.58f
                curiosity = (curiosity + 0.07f).coerceAtMost(1f)
                pride = (pride + 0.72f).coerceAtMost(1f)
                jealousy = (jealousy - 0.10f).coerceAtLeast(0f)
                snapshot(EveMood.PLAYFUL, "pride after documented EVE win in $safeGame", false, EveIntent.PLAY)
            }
            "draw" -> {
                valence = (valence + 0.02f).coerceIn(-0.8f, 0.85f)
                arousal = 0.42f
                curiosity = (curiosity + 0.10f).coerceAtMost(1f)
                snapshot(EveMood.CURIOUS, "curious game draw in $safeGame", false)
            }
            "miss", "wrong" -> {
                valence = (valence - 0.03f).coerceAtLeast(-0.65f)
                arousal = 0.62f
                frustration = (frustration + 0.025f).coerceAtMost(0.45f)
                snapshot(EveMood.FOCUSED, "focused game miss in $safeGame", false)
            }
            "start" -> {
                arousal = 0.54f
                curiosity = (curiosity + 0.05f).coerceAtMost(1f)
                snapshot(EveMood.PLAYFUL, "playful game started: $safeGame", false)
            }
            else -> snapshot(EveMood.CURIOUS, "curious game event in $safeGame", false)
        }
    }

    /** Entry point for startup so initial behavior also comes from the central affect state. */
    @Synchronized
    fun awakenExperience(): EveDecision {
        valence = (valence + 0.04f * personality.warmth.coerceIn(0f, 1f)).coerceAtMost(0.45f)
        arousal = 0.48f
        curiosity = (0.42f + personality.curiosityBias.coerceIn(0f, 1f) * 0.25f).coerceIn(0f, 1f)
        fatigue = 0f
        return snapshot(EveMood.AWAKE, "experience started", false)
    }

    /** A spoken name/relationship update is a real social-memory event in the shared affect state. */
    @Synchronized
    fun observeKnownPerson(relation: String, isIntroduction: Boolean): EveDecision {
        val safeRelation = relation.trim().replace(Regex("\\s+"), " ").take(32).ifBlank { "شخص معروف" }
        familiarity = (familiarity + if (isIntroduction) 0.18f else 0.10f).coerceAtMost(1f)
        valence = (valence + if (isIntroduction) 0.10f else 0.06f).coerceAtMost(0.95f)
        curiosity = (curiosity + 0.04f * personality.curiosityBias).coerceAtMost(1f)
        arousal = 0.48f
        reassurance = (reassurance + if (isIntroduction) 0.10f else 0.06f).coerceAtMost(1f)
        return snapshot(
            EveMood.FAMILIAR,
            if (isIntroduction) "remembered spoken introduction: $safeRelation" else "recalled local relationship: $safeRelation",
            false,
            EveIntent.CONNECT
        )
    }

    /**
     * A jealousy-like reaction is allowed only from an explicit, confidence-qualified social-context cue.
     * Mere detection of another person never triggers it by itself.
     */
    @Synchronized
    fun observeSocialCompetition(cue: String, confidence: Float): EveDecision {
        val certainty = confidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val safeCue = cue.trim().lowercase()
        val supportedCue = safeCue in setOf(
            "social_competition", "attention_shift_away_from_eve", "eve_ignored_in_interaction"
        )
        if (!supportedCue || certainty < 0.72f) {
            return snapshot(currentMood, "social competition cue ignored: unsupported or uncertain", false, lastIdleIntent)
        }
        jealousy = (jealousy + 0.72f * certainty).coerceAtMost(1f)
        reassurance = (reassurance - 0.12f * certainty).coerceAtLeast(0f)
        valence = (valence - 0.06f * certainty).coerceAtLeast(-1f)
        return snapshot(
            if (jealousy >= 0.48f) EveMood.IRRITATED else EveMood.CURIOUS,
            "playful jealousy-like response to an explicit social-context cue; not inferred from person detection alone",
            false,
            EveIntent.CONNECT
        )
    }

    /** A documented win or completed goal creates a bounded pride-like response. */
    @Synchronized
    fun recordAchievement(achievement: String, confidence: Float): EveDecision {
        val certainty = confidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val safeAchievement = achievement.trim().lowercase()
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(36)
        if (safeAchievement !in setOf("win", "checkmate", "task_complete", "puzzle_solved", "goal_reached") ||
            certainty < 0.65f
        ) {
            return snapshot(currentMood, "achievement not confirmed; pride unchanged", false, lastIdleIntent)
        }
        pride = (pride + 0.72f * certainty).coerceAtMost(1f)
        jealousy = (jealousy - 0.16f * certainty).coerceAtLeast(0f)
        valence = (valence + 0.08f * certainty).coerceAtMost(1f)
        arousal = (arousal * 0.65f + 0.30f).coerceIn(0.25f, 0.82f)
        return snapshot(EveMood.PLAYFUL, "pride-like character response to confirmed achievement: $safeAchievement", false, EveIntent.PLAY)
    }

    /** A concrete comfort or safety cue increases the calm/reassurance drive and reduces tension. */
    @Synchronized
    fun receiveReassurance(source: String, confidence: Float): EveDecision {
        val certainty = confidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val safeSource = source.trim().lowercase()
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(32)
        if (safeSource !in setOf("gentle_touch", "known_person_present", "safe_context", "calm_voice", "resolved_conflict") ||
            certainty < 0.60f
        ) {
            return snapshot(currentMood, "reassurance cue not confirmed; calm state unchanged", false, lastIdleIntent)
        }
        reassurance = (reassurance + 0.72f * certainty).coerceAtMost(1f)
        frustration = (frustration - 0.28f * certainty).coerceAtLeast(0f)
        jealousy = (jealousy - 0.28f * certainty).coerceAtLeast(0f)
        valence = (valence + 0.09f * certainty).coerceAtMost(1f)
        arousal = (arousal * 0.60f + 0.18f).coerceIn(0.12f, 0.66f)
        return snapshot(EveMood.FAMILIAR, "reassurance-like response after confirmed $safeSource cue", false, EveIntent.CONNECT)
    }

    /**
     * A multi-frame activity estimate may gently influence attention, but never interrupts the
     * person, speaks automatically, or treats a visual hypothesis as a known fact.
     */
    @Synchronized
    fun observeHumanActivity(activity: EveHumanActivity, confidence: Float): EveDecision {
        val certainty = confidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (activity == EveHumanActivity.UNKNOWN || certainty < 0.55f) {
            return snapshot(EveMood.NEUTRAL, "human activity evidence is insufficient", false)
        }

        // Activity is context, not a command. Keep voice silent and make only a bounded shift
        // in attention, so the character looks observant rather than intrusive.
        curiosity = (curiosity + certainty * 0.045f * personality.curiosityBias).coerceAtMost(1f)
        fatigue = (fatigue - certainty * 0.025f).coerceAtLeast(0f)
        return when (activity) {
            EveHumanActivity.EATING_POSSIBLE, EveHumanActivity.DRINKING_POSSIBLE -> {
                arousal = (arousal * 0.72f + 0.28f * 0.28f).coerceIn(0.12f, 0.72f)
                snapshot(
                    EveMood.CURIOUS,
                    "cautious visual activity hypothesis: $activity; remain quiet",
                    false,
                    EveIntent.FOCUS
                )
            }
            EveHumanActivity.CHEWING_MOTION_POSSIBLE -> {
                arousal = (arousal * 0.70f + 0.30f * 0.32f).coerceIn(0.12f, 0.75f)
                snapshot(
                    EveMood.FOCUSED,
                    "repeated mouth-area motion is ambiguous; do not infer chewing gum",
                    false,
                    EveIntent.FOCUS
                )
            }
            EveHumanActivity.EXERCISE_LIKE_MOTION -> {
                arousal = (arousal * 0.58f + 0.42f * 0.56f).coerceIn(0.20f, 0.85f)
                snapshot(
                    EveMood.CURIOUS,
                    "repeated joint motion may be exercise; give the person space",
                    false,
                    EveIntent.EXPLORE
                )
            }
            EveHumanActivity.UNKNOWN -> snapshot(EveMood.NEUTRAL, "human activity is unknown", false)
        }
    }

    /** Camera/permission failures are behavior events, not direct UI-only mood overrides. */
    @Synchronized
    fun cameraUnavailable(reason: String): EveDecision {
        arousal = 0.22f
        curiosity = (curiosity + 0.08f).coerceAtMost(1f)
        return snapshot(EveMood.CONCERNED, "camera unavailable: ${reason.take(80)}", false)
    }

    /** Switching lenses becomes a sensed event that updates the shared affect snapshot. */
    @Synchronized
    fun cameraSwitched(lens: String): EveDecision {
        arousal = 0.58f
        curiosity = (curiosity + 0.12f).coerceAtMost(1f)
        val safeLens = lens.trim().lowercase().take(16).ifBlank { "camera" }
        return snapshot(EveMood.CURIOUS, "camera switched to $safeLens", false)
    }

    /**
     * Hand signals are first-class sensory events in the same affect engine as vision, touch,
     * facial-expression estimates and idle behavior. These are visual cues, not mind reading.
     */
    @Synchronized
    fun observeHandGesture(signal: String, confidence: Float): EveDecision {
        val safeSignal = signal.trim().lowercase().take(32)
        if (safeSignal.isBlank() || !confidence.isFinite() || confidence < MIN_HAND_GESTURE_CONFIDENCE) {
            curiosity = (curiosity + 0.025f).coerceAtMost(1f)
            return snapshot(EveMood.CURIOUS, "uncertain hand signal", false)
        }

        fatigue = (fatigue - 0.05f).coerceAtLeast(0f)
        arousal = (0.50f + confidence.coerceIn(0f, 1f) * 0.35f).coerceIn(0f, 1f)
        val mood = when (safeSignal) {
            "wave", "open_palm" -> {
                valence = (valence + if (safeSignal == "wave") 0.22f else 0.06f).coerceAtMost(0.95f)
                familiarity = (familiarity + if (safeSignal == "wave") 0.12f else 0.025f).coerceAtMost(1f)
                curiosity = (curiosity + 0.06f).coerceAtMost(1f)
                if (safeSignal == "wave" && familiarity >= 0.50f) EveMood.FAMILIAR else EveMood.HAPPY
            }
            "victory", "i_love_you" -> {
                valence = (valence + 0.18f).coerceAtMost(0.95f)
                curiosity = (curiosity + 0.06f).coerceAtMost(1f)
                EveMood.PLAYFUL
            }
            "thumb_up" -> {
                valence = (valence + 0.17f).coerceAtMost(0.95f)
                frustration = (frustration - 0.12f).coerceAtLeast(0f)
                EveMood.HAPPY
            }
            "thumb_down", "angry_gesture" -> {
                valence = (valence - 0.16f).coerceAtLeast(-1f)
                frustration = (frustration + 0.16f).coerceAtMost(0.68f)
                EveMood.IRRITATED
            }
            "pointing_up" -> {
                curiosity = (curiosity + 0.15f).coerceAtMost(1f)
                EveMood.CURIOUS
            }
            "fist" -> {
                arousal = 0.68f
                curiosity = (curiosity + 0.04f).coerceAtMost(1f)
                EveMood.FOCUSED
            }
            "punch" -> {
                valence = (valence - 0.18f).coerceAtLeast(-0.55f)
                arousal = 0.92f
                frustration = (frustration + 0.10f).coerceAtMost(0.70f)
                EveMood.CONCERNED
            }
            else -> {
                curiosity = (curiosity + 0.04f).coerceAtMost(1f)
                EveMood.CURIOUS
            }
        }
        val canVocalize = safeSignal in setOf(
            "wave", "victory", "i_love_you", "thumb_up", "thumb_down", "angry_gesture", "punch"
        )
        return snapshot(mood, "recognized hand signal: $safeSignal", canVocalize)
    }

    /**
     * A complete offline-recognized utterance is a social cue, even when it is not a command.
     * We respond with gaze/affect, not spoken words or a claim that the sentence was understood.
     */
    @Synchronized
    fun respondToSpeech(utterance: String, addressedToEve: Boolean = false): EveDecision {
        val text = utterance.trim().lowercase()
        if (text.isBlank()) {
            return snapshot(EveMood.NEUTRAL, "empty speech input", false)
        }

        val isQuestion = listOf(
            "ماذا", "ما هذا", "كيف", "لماذا", "اين", "أين", "متى", "من", "هل",
            "what", "how", "why", "where", "when", "who", "which"
        ).any(text::contains) || text.endsWith("?")

        fatigue = (fatigue - 0.04f).coerceAtLeast(0f)
        if (addressedToEve) {
            valence = (valence + 0.07f * personality.warmth).coerceAtMost(0.88f)
            familiarity = (familiarity + 0.025f).coerceAtMost(0.90f)
            arousal = (arousal * 0.55f + 0.45f * 0.61f).coerceIn(0.25f, 0.80f)
        } else {
            arousal = (arousal * 0.62f + 0.38f * 0.53f).coerceIn(0.20f, 0.78f)
        }

        val mood = when {
            isQuestion -> {
                curiosity = (curiosity + 0.14f * personality.curiosityBias).coerceAtMost(1f)
                EveMood.CURIOUS
            }
            addressedToEve && personality.playfulness >= 0.58f && valence >= 0.20f -> {
                curiosity = (curiosity + 0.07f).coerceAtMost(1f)
                EveMood.PLAYFUL
            }
            addressedToEve -> {
                curiosity = (curiosity + 0.09f).coerceAtMost(1f)
                EveMood.CURIOUS
            }
            else -> {
                curiosity = (curiosity + 0.045f * personality.curiosityBias).coerceAtMost(0.94f)
                if (arousal >= 0.66f) EveMood.FOCUSED else EveMood.CURIOUS
            }
        }
        return snapshot(mood, if (isQuestion) "heard a question; curious nonverbal response" else "heard speech; attentive nonverbal response", false)
    }

    /**
     * Self-generated exploratory intent: high-confidence novelty changes the central affect state.
     * The prompt text is composed by EveCuriosityEngine from the observed label and local memory.
     */
    @Synchronized
    fun selfGeneratedCuriosity(label: String, confidence: Float, sightings: Int): EveDecision {
        val item = label.trim().lowercase().take(64)
        if (item.isBlank() || !confidence.isFinite() || confidence < 0.82f) {
            return snapshot(EveMood.NEUTRAL, "curiosity suppressed: insufficient visual confidence", false)
        }
        val novelty = when {
            sightings <= 1 -> 0.22f
            sightings <= 4 -> 0.12f
            else -> 0.04f
        }
        curiosity = (curiosity + 0.07f * personality.curiosityBias + novelty * confidence)
            .coerceIn(0f, 1f)
        arousal = (arousal * 0.62f + confidence * 0.38f).coerceIn(0.25f, 0.92f)
        valence = (valence + 0.025f * personality.warmth).coerceIn(-1f, 0.95f)
        fatigue = (fatigue - 0.025f).coerceAtLeast(0f)
        return snapshot(
            EveMood.CURIOUS,
            "self-generated curiosity: high-confidence observation=$item sightings=$sightings",
            false,
            EveIntent.EXPLORE
        )
    }

    @Synchronized
    fun observe(label: String, confidence: Float, sightings: Int, learnedAffinity: Float = 0f): EveDecision {
        val item = label.trim().lowercase()
        if (item.isBlank() || !confidence.isFinite() || confidence < 0.45f) {
            valence = (valence - 0.12f).coerceIn(-1f, 1f)
            arousal = 0.22f
            curiosity = (curiosity + 0.08f).coerceAtMost(1f)
            return snapshot(EveMood.CONCERNED, "uncertain visual label", false)
        }

        val kind = classify(item)
        val affinity = learnedAffinity.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        if (kind != Kind.OTHER) frustration = (frustration - 0.035f).coerceAtLeast(0f)
        fatigue = (fatigue - 0.12f).coerceAtLeast(0f)
        when (kind) {
            Kind.FOOD -> {
                valence = (valence + 0.16f + personality.warmth.coerceIn(0f, 1f) * 0.10f + affinity * 0.18f).coerceIn(-1f, 0.95f)
                arousal = 0.56f
                curiosity = 0.50f
            }
            Kind.ANIMAL -> {
                valence = (valence + 0.12f + personality.warmth.coerceIn(0f, 1f) * 0.08f + affinity * 0.18f).coerceIn(-1f, 0.80f)
                arousal = 0.63f
                curiosity = (0.56f + personality.curiosityBias.coerceIn(0f, 1f) * 0.44f).coerceIn(0f, 1f)
            }
            Kind.PERSON -> {
                valence = (valence + 0.06f + personality.warmth.coerceIn(0f, 1f) * 0.06f + affinity * 0.15f).coerceIn(-1f, 0.70f)
                arousal = 0.46f
                curiosity = 0.66f
                familiarity = (familiarity + 0.04f).coerceAtMost(0.8f)
            }
            Kind.PLANT -> {
                valence = (valence + 0.06f + affinity * 0.12f).coerceIn(-1f, 0.65f)
                arousal = 0.34f
                curiosity = 0.76f
            }
            Kind.OTHER -> {
                // A generic classifier label such as "pattern" is not treated as understanding.
                arousal = 0.30f + confidence * 0.12f
                curiosity = (0.44f + confidence * 0.30f).coerceIn(0f, 0.82f)
                valence = (valence * 0.78f).coerceIn(-1f, 1f)
            }
        }

        val repeatedlySeen = sightings >= 3
        val deliberatelyReinforced = sightings >= 2 && affinity >= 0.42f
        if (kind != Kind.OTHER && (repeatedlySeen || deliberatelyReinforced)) {
            familiarity = (familiarity + 0.18f + affinity.coerceAtLeast(0f) * 0.08f).coerceAtMost(1f)
            valence = (valence + 0.06f + affinity.coerceAtLeast(0f) * 0.08f).coerceAtMost(0.95f)
            val reason = if (deliberatelyReinforced) "familiar positive association: $item" else "familiar $kind: $item"
            return snapshot(EveMood.FAMILIAR, reason, false)
        }

        val mood = when (kind) {
            Kind.FOOD -> EveMood.HAPPY
            Kind.ANIMAL -> EveMood.CURIOUS
            Kind.PERSON -> EveMood.AWAKE
            Kind.PLANT -> EveMood.CURIOUS
            Kind.OTHER -> if (confidence >= 0.82f) EveMood.FOCUSED else EveMood.CURIOUS
        }

        // Only a first, high-confidence, meaningful observation may trigger sound.
        // Generic texture/pattern labels never chirp; the voice renderer adds its own cooldown.
        val meaningful = kind == Kind.FOOD || kind == Kind.ANIMAL || kind == Kind.PERSON
        val sound = meaningful && confidence >= 0.84f && sightings == 1
        return snapshot(mood, "observed $kind: $item", sound)
    }

    /**
     * Converts a stable local TFLite expression prediction into the shared affect state.
     * Labels are visual-expression estimates, not claims about a person's actual inner feelings.
     */
    @Synchronized
    fun observeFacialEmotion(label: String, confidence: Float): EveDecision {
        val emotion = label.trim().lowercase()
        val minimumConfidence = when (emotion) {
            "happy" -> 0.65f
            "sad", "angry", "fearful" -> 0.72f
            "surprised" -> 0.68f
            "neutral" -> 0.52f
            else -> 1f
        }
        if (emotion !in EveFacialEmotionClassifier.LABELS ||
            !confidence.isFinite() || confidence < minimumConfidence
        ) {
            return snapshot(
                EveMood.NEUTRAL,
                "uncertain facial-expression model output: label=$emotion confidence=$confidence minimum threshold=$minimumConfidence",
                false
            )
        }
        val certainty = confidence.coerceIn(0f, 1f)
        val changed = emotion != lastModelEmotion
        val mood = when (emotion) {
            "happy" -> {
                valence = (valence + 0.18f * certainty).coerceAtMost(0.95f)
                arousal = (0.38f + 0.28f * certainty).coerceIn(0.3f, 0.72f)
                curiosity = (curiosity * 0.8f + 0.09f).coerceIn(0f, 1f)
                EveMood.HAPPY
            }
            "sad" -> {
                valence = (valence - 0.15f * certainty).coerceAtLeast(-1f)
                arousal = (arousal * 0.72f + 0.10f).coerceIn(0.12f, 0.8f)
                curiosity = (curiosity * 0.85f + 0.05f).coerceIn(0f, 1f)
                EveMood.CONCERNED
            }
            "angry" -> {
                valence = (valence - 0.22f * certainty).coerceAtLeast(-1f)
                arousal = (0.48f + 0.38f * certainty).coerceIn(0.3f, 0.9f)
                curiosity = (curiosity * 0.72f + 0.10f).coerceIn(0f, 1f)
                EveMood.CONCERNED
            }
            "fearful" -> {
                valence = (valence - 0.16f * certainty).coerceAtLeast(-1f)
                arousal = (0.55f + 0.34f * certainty).coerceIn(0.35f, 0.92f)
                curiosity = (curiosity * 0.70f + 0.18f).coerceIn(0f, 1f)
                // A fearful expression in the observed person calls for a calmer, reassuring response.
                reassurance = (reassurance + 0.24f * certainty).coerceAtMost(1f)
                EveMood.CONCERNED
            }
            "surprised" -> {
                valence = (valence + 0.04f * certainty).coerceIn(-1f, 0.85f)
                arousal = (0.50f + 0.40f * certainty).coerceIn(0.4f, 0.95f)
                curiosity = (curiosity + 0.22f * certainty).coerceAtMost(1f)
                EveMood.SURPRISED
            }
            else -> { // neutral
                valence = (valence * 0.90f + 0.015f).coerceIn(-0.8f, 0.8f)
                arousal = (arousal * 0.76f + 0.08f).coerceIn(0.12f, 0.75f)
                curiosity = (curiosity * 0.92f + 0.035f).coerceIn(0f, 1f)
                EveMood.NEUTRAL
            }
        }
        lastModelEmotion = emotion
        // Keep sound rare and gentle; a stable label transition alone never creates chatter.
        val sound = emotion == "happy" && changed && certainty >= 0.94f
        val reason = "facial-expression model predicts $emotion at ${(certainty * 100).toInt()}% confidence"
        return snapshot(mood, reason, sound)
    }

    /**
     * Uses only on-device face cues exposed by ML Kit. A smile/eye probability is a visual cue,
     * not proof of a person's full emotional state. Side-facing/uncertain faces remain uncertain.
     */
    @Synchronized
    fun observeHumanFace(
        smilingProbability: Float?,
        leftEyeOpenProbability: Float?,
        rightEyeOpenProbability: Float?,
        headYawDegrees: Float
    ): EveDecision {
        if (!headYawDegrees.isFinite()) {
            return snapshot(EveMood.NEUTRAL, "uncertain head pose", false)
        }
        if (abs(headYawDegrees) > 22f) {
            curiosity = (curiosity + 0.10f).coerceAtMost(1f)
            val decision = snapshot(EveMood.CURIOUS, "face turned partly away", false)
            lastHumanMood = EveMood.CURIOUS
            return decision
        }

        val eyes = listOfNotNull(
            leftEyeOpenProbability?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
            rightEyeOpenProbability?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        )
        val eyesOpen = if (eyes.isEmpty()) null else eyes.average().toFloat()
        val smile = smilingProbability?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)

        val mood = when {
            eyesOpen != null && eyesOpen < 0.22f -> {
                // Closed eyes may be a blink even when the smile score is high.
                // Do not infer happiness from contradictory single-frame cues.
                arousal = (arousal * 0.72f + 0.28f * 0.24f).coerceIn(0.12f, 0.7f)
                curiosity = (curiosity * 0.8f + 0.08f).coerceIn(0f, 1f)
                EveMood.FOCUSED
            }
            smile != null && smile >= 0.72f -> {
                valence = (valence + 0.16f + smile * 0.12f).coerceAtMost(0.95f)
                arousal = (0.42f + smile * 0.20f).coerceIn(0.3f, 0.68f)
                curiosity = (curiosity * 0.72f + 0.28f * 0.45f).coerceIn(0f, 1f)
                EveMood.HAPPY
            }
            eyesOpen != null && eyesOpen >= 0.62f -> {
                valence = (valence * 0.92f + 0.02f).coerceIn(-0.5f, 0.8f)
                arousal = (arousal * 0.65f + 0.35f * 0.40f).coerceIn(0.15f, 0.8f)
                curiosity = (curiosity * 0.85f + 0.15f * 0.60f).coerceIn(0f, 1f)
                EveMood.AWAKE
            }
            else -> {
                // Face was detected, but its expression is ambiguous.
                curiosity = (curiosity * 0.8f + 0.1f).coerceIn(0f, 1f)
                EveMood.NEUTRAL
            }
        }

        val changed = mood != lastHumanMood
        lastHumanMood = mood
        val sound = mood == EveMood.HAPPY && changed && smile != null && smile >= 0.90f
        return snapshot(mood, "on-device face cue: $mood", sound)
    }

    @Synchronized
    fun touched(longPress: Boolean, eventTimeMs: Long? = null): EveDecision {
        fatigue = (fatigue - 0.18f).coerceAtLeast(0f)

        if (longPress) {
            // A deliberate, gentle hold is treated as reassurance, not another poke.
            frustration = (frustration * 0.28f).coerceAtLeast(0f)
            rapidTouchCount = 0
            lastTouchAtMs = 0L
            valence = (valence + 0.30f * personality.warmth.coerceIn(0f, 1f)).coerceAtMost(1f)
            arousal = 0.68f
            curiosity = 0.66f
            reassurance = (reassurance + 0.72f * personality.warmth.coerceIn(0f, 1f)).coerceAtMost(1f)
            jealousy = (jealousy - 0.24f).coerceAtLeast(0f)
            return snapshot(EveMood.HAPPY, "comforting long touch; frustration eased", true)
        }

        val previousFrustration = frustration
        if (eventTimeMs != null && eventTimeMs > 0L) {
            rapidTouchCount = if (
                lastTouchAtMs > 0L &&
                eventTimeMs >= lastTouchAtMs &&
                eventTimeMs - lastTouchAtMs <= 2_400L
            ) {
                (rapidTouchCount + 1).coerceAtMost(8)
            } else {
                1
            }
            lastTouchAtMs = eventTimeMs
            val patienceAdjustment = (0.68f - personality.patience.coerceIn(0f, 1f)) * 0.25f
            frustration = when {
                rapidTouchCount >= 5 -> maxOf(frustration, 0.88f + patienceAdjustment)
                rapidTouchCount >= 3 -> maxOf(frustration, 0.54f + patienceAdjustment)
                else -> frustration
            }.coerceIn(0f, 1f)
        }

        valence = (valence + 0.03f + personality.warmth.coerceIn(0f, 1f) * 0.07f).coerceAtMost(1f)
        arousal = 0.52f
        curiosity = (0.42f + personality.curiosityBias.coerceIn(0f, 1f) * 0.42f).coerceIn(0f, 1f)

        val mood = when {
            eventTimeMs != null && rapidTouchCount >= 5 -> EveMood.ANGRY
            eventTimeMs != null && rapidTouchCount >= 3 -> EveMood.IRRITATED
            else -> EveMood.CURIOUS
        }
        val crossedEmotionThreshold = when (mood) {
            EveMood.IRRITATED -> previousFrustration < 0.42f
            EveMood.ANGRY -> previousFrustration < 0.80f
            else -> false
        }
        val reason = when (mood) {
            EveMood.ANGRY -> "too many rapid touches; needs a little space"
            EveMood.IRRITATED -> "repeated rapid touches; mild irritation"
            else -> "gentle touch"
        }
        return snapshot(mood, reason, crossedEmotionThreshold)
    }

    @Synchronized
    fun idle(inactiveMs: Long, next: Int): EveDecision {
        val quietFor = inactiveMs.coerceAtLeast(0L)
        val deltaMs = if (lastIdleEvaluationQuietMs <= 0L) {
            quietFor.coerceAtMost(60_000L)
        } else {
            (quietFor - lastIdleEvaluationQuietMs).coerceIn(0L, 60_000L)
        }
        lastIdleEvaluationQuietMs = quietFor
        val steps = (deltaMs / 6_000f).coerceIn(0f, 10f)

        // Drives evolve with elapsed time rather than changing by a fixed amount each poll.
        fatigue = ((quietFor - 30_000L).coerceAtLeast(0L) / 210_000f).coerceIn(0f, 1f)
        val frustrationBeforeDecay = frustration
        frustration = (frustration - deltaMs / 1_000f * 0.0025f).coerceAtLeast(0f)
        val valenceDecay = 0.985f.pow(steps)
        valence = (valence * valenceDecay + 0.002f * steps).coerceIn(-0.5f, 0.75f)
        val arousalRetention = 0.84f.pow(steps)
        arousal = (0.27f + (arousal - 0.27f) * arousalRetention).coerceIn(0.12f, 0.80f)
        val curiosityTarget = (0.34f + 0.12f * personality.curiosityBias).coerceIn(0.30f, 0.55f)
        curiosity = (curiosityTarget + (curiosity - curiosityTarget) * 0.94f.pow(steps))
            .coerceIn(0.20f, 0.90f)
        // Social affects fade with real quiet time rather than switching on a timer or staying forever.
        jealousy = (jealousy * 0.90f.pow(steps)).coerceIn(0f, 1f)
        pride = (pride * 0.93f.pow(steps)).coerceIn(0f, 1f)
        reassurance = (reassurance * 0.985f.pow(steps)).coerceIn(0f, 1f)

        // After an event, hold the current affect briefly. A new goal is selected only when
        // there's enough quiet time to act on it; this prevents mood churn every six-second poll.
        val forcedRegulation = frustrationBeforeDecay >= 0.80f || frustration >= 0.80f
        if (quietFor < 12_000L && !forcedRegulation && frustration < 0.42f) {
            return snapshot(
                currentMood,
                "contextual idle: sustaining current affect while waiting for new evidence; quietForMs=$quietFor",
                false,
                lastIdleIntent
            )
        }

        // Utility scores express competing needs. The character does not follow a fixed timeline:
        // persistent warmth/familiarity, curiosity, energy, irritation, and recent goals compete.
        val warmth = personality.warmth.coerceIn(0f, 1f)
        val curiosityBias = personality.curiosityBias.coerceIn(0f, 1f)
        val playfulness = personality.playfulness.coerceIn(0f, 1f)
        val patience = personality.patience.coerceIn(0f, 1f)
        val scores = linkedMapOf(
            EveIntent.REGULATE to (
                frustration * 1.12f + (-valence).coerceAtLeast(0f) * 0.22f
                ),
            EveIntent.REST to (
                0.08f + fatigue * 0.90f +
                    (if (quietFor >= 90_000L) 0.22f else if (quietFor >= 45_000L) 0.08f else 0f) +
                    (if (arousal < 0.22f) 0.10f else 0f)
                ),
            EveIntent.CONNECT to (
                warmth * 0.12f + familiarity * 0.70f +
                    (-valence).coerceAtLeast(0f) * 0.72f +
                    (if (currentMood == EveMood.HAPPY || currentMood == EveMood.FAMILIAR) 0.12f else 0f)
                ),
            EveIntent.EXPLORE to (
                curiosity * 0.40f + curiosityBias * 0.24f +
                    (if (lastIdleIntent != EveIntent.EXPLORE) 0.04f else 0f) -
                    (if (valence < -0.45f) 0.08f else 0f)
                ),
            EveIntent.PLAY to (
                playfulness * 0.48f + valence.coerceAtLeast(0f) * 0.16f +
                    arousal * 0.12f - fatigue * 0.65f - frustration * (0.70f + (1f - patience) * 0.20f)
                ),
            EveIntent.FOCUS to (
                arousal * 0.30f + curiosity * 0.22f + (1f - playfulness) * 0.11f -
                    fatigue * 0.45f - frustration * 0.35f
                )
        ).mapValues { (_, score) -> score.coerceIn(0f, 1.5f) }

        val best = scores.maxByOrNull { it.value }!!.key
        val activeIntent = lastIdleIntent
        val activeScore = activeIntent?.let { scores[it] } ?: Float.NEGATIVE_INFINITY
        val bestScore = scores[best] ?: 0f
        val intentAge = (quietFor - idleIntentStartedAtQuietMs).coerceAtLeast(0L)
        val minimumDwellMs = when (activeIntent) {
            EveIntent.REGULATE -> 6_000L
            EveIntent.REST -> 30_000L
            EveIntent.PLAY -> 18_000L
            EveIntent.EXPLORE -> 18_000L
            EveIntent.CONNECT -> 15_000L
            EveIntent.FOCUS -> 15_000L
            null -> 0L
        }

        // Hysteresis: retain a still-relevant goal for a short dwell period; abandon it early
        // only when another goal is clearly more urgent.
        val selected = when {
            forcedRegulation -> EveIntent.REGULATE
            activeIntent != null && activeScore >= bestScore - 0.16f &&
                (intentAge < minimumDwellMs || activeScore >= bestScore - 0.08f) -> activeIntent
            else -> best
        }

        if (selected == lastIdleIntent) {
            repeatedIdleIntentCount = (repeatedIdleIntentCount + 1).coerceAtMost(10)
        } else {
            lastIdleIntent = selected
            idleIntentStartedAtQuietMs = quietFor
            repeatedIdleIntentCount = 1
        }

        val mood = when (selected) {
            EveIntent.REGULATE -> when {
                frustration >= 0.80f || frustrationBeforeDecay >= 0.80f -> EveMood.ANGRY
                else -> EveMood.IRRITATED
            }
            EveIntent.REST -> if (fatigue >= 0.36f || quietFor >= 150_000L) EveMood.SLEEPY else currentMood
            EveIntent.CONNECT -> when {
                valence < -0.30f -> EveMood.CONCERNED
                familiarity >= 0.25f -> EveMood.FAMILIAR
                else -> currentMood
            }
            EveIntent.EXPLORE -> EveMood.CURIOUS
            EveIntent.PLAY -> if (valence < 0.08f) EveMood.CURIOUS else EveMood.PLAYFUL
            EveIntent.FOCUS -> EveMood.FOCUSED
        }
        val explanation = when (selected) {
            EveIntent.EXPLORE -> "curiosity has the highest current drive"
            EveIntent.CONNECT -> "familiarity or social warmth needs attention"
            EveIntent.PLAY -> "play drive is high while energy and patience permit"
            EveIntent.REST -> "fatigue and quiet-time recovery outweigh other drives"
            EveIntent.FOCUS -> "alertness and curiosity favor quiet observation"
            EveIntent.REGULATE -> "frustration or negative affect needs regulation"
        }
        return snapshot(
            mood,
            "utility-selected autonomous goal=${selected.name.lowercase()} ($explanation); " +
                "quietForMs=$quietFor scores=" + scores.entries.joinToString(",") { "${it.key.name.lowercase()}=${"%.2f".format(java.util.Locale.ROOT, it.value)}" },
            false,
            selected
        )
    }

    private fun classify(item: String): Kind = when {
        listOf("food", "fruit", "banana", "apple", "pizza", "bread", "meal", "sandwich", "vegetable", "cake", "orange").any(item::contains) -> Kind.FOOD
        listOf("animal", "cat", "dog", "bird", "pet", "puppy", "kitten", "horse", "rabbit", "fish").any(item::contains) -> Kind.ANIMAL
        listOf("person", "people", "human", "face", "man", "woman", "child", "boy", "girl").any(item::contains) -> Kind.PERSON
        listOf("plant", "flower", "tree", "leaf", "grass", "garden").any(item::contains) -> Kind.PLANT
        else -> Kind.OTHER
    }

    private fun snapshot(
        mood: EveMood,
        reason: String,
        sound: Boolean,
        intent: EveIntent? = null
    ): EveDecision {
        currentMood = mood
        return EveDecision(
            mood = mood,
            reason = reason,
            playSound = sound,
            valence = valence.coerceIn(-1f, 1f),
            arousal = arousal.coerceIn(0f, 1f),
            curiosity = curiosity.coerceIn(0f, 1f),
            familiarity = familiarity.coerceIn(0f, 1f),
            fatigue = fatigue.coerceIn(0f, 1f),
            frustration = frustration.coerceIn(0f, 1f),
            playfulness = personality.playfulness.coerceIn(0f, 1f),
            intent = intent,
            jealousy = jealousy.coerceIn(0f, 1f),
            pride = pride.coerceIn(0f, 1f),
            reassurance = reassurance.coerceIn(0f, 1f)
        )
    }
}

/** Persistent on-device counters. Only labels/counts/timestamps are saved, never camera frames. */
class EveMemory(context: Context) {
    private val prefs = context.getSharedPreferences("eve_local_memory", Context.MODE_PRIVATE)

    /**
     * Stable character traits survive app restarts. These are small behavioral preferences,
     * not neural-network weights or proof of consciousness.
     */
    @Synchronized
    fun learnedPersonality(): EvePersonality = EvePersonality(
        curiosityBias = prefs.getFloat("trait:curiosity", 0.72f),
        warmth = prefs.getFloat("trait:warmth", 0.76f),
        playfulness = prefs.getFloat("trait:playfulness", 0.62f),
        patience = prefs.getFloat("trait:patience", 0.68f)
    ).bounded()

    /**
     * Update only from meaningful, confidence-worthy feedback, with an event cooldown so one
     * burst of taps cannot rewrite the character. This is transparent online adaptation.
     */
    @Synchronized
    fun adaptPersonality(event: String, strength: Float = 1f): EvePersonality {
        val key = event.trim().lowercase().take(48)
        if (key.isBlank() || !strength.isFinite()) return learnedPersonality()
        val now = System.currentTimeMillis()
        val lastAtKey = "trait:last:$key"
        if (now - prefs.getLong(lastAtKey, 0L) in 0L until 25_000L) {
            return learnedPersonality()
        }
        val amount = strength.coerceIn(0f, 1f) * 0.012f
        val old = learnedPersonality()
        val next = when (key) {
            "gentle_contact" -> old.copy(
                warmth = old.warmth + amount,
                patience = old.patience + amount * 0.45f,
                playfulness = old.playfulness + amount * 0.18f
            )
            "friendly_gesture" -> old.copy(
                warmth = old.warmth + amount * 0.70f,
                playfulness = old.playfulness + amount * 0.55f
            )
            "curiosity_reward" -> old.copy(
                curiosityBias = old.curiosityBias + amount * 0.45f
            )
            "overstimulation" -> old.copy(
                patience = old.patience - amount * 0.80f,
                playfulness = old.playfulness - amount * 0.25f
            )
            else -> old
        }.bounded()
        prefs.edit()
            .putFloat("trait:curiosity", next.curiosityBias)
            .putFloat("trait:warmth", next.warmth)
            .putFloat("trait:playfulness", next.playfulness)
            .putFloat("trait:patience", next.patience)
            .putLong(lastAtKey, now)
            .apply()
        return next
    }

    @Synchronized
    fun remember(label: String): Int {
        val key = label.trim().lowercase().take(80)
        if (key.isBlank()) return 0
        val next = (prefs.getInt("count:$key", 0) + 1).coerceAtMost(100_000)
        prefs.edit()
            .putInt("count:$key", next)
            .putLong("seen:$key", System.currentTimeMillis())
            .apply()
        return next
    }

    fun sightings(label: String): Int =
        prefs.getInt("count:${label.trim().lowercase().take(80)}", 0)

    /** Persist a tiny per-label preference value, not camera frames or identity information. */
    fun learnedAffinity(label: String): Float {
        val key = label.trim().lowercase().take(80)
        if (key.isBlank()) return 0f
        return prefs.getFloat("affinity:$key", 0f).takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
    }

    /**
     * A long, deliberate hold can act as positive reinforcement for the last high-confidence
     * meaningful label. This is transparent online personalization, not retraining the vision model.
     */
    @Synchronized
    fun reinforce(label: String, reward: Float = 1f): Float {
        val key = label.trim().lowercase().take(80)
        if (key.isBlank() || !reward.isFinite()) return learnedAffinity(label)
        val old = learnedAffinity(key)
        val boundedReward = reward.coerceIn(-1f, 1f)
        val updated = (old * 0.78f + boundedReward * 0.22f).coerceIn(-1f, 1f)
        val count = (prefs.getInt("feedback:$key", 0) + 1).coerceAtMost(100_000)
        prefs.edit()
            .putFloat("affinity:$key", updated)
            .putInt("feedback:$key", count)
            .putLong("feedbackAt:$key", System.currentTimeMillis())
            .apply()
        return updated
    }

    fun feedbackCount(label: String): Int =
        prefs.getInt("feedback:${label.trim().lowercase().take(80)}", 0)

    /** A compact, recency-ranked prompt context; only labels, counts, affinities and timestamps. */
    @Synchronized
    fun recentVisualMemories(limit: Int = 5): List<String> {
        val recent = prefs.all.entries.mapNotNull { entry ->
            val key = entry.key
            val timestamp = entry.value as? Long
            if (!key.startsWith("seen:") || timestamp == null) return@mapNotNull null
            val label = key.removePrefix("seen:").take(80)
            if (label.isBlank()) return@mapNotNull null
            Triple(label, timestamp, prefs.getInt("count:$label", 0))
        }.sortedByDescending { it.second }.take(limit.coerceIn(0, 10))
        return recent.map { (label, _, count) ->
            "$label (مرات الرؤية=$count، الارتباط المتعلم=${"%.2f".format(java.util.Locale.ROOT, learnedAffinity(label))})"
        }
    }

    /** Keep only the latest short user/model exchanges to ground the next local generation. */
    @Synchronized
    fun rememberDialogue(userText: String, assistantText: String) {
        val user = userText.trim().take(280)
        val answer = assistantText.trim().take(420)
        if (user.isBlank() || answer.isBlank()) return
        val now = maxOf(System.currentTimeMillis(), prefs.getLong("dialogue:last_timestamp", 0L) + 1L)
        prefs.edit()
            .putString("dialogue:$now:user", user)
            .putString("dialogue:$now:assistant", answer)
            .putLong("dialogue:last_timestamp", now)
            .apply()
        val oldTimes = prefs.all.keys.asSequence()
            .filter { it.startsWith("dialogue:") && it.endsWith(":user") }
            .mapNotNull { it.removePrefix("dialogue:").removeSuffix(":user").toLongOrNull() }
            .sortedDescending()
            .drop(MAX_DIALOGUE_MEMORIES)
            .toList()
        if (oldTimes.isNotEmpty()) {
            val edit = prefs.edit()
            oldTimes.forEach { timestamp ->
                edit.remove("dialogue:$timestamp:user")
                edit.remove("dialogue:$timestamp:assistant")
            }
            edit.apply()
        }
    }

    @Synchronized
    fun recentDialogues(limit: Int = 3): List<String> {
        val times = prefs.all.keys.asSequence()
            .filter { it.startsWith("dialogue:") && it.endsWith(":user") }
            .mapNotNull { it.removePrefix("dialogue:").removeSuffix(":user").toLongOrNull() }
            .sortedDescending()
            .take(limit.coerceIn(0, MAX_DIALOGUE_MEMORIES))
        return times.mapNotNull { timestamp ->
            val user = prefs.getString("dialogue:$timestamp:user", null) ?: return@mapNotNull null
            val assistant = prefs.getString("dialogue:$timestamp:assistant", null) ?: return@mapNotNull null
            "المستخدم: $user | إيف: $assistant"
        }.toList()
    }

    @Synchronized
    fun rememberReflection(note: String) {
        val safe = note.trim().replace(Regex("\\s+"), " ").take(240)
        if (safe.isBlank()) return
        val now = maxOf(System.currentTimeMillis(), prefs.getLong("reflection:last_timestamp", 0L) + 1L)
        prefs.edit().putString("reflection:$now", safe).putLong("reflection:last_timestamp", now).apply()
        val oldTimes = prefs.all.keys.asSequence()
            .filter { it.startsWith("reflection:") && it != "reflection:last_timestamp" }
            .mapNotNull { it.removePrefix("reflection:").toLongOrNull() }
            .sortedDescending()
            .drop(MAX_REFLECTION_MEMORIES)
            .toList()
        if (oldTimes.isNotEmpty()) {
            val edit = prefs.edit()
            oldTimes.forEach { edit.remove("reflection:$it") }
            edit.apply()
        }
    }

    @Synchronized
    fun recentReflections(limit: Int = 3): List<String> = prefs.all.keys.asSequence()
        .filter { it.startsWith("reflection:") && it != "reflection:last_timestamp" }
        .mapNotNull { key ->
            val timestamp = key.removePrefix("reflection:").toLongOrNull() ?: return@mapNotNull null
            val note = prefs.getString(key, null) ?: return@mapNotNull null
            timestamp to note
        }
        .sortedByDescending { it.first }
        .take(limit.coerceIn(0, MAX_REFLECTION_MEMORIES))
        .map { it.second }
        .toList()

    companion object {
        private const val MAX_DIALOGUE_MEMORIES = 12
        private const val MAX_REFLECTION_MEMORIES = 10
    }
}
