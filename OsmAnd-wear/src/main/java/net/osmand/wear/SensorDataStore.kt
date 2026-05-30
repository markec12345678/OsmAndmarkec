package net.osmand.wear

/**
 * SensorDataStore - singleton holding the latest sensor data on the watch.
 *
 * Updated by PhoneListenerService when new data arrives from the phone.
 * Read by MainActivity, complications, and ambient mode for display.
 *
 * Thread safety: All fields are accessed on the main thread only
 * (UI thread for display, WearableListenerService callbacks are main-thread).
 */
class SensorDataStore private constructor() {

    companion object {
        // Data path (must match phone-side WearOsBridge)
        const val SENSOR_DATA_PATH = "/motorcycle/sensors"

        // Data keys (must match phone-side WearOsBridge)
        const val KEY_LEAN_ANGLE = "lean_angle"
        const val KEY_LEAN_DIRECTION = "lean_direction"
        const val KEY_TOTAL_G = "total_g"
        const val KEY_LATERAL_G = "lateral_g"
        const val KEY_LONGITUDINAL_G = "longitudinal_g"
        const val KEY_SPEED_KMH = "speed_kmh"
        const val KEY_MAX_LEAN = "max_lean"
        const val KEY_MAX_G = "max_g"
        const val KEY_TIMESTAMP = "timestamp"

        @Volatile
        private var instance: SensorDataStore? = null

        fun getInstance(): SensorDataStore {
            return instance ?: synchronized(this) {
                instance ?: SensorDataStore().also { instance = it }
            }
        }
    }

    // Current sensor values
    var leanAngleDeg: Float = 0f
    var leanDirection: String = ""
    var totalG: Float = 0f
    var lateralG: Float = 0f
    var longitudinalG: Float = 0f
    var speedKmh: Float = 0f
    var maxLean: Float = 0f
    var maxG: Float = 0f
    var lastUpdateMs: Long = 0L

    // Connection state
    var isConnected: Boolean = false

    // Crash alert state
    var isCrashActive: Boolean = false
    var crashGForce: Float = 0f
    var crashLat: Double = 0.0
    var crashLon: Double = 0.0

    /**
     * Check if data is stale (no update for >5 seconds).
     */
    fun isStale(): Boolean {
        if (lastUpdateMs == 0L) return true
        return System.currentTimeMillis() - lastUpdateMs > 5000L
    }

    /**
     * Get formatted lean angle string for display.
     */
    fun getLeanAngleDisplay(): String {
        if (isStale()) return "--"
        val abs = Math.abs(leanAngleDeg)
        val dir = if (leanAngleDeg >= 0) "R" else "L"
        return "${"%.0f".format(abs)}${dir}"
    }

    /**
     * Get formatted G-force string for display.
     */
    fun getGForceDisplay(): String {
        if (isStale()) return "--"
        return "${"%.2f".format(totalG)}G"
    }

    /**
     * Get formatted speed string for display.
     */
    fun getSpeedDisplay(): String {
        if (isStale()) return "--"
        return "${"%.0f".format(speedKmh)}"
    }

    /**
     * Reset crash state (user acknowledged on watch).
     */
    fun resetCrash() {
        isCrashActive = false
        crashGForce = 0f
        crashLat = 0.0
        crashLon = 0.0
    }
}
