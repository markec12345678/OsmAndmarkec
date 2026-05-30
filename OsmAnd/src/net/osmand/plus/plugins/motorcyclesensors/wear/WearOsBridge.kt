package net.osmand.plus.plugins.motorcyclesensors.wear

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import net.osmand.PlatformUtil
import org.json.JSONObject

/**
 * WearOsBridge - phone-side communication bridge to Wear OS companion app.
 *
 * Sends real-time motorcycle sensor data from the phone to the connected watch:
 * - Lean angle (degrees, with direction)
 * - G-force (total, lateral, longitudinal)
 * - Speed (km/h)
 * - Crash alert state
 *
 * Architecture:
 * - Uses Google Play Services Wearable Data API
 * - Phone = data producer, Watch = data consumer
 * - Data is sent via DataClient.putDataItem() with auto-throttle
 * - Urgent data (crash alerts) sent via MessageClient for instant delivery
 *
 * Data path: /motorcycle/sensors
 * Update rate: ~2 Hz (every 500ms) to conserve watch battery
 *
 * Design decisions:
 * - DataClient over MessageClient for regular sensor updates
 *   (DataClient is battery-optimized, auto-batched by Play Services)
 * - MessageClient only for crash alerts (instant delivery needed)
 * - All data in a single DataMap to reduce round trips
 * - Timestamp included for staleness detection on watch
 */
class WearOsBridge(private val context: Context) {

    companion object {
        private val LOG = PlatformUtil.getLog(WearOsBridge::class.java)

        // Data paths
        const val SENSOR_DATA_PATH = "/motorcycle/sensors"
        const val CRASH_ALERT_PATH = "/motorcycle/crash"
        const val CAPABILITY_PATH = "motorcycle_sensor_app"

        // Data keys
        const val KEY_LEAN_ANGLE = "lean_angle"
        const val KEY_LEAN_DIRECTION = "lean_direction"
        const val KEY_TOTAL_G = "total_g"
        const val KEY_LATERAL_G = "lateral_g"
        const val KEY_LONGITUDINAL_G = "longitudinal_g"
        const val KEY_SPEED_KMH = "speed_kmh"
        const val KEY_MAX_LEAN = "max_lean"
        const val KEY_MAX_G = "max_g"
        const val KEY_TIMESTAMP = "timestamp"
        const val KEY_CRASH_GFORCE = "crash_gforce"
        const val KEY_CRASH_LAT = "crash_lat"
        const val KEY_CRASH_LON = "crash_lon"

        // Update throttle
        const val MIN_UPDATE_INTERVAL_MS = 500L  // 2 Hz max
    }

    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val messageClient = Wearable.getMessageClient(context)

    private var lastUpdateTime = 0L

    /**
     * Send sensor data to connected watch.
     * Throttled to 2 Hz to conserve battery.
     *
     * @param leanAngleDeg Current lean angle in degrees
     * @param leanDirection "L" or "R" for direction
     * @param totalG Total G-force
     * @param lateralG Lateral G-force
     * @param longitudinalG Longitudinal G-force
     * @param speedKmh Current GPS speed
     * @param maxLean Max lean angle this ride
     * @param maxG Max G-force this ride
     */
    fun sendSensorData(
        leanAngleDeg: Float,
        leanDirection: String,
        totalG: Float,
        lateralG: Float,
        longitudinalG: Float,
        speedKmh: Float,
        maxLean: Float,
        maxG: Float
    ) {
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < MIN_UPDATE_INTERVAL_MS) return
        lastUpdateTime = now

        try {
            val putDataReq = PutDataMapRequest.create(SENSOR_DATA_PATH).apply {
                dataMap.putFloat(KEY_LEAN_ANGLE, leanAngleDeg)
                dataMap.putString(KEY_LEAN_DIRECTION, leanDirection)
                dataMap.putFloat(KEY_TOTAL_G, totalG)
                dataMap.putFloat(KEY_LATERAL_G, lateralG)
                dataMap.putFloat(KEY_LONGITUDINAL_G, longitudinalG)
                dataMap.putFloat(KEY_SPEED_KMH, speedKmh)
                dataMap.putFloat(KEY_MAX_LEAN, maxLean)
                dataMap.putFloat(KEY_MAX_G, maxG)
                dataMap.putLong(KEY_TIMESTAMP, now)
            }
            putDataReq.setUrgent()
            dataClient.putDataItem(putDataReq.asPutDataRequest())

            LOG.debug("WearOsBridge: Sensor data sent - lean: ${"%.1f".format(leanAngleDeg)} deg, " +
                "G: ${"%.2f".format(totalG)}, speed: ${"%.0f".format(speedKmh)} km/h")
        } catch (e: Exception) {
            LOG.error("WearOsBridge: Failed to send sensor data", e)
        }
    }

    /**
     * Send crash alert to watch instantly (no throttle).
     * Uses MessageClient for immediate delivery.
     */
    fun sendCrashAlert(gForce: Float, lat: Double, lon: Double) {
        try {
            val data = JSONObject().apply {
                put(KEY_CRASH_GFORCE, gForce)
                put(KEY_CRASH_LAT, lat)
                put(KEY_CRASH_LON, lon)
                put(KEY_TIMESTAMP, System.currentTimeMillis())
            }

            // Send to all connected nodes
            val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
            for (node in nodes) {
                messageClient.sendMessage(
                    node.id,
                    CRASH_ALERT_PATH,
                    data.toString().toByteArray()
                )
                LOG.warn("WearOsBridge: Crash alert sent to ${node.displayName}")
            }
        } catch (e: Exception) {
            LOG.error("WearOsBridge: Failed to send crash alert", e)
        }
    }

    /**
     * Check if any Wear OS watch is connected.
     */
    fun hasConnectedWatch(): Boolean {
        return try {
            val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
            nodes.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
}
