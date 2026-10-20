package com.eve.robot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EveVisionEvidencePolicyTest {
    @Test fun sceneLevelDogGuessIsSuppressedWhenPersonIsSeenButNoDogBoxConfirmsIt() {
        assertTrue(
            EveVisionEvidencePolicy.shouldSuppressAnimalLabel(
                label = "dog",
                personFaceRecentlyDetected = true,
                freshObjectLabels = setOf("person")
            )
        )
    }

    @Test fun dogCanRemainWhenObjectDetectorFindsBothPersonAndDogBoxes() {
        assertFalse(
            EveVisionEvidencePolicy.shouldSuppressAnimalLabel(
                label = "dog",
                personFaceRecentlyDetected = true,
                freshObjectLabels = setOf("person", "dog")
            )
        )
    }

    @Test fun dogLabelIsNotSuppressedWhenNoPersonFaceWasRecentlyDetected() {
        assertFalse(
            EveVisionEvidencePolicy.shouldSuppressAnimalLabel(
                label = "dog",
                personFaceRecentlyDetected = false,
                freshObjectLabels = emptySet()
            )
        )
    }

    @Test fun puppyAndDogShareCategoryButHotDogIsNotTreatedAsAnAnimal() {
        assertFalse(
            EveVisionEvidencePolicy.shouldSuppressAnimalLabel(
                label = "puppy",
                personFaceRecentlyDetected = true,
                freshObjectLabels = setOf("person", "dog")
            )
        )
        assertFalse(EveVisionEvidencePolicy.isAnimalLabel("hot dog"))
    }

    @Test fun nonAnimalCategoriesAreNotAffectedByThisGuard() {
        assertFalse(
            EveVisionEvidencePolicy.shouldSuppressAnimalLabel(
                label = "person",
                personFaceRecentlyDetected = true,
                freshObjectLabels = emptySet()
            )
        )
        assertFalse(
            EveVisionEvidencePolicy.shouldSuppressAnimalLabel(
                label = "banana",
                personFaceRecentlyDetected = true,
                freshObjectLabels = setOf("person")
            )
        )
    }
}
