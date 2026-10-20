package com.eve.robot

import java.util.Locale

/**
 * Conservative cross-check for whole-image animal guesses.
 *
 * A face detector can confirm that a person is present, but it cannot prove that an animal
 * is absent. Therefore, when a face is present, a scene-level animal guess is accepted only
 * if the independent, box-based object detector reports both a person and that same animal
 * in the current scene. This favors "uncertain" over silently saving a wrong identity label.
 */
object EveVisionEvidencePolicy {
    private val animalGroups: List<Set<String>> = listOf(
        setOf(
            "dog", "puppy", "labrador retriever", "golden retriever", "german shepherd",
            "standard poodle", "miniature poodle", "beagle", "pug", "chihuahua",
            "bulldog", "rottweiler", "doberman", "boxer", "terrier", "retriever",
            "great dane", "husky", "dalmatian"
        ),
        setOf("cat", "kitten", "tabby", "tiger cat", "persian cat", "siamese cat", "egyptian cat"),
        setOf("bird", "hen", "rooster", "chicken", "parrot"),
        setOf("horse"),
        setOf("cow", "cattle", "ox"),
        setOf("sheep", "ram", "ewe"),
        setOf("elephant"),
        setOf("bear", "black bear", "brown bear", "polar bear", "grizzly"),
        setOf("zebra"),
        setOf("giraffe"),
        setOf("fish", "goldfish"),
        setOf("rabbit", "hare", "bunny"),
        setOf("turtle", "tortoise"),
        setOf("hamster"),
        setOf("snake"),
        setOf("lizard", "gecko"),
        setOf("butterfly", "moth"),
        setOf("spider"),
        setOf("frog", "toad")
    )

    private val personLabels = setOf("person", "people", "human", "man", "woman", "child", "boy", "girl")

    fun normalize(label: String): String = label.trim()
        .lowercase(Locale.ROOT)
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(Regex("\\s+"), " ")

    fun isAnimalLabel(label: String): Boolean {
        val normalized = normalize(label)
        // Exact normalized labels avoid treating "hot dog" as an animal.
        return animalGroups.any { normalized in it } || normalized == "animal" || normalized == "pet"
    }

    private fun canonicalAnimal(label: String): Int? {
        val normalized = normalize(label)
        return animalGroups.indexOfFirst { normalized in it }.takeIf { it >= 0 }
    }

    /**
     * Returns true only for an animal label that conflicts with current person evidence.
     * [freshObjectLabels] must come from recent, box-based object detection, not image labels.
     */
    fun shouldSuppressAnimalLabel(
        label: String,
        personFaceRecentlyDetected: Boolean,
        freshObjectLabels: Set<String>
    ): Boolean {
        if (!personFaceRecentlyDetected || !isAnimalLabel(label)) return false
        val normalized = freshObjectLabels.map(::normalize).toSet()
        val hasPersonBox = normalized.any { it in personLabels }
        val targetAnimal = canonicalAnimal(label)
        val hasMatchingAnimalBox = if (targetAnimal != null) {
            normalized.any { canonicalAnimal(it) == targetAnimal }
        } else {
            // Generic "animal"/"pet" needs at least one concrete animal box, alongside person.
            normalized.any { canonicalAnimal(it) != null }
        }
        return !(hasPersonBox && hasMatchingAnimalBox)
    }
}
