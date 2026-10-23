package com.nova.robot

import org.junit.Assert.*
import org.junit.Test

class NovaFaceCueEvidenceTest {
    @Test fun highSmileProbabilityIsOnlyReportedAsVisualCue() {
        val result = NovaFaceCueEvidence.summarize(0.91f, 0.88f, 0.92f)
        assertNotNull(result)
        assertTrue(result!!.contains("إشارة ابتسام محتملة"))
        assertTrue(result.contains("ليست حكمًا على مشاعر"))
    }

    @Test fun closedEyeCueRequiresBothEyesAndValidProbabilities() {
        val bothClosed = NovaFaceCueEvidence.summarize(0.5f, 0.1f, 0.2f)
        assertNotNull(bothClosed)
        assertTrue(bothClosed!!.contains("العينان تبدوان شبه مغلقتين"))

        val oneClosed = NovaFaceCueEvidence.summarize(0.5f, 0.1f, 0.9f)
        assertNull(oneClosed)
    }

    @Test fun missingOrInvalidProbabilitiesDoNotCreateClaims() {
        assertNull(NovaFaceCueEvidence.summarize(null, null, null))
        assertNull(NovaFaceCueEvidence.summarize(Float.NaN, 1.4f, -0.1f))
    }
}
