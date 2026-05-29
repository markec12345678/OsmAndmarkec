package net.osmand.plus.plugins.motorcyclesensors.safety

import android.location.Location
import net.osmand.PlatformUtil
import net.osmand.plus.plugins.motorcyclesensors.sensors.GForceData
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Crash Detection Helper - Detects potential motorcycle crashes using sensor data.
 *
 * Crash signature:
 * 1. Sudden high G-force (>2.5G impact) OR rapid rotation change
 * 2. Within 3 seconds, speed drops to near zero (or no movement)
 * 3. Combined: high G + rapid rotation + speed drop = CRASH
 *
 * This class ONLY detects crashes. The actual emergency response
 * (SMS, calls) is handled by the plugin and requires user permissions.
 *
 * Algorithm details:
 * - Monitors G-force for sudden spikes above CRASH_G_THRESHOLD (2.5G)
 * - Monitors gyroscope for rapid rotation (>300 deg/s = crash rotation)
 * - Monitors speed for sudden deceleration to zero
 * - Requires at least 2 of 3 signals within CRASH_DETECTION_WINDOW_MS
 * - Has a cooldown period to prevent false positives from speed bumps
 * - Minimum speed threshold prevents false triggers while stationary
 */
class CrashDetectionHelper {

    companion object {
        private val LOG = PlatformUtil.getLog(CrashDetectionHelper::class.java)

        // Crash detection thresholds
        const val CRASH_G_THRESHOLD = 2.5f          // G-force threshold for impact
        const val CRASH_ROTATION_RATE = 5.24f        // rad/s (~300 deg/s) - crash rotation
        const val CRASH_SPEED_DROP_MS = 2.0f         // Speed drop to below this (m/s)
        const val MIN_SPEED_FOR_CRASH_MS = 5.0f      // Minimum speed before crash (m/s) = ~18 km/h
        const val CRASH_DETECTION_WINDOW_MS = 3000L   // 3 second window for crash signals
        const val CRASH_COOLDOWN_MS = 30000L          // 30 second cooldown after detection

        // Signal flags
        private const val SIGNAL_HIGH_G = 0x01
        private const val SIGNAL_HIGH_ROTATION = 0x02
        private const val SIGNAL_SPEED_DROP = 0x04
        private const val CRASH_SIGNAL_MASK = SIGNAL_HIGH_G or SIGNAL_HIGH_ROTATION or SIGNAL_SPEED_DROP
    }

    // Crash detection state
    private var crashDetected = false
    private var lastCrashDetectionTimeMs: Long = 0
    private var crashSignals: Int = 0

    // Sensitivity level (1=low, 2=medium, 3=high)
    private var sensitivityLevel: Int = 2

    // Sensor tracking
    private var highGDetectedTimeMs: Long = 0
    private var highRotationDetectedTimeMs: Long = 0
    private var speedBeforeEventMs: Float = 0f

    // Last known location for emergency
    private var lastKnownLocation: Location? = null
    private var lastLocationSpeedMs: Float = 0f
    private var lastLocationTimeMs: Long = 0

    // Sensor data tracking
    private var lastGForceTotal: Float = 0f
    private var lastGyroRotationRate: Float = 0f

    // Callback interface
    interface CrashDetectionListener {
        fun onCrashDetected(location: Location?, gForceAtImpact: Float, rotationRateAtImpact: Float)
        fun onPotentialCrash(gForce: Float, reason: String)
    }

    private val listeners = mutableListOf<CrashDetectionListener>()

    fun addListener(listener: CrashDetectionListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: CrashDetectionListener) {
        listeners.remove(listener)
    }

    /**
     * Set sensitivity level for crash detection.
     * @param level 1=Low (fewer false positives, higher thresholds),
     *              2=Medium (default),
     *              3=High (more responsive, lower thresholds)
     */
    fun setSensitivity(level: Int) {
        sensitivityLevel = level.coerceIn(1, 3)
    }

    /**
     * Get the effective G-force threshold based on sensitivity.
     * Low: 3.5G, Medium: 2.5G, High: 1.8G
     */
    private fun getEffectiveGThreshold(): Float {
        return when (sensitivityLevel) {
            1 -> CRASH_G_THRESHOLD * 1.4f     // 3.5G - fewer false positives
            3 -> CRASH_G_THRESHOLD * 0.72f    // 1.8G - very responsive
            else -> CRASH_G_THRESHOLD          // 2.5G - default
        }
    }

    /**
     * Get the effective rotation rate threshold based on sensitivity.
     * Low: 7.33 rad/s (~420 deg/s), Medium: 5.24 rad/s (~300 deg/s), High: 3.49 rad/s (~200 deg/s)
     */
    private fun getEffectiveRotationThreshold(): Float {
        return when (sensitivityLevel) {
            1 -> CRASH_ROTATION_RATE * 1.4f   // 7.33 rad/s
            3 -> CRASH_ROTATION_RATE * 0.667f  // 3.49 rad/s
            else -> CRASH_ROTATION_RATE         // 5.24 rad/s
        }
    }

    /**
     * Update with new sensor data from accelerometer and gyroscope.
     *
     * @param accelX Accelerometer X (lateral)
     * @param accelY Accelerometer Y (longitudinal)
     * @param accelZ Accelerometer Z (vertical)
     * @param gyroX Gyroscope X (roll rate rad/s)
     * @param gyroY Gyroscope Y (pitch rate rad/s)
     * @param gyroZ Gyroscope Z (yaw rate rad/s)
     * @param timestamp Sensor timestamp in nanoseconds
     */
    fun updateSensorData(
        accelX: Float, accelY: Float, accelZ: Float,
        gyroX: Float, gyroY: Float, gyroZ: Float,
        timestamp: Long
    ) {
        if (crashDetected) return // Already detected, don't re-trigger

        val nowMs = System.currentTimeMillis()

        // Check cooldown
        if (nowMs - lastCrashDetectionTimeMs < CRASH_COOLDOWN_MS) return

        // Calculate total G-force (without gravity)
        val totalAccel = sqrt((accelX * accelX + accelY * accelY + accelZ * accelZ).toDouble()).toFloat()
        val totalG = totalAccel / 9.80665f
        lastGForceTotal = totalG

        // Calculate total rotation rate
        val rotationRate = sqrt((gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ).toDouble()).toFloat()
        lastGyroRotationRate = rotationRate

        // Check for high G-force signal
        if (totalG >= getEffectiveGThreshold() && speedBeforeEventMs >= MIN_SPEED_FOR_CRASH_MS) {
            highGDetectedTimeMs = nowMs
            crashSignals = crashSignals or SIGNAL_HIGH_G
            LOG.warn("CrashDetection: High G detected: ${"%.2f".format(totalG)}G (threshold: ${"%.2f".format(getEffectiveGThreshold())}G) at speed ${"%.1f".format(speedBeforeEventMs)}m/s")
        }

        // Check for high rotation rate signal
        if (rotationRate >= getEffectiveRotationThreshold() && speedBeforeEventMs >= MIN_SPEED_FOR_CRASH_MS) {
            highRotationDetectedTimeMs = nowMs
            crashSignals = crashSignals or SIGNAL_HIGH_ROTATION
            LOG.warn("CrashDetection: High rotation detected: ${"%.2f".format(rotationRate)}rad/s (threshold: ${"%.2f".format(getEffectiveRotationThreshold())}rad/s)")
        }

        // Evaluate crash signals
        evaluateCrashSignals(nowMs, totalG, rotationRate)
    }

    /**
     * Update with new GPS location data.
     */
    fun updateLocation(location: Location) {
        val nowMs = System.currentTimeMillis()
        val currentSpeedMs = location.speed

        // Check for speed drop signal
        if (lastLocationSpeedMs >= MIN_SPEED_FOR_CRASH_MS &&
            currentSpeedMs < CRASH_SPEED_DROP_MS &&
            nowMs - highGDetectedTimeMs < CRASH_DETECTION_WINDOW_MS) {
            crashSignals = crashSignals or SIGNAL_SPEED_DROP
            LOG.warn("CrashDetection: Speed drop detected: ${"%.1f".format(lastLocationSpeedMs)} -> ${"%.1f".format(currentSpeedMs)}m/s")
        }

        speedBeforeEventMs = currentSpeedMs
        lastLocationSpeedMs = currentSpeedMs
        lastLocationTimeMs = nowMs
        lastKnownLocation = location

        // Evaluate
        evaluateCrashSignals(nowMs, lastGForceTotal, lastGyroRotationRate)
    }

    /**
     * Evaluate the crash signal combination and determine if a crash occurred.
     *
     * Requires at least 2 of 3 signals within the detection window:
     * - High G-force
     * - High rotation rate
     * - Speed drop to near zero
     */
    private fun evaluateCrashSignals(nowMs: Long, gForceAtImpact: Float, rotationRateAtImpact: Float) {
        if (crashDetected) return

        // Check if signals are within the detection window
        val highGRecent = (crashSignals and SIGNAL_HIGH_G) != 0 &&
            (nowMs - highGDetectedTimeMs) < CRASH_DETECTION_WINDOW_MS
        val highRotRecent = (crashSignals and SIGNAL_HIGH_ROTATION) != 0 &&
            (nowMs - highRotationDetectedTimeMs) < CRASH_DETECTION_WINDOW_MS
        val speedDropActive = (crashSignals and SIGNAL_SPEED_DROP) != 0

        // Count active signals
        var activeSignals = 0
        if (highGRecent) activeSignals++
        if (highRotRecent) activeSignals++
        if (speedDropActive) activeSignals++

        // Need at least 2 of 3 signals for crash confirmation
        if (activeSignals >= 2) {
            triggerCrashDetection(gForceAtImpact, rotationRateAtImpact)
        }

        // Single signal: potential crash notification (lower confidence)
        if (activeSignals == 1 && highGRecent && gForceAtImpact > getEffectiveGThreshold() * 1.5f) {
            // Very high G alone might be enough (e.g., head-on collision)
            for (listener in listeners) {
                listener.onPotentialCrash(gForceAtImpact, "Very high G-force: ${"%.1f".format(gForceAtImpact)}G")
            }
        }

        // Reset old signals outside detection window
        if (!highGRecent) crashSignals = crashSignals and SIGNAL_HIGH_G.inv()
        if (!highRotRecent) crashSignals = crashSignals and SIGNAL_HIGH_ROTATION.inv()
        if (!speedDropActive && (nowMs - highGDetectedTimeMs) > CRASH_DETECTION_WINDOW_MS) {
            crashSignals = crashSignals and SIGNAL_SPEED_DROP.inv()
        }
    }

    /**
     * Trigger crash detection - notify all listeners.
     */
    private fun triggerCrashDetection(gForceAtImpact: Float, rotationRateAtImpact: Float) {
        crashDetected = true
        lastCrashDetectionTimeMs = System.currentTimeMillis()

        LOG.error("CRASH DETECTED! G=${"%.2f".format(gForceAtImpact)}G, " +
            "rotation=${"%.2f".format(rotationRateAtImpact)}rad/s, " +
            "location=${lastKnownLocation?.latitude},${lastKnownLocation?.longitude}")

        for (listener in listeners) {
            listener.onCrashDetected(lastKnownLocation, gForceAtImpact, rotationRateAtImpact)
        }
    }

    /**
     * Check if a crash has been detected.
     */
    fun isCrashDetected(): Boolean = crashDetected

    /**
     * Get the last known GPS location (for emergency SMS).
     */
    fun getLastKnownLocation(): Location? = lastKnownLocation

    /**
     * Get the G-force at the moment of crash detection.
     */
    fun getImpactGForce(): Float = lastGForceTotal

    /**
     * Get the rotation rate at the moment of crash detection.
     */
    fun getImpactRotationRate(): Float = lastGyroRotationRate

    /**
     * Reset crash detection state (e.g., after user confirms they're okay).
     */
    fun reset() {
        crashDetected = false
        crashSignals = 0
        highGDetectedTimeMs = 0
        highRotationDetectedTimeMs = 0
        speedBeforeEventMs = 0f
        lastGForceTotal = 0f
        lastGyroRotationRate = 0f
        LOG.info("CrashDetection: Reset")
    }

    /**
     * Clear all state including location history.
     */
    fun destroy() {
        reset()
        listeners.clear()
        lastKnownLocation = null
        lastLocationSpeedMs = 0f
        lastLocationTimeMs = 0
    }
}
