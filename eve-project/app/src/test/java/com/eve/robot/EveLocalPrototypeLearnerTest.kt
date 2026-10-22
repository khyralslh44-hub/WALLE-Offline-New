package com.eve.robot

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EveLocalPrototypeLearnerTest {
    private fun learner(file: File) = EveLocalPrototypeLearner(
        stateFile = file,
        allowedLabels = setOf("wave", "still"),
        featureCount = 3,
        minimumExamplesPerLabel = 3
    )

    @Test fun needsSeveralVerifiedExamplesBeforePredicting() {
        val file = File.createTempFile("eve-learn", ".properties").apply { delete() }
        val model = learner(file)
        assertTrue(model.learn("wave", floatArrayOf(0.9f, 0.8f, 0.1f), EveLearningLabelSource.USER_CONFIRMED))
        assertNull(model.predict(floatArrayOf(0.9f, 0.8f, 0.1f)))
        assertTrue(model.learn("wave", floatArrayOf(0.88f, 0.82f, 0.12f), EveLearningLabelSource.TRUSTED_SENSOR))
        assertTrue(model.learn("wave", floatArrayOf(0.92f, 0.78f, 0.08f), EveLearningLabelSource.USER_CONFIRMED))
        assertEquals("wave", model.predict(floatArrayOf(0.9f, 0.8f, 0.1f))?.label)
        file.delete()
    }

    @Test fun refusesSelfGeneratedLabelsAndUnsafeExamples() {
        val file = File.createTempFile("eve-learn", ".properties").apply { delete() }
        val model = learner(file)
        assertFalse(model.learn("wave", floatArrayOf(0.9f, 0.8f, 0.1f), EveLearningLabelSource.MODEL_PREDICTION))
        assertFalse(model.learn("unknown", floatArrayOf(0.9f, 0.8f, 0.1f), EveLearningLabelSource.USER_CONFIRMED))
        assertFalse(model.learn("wave", floatArrayOf(Float.NaN, 0.2f, 0.3f), EveLearningLabelSource.USER_CONFIRMED))
        assertTrue(model.exampleCounts().isEmpty())
        file.delete()
    }

    @Test fun learnedModelPersistsAcrossInstances() {
        val file = File.createTempFile("eve-learn", ".properties").apply { delete() }
        val first = learner(file)
        repeat(3) {
            assertTrue(first.learn("still", floatArrayOf(0.1f, 0.1f, 0.9f), EveLearningLabelSource.USER_CONFIRMED))
        }
        val reloaded = learner(file)
        assertEquals("still", reloaded.predict(floatArrayOf(0.1f, 0.1f, 0.9f))?.label)
        file.delete()
    }

    @Test fun rejectsAmbiguousOrDistantPredictions() {
        val file = File.createTempFile("eve-learn", ".properties").apply { delete() }
        val model = learner(file)
        repeat(3) { model.learn("wave", floatArrayOf(0.9f, 0.9f, 0.1f), EveLearningLabelSource.USER_CONFIRMED) }
        repeat(3) { model.learn("still", floatArrayOf(0.1f, 0.1f, 0.9f), EveLearningLabelSource.USER_CONFIRMED) }
        assertNull(model.predict(floatArrayOf(0.5f, 0.5f, 0.5f)))
        assertNull(model.predict(floatArrayOf(0f, 1f, 0f)))
        file.delete()
    }
}
