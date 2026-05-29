package net.osmand.plus.plugins.motorcyclesensors.calibration

import net.osmand.Location
import net.osmand.PlatformUtil
import net.osmand.plus.settings.backend.OsmandSettings
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Sensor Calibration Helper - Handles baseline calibration for motorcycle sensors.
 *
 * This solves the #1 real-world problem: every phone mount is different.
 * Phone mounting angle, vibration characteristics, and sensor biases vary
 * wildly between devices and mount positions.
 *
 * Calibration flow:
 * 1. User starts "Calibration Ride" from settings
 * 2. Ride at low speed (<30 km/h) on a straight, flat road for ~30 seconds
 * 3. System collects baseline data:
 *    - Zero lean angle reference (what "straight" looks like for this mount)
 *    - Gyro bias (zero-rate offset for each axis)
 *    - Accelerometer bias (gravity direction in this mount orientation)
 *    - Mount orientation (portrait/landscape and tilt)
 * 4. Calibration is saved and applied to all subsequent lean angle / G-force calculations
 *
 * Key insight: The complementary filter in LeanAngleCalculator uses COMPLEMENTARY_ALPHA = 0.02,
 * which trusts gyro 98% and accelerometer 2%. But if the gyro has a bias of 0.02 rad/s,
 * that's 1.15 deg/s drift, which accumulates to 69 deg/min. Calibration eliminates this.
 *
 * Mount orientation detection:
 * - Portrait (screen vertical): Most common, phone axis Z = up
 * - Landscape left/right: Phone rotated 90 deg
 * - The mount tilt angle (how far from vertical the phone is tilted) is critical
 *   for correct lean angle calculation
 */
class SensorCalibrationHelper(private val settings: OsmandSettings) {

    companion object {
        private val LOG = PlatformUtil.getLog(SensorCalibrationHelper::class.java)

        // Calibration collection parameters
        private const val CALIBRATION_DURATION_MS = 30_000L     // 30 seconds of data
        private const val CALIBRATION_MIN_SAMPLES = 300         // ~10 seconds at 50Hz
        private const val CALIBRATION_MAX_SPEED_MS = 8.33f      // ~30 km/h
        private const val CALIBRATION_MIN_SPEED_MS = 1.0f       // Must be moving

        // Quality thresholds
        private const val MAX_SPEED_VARIANCE_DURING_CALIB = 5.0f // m/s - must be steady speed

        // Preference keys
        const val PREF_CALIBRATION_LEAN_BIAS = "motorcycle_calib_lean_bias"
        const val PREF_CALIBRATION_GYRO_BIAS_X = "motorcycle_calib_gyro_bias_x"
        const val PREF_CALIBRATION_GYRO_BIAS_Y = "motorcycle_calib_gyro_bias_y"
        const val PREF_CALIBRATION_GYRO_BIAS_Z = "motorcycle_calib_gyro_bias_z"
        const val PREF_CALIBRATION_MOUNT_TILT = "motorcycle_calib_mount_tilt"
        const val PREF_CALIBRATION_MOUNT_ORIENTATION = "motorcycle_calib_mount_orientation"
        const val PREF_CALIBRATION_TIMESTAMP = "motorcycle_calib_timestamp"
        const val PREF_CALIBRATION_QUALITY = "motorcycle_calib_quality"
    }

    // Calibration state
    enum class CalibrationState {
        IDLE,           // Not calibrating
        COLLECTING,     // Collecting baseline data
        COMPLETE,       // Calibration data collected and saved
        FAILED          // Calibration failed (bad data)
    }

    private var calibrationState = CalibrationState.IDLE
    private var calibrationStartTimeMs = 0L

    // Collected calibration samples
    private val calibrationAccelSamples = ConcurrentLinkedQueue<FloatArray>()
    private val calibrationGyroSamples = ConcurrentLinkedQueue<FloatArray>()
    private val calibrationRollSamples = ConcurrentLinkedQueue<Float>()
    private val calibrationSpeedSamples = ConcurrentLinkedQueue<Float>()

    // Computed calibration values (persisted)
    data class CalibrationData(
        val leanBiasDeg: Float = 0f,
        val gyroBiasX: Float = 0f,
        val gyroBiasY: Float = 0f,
        val gyroBiasZ: Float = 0f,
        val mountTiltDeg: Float = 0f,
        val mountOrientation: MountOrientation = MountOrientation.PORTRAIT,
        val quality: CalibrationQuality = CalibrationQuality.UNKNOWN,
        val timestampMs: Long = 0L
    )

    enum class MountOrientation {
        PORTRAIT,
        LANDSCAPE_LEFT,
        LANDSCAPE_RIGHT
    }

    enum class CalibrationQuality(val description: String) {
        UNKNOWN("No calibration data"),
        EXCELLENT("Gyro bias < 0.01 rad/s, lean bias < 1 deg"),
        GOOD("Gyro bias < 0.03 rad/s, lean bias < 3 deg"),
        ACCEPTABLE("Gyro bias < 0.05 rad/s, lean bias < 5 deg"),
        POOR("Calibration data is noisy, recalibrate recommended"),
        FAILED("Calibration failed - insufficient or inconsistent data")
    }

    private var currentCalibration = CalibrationData()

    // ===== Public API =====

    fun startCalibration(): CalibrationResult {
        if (calibrationState == CalibrationState.COLLECTING) {
            return CalibrationResult(false, "Calibration already in progress")
        }

        calibrationState = CalibrationState.COLLECTING
        calibrationStartTimeMs = System.currentTimeMillis()
        calibrationAccelSamples.clear()
        calibrationGyroSamples.clear()
        calibrationRollSamples.clear()
        calibrationSpeedSamples.clear()

        LOG.info("SensorCalibration: Started - ride straight at steady low speed")
        return CalibrationResult(true, "Calibration started. Ride straight at steady speed for 30 seconds.")
    }

    fun updateSensorData(
        accelX: Float, accelY: Float, accelZ: Float,
        gyroX: Float, gyroY: Float, gyroZ: Float,
        roll: Float, pitch: Float,
        timestampNs: Long
    ) {
        if (calibrationState != CalibrationState.COLLECTING) return

        val elapsed = System.currentTimeMillis() - calibrationStartTimeMs
        if (elapsed > CALIBRATION_DURATION_MS + 5000L) {
            finishCalibration()
            return
        }

        calibrationAccelSamples.add(floatArrayOf(accelX, accelY, accelZ))
        calibrationGyroSamples.add(floatArrayOf(gyroX, gyroY, gyroZ))
        calibrationRollSamples.add(roll)

        if (elapsed >= CALIBRATION_DURATION_MS &&
            calibrationGyroSamples.size >= CALIBRATION_MIN_SAMPLES) {
            finishCalibration()
        }
    }

    fun updateGpsSpeed(speedMs: Float) {
        if (calibrationState != CalibrationState.COLLECTING) return
        calibrationSpeedSamples.add(speedMs)
    }

    fun finishCalibration(): CalibrationResult {
        if (calibrationState != CalibrationState.COLLECTING) {
            return CalibrationResult(false, "Not currently calibrating")
        }

        if (calibrationGyroSamples.size < CALIBRATION_MIN_SAMPLES) {
            calibrationState = CalibrationState.FAILED
            LOG.warn("SensorCalibration: Failed - only ${calibrationGyroSamples.size} samples")
            return CalibrationResult(false,
                "Insufficient data: ${calibrationGyroSamples.size}/${CALIBRATION_MIN_SAMPLES} samples. Ride longer.")
        }

        val speedValidation = validateSpeedConditions()
        if (!speedValidation.valid) {
            calibrationState = CalibrationState.FAILED
            return CalibrationResult(false, speedValidation.reason)
        }

        val gyroBias = computeGyroBias()
        val leanBias = computeLeanBias()
        val mountOrientation = detectMountOrientation()
        val mountTilt = computeMountTilt()
        val quality = assessCalibrationQuality(gyroBias, leanBias)

        currentCalibration = CalibrationData(
            leanBiasDeg = leanBias,
            gyroBiasX = gyroBias[0],
            gyroBiasY = gyroBias[1],
            gyroBiasZ = gyroBias[2],
            mountTiltDeg = mountTilt,
            mountOrientation = mountOrientation,
            quality = quality,
            timestampMs = System.currentTimeMillis()
        )

        saveCalibrationToPreferences()
        calibrationState = CalibrationState.COMPLETE

        LOG.info("SensorCalibration: Complete - leanBias=${"%.2f".format(leanBias)}deg, " +
            "gyroBias=[${"%.4f".format(gyroBias[0])}, ${"%.4f".format(gyroBias[1])}, ${"%.4f".format(gyroBias[2])}] rad/s, " +
            "mount=${mountOrientation.name} tilt=${"%.1f".format(mountTilt)}deg, quality=${quality.name}")

        return CalibrationResult(true,
            "Calibration complete. Lean bias: ${"%.1f".format(leanBias)}deg, " +
            "Quality: ${quality.name}", currentCalibration)
    }

    fun cancelCalibration() {
        calibrationState = CalibrationState.IDLE
        calibrationAccelSamples.clear()
        calibrationGyroSamples.clear()
        calibrationRollSamples.clear()
        calibrationSpeedSamples.clear()
        LOG.info("SensorCalibration: Cancelled")
    }

    fun getState(): CalibrationState = calibrationState

    fun getCalibration(): CalibrationData = currentCalibration

    fun hasCalibration(): Boolean {
        return currentCalibration.quality != CalibrationQuality.UNKNOWN &&
               currentCalibration.quality != CalibrationQuality.FAILED
    }

    /**
     * Apply gyro bias correction to raw gyro values.
     * Returns bias-corrected gyro values.
     */
    fun correctGyroBias(gyroX: Float, gyroY: Float, gyroZ: Float): FloatArray {
        if (!hasCalibration()) return floatArrayOf(gyroX, gyroY, gyroZ)
        return floatArrayOf(
            gyroX - currentCalibration.gyroBiasX,
            gyroY - currentCalibration.gyroBiasY,
            gyroZ - currentCalibration.gyroBiasZ
        )
    }

    /**
     * Apply lean angle bias correction.
     * Returns the corrected lean angle.
     */
    fun correctLeanAngle(leanAngleDeg: Float): Float {
        if (!hasCalibration()) return leanAngleDeg
        return leanAngleDeg - currentCalibration.leanBiasDeg
    }

    fun getMountOrientation(): MountOrientation = currentCalibration.mountOrientation

    fun getCalibrationProgress(): Float {
        if (calibrationState != CalibrationState.COLLECTING) return 0f
        val elapsed = System.currentTimeMillis() - calibrationStartTimeMs
        return (elapsed.toFloat() / CALIBRATION_DURATION_MS).coerceIn(0f, 1f)
    }

    // ===== Calibration Computation =====

    private fun computeGyroBias(): FloatArray {
        val samples = calibrationGyroSamples.toList()
        val n = samples.size
        if (n == 0) return FloatArray(3)

        var sumX = 0f; var sumY = 0f; var sumZ = 0f
        for (s in samples) { sumX += s[0]; sumY += s[1]; sumZ += s[2] }

        return floatArrayOf(sumX / n, sumY / n, sumZ / n)
    }

    private fun computeLeanBias(): Float {
        val samples = calibrationRollSamples.toList()
        val n = samples.size
        if (n == 0) return 0f

        var sumRoll = 0.0
        for (r in samples) sumRoll += r
        val avgRollRad = sumRoll / n

        return Math.toDegrees(avgRollRad.toDouble()).toFloat()
    }

    private fun detectMountOrientation(): MountOrientation {
        val samples = calibrationAccelSamples.toList()
        val n = samples.size
        if (n == 0) return MountOrientation.PORTRAIT

        var sumX = 0f; var sumY = 0f; var sumZ = 0f
        for (s in samples) { sumX += s[0]; sumY += s[1]; sumZ += s[2] }
        val avgX = sumX / n; val avgY = sumY / n; val avgZ = sumZ / n

        val absX = abs(avgX); val absY = abs(avgY); val absZ = abs(avgZ)

        return when {
            absZ > absX && absZ > absY -> MountOrientation.PORTRAIT
            avgX < 0 && absX > absZ -> MountOrientation.LANDSCAPE_LEFT
            avgX > 0 && absX > absZ -> MountOrientation.LANDSCAPE_RIGHT
            else -> MountOrientation.PORTRAIT
        }
    }

    private fun computeMountTilt(): Float {
        val samples = calibrationAccelSamples.toList()
        val n = samples.size
        if (n == 0) return 0f

        var sumY = 0f; var sumZ = 0f
        for (s in samples) { sumY += s[1]; sumZ += s[2] }
        val avgY = sumY / n; val avgZ = sumZ / n

        return Math.toDegrees(atan2(avgY.toDouble(), (-avgZ).toDouble())).toFloat()
    }

    private fun validateSpeedConditions(): SpeedValidation {
        val speeds = calibrationSpeedSamples.toList()
        if (speeds.isEmpty()) {
            return SpeedValidation(false, "No GPS speed data - ensure GPS is active")
        }

        val avgSpeed = speeds.average().toFloat()
        if (avgSpeed > CALIBRATION_MAX_SPEED_MS) {
            return SpeedValidation(false,
                "Speed too high (${"%.0f".format(avgSpeed * 3.6f)} km/h). Ride below 30 km/h.")
        }
        if (avgSpeed < CALIBRATION_MIN_SPEED_MS) {
            return SpeedValidation(false,
                "Moving too slowly (${"%.0f".format(avgSpeed * 3.6f)} km/h). Ride at steady speed.")
        }

        var speedVar = 0f
        for (s in speeds) {
            speedVar += (s - avgSpeed) * (s - avgSpeed)
        }
        val speedStddev = sqrt(speedVar / speeds.size)
        if (speedStddev > MAX_SPEED_VARIANCE_DURING_CALIB) {
            return SpeedValidation(false,
                "Speed too variable (stddev ${"%.1f".format(speedStddev)} m/s). Ride at steady speed.")
        }

        return SpeedValidation(true, "")
    }

    private fun assessCalibrationQuality(
        gyroBias: FloatArray,
        leanBiasDeg: Float
    ): CalibrationQuality {
        val gyroBiasMagnitude = sqrt(
            gyroBias[0] * gyroBias[0] +
            gyroBias[1] * gyroBias[1] +
            gyroBias[2] * gyroBias[2]
        )

        val leanBiasMagnitude = abs(leanBiasDeg)

        return when {
            gyroBiasMagnitude < 0.01f && leanBiasMagnitude < 1f ->
                CalibrationQuality.EXCELLENT
            gyroBiasMagnitude < 0.03f && leanBiasMagnitude < 3f ->
                CalibrationQuality.GOOD
            gyroBiasMagnitude < 0.05f && leanBiasMagnitude < 5f ->
                CalibrationQuality.ACCEPTABLE
            gyroBiasMagnitude < 0.1f && leanBiasMagnitude < 10f ->
                CalibrationQuality.POOR
            else ->
                CalibrationQuality.FAILED
        }
    }

    // ===== Persistence =====

    private fun saveCalibrationToPreferences() {
        try {
            settings.registerFloatPreference(PREF_CALIBRATION_LEAN_BIAS, currentCalibration.leanBiasDeg)
                .makeGlobal().set(currentCalibration.leanBiasDeg)
            settings.registerFloatPreference(PREF_CALIBRATION_GYRO_BIAS_X, currentCalibration.gyroBiasX)
                .makeGlobal().set(currentCalibration.gyroBiasX)
            settings.registerFloatPreference(PREF_CALIBRATION_GYRO_BIAS_Y, currentCalibration.gyroBiasY)
                .makeGlobal().set(currentCalibration.gyroBiasY)
            settings.registerFloatPreference(PREF_CALIBRATION_GYRO_BIAS_Z, currentCalibration.gyroBiasZ)
                .makeGlobal().set(currentCalibration.gyroBiasZ)
            settings.registerFloatPreference(PREF_CALIBRATION_MOUNT_TILT, currentCalibration.mountTiltDeg)
                .makeGlobal().set(currentCalibration.mountTiltDeg)
            settings.registerIntPreference(PREF_CALIBRATION_MOUNT_ORIENTATION, currentCalibration.mountOrientation.ordinal)
                .makeGlobal().set(currentCalibration.mountOrientation.ordinal)
            settings.registerLongPreference(PREF_CALIBRATION_TIMESTAMP, currentCalibration.timestampMs)
                .makeGlobal().set(currentCalibration.timestampMs)
            settings.registerIntPreference(PREF_CALIBRATION_QUALITY, currentCalibration.quality.ordinal)
                .makeGlobal().set(currentCalibration.quality.ordinal)
        } catch (e: Exception) {
            LOG.error("Failed to save calibration", e)
        }
    }

    fun loadCalibrationFromPreferences() {
        try {
            val leanBias = settings.registerFloatPreference(PREF_CALIBRATION_LEAN_BIAS, 0f).makeGlobal().get()
            val gyroX = settings.registerFloatPreference(PREF_CALIBRATION_GYRO_BIAS_X, 0f).makeGlobal().get()
            val gyroY = settings.registerFloatPreference(PREF_CALIBRATION_GYRO_BIAS_Y, 0f).makeGlobal().get()
            val gyroZ = settings.registerFloatPreference(PREF_CALIBRATION_GYRO_BIAS_Z, 0f).makeGlobal().get()
            val mountTilt = settings.registerFloatPreference(PREF_CALIBRATION_MOUNT_TILT, 0f).makeGlobal().get()
            val orientationOrdinal = settings.registerIntPreference(PREF_CALIBRATION_MOUNT_ORIENTATION, 0).makeGlobal().get()
            val timestamp = settings.registerLongPreference(PREF_CALIBRATION_TIMESTAMP, 0L).makeGlobal().get()
            val qualityOrdinal = settings.registerIntPreference(PREF_CALIBRATION_QUALITY, 0).makeGlobal().get()

            val orientation = MountOrientation.entries.getOrElse(orientationOrdinal) { MountOrientation.PORTRAIT }
            val quality = CalibrationQuality.entries.getOrElse(qualityOrdinal) { CalibrationQuality.UNKNOWN }

            currentCalibration = CalibrationData(
                leanBiasDeg = leanBias,
                gyroBiasX = gyroX,
                gyroBiasY = gyroY,
                gyroBiasZ = gyroZ,
                mountTiltDeg = mountTilt,
                mountOrientation = orientation,
                quality = quality,
                timestampMs = timestamp
            )

            LOG.info("SensorCalibration: Loaded - quality=$quality, leanBias=${"%.2f".format(leanBias)}deg")
        } catch (e: Exception) {
            LOG.warn("SensorCalibration: No saved calibration found")
            currentCalibration = CalibrationData()
        }
    }
}

data class CalibrationResult(
    val success: Boolean,
    val message: String,
    val calibrationData: SensorCalibrationHelper.CalibrationData? = null
)

private data class SpeedValidation(
    val valid: Boolean,
    val reason: String
)
