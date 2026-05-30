package net.osmand.plus.plugins.motorcyclesensors.routing

import net.osmand.PlatformUtil
import net.osmand.data.LatLon
import net.osmand.router.RouteSegmentResult
import net.osmand.util.MapUtils
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CurvyRoadRouter - Analyzes road segments for "twistiness" (curvature).
 *
 * This is a UNIQUE feature no other open-source navigation app has.
 * Motorcyclists want TWISTY roads, not the fastest route. This class
 * provides the core algorithms for:
 * - Measuring how curvy a road segment is (angle change per distance)
 * - Classifying road segments by twistiness level
 * - Computing overall route curviness statistics
 * - Integrating with OsmAnd's RouteStatisticsListener for UI display
 *
 * The twistiness metric is based on the cumulative angle change per unit
 * of distance. A straight highway has near-zero twistiness, while a
 * mountain road with hairpin turns has very high twistiness.
 *
 * Angle change rate units: degrees per 100 meters (deg/100m)
 * - < 5 deg/100m  = GENTLE (highway, straight road)
 * - 5-15 deg/100m = MODERATE (suburban road, gentle curves)
 * - 15-30 deg/100m = TWISTY (countryside, fun riding)
 * - > 30 deg/100m = VERY_TWISTY (mountain pass, hairpin heaven!)
 */
class CurvyRoadRouter {

        companion object {
                private val LOG = PlatformUtil.getLog(CurvyRoadRouter::class.java)

                // Twistiness thresholds in degrees per 100 meters
                const val GENTLE_THRESHOLD = 5.0f       // < 5 deg/100m
                const val MODERATE_THRESHOLD = 15.0f     // 5-15 deg/100m
                const val TWISTY_THRESHOLD = 30.0f       // 15-30 deg/100m
                // > 30 deg/100m = VERY_TWISTY

                // Minimum segment distance in meters to consider for bearing calculation
                // Short segments produce noisy bearing data
                const val MIN_SEGMENT_DISTANCE = 5.0f

                // Minimum angle change (degrees) to count as a "corner"
                const val MIN_CORNER_ANGLE = 15.0f

                // Route info attribute name for curvy road classification
                const val ROUTE_INFO_CURVINESS = "routeInfo_curviness"

                // Attribute property names for route statistics display
                const val CURVINESS_GENTLE = "gentle"
                const val CURVINESS_MODERATE = "moderate"
                const val CURVINESS_TWISTY = "twisty"
                const val CURVINESS_VERY_TWISTY = "very_twisty"
        }

        /**
         * Calculate the twistiness (angle change rate) between two consecutive road segments.
         *
         * The twistiness is the absolute angle change between the previous bearing and
         * the current bearing, normalized per 100 meters of distance. This gives a
         * consistent measure regardless of segment length.
         *
         * @param prevBearing    Bearing of the previous road segment in degrees (0-360)
         * @param currentBearing Bearing of the current road segment in degrees (0-360)
         * @param distance       Distance of the current road segment in meters
         * @return Angle change rate in degrees per 100 meters. Returns 0 if distance is too short.
         */
        fun calculateTwistiness(prevBearing: Float, currentBearing: Float, distance: Float): Float {
                if (distance < MIN_SEGMENT_DISTANCE) {
                        return 0f
                }

                // Calculate the shortest angle difference between bearings
                val angleDiff = abs(MapUtils.degreesDiff(prevBearing.toDouble(), currentBearing.toDouble())).toFloat()

                // Normalize to degrees per 100 meters
                return (angleDiff / distance) * 100f
        }

        /**
         * Classify a road segment's twistiness based on its angle change rate.
         *
         * @param angleChangeRate The twistiness value in degrees per 100 meters
         * @return TwistinessClass enum value
         */
        fun classifyRoadTwistiness(angleChangeRate: Float): TwistinessClass {
                return when {
                        angleChangeRate < GENTLE_THRESHOLD -> TwistinessClass.GENTLE
                        angleChangeRate < MODERATE_THRESHOLD -> TwistinessClass.MODERATE
                        angleChangeRate < TWISTY_THRESHOLD -> TwistinessClass.TWISTY
                        else -> TwistinessClass.VERY_TWISTY
                }
        }

        /**
         * Calculate comprehensive curviness statistics for an entire route.
         *
         * Walks through all route segments, computing the angle change between
         * consecutive segments and accumulating statistics. This provides the
         * data needed to show the rider how "fun" a route is.
         *
         * @param routeSegments List of RouteSegmentResult from the calculated route
         * @return RouteCurvinessStats with detailed curviness information
         */
        fun calculateRouteCurviness(routeSegments: List<RouteSegmentResult>): RouteCurvinessStats {
                if (routeSegments.isEmpty()) {
                        return RouteCurvinessStats(
                                totalCurviness = 0f,
                                avgCurviness = 0f,
                                twistyPercentage = 0f,
                                veryTwistyPercentage = 0f,
                                totalCorners = 0,
                                totalDistance = 0f,
                                classification = TwistinessClass.GENTLE
                        )
                }

                var totalCurviness = 0f
                var totalDistance = 0f
                var twistyDistance = 0f
                var veryTwistyDistance = 0f
                var totalCorners = 0
                var segmentCount = 0

                var prevBearing: Float? = null

                for (i in routeSegments.indices) {
                        val segment = routeSegments[i]
                        val segmentDistance = segment.distance

                        if (segmentDistance < MIN_SEGMENT_DISTANCE) {
                                continue
                        }

                        // Calculate bearing for current segment
                        val currentBearing = calculateSegmentBearing(segment)

                        if (prevBearing != null) {
                                // Calculate twistiness between this segment and the previous one
                                val twistiness = calculateTwistiness(prevBearing, currentBearing, segmentDistance)
                                val classification = classifyRoadTwistiness(twistiness)

                                totalCurviness += twistiness
                                totalDistance += segmentDistance
                                segmentCount++

                                // Track distances by classification
                                when (classification) {
                                        TwistinessClass.TWISTY -> twistyDistance += segmentDistance
                                        TwistinessClass.VERY_TWISTY -> {
                                                twistyDistance += segmentDistance
                                                veryTwistyDistance += segmentDistance
                                        }
                                        else -> { /* GENTLE and MODERATE don't add to twisty stats */ }
                                }

                                // Count corners (significant direction changes)
                                val angleDiff = abs(
                                        MapUtils.degreesDiff(prevBearing.toDouble(), currentBearing.toDouble())
                                ).toFloat()
                                if (angleDiff >= MIN_CORNER_ANGLE) {
                                        totalCorners++
                                }
                        } else {
                                // First segment with valid distance - just track distance
                                totalDistance += segmentDistance
                        }

                        prevBearing = currentBearing
                }

                // Calculate averages and percentages
                val avgCurviness = if (segmentCount > 0) totalCurviness / segmentCount else 0f
                val twistyPercentage = if (totalDistance > 0) (twistyDistance / totalDistance) * 100f else 0f
                val veryTwistyPercentage = if (totalDistance > 0) (veryTwistyDistance / totalDistance) * 100f else 0f

                // Classify the overall route
                val overallClassification = classifyRoadTwistiness(avgCurviness)

                return RouteCurvinessStats(
                        totalCurviness = totalCurviness,
                        avgCurviness = avgCurviness,
                        twistyPercentage = twistyPercentage,
                        veryTwistyPercentage = veryTwistyPercentage,
                        totalCorners = totalCorners,
                        totalDistance = totalDistance,
                        classification = overallClassification
                )
        }

        /**
         * Calculate the bearing (direction) of a route segment.
         *
         * Uses the segment's start and end points to compute the heading.
         * For segments traversed in reverse (endPointIndex < startPointIndex),
         * the bearing is computed accordingly.
         *
         * @param segment The route segment
         * @return Bearing in degrees (0-360)
         */
        private fun calculateSegmentBearing(segment: RouteSegmentResult): Float {
                val startPoint = segment.startPoint
                val endPoint = segment.endPoint
                return bearing(
                        startPoint.latitude, startPoint.longitude,
                        endPoint.latitude, endPoint.longitude
                )
        }

        /**
         * Calculate bearing between two lat/lon points in degrees (0-360).
         */
        private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
                val lat1Rad = Math.toRadians(lat1)
                val lat2Rad = Math.toRadians(lat2)
                val dLonRad = Math.toRadians(lon2 - lon1)

                val y = sin(dLonRad) * cos(lat2Rad)
                val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLonRad)

                val bearingRad = atan2(y, x)
                return ((Math.toDegrees(bearingRad) + 360) % 360).toFloat()
        }

        /**
         * Build a curviness breakdown for a route as a list of segment classifications.
         *
         * Note: RouteStatistics constructor is private in OsmAnd, so we return
         * a simpler data structure that can be used for display or further processing.
         * To integrate with OsmAnd's route details UI in the future, use
         * RouteStatisticsHelper.computeStatistic() with proper route data.
         *
         * @param routeSegments The calculated route segments
         * @return List of (segmentDistance, twistinessClass) pairs for UI rendering
         */
        fun buildCurvinessBreakdown(routeSegments: List<RouteSegmentResult>): List<Pair<Float, TwistinessClass>> {
                if (routeSegments.isEmpty()) {
                        return emptyList()
                }

                val breakdown = mutableListOf<Pair<Float, TwistinessClass>>()
                var prevBearing: Float? = null

                for (segment in routeSegments) {
                        val segmentDistance = segment.distance
                        if (segmentDistance < MIN_SEGMENT_DISTANCE) {
                                continue
                        }

                        val currentBearing = calculateSegmentBearing(segment)

                        if (prevBearing != null) {
                                val twistiness = calculateTwistiness(prevBearing, currentBearing, segmentDistance)
                                val classification = classifyRoadTwistiness(twistiness)
                                breakdown.add(Pair(segmentDistance, classification))
                        }

                        prevBearing = currentBearing
                }

                return breakdown
        }

        /**
         * Get the route statistics property name for a twistiness class.
         */
        private fun classificationToPropertyName(cls: TwistinessClass): String {
                return when (cls) {
                        TwistinessClass.STRAIGHT -> "straight"
                        TwistinessClass.GENTLE -> CURVINESS_GENTLE
                        TwistinessClass.MODERATE -> CURVINESS_MODERATE
                        TwistinessClass.TWISTY -> CURVINESS_TWISTY
                        TwistinessClass.VERY_TWISTY -> CURVINESS_VERY_TWISTY
                }
        }

        /**
         * Calculate a "fun score" for the route (0-100).
         *
         * This is a rider-oriented metric that combines twistiness,
         * number of corners, and the percentage of twisty roads.
         * Higher scores mean a more enjoyable motorcycle route.
         *
         * @param stats Route curviness statistics
         * @return Fun score from 0 (boring highway) to 100 (epic mountain pass)
         */
        fun calculateFunScore(stats: RouteCurvinessStats): Int {
                // Weight factors for fun score components
                val twistyWeight = 0.4f
                val veryTwistyWeight = 0.3f
                val cornerDensityWeight = 0.2f
                val avgCurvinessWeight = 0.1f

                // Twisty percentage contribution (0-40 points)
                val twistyScore = min(stats.twistyPercentage, 100f) / 100f * 40f

                // Very twisty percentage contribution (0-30 points)
                val veryTwistyScore = min(stats.veryTwistyPercentage, 100f) / 100f * 30f

                // Corner density (corners per km) contribution (0-20 points)
                // 10 corners per km is considered very high
                val cornersPerKm = if (stats.totalDistance > 0) {
                        stats.totalCorners / (stats.totalDistance / 1000f)
                } else 0f
                val cornerScore = min(cornersPerKm / 10f, 1f) * 20f

                // Average curviness contribution (0-10 points)
                // 30 deg/100m is considered very high average curviness
                val avgCurvinessScore = min(stats.avgCurviness / TWISTY_THRESHOLD, 1f) * 10f

                val totalScore = twistyScore * twistyWeight / 0.4f +
                                veryTwistyScore * veryTwistyWeight / 0.3f +
                                cornerScore * cornerDensityWeight / 0.2f +
                                avgCurvinessScore * avgCurvinessWeight / 0.1f

                return totalScore.toInt().coerceIn(0, 100)
        }

        /**
         * Get a human-readable description for a twistiness class.
         */
        fun getTwistinessDescription(cls: TwistinessClass): String {
                return when (cls) {
                        TwistinessClass.STRAIGHT -> "Straight road - no curves detected"
                        TwistinessClass.GENTLE -> "Straight roads, highways - boring for riders"
                        TwistinessClass.MODERATE -> "Gentle curves, suburban roads - some fun"
                        TwistinessClass.TWISTY -> "Nice curves, countryside - fun riding!"
                        TwistinessClass.VERY_TWISTY -> "Hairpin turns, mountain passes - motorcycle heaven!"
                }
        }

        /**
         * Get an emoji representation for a twistiness class.
         */
        fun getTwistinessEmoji(cls: TwistinessClass): String {
                return when (cls) {
                        TwistinessClass.STRAIGHT -> "\u26AA"          // White circle
                        TwistinessClass.GENTLE -> "\uD83D\uDFE1"       // Yellow circle
                        TwistinessClass.MODERATE -> "\uD83D\uDFE0"     // Orange circle
                        TwistinessClass.TWISTY -> "\uD83D\uDFE2"       // Green circle
                        TwistinessClass.VERY_TWISTY -> "\uD83D\uDD35"  // Blue circle
                }
        }

        /**
         * Build a detailed segment curviness breakdown with geographic points.
         * Used by CurvyRoadOverlay to color road segments on the map.
         */
        fun buildSegmentCurvinessBreakdown(routeSegments: List<RouteSegmentResult>): List<SegmentCurviness> {
                if (routeSegments.isEmpty()) return emptyList()

                val breakdown = mutableListOf<SegmentCurviness>()
                var prevBearing: Float? = null

                for (segment in routeSegments) {
                        val segmentDistance = segment.distance
                        if (segmentDistance < MIN_SEGMENT_DISTANCE) continue

                        val currentBearing = calculateSegmentBearing(segment)
                        val points = listOf(
                                segment.startPoint,
                                segment.endPoint
                        )

                        if (prevBearing != null) {
                                val twistiness = calculateTwistiness(prevBearing, currentBearing, segmentDistance)
                                val classification = classifyRoadTwistiness(twistiness)
                                breakdown.add(SegmentCurviness(
                                        points = points,
                                        twistinessClass = classification,
                                        distanceM = segmentDistance,
                                        angleChangeRate = twistiness
                                ))
                        } else {
                                breakdown.add(SegmentCurviness(
                                        points = points,
                                        twistinessClass = TwistinessClass.STRAIGHT,
                                        distanceM = segmentDistance,
                                        angleChangeRate = 0f
                                ))
                        }

                        prevBearing = currentBearing
                }

                return breakdown
        }
}

/**
 * Classification of road segment twistiness.
 *
 * GENTLE      - Straight or nearly straight roads (highways, motorways)
 *               Angle change < 5 deg/100m
 * MODERATE    - Roads with gentle curves (suburban, rural)
 *               Angle change 5-15 deg/100m
 * TWISTY      - Roads with pronounced curves (countryside, hilly)
 *               Angle change 15-30 deg/100m - FUN for motorcyclists!
 * VERY_TWISTY - Roads with tight curves, switchbacks (mountain passes)
 *               Angle change > 30 deg/100m - MOTORCYCLE HEAVEN!
 */
enum class TwistinessClass(val displayName: String, val color: Int) {
        STRAIGHT("Straight", 0xFF9E9E9E.toInt()),         // Grey
        GENTLE("Gentle", 0xFFD4A017.toInt()),           // Gold/amber
        MODERATE("Moderate", 0xFFFF8C00.toInt()),        // Dark orange
        TWISTY("Twisty", 0xFF2E8B57.toInt()),            // Sea green
        VERY_TWISTY("Very Twisty", 0xFF4169E1.toInt())   // Royal blue
}

/**
 * Comprehensive statistics about a route's curviness.
 *
 * @param totalCurviness      Sum of all angle change rates (deg/100m) across the route
 * @param avgCurviness        Average angle change rate per segment transition
 * @param twistyPercentage    Percentage of route distance classified as TWISTY or VERY_TWISTY
 * @param veryTwistyPercentage Percentage of route distance classified as VERY_TWISTY
 * @param totalCorners        Number of significant direction changes (>15 degrees)
 * @param totalDistance        Total distance analyzed in meters
 * @param classification      Overall route twistiness classification
 */
/**
 * Per-segment curviness data for map overlay rendering.
 * Contains the segment's geographic points and its twistiness classification.
 */
data class SegmentCurviness(
        val points: List<LatLon>,
        val twistinessClass: TwistinessClass,
        val distanceM: Float,
        val angleChangeRate: Float
)

data class RouteCurvinessStats(
        val totalCurviness: Float,
        val avgCurviness: Float,
        val twistyPercentage: Float,
        val veryTwistyPercentage: Float,
        val totalCorners: Int,
        val totalDistance: Float,
        val classification: TwistinessClass
) {
        /**
         * Get corners per kilometer for the route.
         */
        val cornersPerKm: Float
                get() = if (totalDistance > 0) totalCorners / (totalDistance / 1000f) else 0f

        /**
         * Get a formatted summary of the route curviness.
         */
        fun getSummary(router: CurvyRoadRouter): String {
                val funScore = router.calculateFunScore(this)
                return String.format(
                        "%s route | %.1f%% twisty | %d corners | Fun: %d/100",
                        classification.displayName,
                        twistyPercentage,
                        totalCorners,
                        funScore
                )
        }
}
