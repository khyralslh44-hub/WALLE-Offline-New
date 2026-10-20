package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EveEnvironmentUnderstandingTest {
    @Test fun aSingleUmbrellaDoesNotProveRain() {
        val interpreter = EveEnvironmentUnderstanding()
        val result = interpreter.observe(
            timestampMs = 1_000L,
            candidates = listOf(EveImageClass("umbrella", 0.91f)),
            meanLuminance = 92f,
            localHour = 14
        )
        assertEquals(EveWeatherCondition.UNKNOWN, result.weather)
        assertTrue(result.summaryArabic.isBlank())
    }

    @Test fun repeatedRainEvidenceCanProduceACautiousWeatherHypothesis() {
        val interpreter = EveEnvironmentUnderstanding()
        interpreter.observe(
            timestampMs = 1_000L,
            candidates = listOf(EveImageClass("rain", 0.55f)),
            meanLuminance = 86f
        )
        val result = interpreter.observe(
            timestampMs = 5_500L,
            candidates = listOf(EveImageClass("rain", 0.61f)),
            meanLuminance = 78f
        )
        assertEquals(EveWeatherCondition.RAIN, result.weather)
        assertTrue(result.weatherConfidence in 0.55f..0.82f)
        assertTrue(result.summaryArabic.contains("المطر"))
    }

    @Test fun repeatedSnowEvidenceIsKeptSeparateFromRain() {
        val interpreter = EveEnvironmentUnderstanding()
        interpreter.observe(1_000L, listOf(EveImageClass("snow", 0.48f)), 110f)
        val result = interpreter.observe(6_000L, listOf(EveImageClass("snow", 0.52f)), 105f)
        assertEquals(EveWeatherCondition.SNOW, result.weather)
        assertEquals(EveWeatherCondition.RAIN, EveWeatherCondition.valueOf("RAIN"))
        assertTrue(result.summaryArabic.contains("الثلج"))
    }

    @Test fun brightnessAloneDoesNotClaimDaylightOrWeather() {
        val result = EveEnvironmentUnderstanding().observe(
            timestampMs = 1_000L,
            candidates = emptyList(),
            meanLuminance = 230f
        )
        assertEquals(EveVisualLight.BRIGHT_SCENE, result.visualLight)
        assertEquals(EveWeatherCondition.UNKNOWN, result.weather)
        assertTrue(result.summaryArabic.contains("لا يثبت"))
    }

    @Test fun explicitDaylightLabelIsReportedAsVisualEvidence() {
        val result = EveEnvironmentUnderstanding().observe(
            timestampMs = 1_000L,
            candidates = listOf(EveImageClass("daylight", 0.88f)),
            meanLuminance = 160f
        )
        assertEquals(EveVisualLight.DAYLIGHT, result.visualLight)
        assertTrue(result.lightConfidence >= 0.72f)
    }

    @Test fun clockPeriodIsSeparatedFromWhatTheCameraActuallySees() {
        val result = EveEnvironmentUnderstanding().observe(
            timestampMs = 1_000L,
            candidates = emptyList(),
            meanLuminance = 100f,
            localHour = 23
        )
        assertEquals(EveVisualLight.UNKNOWN, result.visualLight)
        assertTrue(result.clockPeriodArabic.contains("الليل"))
    }

    @Test fun staleEnvironmentFramesDoNotChangeTheLatestInterpretation() {
        val interpreter = EveEnvironmentUnderstanding()
        interpreter.observe(2_000L, listOf(EveImageClass("rain", 0.58f)), 80f)
        val result = interpreter.observe(1_000L, listOf(EveImageClass("snow", 0.95f)), 80f)
        assertEquals(EveWeatherCondition.UNKNOWN, result.weather)
        assertTrue(result.summaryArabic.contains("قديم"))
    }
}
