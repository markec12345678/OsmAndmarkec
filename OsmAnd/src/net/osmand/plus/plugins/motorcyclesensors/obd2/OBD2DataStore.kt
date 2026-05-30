package net.osmand.plus.plugins.motorcyclesensors.obd2

/**
 * OBD2DataStore - singleton holding the latest OBD2 data from the motorcycle's ECU.
 *
 * Updated by OBD2Helper when new data arrives via Bluetooth.
 * Read by OBD2Widget and other components for display.
 *
 * All OBD2 PID values are stored in their raw form with units
 * already converted for display (RPM as integer, temp in Celsius, etc.).
 *
 * Thread safety: All fields are accessed on the main thread only.
 */
class OBD2DataStore private constructor() {

    companion object {
        @Volatile
        private var instance: OBD2DataStore? = null

        fun getInstance(): OBD2DataStore {
            return instance ?: synchronized(this) {
                instance ?: OBD2DataStore().also { instance = it }
            }
        }
    }

    // Connection state
    var isConnected: Boolean = false
        private set
    var deviceName: String = ""
        private set
    var deviceAddress: String = ""
        private set
    var lastUpdateMs: Long = 0L
        private set

    // Engine data
    var rpm: Int = 0
        private set
    var engineCoolantTemp: Int = 0         // Celsius
        private set
    var throttlePosition: Float = 0f       // 0-100%
        private set
    var engineLoad: Float = 0f             // 0-100%
        private set

    // Calculated data
    var currentGear: Int = 0               // 0=neutral, 1-6=gears
        private set
    var speedKmh: Float = 0f               // from OBD2 (may differ from GPS)
        private set
    var fuelLevel: Float = 0f              // 0-100%
        private set
    var intakeAirTemp: Int = 0             // Celsius
        private set
    var manifoldPressure: Float = 0f       // kPa
        private set

    // DTC (Diagnostic Trouble Codes)
    var hasCheckEngineLight: Boolean = false
        private set
    var dtcCount: Int = 0
        private set

    /**
     * Update OBD2 data from a parsed PID response.
     */
    @Synchronized
    fun updateData(pid: String, value: Any) {
        lastUpdateMs = System.currentTimeMillis()
        when (pid) {
            "0C" -> rpm = value as Int
            "05" -> engineCoolantTemp = value as Int
            "11" -> throttlePosition = value as Float
            "04" -> engineLoad = value as Float
            "0D" -> speedKmh = value as Float
            "2F" -> fuelLevel = value as Float
            "0F" -> intakeAirTemp = value as Int
            "0B" -> manifoldPressure = value as Float
            "01" -> {
                // Mode 01 PID 01 contains DTC info
                val dtcData = value as DTCStatus
                hasCheckEngineLight = dtcData.hasCheckEngine
                dtcCount = dtcData.count
            }
        }

        // Auto-calculate gear when RPM or speed changes
        if (pid == "0C" || pid == "0D") {
            currentGear = calculateGear(rpm, speedKmh)
        }
    }

    @Synchronized
    fun setConnectionState(connected: Boolean, name: String = "", address: String = "") {
        isConnected = connected
        deviceName = name
        deviceAddress = address
        if (!connected) {
            reset()
        }
    }

    /**
     * Calculate gear position based on RPM, speed, and gear ratios.
     * Uses typical motorcycle gear ratios for estimation.
     */
    fun calculateGear(rpm: Int, speedKmh: Float): Int {
        if (speedKmh < 2f || rpm < 500) return 0  // neutral / stopped

        // Typical motorcycle: 1000 RPM at ~10 km/h in 1st gear
        // Gear ratios roughly double between gears
        val rpmPerKmh = rpm.toFloat() / speedKmh

        return when {
            rpmPerKmh > 180 -> 1
            rpmPerKmh > 120 -> 2
            rpmPerKmh > 85 -> 3
            rpmPerKmh > 65 -> 4
            rpmPerKmh > 50 -> 5
            rpmPerKmh > 0 -> 6
            else -> 0
        }
    }

    /**
     * Check if data is stale (no update for >5 seconds).
     */
    fun isStale(): Boolean {
        if (lastUpdateMs == 0L) return true
        return System.currentTimeMillis() - lastUpdateMs > 5000L
    }

    /**
     * Get RPM display string.
     */
    fun getRpmDisplay(): String {
        return if (isStale()) "--" else rpm.toString()
    }

    /**
     * Get gear display string.
     */
    fun getGearDisplay(): String {
        return if (isStale()) "--" else if (currentGear == 0) "N" else currentGear.toString()
    }

    /**
     * Get temperature display string.
     */
    fun getTempDisplay(): String {
        return if (isStale()) "--" else "${engineCoolantTemp}°C"
    }

    /**
     * Get throttle position display string.
     */
    fun getThrottleDisplay(): String {
        return if (isStale()) "--" else "${"%.0f".format(throttlePosition)}%"
    }

    /**
     * Get fuel level display string.
     */
    fun getFuelDisplay(): String {
        return if (isStale()) "--" else "${"%.0f".format(fuelLevel)}%"
    }

    @Synchronized
    fun reset() {
        rpm = 0
        engineCoolantTemp = 0
        throttlePosition = 0f
        engineLoad = 0f
        currentGear = 0
        speedKmh = 0f
        fuelLevel = 0f
        intakeAirTemp = 0
        manifoldPressure = 0f
        hasCheckEngineLight = false
        dtcCount = 0
        lastUpdateMs = 0L
    }
}

/**
 * DTC status data from PID 01.
 */
data class DTCStatus(
    val hasCheckEngine: Boolean,
    val count: Int
)
