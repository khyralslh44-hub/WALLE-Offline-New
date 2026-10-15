package com.eve.robot

import android.content.Context
import kotlin.math.abs

enum class EveMood {
    AWAKE, NEUTRAL, CURIOUS, HAPPY, SURPRISED, CONCERNED, FAMILIAR, SLEEPY, FOCUSED,
    PLAYFUL, IRRITATED, ANGRY
}

/**
 * Stable personality traits tune EVE's behavior. They are deliberately bounded and deterministic.
 */
data class EvePersonality(
    val curiosityBias: Float = 0.72f,
    val warmth: Float = 0.76f,
    val playfulness: Float = 0.62f,
    val patience: Float = 0.68f
)

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
    val playfulness: Float = 0.58f
)

/**
 * Stateful local affect engine. It reacts to confidence, semantic category, repeated sightings,
 * touch and inactivity. This is a lightweight emotional-behavior simulation, not consciousness.
 */
class RobotBrain(private val personality: EvePersonality = EvePersonality()) {
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

    private enum class Kind { FOOD, ANIMAL, PERSON, PLANT, OTHER }

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
        // Idle actions follow elapsed time and the current affect state, never a random mood picker.
        val quietFor = inactiveMs.coerceAtLeast(0L)
        fatigue = ((quietFor - 45_000L).coerceAtLeast(0L) / 240_000f).coerceIn(0f, 1f)
        if (quietFor >= 5_000L) {
            frustration = (frustration - if (quietFor >= 45_000L) 0.16f else 0.11f).coerceAtLeast(0f)
        }
        valence = (valence * 0.985f + 0.002f).coerceIn(-0.5f, 0.75f)
        arousal = (arousal * 0.84f + 0.16f * 0.27f).coerceIn(0.12f, 0.80f)
        curiosity = (curiosity * 0.94f +
            if (quietFor < 45_000L) 0.035f * personality.curiosityBias.coerceIn(0f, 1f) else 0.012f)
            .coerceIn(0.25f, 0.90f)

        val mood = when {
            frustration >= 0.80f && quietFor < 12_000L -> EveMood.ANGRY
            frustration >= 0.42f && quietFor < 24_000L -> EveMood.IRRITATED
            valence < -0.38f && quietFor < 90_000L -> EveMood.CONCERNED
            quietFor < 10_000L -> EveMood.NEUTRAL
            quietFor < 25_000L -> when {
                familiarity > 0.58f -> EveMood.FAMILIAR
                curiosity >= 0.45f -> EveMood.CURIOUS
                else -> EveMood.NEUTRAL
            }
            quietFor < 45_000L -> when {
                valence > 0.60f -> EveMood.HAPPY
                familiarity > 0.70f -> EveMood.FAMILIAR
                personality.playfulness.coerceIn(0f, 1f) >= 0.58f &&
                    curiosity >= 0.50f && frustration < 0.30f -> EveMood.PLAYFUL
                else -> EveMood.FOCUSED
            }
            quietFor < 90_000L -> EveMood.SLEEPY
            // After resting, EVE gently reorients to her surroundings instead of staying asleep forever.
            quietFor < 120_000L -> EveMood.CURIOUS
            quietFor < 180_000L -> if (familiarity > 0.62f) EveMood.FAMILIAR else EveMood.NEUTRAL
            fatigue > 0.65f -> EveMood.SLEEPY
            curiosity >= 0.60f -> EveMood.CURIOUS
            else -> EveMood.NEUTRAL
        }
        return snapshot(mood, "contextual idle: quietForMs=$quietFor", false)
    }

    private fun classify(item: String): Kind = when {
        listOf("food", "fruit", "banana", "apple", "pizza", "bread", "meal", "sandwich", "vegetable", "cake", "orange").any(item::contains) -> Kind.FOOD
        listOf("animal", "cat", "dog", "bird", "pet", "puppy", "kitten", "horse", "rabbit", "fish").any(item::contains) -> Kind.ANIMAL
        listOf("person", "people", "human", "face", "man", "woman", "child", "boy", "girl").any(item::contains) -> Kind.PERSON
        listOf("plant", "flower", "tree", "leaf", "grass", "garden").any(item::contains) -> Kind.PLANT
        else -> Kind.OTHER
    }

    private fun snapshot(mood: EveMood, reason: String, sound: Boolean) = EveDecision(
        mood = mood,
        reason = reason,
        playSound = sound,
        valence = valence.coerceIn(-1f, 1f),
        arousal = arousal.coerceIn(0f, 1f),
        curiosity = curiosity.coerceIn(0f, 1f),
        familiarity = familiarity.coerceIn(0f, 1f),
        fatigue = fatigue.coerceIn(0f, 1f),
        frustration = frustration.coerceIn(0f, 1f),
        playfulness = personality.playfulness.coerceIn(0f, 1f)
    )
}

/** Persistent on-device counters. Only labels/counts/timestamps are saved, never camera frames. */
class EveMemory(context: Context) {
    private val prefs = context.getSharedPreferences("eve_local_memory", Context.MODE_PRIVATE)

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
