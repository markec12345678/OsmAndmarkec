package net.osmand.plus.plugins.motorcyclesensors.recording

import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import net.osmand.plus.plugins.motorcyclesensors.MotorcycleSensorsPlugin
import net.osmand.plus.plugins.motorcyclesensors.sensors.GForceData
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Records motorcycle sensor data (lean angle, G-force) during a ride.
 *
 * Data is recorded in two ways:
 * 1. Via GPX track extensions (attached by MotorcycleSensorsPlugin.attachAdditionalInfoToRecordedTrack)
 * 2. In-memory buffer for real-time display and post-ride analysis
 *
 * GPX extensions format:
 * <extensions>
 *   <lean_angle>23.5</lean_angle>
 *   <lateral_g>0.450</lateral_g>
 *   <longitudinal_g>0.120</longitudinal_g>
 *   <total_g>0.465</total_g>
 * </extensions>
 */
class MotorcycleSensorRecorder(private val app: OsmandApplication) {

    companion object {
        private val LOG = PlatformUtil.getLog(MotorcycleSensorRecorder::class.java)

        // Maximum recording buffer size (prevents memory issues on long rides)
        // At 50Hz, this is ~5.5 hours of data
        private const val MAX_BUFFER_SIZE = 1_000_000

        // Minimum interval between recorded samples (in nanoseconds)
        // 100ms = 10Hz recording rate (sufficient for post-ride analysis)
        private const val MIN_RECORDING_INTERVAL_NS = 100_000_000L
    }

    private val executor = Executors.newSingleThreadExecutor()

    // Recording state
    @Volatile
    var isRecording = false
        private set

    private var lastRecordedTimestampNs: Long = 0

    // In-memory data buffer for post-ride analysis
    private val dataBuffer = CopyOnWriteArrayList<MotorcycleSensorDataPoint>()
    private val dataListeners = CopyOnWriteArrayList<SensorRecordingListener>()

    // Ride statistics
    private var rideStartTimeMs: Long = 0
    private var rideEndTimeMs: Long = 0
    private var maxLeanAngle: Float = 0f
    private var maxLateralG: Float = 0f
    private var maxTotalG: Float = 0f
    private var maxBrakingG: Float = 0f
    private var leanAngleSum: Float = 0f
    private var leanAngleCount: Int = 0
    private var gForceSum: Float = 0f
    private var gForceCount: Int = 0

    interface SensorRecordingListener {
        fun onDataRecorded(dataPoint: MotorcycleSensorDataPoint)
        fun onRecordingStarted()
        fun onRecordingStopped()
    }

    /**
     * Start recording sensor data
     */
    fun startRecording() {
        if (isRecording) return
        isRecording = true
        rideStartTimeMs = System.currentTimeMillis()
        lastRecordedTimestampNs = 0
        LOG.info("Motorcycle sensor recording started")
        notifyRecordingStarted()
    }

    /**
     * Stop recording sensor data
     */
    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        rideEndTimeMs = System.currentTimeMillis()
        LOG.info("Motorcycle sensor recording stopped. Data points: ${dataBuffer.size}")
        notifyRecordingStopped()
    }

    /**
     * Record a sensor data point.
     * Called from the sensor update loop at ~50Hz, but only records at 10Hz.
     */
    fun recordSensorData(
        leanAngleDeg: Float,
        gForceData: GForceData?,
        timestamp: Long
    ) {
        if (!isRecording) return

        // Throttle recording rate
        if (timestamp - lastRecordedTimestampNs < MIN_RECORDING_INTERVAL_NS) return
        lastRecordedTimestampNs = timestamp

        executor.execute {
            val dataPoint = MotorcycleSensorDataPoint(
                timestampMs = System.currentTimeMillis(),
                leanAngleDeg = leanAngleDeg,
                lateralG = gForceData?.lateralG ?: 0f,
                longitudinalG = gForceData?.longitudinalG ?: 0f,
                totalG = gForceData?.totalG ?: 0f
            )

            // Add to buffer (with size limit)
            if (dataBuffer.size >= MAX_BUFFER_SIZE) {
                dataBuffer.removeAt(0)
            }
            dataBuffer.add(dataPoint)

            // Update statistics
            updateStatistics(dataPoint)

            // Notify listeners
            notifyDataRecorded(dataPoint)
        }
    }

    private fun updateStatistics(dataPoint: MotorcycleSensorDataPoint) {
        val absLean = Math.abs(dataPoint.leanAngleDeg)
        if (absLean > maxLeanAngle) maxLeanAngle = absLean
        if (Math.abs(dataPoint.lateralG) > Math.abs(maxLateralG)) maxLateralG = dataPoint.lateralG
        if (dataPoint.totalG > maxTotalG) maxTotalG = dataPoint.totalG
        if (dataPoint.longitudinalG < maxBrakingG) maxBrakingG = dataPoint.longitudinalG

        leanAngleSum += absLean
        leanAngleCount++
        gForceSum += dataPoint.totalG
        gForceCount++
    }

    /**
     * Get all recorded data points for post-ride analysis
     */
    fun getRecordedData(): List<MotorcycleSensorDataPoint> {
        return dataBuffer.toList()
    }

    /**
     * Get ride statistics
     */
    fun getRideStatistics(): MotorcycleRideStatistics {
        val avgLean = if (leanAngleCount > 0) leanAngleSum / leanAngleCount else 0f
        val avgGForce = if (gForceCount > 0) gForceSum / gForceCount else 0f
        val durationMs = if (rideEndTimeMs > 0) rideEndTimeMs - rideStartTimeMs
                         else System.currentTimeMillis() - rideStartTimeMs

        return MotorcycleRideStatistics(
            durationMs = durationMs,
            dataPointCount = dataBuffer.size,
            maxLeanAngleDeg = maxLeanAngle,
            avgLeanAngleDeg = avgLean,
            maxLateralG = maxLateralG,
            maxTotalG = maxTotalG,
            maxBrakingG = maxBrakingG,
            avgTotalG = avgGForce
        )
    }

    /**
     * Get data points in a time range for chart display
     */
    fun getDataInRange(fromMs: Long, toMs: Long): List<MotorcycleSensorDataPoint> {
        return dataBuffer.filter { it.timestampMs in fromMs..toMs }
    }

    /**
     * Get lean angle distribution (histogram data)
     * Returns map of angle ranges to count of data points
     */
    fun getLeanAngleDistribution(): Map<String, Int> {
        val distribution = mutableMapOf<String, Int>()
        val ranges = listOf("0-10°", "10-20°", "20-30°", "30-40°", "40-50°", "50°+")

        for (data in dataBuffer) {
            val absLean = Math.abs(data.leanAngleDeg).toInt()
            val range = when {
                absLean < 10 -> "0-10°"
                absLean < 20 -> "10-20°"
                absLean < 30 -> "20-30°"
                absLean < 40 -> "30-40°"
                absLean < 50 -> "40-50°"
                else -> "50°+"
            }
            distribution[range] = (distribution[range] ?: 0) + 1
        }

        return distribution
    }

    fun addListener(listener: SensorRecordingListener) {
        if (!dataListeners.contains(listener)) dataListeners.add(listener)
    }

    fun removeListener(listener: SensorRecordingListener) {
        dataListeners.remove(listener)
    }

    private fun notifyDataRecorded(dataPoint: MotorcycleSensorDataPoint) {
        for (listener in dataListeners) {
            listener.onDataRecorded(dataPoint)
        }
    }

    private fun notifyRecordingStarted() {
        for (listener in dataListeners) {
            listener.onRecordingStarted()
        }
    }

    private fun notifyRecordingStopped() {
        for (listener in dataListeners) {
            listener.onRecordingStopped()
        }
    }

    /**
     * Reset all recorded data
     */
    fun reset() {
        dataBuffer.clear()
        maxLeanAngle = 0f
        maxLateralG = 0f
        maxTotalG = 0f
        maxBrakingG = 0f
        leanAngleSum = 0f
        leanAngleCount = 0
        gForceSum = 0f
        gForceCount = 0
        rideStartTimeMs = 0
        rideEndTimeMs = 0
        lastRecordedTimestampNs = 0
    }

    fun destroy() {
        stopRecording()
        dataBuffer.clear()
        dataListeners.clear()
        executor.shutdown()
    }
}

/**
 * Single data point of motorcycle sensor data
 */
data class MotorcycleSensorDataPoint(
    val timestampMs: Long,
    val leanAngleDeg: Float,
    val lateralG: Float,
    val longitudinalG: Float,
    val totalG: Float
)

/**
 * Aggregate ride statistics
 */
data class MotorcycleRideStatistics(
    val durationMs: Long,
    val dataPointCount: Int,
    val maxLeanAngleDeg: Float,
    val avgLeanAngleDeg: Float,
    val maxLateralG: Float,
    val maxTotalG: Float,
    val maxBrakingG: Float,
    val avgTotalG: Float
)
