package com.eve.robot

import java.util.ArrayDeque
import java.util.Locale

enum class EveWeatherCondition { SUNNY, CLOUDY, RAIN, SNOW, FOG, UNKNOWN }
enum class EveVisualLight { DAYLIGHT, NIGHT, BRIGHT_SCENE, DIM_SCENE, UNKNOWN }

/**
 * Conservative interpretation of local whole-image labels.
 *
 * This is an evidence combiner, not another neural model. A single ambiguous label such as
 * "umbrella" or "dark" never proves rain or night. Weather needs repeated on-device classifier
 * evidence; brightness alone is reported as scene lighting, not as time of day.
 */
data class EveEnvironmentSnapshot(
    val weather: EveWeatherCondition,
    val visualLight: EveVisualLight,
    val weatherConfidence: Float,
    val lightConfidence: Float,
    val clockPeriodArabic: String,
    val summaryArabic: String,
    val observedAtMs: Long
)

class EveEnvironmentUnderstanding(
    private val windowMs: Long = 24_000L,
    private val maxSamples: Int = 8
) {
    private data class Sample(
        val timestampMs: Long,
        val weather: EveWeatherCondition,
        val weatherScore: Float,
        val visualLight: EveVisualLight,
        val lightScore: Float
    )

    private val history = ArrayDeque<Sample>()
    private var lastTimestampMs = 0L

    @Synchronized
    fun observe(
        timestampMs: Long,
        candidates: List<EveImageClass>,
        meanLuminance: Float,
        localHour: Int? = null
    ): EveEnvironmentSnapshot {
        if (lastTimestampMs > timestampMs) {
            return snapshot(
                timestampMs = timestampMs,
                weather = EveWeatherCondition.UNKNOWN,
                visualLight = EveVisualLight.UNKNOWN,
                weatherConfidence = 0f,
                lightConfidence = 0f,
                localHour = localHour,
                summary = "وصل تحليل صورة قديم؛ تجاهلتُه بدل تغيير فهم المشهد."
            )
        }
        lastTimestampMs = timestampMs

        val normalized = candidates.asSequence()
            .filter { it.confidence.isFinite() && it.confidence in 0f..1f && it.label.isNotBlank() }
            .map { normalize(it.label) to it.confidence }
            .toList()

        val rain = score(normalized, RAIN_TERMS)
        val snow = score(normalized, SNOW_TERMS)
        val sun = score(normalized, SUN_TERMS)
        val cloud = score(normalized, CLOUD_TERMS)
        val fog = score(normalized, FOG_TERMS)
        val night = score(normalized, NIGHT_TERMS)
        val daylight = score(normalized, DAYLIGHT_TERMS)

        val weatherCandidate = strongestWeather(rain, snow, sun, cloud, fog)
        val lightCandidate = when {
            night >= EXPLICIT_LIGHT_THRESHOLD && night > daylight + 0.10f ->
                EveVisualLight.NIGHT to night
            daylight >= EXPLICIT_LIGHT_THRESHOLD && daylight > night + 0.10f ->
                EveVisualLight.DAYLIGHT to daylight
            meanLuminance.isFinite() && meanLuminance >= BRIGHT_LUMINANCE ->
                EveVisualLight.BRIGHT_SCENE to normalizeLuminanceConfidence(meanLuminance)
            meanLuminance.isFinite() && meanLuminance <= DIM_LUMINANCE ->
                EveVisualLight.DIM_SCENE to normalizeDimConfidence(meanLuminance)
            else -> EveVisualLight.UNKNOWN to 0f
        }

        history.addLast(
            Sample(
                timestampMs = timestampMs,
                weather = weatherCandidate.first,
                weatherScore = weatherCandidate.second,
                visualLight = lightCandidate.first,
                lightScore = lightCandidate.second
            )
        )
        while (history.isNotEmpty() && timestampMs - history.first().timestampMs > windowMs) {
            history.removeFirst()
        }
        while (history.size > maxSamples) history.removeFirst()

        val matchingWeather = history.filter {
            it.weather == weatherCandidate.first && it.weather != EveWeatherCondition.UNKNOWN
        }
        val weatherStable = when {
            weatherCandidate.first == EveWeatherCondition.UNKNOWN -> false
            weatherCandidate.second >= STRONG_SINGLE_FRAME_THRESHOLD -> true
            else -> matchingWeather.size >= REQUIRED_WEATHER_VOTES
        }
        val weather = if (weatherStable) weatherCandidate.first else EveWeatherCondition.UNKNOWN
        val weatherConfidence = if (weatherStable) {
            (matchingWeather.map { it.weatherScore }.average().toFloat())
                .coerceIn(0f, 0.82f)
        } else {
            0f
        }

        val matchingLight = history.filter {
            it.visualLight == lightCandidate.first && it.visualLight != EveVisualLight.UNKNOWN
        }
        val lightStable = when (lightCandidate.first) {
            EveVisualLight.DAYLIGHT, EveVisualLight.NIGHT ->
                lightCandidate.second >= STRONG_SINGLE_FRAME_THRESHOLD || matchingLight.size >= REQUIRED_WEATHER_VOTES
            EveVisualLight.BRIGHT_SCENE, EveVisualLight.DIM_SCENE -> true
            EveVisualLight.UNKNOWN -> false
        }
        val visualLight = if (lightStable) lightCandidate.first else EveVisualLight.UNKNOWN
        val lightConfidence = if (lightStable) lightCandidate.second.coerceIn(0f, 0.82f) else 0f

        val summary = buildList {
            when (weather) {
                EveWeatherCondition.SUNNY -> add("قد يكون المشهد مشمسًا")
                EveWeatherCondition.CLOUDY -> add("تظهر مؤشرات على غيوم")
                EveWeatherCondition.RAIN -> add("توجد مؤشرات متكررة على المطر")
                EveWeatherCondition.SNOW -> add("توجد مؤشرات متكررة على الثلج")
                EveWeatherCondition.FOG -> add("توجد مؤشرات على الضباب")
                EveWeatherCondition.UNKNOWN -> Unit
            }
            when (visualLight) {
                EveVisualLight.DAYLIGHT -> add("وتظهر مؤشرات على ضوء النهار")
                EveVisualLight.NIGHT -> add("وتظهر مؤشرات بصرية على الليل")
                EveVisualLight.BRIGHT_SCENE -> add("المشهد مضيء، لكن السطوع وحده لا يثبت أن الوقت نهار")
                EveVisualLight.DIM_SCENE -> add("المشهد منخفض الإضاءة، لكن ذلك وحده لا يثبت أنه ليل")
                EveVisualLight.UNKNOWN -> Unit
            }
        }.joinToString("؛ ")

        return snapshot(
            timestampMs = timestampMs,
            weather = weather,
            visualLight = visualLight,
            weatherConfidence = weatherConfidence,
            lightConfidence = lightConfidence,
            localHour = localHour,
            summary = summary
        )
    }

    @Synchronized
    fun reset() {
        history.clear()
        lastTimestampMs = 0L
    }

    private fun snapshot(
        timestampMs: Long,
        weather: EveWeatherCondition,
        visualLight: EveVisualLight,
        weatherConfidence: Float,
        lightConfidence: Float,
        localHour: Int?,
        summary: String
    ) = EveEnvironmentSnapshot(
        weather = weather,
        visualLight = visualLight,
        weatherConfidence = weatherConfidence,
        lightConfidence = lightConfidence,
        clockPeriodArabic = when (localHour?.takeIf { it in 0..23 }) {
            in 6..17 -> "وقت الجهاز ضمن النهار"
            in 18..23, in 0..5 -> "وقت الجهاز ضمن الليل"
            else -> ""
        },
        summaryArabic = summary,
        observedAtMs = timestampMs
    )

    private fun strongestWeather(
        rain: Float,
        snow: Float,
        sun: Float,
        cloud: Float,
        fog: Float
    ): Pair<EveWeatherCondition, Float> {
        val candidates = listOf(
            EveWeatherCondition.RAIN to rain,
            EveWeatherCondition.SNOW to snow,
            EveWeatherCondition.SUNNY to sun,
            EveWeatherCondition.CLOUDY to cloud,
            EveWeatherCondition.FOG to fog
        ).sortedByDescending { it.second }
        val best = candidates.first()
        val second = candidates.getOrNull(1)?.second ?: 0f
        if (best.second < MIN_WEATHER_LABEL_SCORE || best.second - second < MIN_WEATHER_MARGIN) {
            return EveWeatherCondition.UNKNOWN to 0f
        }
        return best
    }

    private fun score(labels: List<Pair<String, Float>>, terms: Set<String>): Float =
        labels.asSequence()
            .filter { (label, _) -> terms.any { term -> label == term || label.contains(term) } }
            .maxOfOrNull { it.second }
            ?: 0f

    private fun normalize(raw: String): String = raw.trim()
        .lowercase(Locale.ROOT)
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(Regex("\\s+"), " ")

    private fun normalizeLuminanceConfidence(value: Float) =
        (0.42f + ((value - BRIGHT_LUMINANCE) / (255f - BRIGHT_LUMINANCE)) * 0.26f).coerceIn(0.42f, 0.68f)

    private fun normalizeDimConfidence(value: Float) =
        (0.42f + ((DIM_LUMINANCE - value) / DIM_LUMINANCE) * 0.22f).coerceIn(0.42f, 0.64f)

    companion object {
        private const val REQUIRED_WEATHER_VOTES = 2
        private const val MIN_WEATHER_LABEL_SCORE = 0.22f
        private const val MIN_WEATHER_MARGIN = 0.08f
        private const val STRONG_SINGLE_FRAME_THRESHOLD = 0.72f
        private const val EXPLICIT_LIGHT_THRESHOLD = 0.42f
        private const val BRIGHT_LUMINANCE = 178f
        private const val DIM_LUMINANCE = 35f

        private val RAIN_TERMS = setOf("rain", "rainy", "rainfall", "rainstorm", "downpour", "thunderstorm", "storm")
        private val SNOW_TERMS = setOf("snow", "snowy", "snowfall", "snowfield", "snowman", "blizzard")
        private val SUN_TERMS = setOf("sun", "sunny", "sunlight", "sunshine", "sunrise", "sunset", "daylight", "daytime")
        private val CLOUD_TERMS = setOf("cloud", "cloudy", "overcast")
        private val FOG_TERMS = setOf("fog", "foggy", "mist", "misty")
        private val NIGHT_TERMS = setOf("night", "night sky", "moonlit", "starry night", "midnight")
        private val DAYLIGHT_TERMS = setOf("daylight", "daytime", "sunlight", "sunshine", "sunny", "sunrise")
    }
}
