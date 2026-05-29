package net.osmand.plus.plugins.motorcyclesensors.sensors

import net.osmand.PlatformUtil
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Calculates G-force (acceleration) experienced by the motorcycle and rider.
 *
 * Two types of G-force are calculated:
 * 1. Longitudinal G: Acceleration/deceleration (front-back) - from speed changes
 * 2. Lateral G: Cornering force (left-right) - from turning
 * 3. Total G: Combined magnitude of all forces
 *
 * For motorcyclists, lateral G is most interesting as it correlates with
 * cornering speed and lean angle. Longitudinal G shows braking/acceleration intensity.
 *
 * Physics:
 * - When stationary: accelerometer reads (0, 0, 9.81) = 1G from gravity
 * - When accelerating: additional forward force detected
 * - When cornering: centripetal acceleration detected laterally
 * - When braking: deceleration force detected
 *
 * We subtract gravity to get pure dynamic acceleration,
 * then convert to G-force units.
 */
class GForceCalculator {

    companion object {
        private val LOG = PlatformUtil.getLog(GForceCalculator::class.java)

        // Standard gravity in m/s^2
        private const val GRAVITY = 9.80665f

        // Maximum realistic G-force for motorcycles
        // Sport bikes: ~1.2-1.5G lateral, ~1.0G braking
        // Race bikes: ~2.0G max
        private const val MAX_G_FORCE = 3.0f

        // Smoothing window for output
        private const val SMOOTHING_WINDOW_SIZE = 5
    }

    // Current G-force values
    private var lateralG = 0f        // Left-right (positive = right)
    private var longitudinalG = 0f   // Front-back (positive = acceleration)
    private var verticalG = 0f       // Up-down
    private var totalG = 0f          // Total magnitude

    // Peak values for current ride
    private var peakLateralG = 0f
    private var peakLongitudinalG = 0f
    private var peakTotalG = 0f
    private var peakBrakingG = 0f    // Maximum deceleration (negative longitudinal)

    // Smoothing buffers
    private val lateralBuffer = FloatArray(SMOOTHING_WINDOW_SIZE)
    private val longBuffer = FloatArray(SMOOTHING_WINDOW_SIZE)
    private var bufferIndex = 0
    private var bufferCount = 0

    /**
     * Calculate G-force from accelerometer data with gravity compensation.
     *
     * @param accelX Raw accelerometer X (lateral - left/right)
     * @param accelY Raw accelerometer Y (longitudinal - front/back)
     * @param accelZ Raw accelerometer Z (vertical)
     * @param roll Current lean angle in radians (from LeanAngleCalculator)
     * @param pitch Current pitch angle in radians
     * @return GForceData with all computed values
     */
    fun calculateGForce(
        accelX: Float, accelY: Float, accelZ: Float,
        roll: Float, pitch: Float
    ): GForceData {
        // Step 1: Remove gravity component from accelerometer data
        // Gravity vector in world frame is (0, 0, GRAVITY)
        // We need to rotate it to the phone's frame using roll and pitch
        val gravityX = -GRAVITY * sin(roll.toDouble()).toFloat() * cos(pitch.toDouble()).toFloat()
        val gravityY = GRAVITY * sin(pitch.toDouble()).toFloat()
        val gravityZ = GRAVITY * cos(roll.toDouble()).toFloat() * cos(pitch.toDouble()).toFloat()

        // Dynamic acceleration = total acceleration - gravity
        val dynamicX = accelX - gravityX
        val dynamicY = accelY - gravityY
        val dynamicZ = accelZ - gravityZ

        // Step 2: Convert to G-force units
        val rawLateralG = dynamicX / GRAVITY
        val rawLongitudinalG = dynamicY / GRAVITY
        val rawVerticalG = dynamicZ / GRAVITY

        // Step 3: Clamp to realistic range
        lateralG = rawLateralG.coerceIn(-MAX_G_FORCE, MAX_G_FORCE)
        longitudinalG = rawLongitudinalG.coerceIn(-MAX_G_FORCE, MAX_G_FORCE)
        verticalG = rawVerticalG.coerceIn(-MAX_G_FORCE, MAX_G_FORCE)

        // Step 4: Calculate total G-force magnitude
        totalG = sqrt(
            (lateralG * lateralG + longitudinalG * longitudinalG + verticalG * verticalG).toDouble()
        ).toFloat()

        // Step 5: Smooth the values
        lateralG = smoothValue(lateralG, lateralBuffer)
        longitudinalG = smoothValue(longitudinalG, longBuffer)

        // Step 6: Update peak values
        if (abs(lateralG) > abs(peakLateralG)) peakLateralG = lateralG
        if (abs(longitudinalG) > abs(peakLongitudinalG)) peakLongitudinalG = longitudinalG
        if (totalG > peakTotalG) peakTotalG = totalG
        if (longitudinalG < peakBrakingG) peakBrakingG = longitudinalG // Most negative = hardest braking

        return GForceData(
            lateralG = lateralG,
            longitudinalG = longitudinalG,
            verticalG = verticalG,
            totalG = totalG,
            peakLateralG = peakLateralG,
            peakLongitudinalG = peakLongitudinalG,
            peakTotalG = peakTotalG,
            peakBrakingG = peakBrakingG
        )
    }

    private fun smoothValue(value: Float, buffer: FloatArray): Float {
        buffer[bufferIndex] = value
        if (bufferCount < SMOOTHING_WINDOW_SIZE) bufferCount++
        var sum = 0f
        for (i in 0 until bufferCount) {
            sum += buffer[i]
        }
        bufferIndex = (bufferIndex + 1) % SMOOTHING_WINDOW_SIZE
        return sum / bufferCount
    }

    /**
     * Get the current total G-force
     */
    fun getCurrentTotalG(): Float = totalG

    /**
     * Get formatted total G-force string
     */
    fun getFormattedTotalG(): String {
        return String.format("%.2f G", totalG)
    }

    /**
     * Get formatted lateral G-force with direction
     */
    fun getFormattedLateralG(): String {
        val abs = abs(lateralG)
        val direction = if (lateralG >= 0) "R" else "L"
        return if (abs < 0.05f) {
            "0.00 G"
        } else {
            String.format("%.2f G %s", abs, direction)
        }
    }

    /**
     * Get formatted longitudinal G-force with direction
     */
    fun getFormattedLongitudinalG(): String {
        val abs = abs(longitudinalG)
        val direction = if (longitudinalG >= 0) "ACC" else "BRK"
        return if (abs < 0.05f) {
            "0.00 G"
        } else {
            String.format("%.2f G %s", abs, direction)
        }
    }

    /**
     * Reset peak values (e.g., at start of new ride)
     */
    fun resetPeaks() {
        peakLateralG = 0f
        peakLongitudinalG = 0f
        peakTotalG = 0f
        peakBrakingG = 0f
        bufferIndex = 0
        bufferCount = 0
        lateralBuffer.fill(0f)
        longBuffer.fill(0f)
    }

    /**
     * Full reset
     */
    fun reset() {
        lateralG = 0f
        longitudinalG = 0f
        verticalG = 0f
        totalG = 0f
        resetPeaks()
    }
}

/**
 * Data class holding G-force computation results
 */
data class GForceData(
    val lateralG: Float,          // Left-right G (positive = right)
    val longitudinalG: Float,     // Front-back G (positive = acceleration)
    val verticalG: Float,         // Up-down G
    val totalG: Float,            // Total magnitude
    val peakLateralG: Float,      // Peak lateral G for this ride
    val peakLongitudinalG: Float, // Peak longitudinal G for this ride
    val peakTotalG: Float,        // Peak total G for this ride
    val peakBrakingG: Float       // Peak braking G (most negative) for this ride
)
