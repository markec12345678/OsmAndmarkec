package net.osmand.plus.plugins.motorcyclesensors.safety

import android.content.Context
import android.content.SharedPreferences
import net.osmand.PlatformUtil
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent crash event log - stores all crash detection events for later review.
 *
 * Every time CrashDetectionHelper triggers, the event is logged here regardless
 * of whether it was a false alarm or a real crash. This provides:
 *
 * 1. Historical record of all crash detection events
 * 2. False positive rate tracking (user cancelled = false alarm)
 * 3. Impact data for post-ride analysis
 * 4. Evidence for tuning crash detection thresholds
 *
 * Events are stored in SharedPreferences as JSON array.
 * Maximum 50 events stored (ring buffer behavior).
 *
 * This is NOT an emergency response system. It's a diagnostic and logging tool.
 */
object CrashEventLog {

    private val LOG = PlatformUtil.getLog(CrashEventLog::class.java)

    private const val PREFS_NAME = "motorcycle_crash_events"
    private const val KEY_EVENTS = "crash_events"
    private const val MAX_EVENTS = 50

    data class CrashEvent(
        val timestampMs: Long,
        val gForceAtImpact: Float,
        val rotationAtImpact: Float,
        val latitude: Double,
        val longitude: Double,
        val speedAtImpactMs: Float,
        val userCancelled: Boolean,
        val countdownCompleted: Boolean
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("timestamp", timestampMs)
                put("date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs)))
                put("gForce", gForceAtImpact)
                put("rotationRadPerSec", rotationAtImpact)
                put("lat", latitude)
                put("lon", longitude)
                put("speedMs", speedAtImpactMs)
                put("speedKmh", speedAtImpactMs * 3.6f)
                put("userCancelled", userCancelled)
                put("countdownCompleted", countdownCompleted)
                put("isFalseAlarm", userCancelled)
            }
        }

        companion object {
            fun fromJson(json: JSONObject): CrashEvent {
                return CrashEvent(
                    timestampMs = json.optLong("timestamp", 0),
                    gForceAtImpact = json.optDouble("gForce", 0.0).toFloat(),
                    rotationAtImpact = json.optDouble("rotationRadPerSec", 0.0).toFloat(),
                    latitude = json.optDouble("lat", 0.0),
                    longitude = json.optDouble("lon", 0.0),
                    speedAtImpactMs = json.optDouble("speedMs", 0.0).toFloat(),
                    userCancelled = json.optBoolean("userCancelled", false),
                    countdownCompleted = json.optBoolean("countdownCompleted", false)
                )
            }
        }
    }

    /**
     * Log a crash event to persistent storage.
     */
    fun logEvent(context: Context, event: CrashEvent) {
        try {
            val prefs = getPrefs(context)
            val events = loadEvents(prefs)

            // Add new event
            events.add(event)

            // Ring buffer: remove oldest if over limit
            while (events.size > MAX_EVENTS) {
                events.removeAt(0)
            }

            // Save
            saveEvents(prefs, events)

            LOG.info("CrashEventLog: Event logged - falseAlarm=${event.userCancelled}, " +
                "G=${"%.1f".format(event.gForceAtImpact)}, " +
                "speed=${"%.0f".format(event.speedAtImpactMs * 3.6f)}km/h")
        } catch (e: Exception) {
            LOG.error("CrashEventLog: Failed to log event", e)
        }
    }

    /**
     * Get all stored crash events, newest first.
     */
    fun getEvents(context: Context): List<CrashEvent> {
        val prefs = getPrefs(context)
        return loadEvents(prefs).reversed()
    }

    /**
     * Get crash event statistics for the current session.
     *
     * Key metrics:
     * - Total events detected
     * - False alarm rate (user cancelled / total)
     * - Average G-force at impact
     * - Speed distribution at detection time
     */
    fun getStatistics(context: Context): CrashStatistics {
        val events = getEvents(context)
        if (events.isEmpty()) return CrashStatistics()

        val totalEvents = events.size
        val falseAlarms = events.count { it.userCancelled }
        val confirmedCrashes = events.count { !it.userCancelled && it.countdownCompleted }
        val avgGForce = events.map { it.gForceAtImpact }.average().toFloat()
        val maxGForce = events.maxOfOrNull { it.gForceAtImpact } ?: 0f
        val avgSpeed = events.map { it.speedAtImpactMs }.average().toFloat()
        val falseAlarmRate = if (totalEvents > 0) falseAlarms.toFloat() / totalEvents else 0f

        return CrashStatistics(
            totalEvents = totalEvents,
            falseAlarms = falseAlarms,
            confirmedCrashes = confirmedCrashes,
            falseAlarmRate = falseAlarmRate,
            avgGForceAtImpact = avgGForce,
            maxGForceAtImpact = maxGForce,
            avgSpeedAtImpactMs = avgSpeed
        )
    }

    /**
     * Clear all stored crash events.
     */
    fun clearEvents(context: Context) {
        val prefs = getPrefs(context)
        prefs.edit().remove(KEY_EVENTS).apply()
        LOG.info("CrashEventLog: All events cleared")
    }

    // ===== Private helpers =====

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun loadEvents(prefs: SharedPreferences): MutableList<CrashEvent> {
        val json = prefs.getString(KEY_EVENTS, null) ?: return mutableListOf()
        val array = JSONArray(json)
        val events = mutableListOf<CrashEvent>()
        for (i in 0 until array.length()) {
            try {
                events.add(CrashEvent.fromJson(array.getJSONObject(i)))
            } catch (e: Exception) {
                LOG.warn("CrashEventLog: Skipping malformed event at index $i")
            }
        }
        return events
    }

    private fun saveEvents(prefs: SharedPreferences, events: List<CrashEvent>) {
        val array = JSONArray()
        for (event in events) {
            array.put(event.toJson())
        }
        prefs.edit().putString(KEY_EVENTS, array.toString()).apply()
    }
}

data class CrashStatistics(
    val totalEvents: Int = 0,
    val falseAlarms: Int = 0,
    val confirmedCrashes: Int = 0,
    val falseAlarmRate: Float = 0f,
    val avgGForceAtImpact: Float = 0f,
    val maxGForceAtImpact: Float = 0f,
    val avgSpeedAtImpactMs: Float = 0f
) {
    /** Assessment of crash detection quality based on false alarm rate */
    fun getDetectionQuality(): String {
        return when {
            totalEvents == 0 -> "No events recorded"
            falseAlarmRate < 0.1f -> "Excellent (< 10% false alarms)"
            falseAlarmRate < 0.3f -> "Good (< 30% false alarms)"
            falseAlarmRate < 0.5f -> "Acceptable (< 50% false alarms)"
            falseAlarmRate < 0.8f -> "Poor (> 50% false alarms) - increase threshold"
            else -> "Unusable (> 80% false alarms) - recalibrate or increase sensitivity threshold"
        }
    }
}
