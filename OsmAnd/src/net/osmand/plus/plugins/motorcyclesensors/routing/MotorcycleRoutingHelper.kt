package net.osmand.plus.plugins.motorcyclesensors.routing

import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import net.osmand.router.RouteCalculationResult
import net.osmand.router.RouteSegmentResult

/**
 * Motorcycle Routing Helper - customizes routing for motorcycle riders.
 *
 * Unlike car routing which prioritizes fastest time, motorcycle routing
 * should prioritize the RIDE EXPERIENCE:
 * - Prefer curvy, scenic roads over straight highways
 * - Avoid motorways (boring, straight, no enjoyment)
 * - Prefer tertiary and secondary roads (more curves)
 * - Lower speed priority (enjoy the ride, don't rush)
 * - Allow narrower roads that cars might avoid
 *
 * This class provides:
 * 1. Custom routing parameters for the MOTORCYCLE profile
 * 2. Route curviness analysis after calculation
 * 3. Curvy road preference integration with OsmAnd's routing engine
 */
class MotorcycleRoutingHelper(private val app: OsmandApplication) {

    companion object {
        private val LOG = PlatformUtil.getLog(MotorcycleRoutingHelper::class.java)

        // Routing parameter keys (must match routing.xml definitions)
        const val PREF_CURVY_ROADS = "motorcycle_curvy_roads"
        const val PREF_AVOID_MOTORWAY = "motorcycle_avoid_motorway"
        const val PREF_AVOID_TRUNK = "motorcycle_avoid_trunk"
        const val PREF_PREFER_SCENIC = "motorcycle_prefer_scenic"
        const val PREF_ALLOW_NARROW = "motorcycle_allow_narrow"

        // Road class priority weights for curvy routing
        // Lower number = lower priority (we want to AVOID motorways)
        const val MOTORWAY_WEIGHT = 0.3f      // Avoid - straight, boring
        const val TRUNK_WEIGHT = 0.5f          // Mostly avoid - fast but somewhat curvy
        const val PRIMARY_WEIGHT = 0.7f        // Neutral - can be fun
        const val SECONDARY_WEIGHT = 0.9f      // Prefer - often curvy
        const val TERTIARY_WEIGHT = 1.0f       // Most prefer - typically the curviest
        const val RESIDENTIAL_WEIGHT = 0.6f    // Neutral - short segments
        const val SERVICE_WEIGHT = 0.4f        // Avoid - dead ends, driveways
        const val TRACK_WEIGHT = 0.8f          // Can be fun but unpaved
        const val UNCLASSIFIED_WEIGHT = 0.85f  // Often curvy rural roads
    }

    private val curvyRoadRouter = CurvyRoadRouter()

    /**
     * Get custom routing parameters for motorcycle curvy road preference.
     *
     * These parameters are used to configure OsmAnd's GeneralRouter
     * to prefer curvy roads. They modify the road class priorities
     * and speed priorities to make the routing engine choose twistier routes.
     *
     * @return Map of parameter name to parameter value
     */
    fun getCurvyRouteParams(): Map<String, String> {
        return mapOf(
            // Road class priorities (0-1, lower = less preferred)
            "motorway" to MOTORWAY_WEIGHT.toString(),
            "trunk" to TRUNK_WEIGHT.toString(),
            "primary" to PRIMARY_WEIGHT.toString(),
            "secondary" to SECONDARY_WEIGHT.toString(),
            "tertiary" to TERTIARY_WEIGHT.toString(),
            "residential" to RESIDENTIAL_WEIGHT.toString(),
            "service" to SERVICE_WEIGHT.toString(),
            "track" to TRACK_WEIGHT.toString(),
            "unclassified" to UNCLASSIFIED_WEIGHT.toString(),

            // Speed preferences - lower to allow slower scenic routes
            "min_speed" to "20",
            "max_speed" to "130",

            // Routing priorities
            "prefer_motorcycle_roads" to "true",
            "avoid_motorways" to "true",
            "allow_tracks" to "true"
        )
    }

    /**
     * Get routing parameters for when curvy roads preference is DISABLED.
     * Uses standard motorcycle routing (similar to car but with motorcycle speeds).
     */
    fun getStandardRouteParams(): Map<String, String> {
        return mapOf(
            "motorway" to "0.8",
            "trunk" to "0.9",
            "primary" to "0.9",
            "secondary" to "0.95",
            "tertiary" to "0.95",
            "residential" to "0.7",
            "service" to "0.5",
            "track" to "0.5",
            "unclassified" to "0.85"
        )
    }

    /**
     * Analyze the curviness of a calculated route.
     *
     * After route calculation, this method provides statistics about
     * how curvy the route is, how many corners it has, and a "fun score".
     *
     * @param route The calculated route result
     * @return RouteCurvinessStats with detailed curviness analysis
     */
    fun analyzeRouteCurviness(route: RouteCalculationResult): RouteCurvinessStats {
        val routeSegments = collectRouteSegments(route)
        return curvyRoadRouter.calculateRouteCurviness(routeSegments)
    }

    /**
     * Build curviness route statistics for display in route details.
     */
    fun buildCurvinessStatistics(route: RouteCalculationResult) =
        curvyRoadRouter.buildCurvinessRouteStatistics(collectRouteSegments(route))

    /**
     * Get the fun score for a route (0-100).
     */
    fun calculateFunScore(route: RouteCalculationResult): Int {
        val stats = analyzeRouteCurviness(route)
        return curvyRoadRouter.calculateFunScore(stats)
    }

    /**
     * Collect route segments from a RouteCalculationResult.
     * Uses getOriginalRoute() which returns the List<RouteSegmentResult>.
     * Falls back to getRouteLocations() if no segments available.
     */
    private fun collectRouteSegments(route: RouteCalculationResult): List<RouteSegmentResult> {
        // Primary: use getOriginalRoute() - the public API for route segments
        val originalRoute = route.originalRoute
        if (!originalRoute.isNullOrEmpty()) {
            return originalRoute
        }

        // Fallback: if no segment data, return empty list
        // (RouteSegmentResult cannot be created from locations alone)
        return emptyList()
    }

    /**
     * Get a human-readable route description with curviness info.
     */
    fun getRouteDescription(route: RouteCalculationResult): String {
        val stats = analyzeRouteCurviness(route)
        val funScore = curvyRoadRouter.calculateFunScore(stats)
        val curvinessDesc = curvyRoadRouter.getTwistinessDescription(stats.classification)

        return buildString {
            append("Route Classification: ${stats.classification.displayName}\n")
            append("Fun Score: $funScore/100\n")
            append("Twisty Roads: ${"%.1f".format(stats.twistyPercentage)}%\n")
            append("Hairpin Turns: ${"%.1f".format(stats.veryTwistyPercentage)}%\n")
            append("Total Corners: ${stats.totalCorners}\n")
            append("Corners/km: ${"%.1f".format(stats.cornersPerKm)}\n")
            append("Avg Curviness: ${"%.1f".format(stats.avgCurviness)} deg/100m\n")
            append("\n$curvinessDesc")
        }
    }
}
