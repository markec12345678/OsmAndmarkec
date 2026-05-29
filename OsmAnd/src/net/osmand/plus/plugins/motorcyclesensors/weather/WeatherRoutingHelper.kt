package net.osmand.plus.plugins.motorcyclesensors.weather

import net.osmand.PlatformUtil
import net.osmand.Location
import net.osmand.data.LatLon
import java.util.concurrent.TimeUnit

/**
 * Weather Impact on Routing - provides weather warnings along the planned route.
 *
 * Architecture:
 * - Queries weather data for waypoints along the route
 * - Analyzes weather conditions for motorcycle safety risks
 * - Issues warnings for: strong wind, rain, ice, snow
 * - Integrates with routing to suggest safer alternatives
 *
 * Data sources (designed for extension):
 * - Primary: Open-Meteo API (free, no API key required)
 * - Future: OsmAnd's built-in weather plugin data
 *
 * Safety thresholds for motorcyclists:
 * - Wind: > 40 km/h crosswind is dangerous for motorcycles
 * - Rain: any precipitation reduces grip significantly
 * - Ice: temperatures below 3°C with moisture = black ice risk
 * - Snow: any snow accumulation = extremely dangerous
 */
class WeatherRoutingHelper {

    companion object {
        private val LOG = PlatformUtil.getLog(WeatherRoutingHelper::class.java)

        // Safety thresholds for motorcyclists
        const val WIND_SPEED_DANGEROUS_KMH = 40.0
        const val WIND_SPEED_WARNING_KMH = 25.0
        const val ICE_RISK_TEMP_C = 3.0
        const val SNOW_ACCUMULATION_MM = 1.0
        const val RAIN_PRECIPITATION_MM = 0.5

        // Cache duration
        const val CACHE_DURATION_MS = TimeUnit.MINUTES.toMillis(30)

        // Open-Meteo API (free, no key required)
        const val OPEN_METEO_BASE_URL = "https://api.open-meteo.com/v1/forecast"
    }

    /**
     * Weather condition along a route segment.
     */
    data class RouteWeather(
        val latLon: LatLon,
        val temperature: Float,          // Celsius
        val windSpeed: Float,             // km/h
        val windDirection: Float,         // degrees
        val windGusts: Float,             // km/h
        val precipitation: Float,         // mm/h
        val weatherCode: Int,             // WMO weather code
        val isDay: Boolean,
        val timestamp: Long
    )

    /**
     * Weather warning for a specific route segment.
     */
    data class WeatherWarning(
        val type: WeatherWarningType,
        val severity: WarningSeverity,
        val latLon: LatLon,
        val message: String,
        val detail: String
    )

    enum class WeatherWarningType {
        WIND, RAIN, ICE, SNOW, LOW_VISIBILITY, THUNDERSTORM
    }

    enum class WarningSeverity {
        INFO, WARNING, DANGER
    }

    /**
     * Analyzed weather impact for the entire route.
     */
    data class RouteWeatherReport(
        val warnings: List<WeatherWarning>,
        val maxWindSpeed: Float,
        val minTemperature: Float,
        val totalPrecipitation: Float,
        val hasIceRisk: Boolean,
        val hasSnowRisk: Boolean,
        val overallRisk: WarningSeverity
    ) {
        fun isSafe(): Boolean = overallRisk != WarningSeverity.DANGER
    }

    // Weather cache (lat/lon -> weather data)
    private val weatherCache = mutableMapOf<String, Pair<RouteWeather, Long>>()

    /**
     * Analyze weather impact on a route.
     *
     * Sampling strategy: Take weather readings every ~20km along the route.
     * This balances API usage vs coverage.
     *
     * @param routePoints The route waypoints to analyze
     * @return RouteWeatherReport with warnings and risk assessment
     */
    fun analyzeRouteWeather(routePoints: List<LatLon>): RouteWeatherReport {
        val warnings = mutableListOf<WeatherWarning>()
        var maxWindSpeed = 0f
        var minTemp = Float.MAX_VALUE
        var totalPrecip = 0f
        var hasIceRisk = false
        var hasSnowRisk = false

        // Sample every ~20km (approx every 15-20 points for typical route density)
        val sampleInterval = maxOf(1, routePoints.size / 15)
        val samplePoints = routePoints.filterIndexed { index, _ -> index % sampleInterval == 0 }

        for (point in samplePoints) {
            val weather = getCachedOrFetchWeather(point) ?: continue

            maxWindSpeed = maxOf(maxWindSpeed, weather.windSpeed, weather.windGusts)
            minTemp = minOf(minTemp, weather.temperature)
            totalPrecip += weather.precipitation

            // Check wind danger
            if (weather.windSpeed >= WIND_SPEED_DANGEROUS_KMH || weather.windGusts >= WIND_SPEED_DANGEROUS_KMH) {
                warnings.add(WeatherWarning(
                    WeatherWarningType.WIND, WarningSeverity.DANGER, point,
                    "Dangerous crosswind: ${weather.windSpeed.toInt()} km/h",
                    "Gusts up to ${weather.windGusts.toInt()} km/h. Consider alternative route."
                ))
            } else if (weather.windSpeed >= WIND_SPEED_WARNING_KMH) {
                warnings.add(WeatherWarning(
                    WeatherWarningType.WIND, WarningSeverity.WARNING, point,
                    "Strong wind: ${weather.windSpeed.toInt()} km/h",
                    "Exercise extra caution, especially on open roads."
                ))
            }

            // Check rain
            if (weather.precipitation >= RAIN_PRECIPITATION_MM) {
                val severity = if (weather.precipitation > 5.0f) WarningSeverity.DANGER else WarningSeverity.WARNING
                warnings.add(WeatherWarning(
                    WeatherWarningType.RAIN, severity, point,
                    "Rain: ${"%.1f".format(weather.precipitation)} mm/h",
                    "Reduced grip. Reduce speed and increase following distance."
                ))
            }

            // Check ice risk
            if (weather.temperature <= ICE_RISK_TEMP_C && weather.precipitation > 0) {
                hasIceRisk = true
                warnings.add(WeatherWarning(
                    WeatherWarningType.ICE, WarningSeverity.DANGER, point,
                    "Black ice risk: ${weather.temperature.toInt()}°C with moisture",
                    "Extremely dangerous for motorcycles. Consider postponing ride."
                ))
            }

            // Check snow
            if (weather.temperature <= 0f && weather.precipitation >= SNOW_ACCUMULATION_MM) {
                hasSnowRisk = true
                warnings.add(WeatherWarning(
                    WeatherWarningType.SNOW, WarningSeverity.DANGER, point,
                    "Snow: ${weather.temperature.toInt()}°C with precipitation",
                    "Roads may be icy and slippery. Avoid if possible."
                ))
            }

            // Check thunderstorm (WMO code 95-99)
            if (weather.weatherCode in 95..99) {
                warnings.add(WeatherWarning(
                    WeatherWarningType.THUNDERSTORM, WarningSeverity.DANGER, point,
                    "Thunderstorm detected",
                    "Lightning risk. Seek shelter immediately."
                ))
            }
        }

        val overallRisk = when {
            warnings.any { it.severity == WarningSeverity.DANGER } -> WarningSeverity.DANGER
            warnings.any { it.severity == WarningSeverity.WARNING } -> WarningSeverity.WARNING
            warnings.isNotEmpty() -> WarningSeverity.INFO
            else -> WarningSeverity.INFO
        }

        return RouteWeatherReport(
            warnings = warnings,
            maxWindSpeed = maxWindSpeed,
            minTemperature = if (minTemp == Float.MAX_VALUE) 0f else minTemp,
            totalPrecipitation = totalPrecip,
            hasIceRisk = hasIceRisk,
            hasSnowRisk = hasSnowRisk,
            overallRisk = overallRisk
        )
    }

    /**
     * Get cached weather or return null (actual API fetch would be async).
     * In production, this would use OkHttp/Retrofit to call Open-Meteo API.
     * For now, returns null to indicate "weather data not yet fetched".
     */
    private fun getCachedOrFetchWeather(latLon: LatLon): RouteWeather? {
        val cacheKey = "${"%.2f".format(latLon.latitude)}_${"%.2f".format(latLon.longitude)}"
        val cached = weatherCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.second < CACHE_DURATION_MS) {
            return cached.first
        }
        // Weather data not available - would need async API call
        // For now, return null (feature requires network integration)
        return null
    }

    /**
     * Build Open-Meteo API URL for a given location.
     * Returns hourly: temperature, wind speed/direction/gusts, precipitation, weather code.
     */
    fun buildApiUrl(latLon: LatLon): String {
        return "$OPEN_METEO_BASE_URL?" +
            "latitude=${"%.4f".format(latLon.latitude)}" +
            "&longitude=${"%.4f".format(latLon.longitude)}" +
            "&hourly=temperature_2m,windspeed_10m,winddirection_10m,windgusts_10m," +
            "precipitation,weathercode,is_day" +
            "&forecast_days=1&timezone=auto"
    }

    /**
     * Clear weather cache.
     */
    fun clearCache() {
        weatherCache.clear()
    }
}
