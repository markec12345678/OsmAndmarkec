package net.osmand.plus.plugins.motorcyclesensors.weather

import android.os.Handler
import android.os.Looper
import net.osmand.PlatformUtil
import net.osmand.data.LatLon
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * WeatherApiClient - Async HTTP client for Open-Meteo weather API.
 *
 * Fetches real-time weather data for route points and feeds it into
 * WeatherRoutingHelper's cache. Uses Android's HttpURLConnection
 * (no external dependencies needed).
 *
 * API details:
 * - Open-Meteo is free, no API key required
 * - Rate limit: ~10,000 requests/day (more than enough for routing)
 * - Returns hourly forecast data for any lat/lon
 * - Response time: typically 100-300ms
 *
 * Thread safety:
 * - All network calls on background executor
 * - Results posted to main thread via Handler
 * - WeatherRoutingHelper cache is thread-safe (synchronized access)
 */
class WeatherApiClient {

    companion object {
        private val LOG = PlatformUtil.getLog(WeatherApiClient::class.java)
        const val REQUEST_TIMEOUT_MS = 10000
        const val USER_AGENT = "MotoTrack-OsmAnd/1.0"
    }

    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Listener for weather fetch results.
     */
    interface WeatherFetchListener {
        fun onWeatherFetched(latLon: LatLon, weather: WeatherRoutingHelper.RouteWeather)
        fun onWeatherFetchFailed(latLon: LatLon, error: String)
        fun onRouteWeatherReportReady(report: WeatherRoutingHelper.RouteWeatherReport)
    }

    private var listener: WeatherFetchListener? = null

    fun setListener(listener: WeatherFetchListener) {
        this.listener = listener
    }

    /**
     * Fetch weather for a single location.
     */
    fun fetchWeather(latLon: LatLon, helper: WeatherRoutingHelper) {
        executor.execute {
            try {
                val url = URL(helper.buildApiUrl(latLon))
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = REQUEST_TIMEOUT_MS
                connection.readTimeout = REQUEST_TIMEOUT_MS
                connection.setRequestProperty("User-Agent", USER_AGENT)

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    LOG.warn("WeatherApiClient: API returned $responseCode for $latLon")
                    handler.post { listener?.onWeatherFetchFailed(latLon, "HTTP $responseCode") }
                    return@execute
                }

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                connection.disconnect()

                val weather = parseWeatherResponse(response.toString(), latLon)
                if (weather != null) {
                    // Update the helper's cache
                    helper.updateWeatherCache(latLon, weather)
                    handler.post { listener?.onWeatherFetched(latLon, weather) }
                } else {
                    handler.post { listener?.onWeatherFetchFailed(latLon, "Parse error") }
                }
            } catch (e: Exception) {
                LOG.error("WeatherApiClient: Fetch failed for $latLon", e)
                handler.post { listener?.onWeatherFetchFailed(latLon, e.message ?: "Unknown error") }
            }
        }
    }

    /**
     * Fetch weather for an entire route (multiple sample points).
     * Calls the listener with the complete RouteWeatherReport when done.
     */
    fun fetchRouteWeather(routePoints: List<LatLon>, helper: WeatherRoutingHelper) {
        executor.execute {
            try {
                // Sample every ~20km
                val sampleInterval = maxOf(1, routePoints.size / 15)
                val samplePoints = routePoints.filterIndexed { index, _ -> index % sampleInterval == 0 }

                for (point in samplePoints) {
                    try {
                        val url = URL(helper.buildApiUrl(point))
                        val connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connectTimeout = REQUEST_TIMEOUT_MS
                        connection.readTimeout = REQUEST_TIMEOUT_MS
                        connection.setRequestProperty("User-Agent", USER_AGENT)

                        if (connection.responseCode == 200) {
                            val reader = BufferedReader(InputStreamReader(connection.inputStream))
                            val response = StringBuilder()
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                response.append(line)
                            }
                            reader.close()

                            val weather = parseWeatherResponse(response.toString(), point)
                            if (weather != null) {
                                helper.updateWeatherCache(point, weather)
                            }
                        }
                        connection.disconnect()

                        // Small delay between requests to avoid rate limiting
                        Thread.sleep(100)
                    } catch (e: Exception) {
                        LOG.warn("WeatherApiClient: Failed to fetch weather for $point", e)
                    }
                }

                // Generate the report from cached data
                val report = helper.analyzeRouteWeather(routePoints)
                handler.post { listener?.onRouteWeatherReportReady(report) }
            } catch (e: Exception) {
                LOG.error("WeatherApiClient: Route weather fetch failed", e)
            }
        }
    }

    /**
     * Parse Open-Meteo API JSON response into RouteWeather.
     *
     * Response format:
     * {
     *   "hourly": {
     *     "time": ["2024-01-01T00:00", ...],
     *     "temperature_2m": [5.0, ...],
     *     "windspeed_10m": [10.0, ...],
     *     "winddirection_10m": [180, ...],
     *     "windgusts_10m": [15.0, ...],
     *     "precipitation": [0.0, ...],
     *     "weathercode": [1, ...],
     *     "is_day": [1, ...]
     *   }
     * }
     */
    private fun parseWeatherResponse(json: String, latLon: LatLon): WeatherRoutingHelper.RouteWeather? {
        return try {
            val root = JSONObject(json)
            val hourly = root.getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            val temps = hourly.getJSONArray("temperature_2m")
            val windSpeeds = hourly.getJSONArray("windspeed_10m")
            val windDirs = hourly.getJSONArray("winddirection_10m")
            val windGusts = hourly.getJSONArray("windgusts_10m")
            val precip = hourly.getJSONArray("precipitation")
            val weatherCodes = hourly.getJSONArray("weathercode")
            val isDay = hourly.getJSONArray("is_day")

            // Find current hour index (use the first entry as closest forecast)
            // Open-Meteo returns data starting from the current hour
            val index = 0

            WeatherRoutingHelper.RouteWeather(
                latLon = latLon,
                temperature = temps.getDouble(index).toFloat(),
                windSpeed = windSpeeds.getDouble(index).toFloat(),
                windDirection = windDirs.getDouble(index).toFloat(),
                windGusts = windGusts.getDouble(index).toFloat(),
                precipitation = precip.getDouble(index).toFloat(),
                weatherCode = weatherCodes.getInt(index),
                isDay = isDay.getInt(index) == 1,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            LOG.error("WeatherApiClient: Parse error", e)
            null
        }
    }

    fun destroy() {
        executor.shutdownNow()
    }
}
