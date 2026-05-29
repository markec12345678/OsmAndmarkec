package net.osmand.plus.plugins.motorcyclesensors.sensors

import net.osmand.PlatformUtil
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Calculates the lean angle of a motorcycle using sensor fusion.
 *
 * Lean angle (roll) is the angle the motorcycle makes with the vertical plane
 * when cornering. This is the most sought-after feature for motorcyclists.
 *
 * Approach:
 * 1. Primary: Use rotation vector / game rotation vector sensor for direct roll angle
 * 2. Secondary: Fallback using accelerometer when phone is mounted in fixed position
 * 3. Fusion: Combine both with complementary filter for best results
 *
 * The phone must be mounted on the motorcycle in a known orientation.
 * We assume the phone is mounted vertically (screen facing rider) which is
 * the most common mounting position.
 */
class LeanAngleCalculator {

    companion object {
        private val LOG = PlatformUtil.getLog(LeanAngleCalculator::class.java)

        // Maximum realistic lean angle for motorcycles (in degrees)
        // Sport bikes: ~55°, Cruisers: ~35°, Scooters: ~30°
        private const val MAX_LEAN_ANGLE = 60f

        // Complementary filter coefficient
        // Higher = trust accelerometer more, Lower = trust gyroscope more
        private const val COMPLEMENTARY_ALPHA = 0.02f

        // Noise threshold - ignore lean angle changes smaller than this
        private const val NOISE_THRESHOLD_DEG = 0.5f

        // Smoothing window for output
        private const val SMOOTHING_WINDOW_SIZE = 5
    }

    // Current lean angle in degrees (positive = right lean, negative = left lean)
    private var currentLeanAngleDeg = 0f

    // Gyroscope-integrated lean angle (for complementary filter)
    private var gyroIntegratedLeanDeg = 0f
    private var lastTimestampNs: Long = 0

    // Smoothing buffer
    private val smoothingBuffer = FloatArray(SMOOTHING_WINDOW_SIZE)
    private var smoothingIndex = 0
    private var smoothingCount = 0

    /**
     * Calculate lean angle from sensor data.
     *
     * @param accelX Filtered accelerometer X (lateral)
     * @param accelY Filtered accelerometer Y (longitudinal)
     * @param accelZ Filtered accelerometer Z (vertical)
     * @param gyroX Filtered gyroscope X (roll rate in rad/s)
     * @param roll Roll angle from rotation vector (radians)
     * @param timestampNs Sensor timestamp in nanoseconds
     * @return Lean angle in degrees (positive = right, negative = left)
     */
    fun calculateLeanAngle(
        accelX: Float, accelY: Float, accelZ: Float,
        gyroX: Float,
        roll: Float,
        timestampNs: Long
    ): Float {
        // Method 1: Direct roll from rotation vector (most accurate when available)
        val rollFromRotationVector = Math.toDegrees(roll.toDouble()).toFloat()

        // Method 2: Accelerometer-based lean angle calculation
        // When motorcycle leans, gravity vector tilts relative to phone axes
        // Lean angle = atan2(lateral_accel, vertical_accel)
        val lateralAccel = accelX // Lateral acceleration (left-right)
        val verticalAccel = sqrt((accelY * accelY + accelZ * accelZ).toDouble()).toFloat()
        val rollFromAccel = Math.toDegrees(
            atan2(lateralAccel.toDouble(), verticalAccel.toDouble())
        ).toFloat()

        // Method 3: Gyroscope integration (short-term accurate, drifts over time)
        val gyroLeanDeg = integrateGyroscope(gyroX, timestampNs)

        // Sensor fusion: Complementary filter
        // Use rotation vector as primary source (most accurate for static lean)
        // Augment with gyroscope for fast dynamic changes
        val fusedLeanAngle: Float = if (Math.abs(rollFromRotationVector) > 0.1) {
            // Rotation vector available - use complementary filter with gyro
            COMPLEMENTARY_ALPHA * rollFromAccel + (1 - COMPLEMENTARY_ALPHA) * gyroLeanDeg
        } else {
            // No rotation vector - use accelerometer as primary
            COMPLEMENTARY_ALPHA * rollFromAccel + (1 - COMPLEMENTARY_ALPHA) * gyroLeanDeg
        }

        // Clamp to realistic range
        val clampedLean = fusedLeanAngle.coerceIn(-MAX_LEAN_ANGLE, MAX_LEAN_ANGLE)

        // Apply noise threshold
        val denoisedLean = if (Math.abs(clampedLean - currentLeanAngleDeg) < NOISE_THRESHOLD_DEG) {
            currentLeanAngleDeg
        } else {
            clampedLean
        }

        // Smooth the output
        currentLeanAngleDeg = smoothValue(denoisedLean)

        return currentLeanAngleDeg
    }

    /**
     * Integrate gyroscope roll rate to get lean angle.
     * This is accurate for short-term but drifts over time.
     * Must be combined with accelerometer/rotation vector for correction.
     */
    private fun integrateGyroscope(gyroRollRateRadPerSec: Float, timestampNs: Long): Float {
        if (lastTimestampNs == 0L) {
            lastTimestampNs = timestampNs
            return gyroIntegratedLeanDeg
        }

        val dtSec = (timestampNs - lastTimestampNs) / 1_000_000_000f
        lastTimestampNs = timestampNs

        // Only integrate if dt is reasonable (prevent large jumps)
        if (dtSec in 0.001f..0.1f) {
            val deltaDeg = Math.toDegrees((gyroRollRateRadPerSec * dtSec).toDouble()).toFloat()
            gyroIntegratedLeanDeg += deltaDeg

            // Prevent drift accumulation - clamp
            gyroIntegratedLeanDeg = gyroIntegratedLeanDeg.coerceIn(
                -MAX_LEAN_ANGLE * 1.5f, MAX_LEAN_ANGLE * 1.5f
            )
        }

        return gyroIntegratedLeanDeg
    }

    /**
     * Apply moving average smoothing to reduce jitter
     */
    private fun smoothValue(value: Float): Float {
        smoothingBuffer[smoothingIndex] = value
        smoothingIndex = (smoothingIndex + 1) % SMOOTHING_WINDOW_SIZE
        if (smoothingCount < SMOOTHING_WINDOW_SIZE) smoothingCount++

        var sum = 0f
        for (i in 0 until smoothingCount) {
            sum += smoothingBuffer[i]
        }
        return sum / smoothingCount
    }

    /**
     * Get the current lean angle without recalculating
     */
    fun getCurrentLeanAngle(): Float = currentLeanAngleDeg

    /**
     * Get the absolute lean angle (always positive)
     */
    fun getAbsoluteLeanAngle(): Float = Math.abs(currentLeanAngleDeg)

    /**
     * Get lean angle formatted as string with direction indicator
     * e.g., "23.5° R" or "18.2° L"
     */
    fun getFormattedLeanAngle(): String {
        val abs = Math.abs(currentLeanAngleDeg)
        val direction = if (currentLeanAngleDeg >= 0) "R" else "L"
        return if (abs < NOISE_THRESHOLD_DEG) {
            "0.0°"
        } else {
            String.format("%.1f° %s", abs, direction)
        }
    }

    /**
     * Reset the calculator (e.g., when phone is remounted)
     */
    fun reset() {
        currentLeanAngleDeg = 0f
        gyroIntegratedLeanDeg = 0f
        lastTimestampNs = 0
        smoothingIndex = 0
        smoothingCount = 0
        smoothingBuffer.fill(0f)
    }
}
