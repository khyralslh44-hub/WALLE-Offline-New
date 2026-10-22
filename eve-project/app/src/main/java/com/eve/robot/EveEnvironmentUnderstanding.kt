package com.eve.robot

import java.util.ArrayDeque
import java.util.Locale

enum class EveWeatherCondition { SUNNY, CLOUDY, RAIN, SNOW, FOG, UNKNOWN }
enum class EveVisualLight { DAYLIGHT, NIGHT, BRIGHT_SCENE, DIM_SCENE, UNKNOWN }
enum class EveSeason { SPRING, SUMMER, AUTUMN, WINTER, UNKNOWN }
enum class EveHemisphere { NORTHERN, SOUTHERN, UNKNOWN }

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
    val observedAtMs: Long,
    val season: EveSeason = EveSeason.UNKNOWN,
    val hemisphere: EveHemisphere = EveHemisphere.UNKNOWN,
    val seasonConfidence: Float = 0f,
    val seasonalClue: EveSeason = EveSeason.UNKNOWN,
    val seasonalClueConfidence: Float = 0f
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
        val lightScore: Float,
        val seasonalClue: EveSeason,
        val seasonalClueScore: Float
    )

    private val history = ArrayDeque<Sample>()
    private var lastTimestampMs = 0L

    @Synchronized
    fun observe(
        timestampMs: Long,
        candidates: List<EveImageClass>,
        meanLuminance: Float,
        localHour: Int? = null,
        localMonth: Int? = null,
        hemisphere: EveHemisphere = EveHemisphere.NORTHERN
    ): EveEnvironmentSnapshot {
        if (lastTimestampMs > timestampMs) {
            return snapshot(
                timestampMs = timestampMs,
                weather = EveWeatherCondition.UNKNOWN,
                visualLight = EveVisualLight.UNKNOWN,
                weatherConfidence = 0f,
                lightConfidence = 0f,
                localHour = localHour,
                summary = "وصل تحليل صورة قديم؛ تجاهلتُه بدل تغيير فهم المشهد.",
                localMonth = localMonth,
                hemisphere = hemisphere
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
        val spring = score(normalized, SPRING_TERMS)
        val summer = score(normalized, SUMMER_TERMS)
        val autumn = score(normalized, AUTUMN_TERMS)
        val winter = score(normalized, WINTER_TERMS)

        val weatherCandidate = strongestWeather(rain, snow, sun, cloud, fog)
        val seasonalCandidate = strongestSeason(spring, summer, autumn, winter)
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
                lightScore = lightCandidate.second,
                seasonalClue = seasonalCandidate.first,
                seasonalClueScore = seasonalCandidate.second
            )
        )
        while (history.isNotEmpty() && timestampMs - history.first().timestampMs > windowMs) {
            history.removeFirst()
        }
        while (history.size > maxSamples) history.removeFirst()

        val matchingSeasonalClue = history.filter {
            it.seasonalClue == seasonalCandidate.first && it.seasonalClue != EveSeason.UNKNOWN
        }
        val seasonalClueStable = when {
            seasonalCandidate.first == EveSeason.UNKNOWN -> false
            seasonalCandidate.second >= STRONG_SINGLE_SEASON_THRESHOLD -> true
            else -> matchingSeasonalClue.size >= REQUIRED_WEATHER_VOTES
        }
        val seasonalClue = if (seasonalClueStable) seasonalCandidate.first else EveSeason.UNKNOWN
        val seasonalClueConfidence = if (seasonalClueStable) {
            matchingSeasonalClue.map { it.seasonalClueScore }.average().toFloat().coerceIn(0f, 0.95f)
        } else 0f
        val calendarSeason = seasonForMonth(localMonth, hemisphere)

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
            if (calendarSeason != EveSeason.UNKNOWN) {
                add("وفق تاريخ الجهاز، الفصل التقويمي هو ${seasonArabic(calendarSeason)}")
            }
            if (seasonalClue != EveSeason.UNKNOWN) {
                if (calendarSeason == EveSeason.UNKNOWN || seasonalClue == calendarSeason) {
                    add("تظهر الصورة مؤشرات بصرية متوافقة مع ${seasonArabic(seasonalClue)}")
                } else {
                    add("تظهر الصورة مؤشرات تشبه ${seasonArabic(seasonalClue)}، بينما يشير التقويم إلى ${seasonArabic(calendarSeason)}؛ لا تكفي الصورة وحدها لتغيير الفصل")
                }
            }
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
            summary = summary,
            localMonth = localMonth,
            hemisphere = hemisphere,
            seasonalClue = seasonalClue,
            seasonalClueConfidence = seasonalClueConfidence
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
        summary: String,
        localMonth: Int? = null,
        hemisphere: EveHemisphere = EveHemisphere.UNKNOWN,
        seasonalClue: EveSeason = EveSeason.UNKNOWN,
        seasonalClueConfidence: Float = 0f
    ) = EveEnvironmentSnapshot(
        weather = weather,
        visualLight = visualLight,
        weatherConfidence = weatherConfidence,
        lightConfidence = lightConfidence,
        clockPeriodArabic = when {
            localHour != null && localHour in 6..17 -> "وقت الجهاز ضمن النهار"
            localHour != null && (localHour in 18..23 || localHour in 0..5) -> "وقت الجهاز ضمن الليل"
            else -> ""
        },
        summaryArabic = summary,
        observedAtMs = timestampMs,
        season = seasonForMonth(localMonth, hemisphere),
        hemisphere = hemisphere,
        seasonConfidence = if (seasonForMonth(localMonth, hemisphere) != EveSeason.UNKNOWN) CALENDAR_SEASON_CONFIDENCE else 0f,
        seasonalClue = seasonalClue,
        seasonalClueConfidence = seasonalClueConfidence
    )

    private fun seasonForMonth(month: Int?, hemisphere: EveHemisphere): EveSeason {
        if (month == null || month !in 1..12 || hemisphere == EveHemisphere.UNKNOWN) return EveSeason.UNKNOWN
        val northern = when (month) {
            3, 4, 5 -> EveSeason.SPRING
            6, 7, 8 -> EveSeason.SUMMER
            9, 10, 11 -> EveSeason.AUTUMN
            else -> EveSeason.WINTER
        }
        if (hemisphere == EveHemisphere.NORTHERN) return northern
        return when (northern) {
            EveSeason.SPRING -> EveSeason.AUTUMN
            EveSeason.SUMMER -> EveSeason.WINTER
            EveSeason.AUTUMN -> EveSeason.SPRING
            EveSeason.WINTER -> EveSeason.SUMMER
            EveSeason.UNKNOWN -> EveSeason.UNKNOWN
        }
    }

    private fun seasonArabic(season: EveSeason): String = when (season) {
        EveSeason.SPRING -> "الربيع"
        EveSeason.SUMMER -> "الصيف"
        EveSeason.AUTUMN -> "الخريف"
        EveSeason.WINTER -> "الشتاء"
        EveSeason.UNKNOWN -> "فصل غير محدد"
    }

    private fun strongestSeason(spring: Float, summer: Float, autumn: Float, winter: Float): Pair<EveSeason, Float> {
        val candidates = listOf(
            EveSeason.SPRING to spring,
            EveSeason.SUMMER to summer,
            EveSeason.AUTUMN to autumn,
            EveSeason.WINTER to winter
        ).sortedByDescending { it.second }
        val best = candidates.first()
        val second = candidates.getOrNull(1)?.second ?: 0f
        if (best.second < MIN_SEASON_LABEL_SCORE || best.second - second < MIN_SEASON_MARGIN) {
            return EveSeason.UNKNOWN to 0f
        }
        return best
    }

    /**
     * Prefer the device timezone when it unambiguously identifies a region. The calendar
     * inference is date-based and local/offline; it does not claim the camera verified a season.
     */
    fun inferHemisphere(countryCode: String, timeZoneId: String): EveHemisphere {
            val country = countryCode.trim().uppercase(Locale.ROOT)
            val zone = timeZoneId.trim()
            val southernCountries = setOf(
                "AU", "NZ", "ZA", "AR", "CL", "UY", "BO", "PY", "PE", "BR", "NA", "BW",
                "LS", "SZ", "MZ", "ZW", "MG", "MU", "SC", "TL", "FJ", "PG", "SB", "VU", "TO", "WS"
            )
            val southernZonePrefixes = listOf(
                "Australia/", "Pacific/Auckland", "Pacific/Chatham", "Pacific/Fiji", "Pacific/Port_Moresby",
                "Pacific/Guadalcanal", "Pacific/Noumea", "Pacific/Tongatapu", "Pacific/Apia",
                "Indian/Mauritius", "Indian/Antananarivo", "Africa/Johannesburg", "Africa/Windhoek",
                "Africa/Maputo", "Africa/Harare", "Africa/Gaborone", "Africa/Maseru", "Africa/Mbabane",
                "America/Argentina/", "America/Santiago", "America/Punta_Arenas", "America/Montevideo",
                "America/Sao_Paulo", "America/La_Paz", "America/Lima", "America/Asuncion", "America/Cuiaba",
                "America/Campo_Grande", "America/Manaus", "America/Porto_Velho", "America/Rio_Branco",
                "America/Recife", "America/Fortaleza", "America/Bahia", "America/Paramaribo", "America/Cayenne"
            )
            if (country in southernCountries || southernZonePrefixes.any { zone.startsWith(it) }) {
                return EveHemisphere.SOUTHERN
            }
            val southernAsiaZones = listOf("Asia/Jakarta", "Asia/Makassar", "Asia/Jayapura", "Asia/Dili")
            if (southernAsiaZones.any { zone.startsWith(it) }) return EveHemisphere.SOUTHERN
            val northernCountries = setOf(
                "NL", "BE", "DE", "FR", "ES", "PT", "IT", "GB", "IE", "NO", "SE", "FI", "DK", "IS",
                "PL", "CZ", "SK", "AT", "CH", "HU", "RO", "BG", "GR", "EE", "LV", "LT", "UA", "BY",
                "US", "CA", "MX", "CN", "JP", "KR", "KP", "IN", "PK", "BD", "NP", "BT", "AF", "IR",
                "IQ", "IL", "JO", "LB", "SY", "TR", "GE", "AM", "AZ", "KZ", "MN", "RU", "EG", "LY",
                "DZ", "TN", "MA", "MR", "SA", "YE", "OM", "AE", "QA", "BH", "KW", "CY", "MT"
            )
            if (country in northernCountries) return EveHemisphere.NORTHERN
            if (zone.startsWith("Europe/") || zone.startsWith("America/") || zone.startsWith("Asia/")) {
                return EveHemisphere.NORTHERN
            }
            return EveHemisphere.UNKNOWN
        }

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
            .filter { (label, _) ->
                terms.any { term ->
                    // Match whole category words, not substrings such as "sun" in "sunglasses".
                    label == term || label.startsWith("$term ") ||
                        label.endsWith(" $term") || label.contains(" $term ")
                }
            }
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
        private const val STRONG_SINGLE_SEASON_THRESHOLD = 0.80f
        private const val CALENDAR_SEASON_CONFIDENCE = 0.90f
        private const val MIN_SEASON_LABEL_SCORE = 0.25f
        private const val MIN_SEASON_MARGIN = 0.10f
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
        // Require explicit seasonal terms; generic flowers, beaches, leaves, or snow alone are ambiguous.
        private val SPRING_TERMS = setOf("spring", "springtime", "spring blossoms", "cherry blossom", "vernal")
        private val SUMMER_TERMS = setOf("summer", "summertime", "summer scene", "summer landscape")
        private val AUTUMN_TERMS = setOf("autumn", "fall foliage", "autumn leaves", "fall colors", "fall leaves", "autumn foliage")
        private val WINTER_TERMS = setOf("winter", "wintertime", "winter scene", "winter landscape")
    }
}
