package net.osmand.plus.plugins.motorcyclesensors.routing

import net.osmand.PlatformUtil
import net.osmand.data.LatLon
import net.osmand.router.RouteSegmentResult
import net.osmand.util.MapUtils
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * TwistinessCalculator - Analyzes road curvature from OSM/route data.
 *
 * Unlike CurvyRoadRouter which works with calculated route segments,
 * this class works at a lower level with raw geographic data points
 * and can be used for:
 * - Pre-computing twistiness for areas of the map
 * - Analyzing individual road segments for curvy road overlay
 * - Calculating curvature metrics for road selection
 *
 * The key metric is "curvature" - how much a road deviates from a
 * straight line over a given distance. Higher curvature = more fun!
 *
 * Curvature is measured as the cumulative angle change divided by
 * the straight-line distance between the first and last points.
 * A perfect straight line has curvature 0.
 * A perfect circle has curvature = 2*PI / circumference.
 */
class TwistinessCalculator {

    companion object {
        private val LOG = PlatformUtil.getLog(TwistinessCalculator::class.java)

        // Sampling parameters
        const val SAMPLE_INTERVAL_METERS = 10f   // Analyze every 10m
        const val WINDOW_SIZE = 5                 // Smoothing window
        const val MIN_POINTS_FOR_CURVATURE = 3

        // Curvature thresholds (radians per meter)
        const val CURVATURE_GENTLE = 0.001f       // ~0.06 deg/m
        const val CURVATURE_MODERATE = 0.003f      // ~0.17 deg/m
        const val CURVATURE_TWISTY = 0.006f        // ~0.34 deg/m
        // > CURVATURE_TWISTY = VERY_TWISTY

        // Colors for map overlay rendering
        val COLOR_GENTLE = 0xFFAAAAAA.toInt()       // Grey - boring
        val COLOR_MODERATE = 0xFF66BB6A.toInt()     // Light green - okay
        val COLOR_TWISTY = 0xFF43A047.toInt()       // Green - fun!
        val COLOR_VERY_TWISTY = 0xFF1B5E20.toInt()  // Dark green - amazing!
    }

    /**
     * Calculate the curvature of a polyline defined by a list of LatLon points.
     *
     * The curvature is the total angle change divided by the total distance.
     * This gives a measure of how "windy" the road is regardless of its length.
     *
     * @param points List of geographic points forming the road
     * @return RoadCurvature object with curvature metrics
     */
    fun calculateCurvature(points: List<LatLon>): RoadCurvature {
        if (points.size < MIN_POINTS_FOR_CURVATURE) {
            return RoadCurvature(0f, 0f, 0, 0f, TwistinessClass.GENTLE)
        }

        var totalAngleChange = 0f
        var totalDistance = 0f
        var cornerCount = 0
        var maxAngleChange = 0f

        // Calculate bearings and angle changes
        val bearings = FloatArray(points.size - 1)
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            bearings[i] = MapUtils.bearing(p1.latitude, p1.longitude, p2.latitude, p2.longitude).toFloat()
        }

        // Calculate cumulative angle changes
        for (i in 0 until bearings.size - 1) {
            val angleDiff = abs(MapUtils.degreesDiff(bearings[i].toDouble(), bearings[i + 1].toDouble())).toFloat()
            val segmentDist = MapUtils.getDistance(points[i + 1], points[i + 2]).toFloat()

            totalAngleChange += angleDiff
            totalDistance += segmentDist

            if (angleDiff > maxAngleChange) {
                maxAngleChange = angleDiff
            }

            if (angleDiff >= CurvyRoadRouter.MIN_CORNER_ANGLE) {
                cornerCount++
            }
        }

        // Calculate curvature (angle change per meter)
        val curvature = if (totalDistance > 0) {
            Math.toRadians(totalAngleChange.toDouble()).toFloat() / totalDistance
        } else 0f

        // Classify
        val classification = classifyCurvature(curvature)

        return RoadCurvature(
            curvature = curvature,
            totalAngleChange = totalAngleChange,
            cornerCount = cornerCount,
            maxAngleChange = maxAngleChange,
            classification = classification
        )
    }

    /**
     * Calculate twistiness for a single route segment.
     *
     * @param segment The route segment to analyze
     * @return SegmentTwistiness with metrics for this segment
     */
    fun calculateSegmentTwistiness(segment: RouteSegmentResult): SegmentTwistiness {
        val points = extractSegmentPoints(segment)
        val curvature = calculateCurvature(points)

        return SegmentTwistiness(
            segment = segment,
            curvature = curvature.curvature,
            classification = curvature.classification,
            angleChangeRate = if (segment.distance > 0) {
                curvature.totalAngleChange / segment.distance * 100f
            } else 0f,
            color = getColorForClassification(curvature.classification)
        )
    }

    /**
     * Classify curvature value into twistiness class.
     */
    private fun classifyCurvature(curvature: Float): TwistinessClass {
        return when {
            curvature < CURVATURE_GENTLE -> TwistinessClass.GENTLE
            curvature < CURVATURE_MODERATE -> TwistinessClass.MODERATE
            curvature < CURVATURE_TWISTY -> TwistinessClass.TWISTY
            else -> TwistinessClass.VERY_TWISTY
        }
    }

    /**
     * Get the overlay color for a twistiness classification.
     */
    fun getColorForClassification(cls: TwistinessClass): Int {
        return when (cls) {
            TwistinessClass.GENTLE -> COLOR_GENTLE
            TwistinessClass.MODERATE -> COLOR_MODERATE
            TwistinessClass.TWISTY -> COLOR_TWISTY
            TwistinessClass.VERY_TWISTY -> COLOR_VERY_TWISTY
        }
    }

    /**
     * Extract intermediate points from a route segment.
     * For detailed curvature analysis, we need more than just start/end.
     */
    private fun extractSegmentPoints(segment: RouteSegmentResult): List<LatLon> {
        val points = mutableListOf<LatLon>()
        points.add(segment.startPoint)
        points.add(segment.endPoint)
        return points
    }

    /**
     * Calculate the "curvy road score" for a given area.
     * This can be used to highlight areas with good riding roads on the map.
     *
     * @param segments List of road segments in the area
     * @return Area score from 0 (boring) to 100 (rider's paradise)
     */
    fun calculateAreaCurvyScore(segments: List<RouteSegmentResult>): Int {
        if (segments.isEmpty()) return 0

        var twistyDistance = 0f
        var totalDistance = 0f

        for (segment in segments) {
            val twistiness = calculateSegmentTwistiness(segment)
            totalDistance += segment.distance
            if (twistiness.classification == TwistinessClass.TWISTY ||
                twistiness.classification == TwistinessClass.VERY_TWISTY) {
                twistyDistance += segment.distance
            }
        }

        val twistyPercentage = if (totalDistance > 0) (twistyDistance / totalDistance) * 100f else 0f
        return twistyPercentage.toInt().coerceIn(0, 100)
    }
}

/**
 * Curvature metrics for a road or segment.
 */
data class RoadCurvature(
    val curvature: Float,           // Radians per meter
    val totalAngleChange: Float,     // Total degrees of direction change
    val cornerCount: Int,            // Number of significant direction changes
    val maxAngleChange: Float,       // Maximum single angle change (degrees)
    val classification: TwistinessClass
)

/**
 * Twistiness metrics for a single route segment.
 */
data class SegmentTwistiness(
    val segment: RouteSegmentResult,
    val curvature: Float,             // Radians per meter
    val classification: TwistinessClass,
    val angleChangeRate: Float,       // Degrees per 100 meters
    val color: Int                    // Overlay rendering color
)
