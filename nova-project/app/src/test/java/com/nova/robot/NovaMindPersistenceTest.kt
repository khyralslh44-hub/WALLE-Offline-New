package com.nova.robot

import org.junit.Assert.*
import org.junit.Test

class NovaMindPersistenceTest {
    @Test fun correctionSnapshotIsReusedByANewMindInstance() {
        val sharedMemory = InMemoryNovaMindMemory()
        val firstMind = NovaMind(sharedMemory)
        firstMind.learnFromCorrection("عبارة تدريب خاصة بنوفا", NovaIntent.MEMORY_REQUEST, 1234L)
        val savedSnapshot = sharedMemory.read("novamind_own_model_snapshot_v1")
        assertNotNull(savedSnapshot)

        val secondMind = NovaMind(sharedMemory)
        assertEquals(
            firstMind.classifyIntent("عبارة تدريب خاصة بنوفا"),
            secondMind.classifyIntent("عبارة تدريب خاصة بنوفا")
        )
    }

    @Test fun replyIdentifiesItsOwnNeuralClassifierSeparatelyFromTextGeneration() {
        val mind = NovaMind(InMemoryNovaMindMemory())
        val reply = mind.respond(NovaMindInput("ماذا ترين في الكاميرا؟", visualSummary = "رصد أولي"))
        assertTrue(reply.usedOwnModel)
        assertFalse(reply.usedLocalModel)
        assertEquals(NovaIntent.VISION_QUERY, reply.intent)
        assertTrue(reply.intentConfidence in 0f..1f)
    }
}
