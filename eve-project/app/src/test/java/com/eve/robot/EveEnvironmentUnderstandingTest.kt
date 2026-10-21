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


    @Test fun calendarSeasonsFollowNorthernHemisphereMonths() {
        val interpreter = EveEnvironmentUnderstanding()
        fun season(month: Int) = interpreter.observe(
            timestampMs = month * 1_000L,
            candidates = emptyList(),
            meanLuminance = 100f,
            localMonth = month,
            hemisphere = EveHemisphere.NORTHERN
        ).season

        assertEquals(EveSeason.WINTER, season(1))
        assertEquals(EveSeason.SPRING, season(4))
        assertEquals(EveSeason.SUMMER, season(7))
        assertEquals(EveSeason.AUTUMN, season(10))
    }

    @Test fun southernHemisphereUsesOppositeCalendarSeasons() {
        val interpreter = EveEnvironmentUnderstanding()
        fun season(month: Int) = interpreter.observe(
            timestampMs = month * 1_000L,
            candidates = emptyList(),
            meanLuminance = 100f,
            localMonth = month,
            hemisphere = EveHemisphere.SOUTHERN
        ).season

        assertEquals(EveSeason.SUMMER, season(1))
        assertEquals(EveSeason.AUTUMN, season(4))
        assertEquals(EveSeason.WINTER, season(7))
        assertEquals(EveSeason.SPRING, season(10))
    }

    @Test fun hemisphereInferenceUsesKnownDeviceTimezoneOrRegion() {
        val interpreter = EveEnvironmentUnderstanding()
        assertEquals(EveHemisphere.NORTHERN, interpreter.inferHemisphere("NL", "Europe/Amsterdam"))
        assertEquals(EveHemisphere.SOUTHERN, interpreter.inferHemisphere("AU", "Australia/Sydney"))
        assertEquals(EveHemisphere.UNKNOWN, interpreter.inferHemisphere("", "Etc/UTC"))
    }

    @Test fun explicitAutumnVisualEvidenceDoesNotSilentlyOverrideCalendar() {
        val result = EveEnvironmentUnderstanding().observe(
            timestampMs = 1_000L,
            candidates = listOf(EveImageClass("autumn leaves", 0.88f)),
            meanLuminance = 110f,
            localMonth = 4,
            hemisphere = EveHemisphere.NORTHERN
        )

        assertEquals(EveSeason.SPRING, result.season)
        assertEquals(EveSeason.AUTUMN, result.seasonalClue)
        assertTrue(result.seasonConfidence > 0f)
        assertTrue(result.seasonalClueConfidence >= 0.80f)
        assertTrue(result.summaryArabic.contains("الربيع"))
        assertTrue(result.summaryArabic.contains("الخريف"))
        assertTrue(result.summaryArabic.contains("لا تكفي الصورة وحدها"))
    }

    @Test fun genericSnowWeatherIsNotMistakenForCertainWinterSeason() {
        val result = EveEnvironmentUnderstanding().observe(
            timestampMs = 1_000L,
            candidates = listOf(EveImageClass("snow", 0.98f)),
            meanLuminance = 100f,
            localMonth = 7,
            hemisphere = EveHemisphere.NORTHERN
        )

        assertEquals(EveSeason.SUMMER, result.season)
        assertEquals(EveSeason.UNKNOWN, result.seasonalClue)
        assertEquals(EveWeatherCondition.SNOW, result.weather)
    }

    @Test fun moderateVisualSeasonClueNeedsRepeatedEvidence() {
        val interpreter = EveEnvironmentUnderstanding()
        val first = interpreter.observe(
            timestampMs = 1_000L,
            candidates = listOf(EveImageClass("autumn", 0.50f)),
            meanLuminance = 100f,
            localMonth = 10,
            hemisphere = EveHemisphere.NORTHERN
        )
        val second = interpreter.observe(
            timestampMs = 5_000L,
            candidates = listOf(EveImageClass("autumn", 0.54f)),
            meanLuminance = 102f,
            localMonth = 10,
            hemisphere = EveHemisphere.NORTHERN
        )

        assertEquals(EveSeason.UNKNOWN, first.seasonalClue)
        assertEquals(EveSeason.AUTUMN, second.seasonalClue)
    }

}
