package com.nova.robot

import org.junit.Assert.*
import org.junit.Test

class NovaOwnModelTest {
    @Test fun classifiesCommonArabicIntentsFromItsOwnTraining() {
        val model = NovaOwnModel.trainFromScratch()
        assertEquals(NovaIntent.GREETING, model.classify("مرحبا يا نوفا").intent)
        assertEquals(NovaIntent.QUESTION, model.classify("كيف تعمل الكاميرا؟").intent)
        assertEquals(NovaIntent.VISION_QUERY, model.classify("ماذا ترين في الكاميرا؟").intent)
        assertEquals(NovaIntent.MEMORY_REQUEST, model.classify("تذكري أنني أحب الهدوء").intent)
        assertEquals(NovaIntent.POSITIVE_FEEDBACK, model.classify("ممتاز شكرًا").intent)
        assertEquals(NovaIntent.NEGATIVE_FEEDBACK, model.classify("هذا خطأ").intent)
        assertEquals(NovaIntent.FAREWELL, model.classify("مع السلامة").intent)
    }

    @Test fun hasConfidenceAndDoesNotClassifyBlankText() {
        val model = NovaOwnModel.trainFromScratch()
        val greeting = model.classify("أهلا وسهلا")
        assertTrue(greeting.confidence in 0f..1f)
        assertEquals(NovaIntent.GREETING, greeting.intent)
        assertEquals(NovaIntent.UNKNOWN, model.classify("   ").intent)
        assertEquals(0f, model.classify("").confidence, 0f)
    }

    @Test fun snapshotsRestoreTheSamePredictionsWithoutStoringUserText() {
        val model = NovaOwnModel.trainFromScratch()
        model.learnCorrection("أريد مساعدة من فضلك", NovaIntent.QUESTION)
        val snapshot = model.snapshot()
        assertFalse(snapshot.contains("أريد مساعدة"))
        val restored = NovaOwnModel.restore(snapshot)
        assertNotNull(restored)
        assertEquals(model.classify("أريد مساعدة من فضلك"), restored!!.classify("أريد مساعدة من فضلك"))
    }

    @Test fun correctionUpdatesWeightsAndRejectsUnknownAsATrainingLabel() {
        val model = NovaOwnModel.trainFromScratch()
        val before = model.snapshot()
        model.learnCorrection("كلمة جديدة للتجربة", NovaIntent.MEMORY_REQUEST, rounds = 12)
        assertNotEquals(before, model.snapshot())
        try {
            model.learnCorrection("نص", NovaIntent.UNKNOWN)
            fail("UNKNOWN should not be accepted as a training label")
        } catch (_: IllegalArgumentException) {
            // Expected: corrections must provide an explicit known intent.
        }
    }

    @Test fun malformedSnapshotsAreRejected() {
        assertNull(NovaOwnModel.restore("not-a-model"))
        assertNull(NovaOwnModel.restore("nova-own-mlp-v1:broken"))
    }
}
