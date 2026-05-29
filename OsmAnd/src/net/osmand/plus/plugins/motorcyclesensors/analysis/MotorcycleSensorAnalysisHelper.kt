package net.osmand.plus.plugins.motorcyclesensors.analysis

import net.osmand.PlatformUtil
import net.osmand.plus.plugins.motorcyclesensors.recording.MotorcycleRideStatistics
import net.osmand.plus.plugins.motorcyclesensors.recording.MotorcycleSensorDataPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Helper class for post-ride analysis of motorcycle sensor data.
 *
 * Provides:
 * - Lean angle analysis (distribution, max, average by sector)
 * - G-force analysis (peak values, distribution, corner classification)
 * - Corner detection (identifies corners from lean angle changes)
 * - Ride scoring (overall riding style assessment)
 * - Sector analysis (splits ride into sectors for detailed analysis)
 */
class MotorcycleSensorAnalysisHelper {

    companion object {
        private val LOG = PlatformUtil.getLog(MotorcycleSensorAnalysisHelper::class.java)

        // Corner detection thresholds
        private const val CORNER_START_THRESHOLD_DEG = 5f    // Lean angle to start counting a corner
        private const val CORNER_END_THRESHOLD_DEG = 3f      // Lean angle to end a corner
        private const val MIN_CORNER_DURATION_MS = 500L      // Minimum duration for a valid corner

        // G-force classification thresholds
        private const val GENTLE_G_THRESHOLD = 0.3f          // Below this = gentle riding
        private const val MODERATE_G_THRESHOLD = 0.6f        // Moderate cornering
        private const val AGGRESSIVE_G_THRESHOLD = 1.0f      // Aggressive riding
    }

    /**
     * Analyze a complete ride's sensor data
     */
    fun analyzeRide(dataPoints: List<MotorcycleSensorDataPoint>): MotorcycleRideAnalysis {
        if (dataPoints.isEmpty()) {
            return MotorcycleRideAnalysis.empty()
        }

        val corners = detectCorners(dataPoints)
        val leanAngleAnalysis = analyzeLeanAngles(dataPoints)
        val gForceAnalysis = analyzeGForces(dataPoints)
        val rideScore = calculateRideScore(dataPoints, corners)

        return MotorcycleRideAnalysis(
            totalDataPoints = dataPoints.size,
            rideDurationMs = dataPoints.last().timestampMs - dataPoints.first().timestampMs,
            corners = corners,
            leanAngleAnalysis = leanAngleAnalysis,
            gForceAnalysis = gForceAnalysis,
            rideScore = rideScore
        )
    }

    /**
     * Detect corners from lean angle data
     */
    private fun detectCorners(dataPoints: List<MotorcycleSensorDataPoint>): List<CornerEvent> {
        val corners = mutableListOf<CornerEvent>()
        var inCorner = false
        var cornerStartIdx = 0
        var maxLeanInCorner = 0f
        var maxGInCorner = 0f
        var leanSumInCorner = 0f
        var cornerPointCount = 0

        for (i in dataPoints.indices) {
            val point = dataPoints[i]
            val absLean = abs(point.leanAngleDeg)

            if (!inCorner && absLean >= CORNER_START_THRESHOLD_DEG) {
                // Start of corner
                inCorner = true
                cornerStartIdx = i
                maxLeanInCorner = absLean
                maxGInCorner = point.totalG
                leanSumInCorner = absLean
                cornerPointCount = 1
            } else if (inCorner) {
                maxLeanInCorner = max(maxLeanInCorner, absLean)
                maxGInCorner = max(maxGInCorner, point.totalG)
                leanSumInCorner += absLean
                cornerPointCount++

                if (absLean < CORNER_END_THRESHOLD_DEG) {
                    // End of corner
                    val durationMs = point.timestampMs - dataPoints[cornerStartIdx].timestampMs
                    if (durationMs >= MIN_CORNER_DURATION_MS) {
                        val direction = if (dataPoints[cornerStartIdx].leanAngleDeg >= 0)
                            CornerDirection.RIGHT else CornerDirection.LEFT
                        corners.add(
                            CornerEvent(
                                startTimestampMs = dataPoints[cornerStartIdx].timestampMs,
                                endTimestampMs = point.timestampMs,
                                durationMs = durationMs,
                                direction = direction,
                                maxLeanAngleDeg = maxLeanInCorner,
                                avgLeanAngleDeg = leanSumInCorner / cornerPointCount,
                                maxGForce = maxGInCorner,
                                intensity = classifyCornerIntensity(maxLeanInCorner)
                            )
                        )
                    }
                    inCorner = false
                }
            }
        }

        return corners
    }

    /**
     * Classify corner intensity based on max lean angle
     */
    private fun classifyCornerIntensity(maxLeanDeg: Float): CornerIntensity {
        return when {
            maxLeanDeg < 15 -> CornerIntensity.GENTLE
            maxLeanDeg < 30 -> CornerIntensity.MODERATE
            maxLeanDeg < 45 -> CornerIntensity.AGGRESSIVE
            else -> CornerIntensity.EXTREME
        }
    }

    /**
     * Analyze lean angle distribution and statistics
     */
    private fun analyzeLeanAngles(dataPoints: List<MotorcycleSensorDataPoint>): LeanAngleAnalysis {
        var maxLean = 0f
        var sumLean = 0f
        var maxLeftLean = 0f
        var maxRightLean = 0f
        var leftCount = 0
        var rightCount = 0
        var straightCount = 0

        // Distribution buckets (5° increments)
        val distribution = mutableMapOf<String, Int>()

        for (point in dataPoints) {
            val absLean = abs(point.leanAngleDeg)
            maxLean = max(maxLean, absLean)
            sumLean += absLean

            if (point.leanAngleDeg > CORNER_START_THRESHOLD_DEG) {
                rightCount++
                maxRightLean = max(maxRightLean, point.leanAngleDeg)
            } else if (point.leanAngleDeg < -CORNER_START_THRESHOLD_DEG) {
                leftCount++
                maxLeftLean = max(maxLeftLean, abs(point.leanAngleDeg))
            } else {
                straightCount++
            }

            // Distribution
            val bucket = "${(absLean.toInt() / 5) * 5}-${(absLean.toInt() / 5) * 5 + 5}°"
            distribution[bucket] = (distribution[bucket] ?: 0) + 1
        }

        val avgLean = if (dataPoints.isNotEmpty()) sumLean / dataPoints.size else 0f

        return LeanAngleAnalysis(
            maxLeanAngleDeg = maxLean,
            maxLeftLeanDeg = maxLeftLean,
            maxRightLeanDeg = maxRightLean,
            avgLeanAngleDeg = avgLean,
            leftCornerCount = leftCount,
            rightCornerCount = rightCount,
            straightCount = straightCount,
            distribution = distribution
        )
    }

    /**
     * Analyze G-force distribution and statistics
     */
    private fun analyzeGForces(dataPoints: List<MotorcycleSensorDataPoint>): GForceAnalysis {
        var maxTotalG = 0f
        var maxLateralG = 0f
        var maxAccelG = 0f
        var maxBrakingG = 0f
        var sumTotalG = 0f
        var gentleCount = 0
        var moderateCount = 0
        var aggressiveCount = 0
        var extremeCount = 0

        for (point in dataPoints) {
            maxTotalG = max(maxTotalG, point.totalG)
            maxLateralG = max(maxLateralG, abs(point.lateralG))
            maxAccelG = max(maxAccelG, point.longitudinalG)
            maxBrakingG = min(maxBrakingG, point.longitudinalG)
            sumTotalG += point.totalG

            // Classify G-force intensity
            when {
                point.totalG < GENTLE_G_THRESHOLD -> gentleCount++
                point.totalG < MODERATE_G_THRESHOLD -> moderateCount++
                point.totalG < AGGRESSIVE_G_THRESHOLD -> aggressiveCount++
                else -> extremeCount++
            }
        }

        val avgTotalG = if (dataPoints.isNotEmpty()) sumTotalG / dataPoints.size else 0f

        return GForceAnalysis(
            maxTotalG = maxTotalG,
            maxLateralG = maxLateralG,
            maxAccelerationG = maxAccelG,
            maxBrakingG = maxBrakingG,
            avgTotalG = avgTotalG,
            gentlePercentage = if (dataPoints.isNotEmpty()) gentleCount.toFloat() / dataPoints.size * 100 else 0f,
            moderatePercentage = if (dataPoints.isNotEmpty()) moderateCount.toFloat() / dataPoints.size * 100 else 0f,
            aggressivePercentage = if (dataPoints.isNotEmpty()) aggressiveCount.toFloat() / dataPoints.size * 100 else 0f,
            extremePercentage = if (dataPoints.isNotEmpty()) extremeCount.toFloat() / dataPoints.size * 100 else 0f
        )
    }

    /**
     * Calculate an overall ride score (0-100)
     * Based on: corner variety, lean angle usage, G-force control, smoothness
     */
    private fun calculateRideScore(
        dataPoints: List<MotorcycleSensorDataPoint>,
        corners: List<CornerEvent>
    ): RideScore {
        if (dataPoints.isEmpty()) return RideScore(0, 0, 0, 0, 0)

        // 1. Corner variety score (both directions, different intensities)
        val leftCorners = corners.count { it.direction == CornerDirection.LEFT }
        val rightCorners = corners.count { it.direction == CornerDirection.RIGHT }
        val totalCorners = leftCorners + rightCorners
        val balance = if (totalCorners > 0) 1f - abs(leftCorners - rightCorners).toFloat() / totalCorners else 0f
        val cornerVarietyScore = min(100f, totalCorners * 2f) * balance

        // 2. Lean angle usage (using more lean = more confident, but not excessive)
        val maxLean = dataPoints.maxOfOrNull { abs(it.leanAngleDeg) } ?: 0f
        val leanUsageScore = when {
            maxLean < 5 -> 20f   // Barely cornering
            maxLean < 20 -> 50f  // Gentle riding
            maxLean < 35 -> 80f  // Good riding
            maxLean < 50 -> 95f  // Very confident
            else -> 70f          // Possibly too aggressive
        }

        // 3. G-force control (smooth transitions, no spikes)
        val gForceVariance = calculateGForceVariance(dataPoints)
        val smoothnessScore = when {
            gForceVariance < 0.01 -> 95f  // Very smooth
            gForceVariance < 0.05 -> 80f  // Smooth
            gForceVariance < 0.1 -> 60f   // Moderate
            else -> 40f                    // Choppy
        }

        // 4. Braking score (smooth braking, not abrupt)
        val maxBraking = dataPoints.minOfOrNull { it.longitudinalG } ?: 0f
        val brakingScore = when {
            maxBraking > -0.3 -> 90f   // Gentle braking
            maxBraking > -0.6 -> 75f   // Moderate braking
            maxBraking > -1.0 -> 55f   // Hard braking
            else -> 30f                // Very hard braking
        }

        // Overall score (weighted average)
        val overallScore = (
            cornerVarietyScore * 0.25f +
            leanUsageScore * 0.30f +
            smoothnessScore * 0.25f +
            brakingScore * 0.20f
        )

        return RideScore(
            overall = overallScore.toInt().coerceIn(0, 100),
            cornerVariety = cornerVarietyScore.toInt().coerceIn(0, 100),
            leanUsage = leanUsageScore.toInt().coerceIn(0, 100),
            smoothness = smoothnessScore.toInt().coerceIn(0, 100),
            braking = brakingScore.toInt().coerceIn(0, 100)
        )
    }

    private fun calculateGForceVariance(dataPoints: List<MotorcycleSensorDataPoint>): Float {
        if (dataPoints.size < 2) return 0f

        val avgG = dataPoints.map { it.totalG }.average().toFloat()
        var sumSquaredDiff = 0f
        for (point in dataPoints) {
            val diff = point.totalG - avgG
            sumSquaredDiff += diff * diff
        }
        return sqrt((sumSquaredDiff / dataPoints.size).toDouble()).toFloat()
    }
}

// ===== Data classes for analysis results =====

data class MotorcycleRideAnalysis(
    val totalDataPoints: Int,
    val rideDurationMs: Long,
    val corners: List<CornerEvent>,
    val leanAngleAnalysis: LeanAngleAnalysis,
    val gForceAnalysis: GForceAnalysis,
    val rideScore: RideScore
) {
    companion object {
        fun empty() = MotorcycleRideAnalysis(
            totalDataPoints = 0,
            rideDurationMs = 0,
            corners = emptyList(),
            leanAngleAnalysis = LeanAngleAnalysis(
                maxLeanAngleDeg = 0f, maxLeftLeanDeg = 0f, maxRightLeanDeg = 0f,
                avgLeanAngleDeg = 0f, leftCornerCount = 0, rightCornerCount = 0,
                straightCount = 0, distribution = emptyMap()
            ),
            gForceAnalysis = GForceAnalysis(
                maxTotalG = 0f, maxLateralG = 0f, maxAccelerationG = 0f,
                maxBrakingG = 0f, avgTotalG = 0f, gentlePercentage = 0f,
                moderatePercentage = 0f, aggressivePercentage = 0f, extremePercentage = 0f
            ),
            rideScore = RideScore(0, 0, 0, 0, 0)
        )
    }
}

data class CornerEvent(
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val durationMs: Long,
    val direction: CornerDirection,
    val maxLeanAngleDeg: Float,
    val avgLeanAngleDeg: Float,
    val maxGForce: Float,
    val intensity: CornerIntensity
)

enum class CornerDirection { LEFT, RIGHT }

enum class CornerIntensity { GENTLE, MODERATE, AGGRESSIVE, EXTREME }

data class LeanAngleAnalysis(
    val maxLeanAngleDeg: Float,
    val maxLeftLeanDeg: Float,
    val maxRightLeanDeg: Float,
    val avgLeanAngleDeg: Float,
    val leftCornerCount: Int,
    val rightCornerCount: Int,
    val straightCount: Int,
    val distribution: Map<String, Int>
)

data class GForceAnalysis(
    val maxTotalG: Float,
    val maxLateralG: Float,
    val maxAccelerationG: Float,
    val maxBrakingG: Float,
    val avgTotalG: Float,
    val gentlePercentage: Float,
    val moderatePercentage: Float,
    val aggressivePercentage: Float,
    val extremePercentage: Float
)

data class RideScore(
    val overall: Int,
    val cornerVariety: Int,
    val leanUsage: Int,
    val smoothness: Int,
    val braking: Int
)
