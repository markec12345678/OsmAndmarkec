package net.osmand.plus.plugins.motorcyclesensors.instrumentation

import net.osmand.Location
import net.osmand.PlatformUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Sensor Diagnostics Helper - Real device stress test instrumentation layer.
 *
 * This is NOT a user-facing feature. It's an engineering tool that collects
 * raw sensor diagnostics during real rides to identify:
 *
 * 1. Gyro noise floor (variance, mean offset, peak noise)
 * 2. Lean angle consistency (drift rate, variance, calibration error)
 * 3. GPS jitter (position variance, speed consistency, accuracy distribution)
 * 4. Crash false trigger events (what caused the false positive)
 * 5. Sensor update rate consistency (drops, gaps, latency spikes)
 *
 * The diagnostics data is written to a JSON file that can be pulled from
 * the device for post-ride analysis. This enables evidence-based tuning
 * of filter coefficients, thresholds, and calibration parameters.
 *
 * Design principles:
 * - Zero impact on real-time performance (all stats are lazy/accumulated)
 * - Fixed-size ring buffers prevent memory growth
 * - Background thread file I/O
 * - Graceful degradation if storage is unavailable
 */
class SensorDiagnosticsHelper {

    companion object {
        private val LOG = PlatformUtil.getLog(SensorDiagnosticsHelper::class.java)

        // Ring buffer sizes
        private const val GYRO_BUFFER_SIZE = 500      // ~10 seconds at 50Hz
        private const val LEAN_BUFFER_SIZE = 500
        private const val GPS_BUFFER_SIZE = 300        // ~5 minutes at 1Hz
        private const val ACCEL_BUFFER_SIZE = 500
        private const val EVENT_LOG_SIZE = 100

        // Sampling
        private const val DIAGNOSTICS_SAMPLE_INTERVAL_MS = 200L  // 5Hz sampling for diagnostics
    }

    // ===== Ring Buffers =====
    private val gyroSamples = ConcurrentLinkedQueue<GyroSample>()
    private val leanSamples = ConcurrentLinkedQueue<LeanSample>()
    private val gpsSamples = ConcurrentLinkedQueue<GpsSample>()
    private val accelSamples = ConcurrentLinkedQueue<AccelSample>()
    private val eventLog = ConcurrentLinkedQueue<DiagnosticEvent>()

    // ===== State =====
    private var isCollecting = false
    val isCollectingVisible: Boolean get() = isCollecting
    private var rideStartTimeMs = 0L
    private var totalSensorUpdates = 0L
    private var sensorUpdateGaps = 0     // Count of >100ms gaps between updates
    private var lastSensorTimestampNs = 0L
    private var lastDiagnosticsSampleMs = 0L

    // ===== Crash false trigger tracking =====
    private var crashFalseTriggers = 0
    private var lastCrashFalseTriggerReason = ""

    // ===== Data Classes =====

    data class GyroSample(
        val timestampMs: Long,
        val gyroX: Float, val gyroY: Float, val gyroZ: Float,
        val rotationRate: Float  // Total rotation rate
    )

    data class LeanSample(
        val timestampMs: Long,
        val leanAngleDeg: Float,
        val rollFromRotation: Float,
        val rollFromAccel: Float,
        val gyroIntegrated: Float
    )

    data class GpsSample(
        val timestampMs: Long,
        val lat: Double, val lon: Double,
        val speedMs: Float,
        val accuracyM: Float,
        val bearingDeg: Float
    )

    data class AccelSample(
        val timestampMs: Long,
        val accelX: Float, val accelY: Float, val accelZ: Float,
        val totalG: Float
    )

    data class DiagnosticEvent(
        val timestampMs: Long,
        val type: String,
        val message: String,
        val data: Map<String, Float>? = null
    )

    // ===== Public API =====

    /**
     * Start collecting diagnostics data for a ride.
     */
    fun startCollection() {
        isCollecting = true
        rideStartTimeMs = System.currentTimeMillis()
        totalSensorUpdates = 0
        sensorUpdateGaps = 0
        lastSensorTimestampNs = 0
        crashFalseTriggers = 0
        lastCrashFalseTriggerReason = ""
        clearBuffers()
        LOG.info("SensorDiagnostics: Collection started")
    }

    /**
     * Stop collecting and write diagnostics to file.
     */
    fun stopCollection(outputDir: File? = null): File? {
        isCollecting = false
        val report = generateReport()
        val file = writeReportToFile(report, outputDir)
        LOG.info("SensorDiagnostics: Collection stopped, report written to ${file?.absolutePath}")
        return file
    }

    /**
     * Feed sensor data for diagnostics analysis.
     * Called from the same pipeline as the real sensor processing.
     */
    fun updateSensorData(
        accelX: Float, accelY: Float, accelZ: Float,
        gyroX: Float, gyroY: Float, gyroZ: Float,
        leanAngleDeg: Float,
        rollFromRotation: Float, rollFromAccel: Float, gyroIntegrated: Float,
        timestampNs: Long
    ) {
        if (!isCollecting) return

        val nowMs = System.currentTimeMillis()

        // Check for sensor update gaps (>100ms = potential throttling)
        if (lastSensorTimestampNs > 0) {
            val gapMs = (timestampNs - lastSensorTimestampNs) / 1_000_000f
            if (gapMs > 100f) {
                sensorUpdateGaps++
                if (gapMs > 500f) {
                    logEvent("SENSOR_GAP", "Large sensor gap: ${"%.0f".format(gapMs)}ms")
                }
            }
        }
        lastSensorTimestampNs = timestampNs
        totalSensorUpdates++

        // Throttle diagnostics sampling
        if (nowMs - lastDiagnosticsSampleMs < DIAGNOSTICS_SAMPLE_INTERVAL_MS) return
        lastDiagnosticsSampleMs = nowMs

        // Buffer gyro data
        val rotationRate = sqrt((gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ).toDouble()).toFloat()
        addToBuffer(gyroSamples, GyroSample(nowMs, gyroX, gyroY, gyroZ, rotationRate), GYRO_BUFFER_SIZE)

        // Buffer lean data
        addToBuffer(leanSamples, LeanSample(nowMs, leanAngleDeg, rollFromRotation, rollFromAccel, gyroIntegrated), LEAN_BUFFER_SIZE)

        // Buffer accel data
        val totalG = sqrt((accelX * accelX + accelY * accelY + accelZ * accelZ).toDouble()).toFloat() / 9.80665f
        addToBuffer(accelSamples, AccelSample(nowMs, accelX, accelY, accelZ, totalG), ACCEL_BUFFER_SIZE)
    }

    /**
     * Feed GPS data for diagnostics.
     */
    fun updateGpsData(location: Location) {
        if (!isCollecting) return

        val nowMs = System.currentTimeMillis()
        addToBuffer(gpsSamples, GpsSample(
            nowMs, location.latitude, location.longitude,
            location.speed, location.accuracy, location.bearing
        ), GPS_BUFFER_SIZE)
    }

    /**
     * Log a crash false trigger event for analysis.
     */
    fun logCrashFalseTrigger(reason: String, gForceAtTrigger: Float, rotationAtTrigger: Float) {
        crashFalseTriggers++
        lastCrashFalseTriggerReason = reason
        logEvent("CRASH_FALSE_TRIGGER",
            "False crash trigger: $reason (G=${"%.2f".format(gForceAtTrigger)}, rot=${"%.2f".format(rotationAtTrigger)}rad/s)",
            mapOf("gForce" to gForceAtTrigger, "rotation" to rotationAtTrigger)
        )
    }

    /**
     * Log any diagnostic event.
     */
    fun logEvent(type: String, message: String, data: Map<String, Float>? = null) {
        if (eventLog.size >= EVENT_LOG_SIZE) {
            eventLog.poll()
        }
        eventLog.add(DiagnosticEvent(System.currentTimeMillis(), type, message, data))
    }

    // ===== Analysis Methods =====

    /**
     * Calculate gyro noise statistics from buffered samples.
     *
     * Returns:
     * - meanX/Y/Z: average gyro offset (bias)
     * - stddevX/Y/Z: noise standard deviation
     * - peakNoise: maximum single-sample deviation from mean
     * - zeroCrossingRate: how often gyro crosses zero (indicates oscillation vs drift)
     */
    fun analyzeGyroNoise(): GyroNoiseStats {
        if (gyroSamples.size < 10) {
            return GyroNoiseStats()
        }

        val samples = gyroSamples.toList()
        val n = samples.size

        // Mean
        var sumX = 0.0; var sumY = 0.0; var sumZ = 0.0
        for (s in samples) { sumX += s.gyroX; sumY += s.gyroY; sumZ += s.gyroZ }
        val meanX = sumX / n; val meanY = sumY / n; val meanZ = sumZ / n

        // Standard deviation
        var varX = 0.0; var varY = 0.0; var varZ = 0.0
        for (s in samples) {
            varX += (s.gyroX - meanX).pow(2)
            varY += (s.gyroY - meanY).pow(2)
            varZ += (s.gyroZ - meanZ).pow(2)
        }
        val stddevX = sqrt(varX / n); val stddevY = sqrt(varY / n); val stddevZ = sqrt(varZ / n)

        // Peak noise
        var peakNoiseX = 0.0; var peakNoiseY = 0.0; var peakNoiseZ = 0.0
        for (s in samples) {
            peakNoiseX = max(peakNoiseX, abs(s.gyroX - meanX))
            peakNoiseY = max(peakNoiseY, abs(s.gyroY - meanY))
            peakNoiseZ = max(peakNoiseZ, abs(s.gyroZ - meanZ))
        }

        // Zero crossing rate for X (roll axis - most relevant for lean)
        var zeroCrossings = 0
        for (i in 1 until n) {
            if ((samples[i - 1].gyroX - meanX) * (samples[i].gyroX - meanX) < 0) zeroCrossings++
        }
        val zeroCrossingRate = zeroCrossings.toFloat() / n

        return GyroNoiseStats(
            meanX = meanX.toFloat(), meanY = meanY.toFloat(), meanZ = meanZ.toFloat(),
            stddevX = stddevX.toFloat(), stddevY = stddevY.toFloat(), stddevZ = stddevZ.toFloat(),
            peakNoiseX = peakNoiseX.toFloat(), peakNoiseY = peakNoiseY.toFloat(), peakNoiseZ = peakNoiseZ.toFloat(),
            zeroCrossingRate = zeroCrossingRate,
            sampleCount = n
        )
    }

    /**
     * Analyze lean angle consistency and drift.
     */
    fun analyzeLeanConsistency(): LeanConsistencyStats {
        if (leanSamples.size < 10) {
            return LeanConsistencyStats()
        }

        val samples = leanSamples.toList()
        val n = samples.size

        // Variance of lean angle
        var sumLean = 0.0
        for (s in samples) sumLean += s.leanAngleDeg
        val meanLean = sumLean / n

        var varLean = 0.0
        for (s in samples) varLean += (s.leanAngleDeg - meanLean).pow(2)
        val stddevLean = sqrt(varLean / n)

        // Gyro vs Rotation vector discrepancy
        var sumGyroDiff = 0.0
        var sumAccelDiff = 0.0
        var maxGyroDiff = 0.0
        var maxAccelDiff = 0.0
        var count = 0
        for (s in samples) {
            if (abs(s.rollFromRotation) > 0.1f || abs(s.gyroIntegrated) > 0.1f) {
                val gyroDiff = abs(s.gyroIntegrated - s.rollFromRotation)
                val accelDiff = abs(s.rollFromAccel - s.rollFromRotation)
                sumGyroDiff += gyroDiff
                sumAccelDiff += accelDiff
                maxGyroDiff = max(maxGyroDiff, gyroDiff)
                maxAccelDiff = max(maxAccelDiff, accelDiff)
                count++
            }
        }

        // Drift rate: compare first 10 samples average vs last 10 samples average
        val straightSamples = samples.filter { abs(it.leanAngleDeg) < 5f }
        var driftRateDegPerMin = 0f
        if (straightSamples.size >= 20) {
            val first10 = straightSamples.take(10).map { it.leanAngleDeg }.average()
            val last10 = straightSamples.takeLast(10).map { it.leanAngleDeg }.average()
            val timeSpanMs = straightSamples.last().timestampMs - straightSamples.first().timestampMs
            if (timeSpanMs > 0) {
                driftRateDegPerMin = ((last10 - first10) / timeSpanMs * 60000f).toFloat()
            }
        }

        return LeanConsistencyStats(
            meanLeanDeg = meanLean.toFloat(),
            stddevLeanDeg = stddevLean.toFloat(),
            avgGyroRotationDiscrepancyDeg = if (count > 0) (sumGyroDiff / count).toFloat() else 0f,
            avgAccelRotationDiscrepancyDeg = if (count > 0) (sumAccelDiff / count).toFloat() else 0f,
            maxGyroRotationDiscrepancyDeg = maxGyroDiff.toFloat(),
            maxAccelRotationDiscrepancyDeg = maxAccelDiff.toFloat(),
            driftRateDegPerMin = driftRateDegPerMin,
            sampleCount = n
        )
    }

    /**
     * Analyze GPS quality metrics.
     */
    fun analyzeGpsQuality(): GpsQualityStats {
        if (gpsSamples.size < 5) {
            return GpsQualityStats()
        }

        val samples = gpsSamples.toList()
        val n = samples.size

        // Accuracy statistics
        val accuracies = samples.map { it.accuracyM }.filter { it > 0f }
        val avgAccuracy = if (accuracies.isNotEmpty()) accuracies.average().toFloat() else 0f
        val maxAccuracy = accuracies.maxOrNull() ?: 0f
        val minAccuracy = accuracies.minOrNull() ?: 0f

        // Speed jitter
        var speedJitterSum = 0.0
        var speedJitterCount = 0
        var maxSpeedJump = 0f
        for (i in 1 until n) {
            val diff = abs(samples[i].speedMs - samples[i - 1].speedMs)
            speedJitterSum += diff
            speedJitterCount++
            if (diff > maxSpeedJump) maxSpeedJump = diff
        }
        val avgSpeedJitter = if (speedJitterCount > 0) (speedJitterSum / speedJitterCount).toFloat() else 0f

        // Position jitter for near-stationary points
        val stationary = samples.filter { it.speedMs < 1f }
        var positionJitterM = 0f
        if (stationary.size >= 2) {
            val meanLat = stationary.map { it.lat }.average()
            val meanLon = stationary.map { it.lon }.average()
            var jitterSum = 0.0
            for (s in stationary) {
                val dLat = s.lat - meanLat
                val dLon = s.lon - meanLon
                jitterSum += sqrt(dLat * dLat + dLon * dLon)
            }
            positionJitterM = (sqrt(jitterSum / stationary.size) * 111000f).toFloat()
        }

        // GPS loss detection
        var gpsLosses = 0
        for (i in 1 until n) {
            val gapMs = samples[i].timestampMs - samples[i - 1].timestampMs
            if (gapMs > 5000L) gpsLosses++
        }

        return GpsQualityStats(
            avgAccuracyM = avgAccuracy,
            minAccuracyM = minAccuracy,
            maxAccuracyM = maxAccuracy,
            avgSpeedJitterMs = avgSpeedJitter,
            maxSpeedJumpMs = maxSpeedJump,
            positionJitterM = positionJitterM,
            gpsLosses = gpsLosses,
            sampleCount = n
        )
    }

    /**
     * Generate a comprehensive diagnostics report as JSONObject.
     */
    fun generateReport(): JSONObject {
        val report = JSONObject()

        report.put("timestamp", System.currentTimeMillis())
        report.put("date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        report.put("rideDurationMs", if (rideStartTimeMs > 0) System.currentTimeMillis() - rideStartTimeMs else 0)

        // Sensor update statistics
        val sensorStats = JSONObject()
        sensorStats.put("totalUpdates", totalSensorUpdates)
        sensorStats.put("updateGaps", sensorUpdateGaps)
        sensorStats.put("gapRate", if (totalSensorUpdates > 0) sensorUpdateGaps.toDouble() / totalSensorUpdates else 0.0)
        report.put("sensorUpdateStats", sensorStats)

        // Gyro noise
        report.put("gyroNoise", gyroNoiseToJson(analyzeGyroNoise()))

        // Lean consistency
        report.put("leanConsistency", leanConsistencyToJson(analyzeLeanConsistency()))

        // GPS quality
        report.put("gpsQuality", gpsQualityToJson(analyzeGpsQuality()))

        // Crash false triggers
        val crashStats = JSONObject()
        crashStats.put("falseTriggers", crashFalseTriggers)
        crashStats.put("lastReason", lastCrashFalseTriggerReason)
        report.put("crashFalseTriggers", crashStats)

        // Event log
        val eventsArray = JSONArray()
        for (event in eventLog) {
            val evt = JSONObject()
            evt.put("time", event.timestampMs)
            evt.put("type", event.type)
            evt.put("message", event.message)
            eventsArray.put(evt)
        }
        report.put("events", eventsArray)

        return report
    }

    // ===== Helper Methods =====

    private fun <T> addToBuffer(queue: ConcurrentLinkedQueue<T>, item: T, maxSize: Int) {
        while (queue.size >= maxSize) {
            queue.poll()
        }
        queue.add(item)
    }

    private fun clearBuffers() {
        gyroSamples.clear()
        leanSamples.clear()
        gpsSamples.clear()
        accelSamples.clear()
        eventLog.clear()
    }

    private fun writeReportToFile(report: JSONObject, outputDir: File?): File? {
        try {
            if (outputDir == null) {
                LOG.warn("SensorDiagnostics: No output directory specified, skipping report write")
                return null
            }
            val dir = outputDir
            if (!dir.exists()) dir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "moto_diag_$timestamp.json")

            FileWriter(file).use { writer ->
                writer.write(report.toString(2))
            }
            return file
        } catch (e: Exception) {
            LOG.error("Failed to write diagnostics report", e)
            return null
        }
    }

    private fun gyroNoiseToJson(stats: GyroNoiseStats): JSONObject {
        val json = JSONObject()
        json.put("meanX", stats.meanX)
        json.put("meanY", stats.meanY)
        json.put("meanZ", stats.meanZ)
        json.put("stddevX", stats.stddevX)
        json.put("stddevY", stats.stddevY)
        json.put("stddevZ", stats.stddevZ)
        json.put("peakNoiseX", stats.peakNoiseX)
        json.put("peakNoiseY", stats.peakNoiseY)
        json.put("peakNoiseZ", stats.peakNoiseZ)
        json.put("zeroCrossingRate", stats.zeroCrossingRate)
        json.put("sampleCount", stats.sampleCount)
        json.put("noiseLevel", stats.getNoiseLevel().name)
        return json
    }

    private fun leanConsistencyToJson(stats: LeanConsistencyStats): JSONObject {
        val json = JSONObject()
        json.put("meanLeanDeg", stats.meanLeanDeg)
        json.put("stddevLeanDeg", stats.stddevLeanDeg)
        json.put("avgGyroRotationDiscrepancyDeg", stats.avgGyroRotationDiscrepancyDeg)
        json.put("avgAccelRotationDiscrepancyDeg", stats.avgAccelRotationDiscrepancyDeg)
        json.put("maxGyroRotationDiscrepancyDeg", stats.maxGyroRotationDiscrepancyDeg)
        json.put("driftRateDegPerMin", stats.driftRateDegPerMin)
        json.put("sampleCount", stats.sampleCount)
        json.put("quality", stats.getQuality().name)
        return json
    }

    private fun gpsQualityToJson(stats: GpsQualityStats): JSONObject {
        val json = JSONObject()
        json.put("avgAccuracyM", stats.avgAccuracyM)
        json.put("minAccuracyM", stats.minAccuracyM)
        json.put("maxAccuracyM", stats.maxAccuracyM)
        json.put("avgSpeedJitterMs", stats.avgSpeedJitterMs)
        json.put("maxSpeedJumpMs", stats.maxSpeedJumpMs)
        json.put("positionJitterM", stats.positionJitterM)
        json.put("gpsLosses", stats.gpsLosses)
        json.put("sampleCount", stats.sampleCount)
        json.put("quality", stats.getQuality().name)
        return json
    }
}

// ===== Result Data Classes =====

data class GyroNoiseStats(
    val meanX: Float = 0f,
    val meanY: Float = 0f,
    val meanZ: Float = 0f,
    val stddevX: Float = 0f,
    val stddevY: Float = 0f,
    val stddevZ: Float = 0f,
    val peakNoiseX: Float = 0f,
    val peakNoiseY: Float = 0f,
    val peakNoiseZ: Float = 0f,
    val zeroCrossingRate: Float = 0f,
    val sampleCount: Int = 0
) {
    fun getNoiseLevel(): NoiseLevel {
        return when {
            stddevX < 0.01f -> NoiseLevel.EXCELLENT
            stddevX < 0.03f -> NoiseLevel.GOOD
            stddevX < 0.05f -> NoiseLevel.ACCEPTABLE
            stddevX < 0.1f  -> NoiseLevel.POOR
            else -> NoiseLevel.UNUSABLE
        }
    }
}

enum class NoiseLevel(val description: String) {
    EXCELLENT("Gyro noise < 0.6 deg/s - ideal for lean angle calculation"),
    GOOD("Gyro noise < 1.7 deg/s - usable with filtering"),
    ACCEPTABLE("Gyro noise < 2.9 deg/s - requires aggressive filtering"),
    POOR("Gyro noise < 5.7 deg/s - lean angle may be unreliable"),
    UNUSABLE("Gyro noise > 5.7 deg/s - device not suitable for lean sensing")
}

data class LeanConsistencyStats(
    val meanLeanDeg: Float = 0f,
    val stddevLeanDeg: Float = 0f,
    val avgGyroRotationDiscrepancyDeg: Float = 0f,
    val avgAccelRotationDiscrepancyDeg: Float = 0f,
    val maxGyroRotationDiscrepancyDeg: Float = 0f,
    val maxAccelRotationDiscrepancyDeg: Float = 0f,
    val driftRateDegPerMin: Float = 0f,
    val sampleCount: Int = 0
) {
    fun getQuality(): LeanQuality {
        return when {
            driftRateDegPerMin < 0.5f && avgGyroRotationDiscrepancyDeg < 2f -> LeanQuality.EXCELLENT
            driftRateDegPerMin < 1.0f && avgGyroRotationDiscrepancyDeg < 5f -> LeanQuality.GOOD
            driftRateDegPerMin < 3.0f && avgGyroRotationDiscrepancyDeg < 10f -> LeanQuality.ACCEPTABLE
            driftRateDegPerMin < 5.0f -> LeanQuality.POOR
            else -> LeanQuality.UNUSABLE
        }
    }
}

enum class LeanQuality(val description: String) {
    EXCELLENT("Drift < 0.5 deg/min, gyro-rotation discrepancy < 2 deg"),
    GOOD("Drift < 1 deg/min, gyro-rotation discrepancy < 5 deg"),
    ACCEPTABLE("Drift < 3 deg/min, gyro-rotation discrepancy < 10 deg"),
    POOR("Drift < 5 deg/min - requires frequent recalibration"),
    UNUSABLE("Drift > 5 deg/min - lean angle not reliable")
}

data class GpsQualityStats(
    val avgAccuracyM: Float = 0f,
    val minAccuracyM: Float = 0f,
    val maxAccuracyM: Float = 0f,
    val avgSpeedJitterMs: Float = 0f,
    val maxSpeedJumpMs: Float = 0f,
    val positionJitterM: Float = 0f,
    val gpsLosses: Int = 0,
    val sampleCount: Int = 0
) {
    fun getQuality(): GpsQuality {
        return when {
            avgAccuracyM < 5f && gpsLosses == 0 -> GpsQuality.EXCELLENT
            avgAccuracyM < 10f && gpsLosses <= 1 -> GpsQuality.GOOD
            avgAccuracyM < 20f && gpsLosses <= 3 -> GpsQuality.ACCEPTABLE
            avgAccuracyM < 50f -> GpsQuality.POOR
            else -> GpsQuality.UNUSABLE
        }
    }
}

enum class GpsQuality(val description: String) {
    EXCELLENT("GPS accuracy < 5m, no signal losses"),
    GOOD("GPS accuracy < 10m, minimal losses"),
    ACCEPTABLE("GPS accuracy < 20m, occasional losses"),
    POOR("GPS accuracy < 50m - speed-based data may be unreliable"),
    UNUSABLE("GPS accuracy > 50m - cannot trust location data")
}
