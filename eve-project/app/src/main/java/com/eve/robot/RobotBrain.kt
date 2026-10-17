package com.eve.robot

import android.content.Context
import kotlin.math.abs

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
    val intent: EveIntent? = null
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

    private var valence = 0.12f       // negative = wary, positive = warm
    private var arousal = 0.32f       // calm <-> alert
    private var curiosity = 0.58f
    private var familiarity = 0.05f
    private var fatigue = 0f
    private var frustration = 0f
    private var lastTouchAtMs = 0L
    private var rapidTouchCount = 0
    private var lastHumanMood: EveMood? = null
    private var lastModelEmotion: String? = null
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
                snapshot(EveMood.PLAYFUL, "skeptic EVE wins in $safeGame", false)
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
        if (safeSignal.isBlank() || !confidence.isFinite() || confidence < 0.68f) {
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
    @Suppress("UNUSED_PARAMETER")
    fun idle(inactiveMs: Long, next: Int): EveDecision {
        // Update continuous drives first. No random mood picker is used.
        val quietFor = inactiveMs.coerceAtLeast(0L)
        fatigue = ((quietFor - 45_000L).coerceAtLeast(0L) / 240_000f).coerceIn(0f, 1f)
        val frustrationBeforeQuietDecay = frustration
        if (quietFor >= 5_000L) {
            // Irritation fades gradually during a short pause; it should not jump from angry
            // to calm just because the idle loop ran once.
            val decay = if (quietFor >= 45_000L) 0.16f else 0.03f
            frustration = (frustration - decay).coerceAtLeast(0f)
        }
        valence = (valence * 0.985f + 0.002f).coerceIn(-0.5f, 0.75f)
        arousal = (arousal * 0.84f + 0.16f * 0.27f).coerceIn(0.12f, 0.80f)
        curiosity = (curiosity * 0.94f +
            if (quietFor < 45_000L) 0.035f * personality.curiosityBias.coerceIn(0f, 1f) else 0.012f)
            .coerceIn(0.25f, 0.90f)

        // Goal stages are deterministic and contextual: neutral pause, exploration, a
        // personality-shaped play/focus phase, rest, a second curiosity phase, then rest.
        // This prevents the old repeated/random-looking mood flips while preserving agency.
        val forced: Pair<EveIntent, EveMood>? = when {
            (frustrationBeforeQuietDecay >= 0.80f || frustration >= 0.80f) && quietFor < 12_000L ->
                EveIntent.REGULATE to EveMood.ANGRY
            frustration >= 0.42f && quietFor < 24_000L ->
                EveIntent.REGULATE to EveMood.IRRITATED
            valence < -0.38f && quietFor < 90_000L ->
                EveIntent.CONNECT to EveMood.CONCERNED
            else -> null
        }

        val selected: Pair<EveIntent, EveMood> = forced ?: when {
            quietFor < 12_000L -> EveIntent.CONNECT to EveMood.NEUTRAL
            quietFor < 30_000L -> EveIntent.EXPLORE to EveMood.CURIOUS
            quietFor < 42_000L -> {
                val play = personality.playfulness.coerceIn(0f, 1f) * 0.38f +
                    valence.coerceAtLeast(0f) * 0.16f +
                    (1f - fatigue) * 0.12f +
                    (if (quietFor in 30_000L..42_000L) 0.10f else 0f) -
                    frustration * 0.55f
                val focus = curiosity * 0.23f + arousal * 0.22f + 0.14f -
                    frustration * 0.15f - fatigue * 0.20f
                if (play >= focus) {
                    EveIntent.PLAY to if (valence < 0.08f) EveMood.CURIOUS else EveMood.PLAYFUL
                } else {
                    EveIntent.FOCUS to EveMood.FOCUSED
                }
            }
            quietFor < 90_000L -> EveIntent.REST to EveMood.SLEEPY
            quietFor < 150_000L -> EveIntent.EXPLORE to EveMood.CURIOUS
            else -> EveIntent.REST to EveMood.SLEEPY
        }

        if (selected.first == lastIdleIntent) {
            repeatedIdleIntentCount = (repeatedIdleIntentCount + 1).coerceAtMost(10)
        } else {
            lastIdleIntent = selected.first
            repeatedIdleIntentCount = 1
        }
        val explanation = when (selected.first) {
            EveIntent.EXPLORE -> "seeking something novel"
            EveIntent.CONNECT -> "checking in socially"
            EveIntent.PLAY -> "looking for playful interaction"
            EveIntent.REST -> "resting after quiet time"
            EveIntent.FOCUS -> "quietly observing"
            EveIntent.REGULATE -> "regulating frustration"
        }
        return snapshot(
            selected.second,
            "contextual idle goal=${selected.first.name.lowercase()} ($explanation); quietForMs=$quietFor",
            false,
            selected.first
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
    ) = EveDecision(
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
        intent = intent
    )
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
}
