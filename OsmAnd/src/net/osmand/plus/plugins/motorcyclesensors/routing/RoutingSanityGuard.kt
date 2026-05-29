package net.osmand.plus.plugins.motorcyclesensors.routing

import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import net.osmand.plus.plugins.motorcyclesensors.instrumentation.SensorDiagnosticsHelper
import net.osmand.router.RouteCalculationResult
import kotlin.math.abs

/**
 * Routing Sanity Guard - Prevents curvy routing from producing terrible routes.
 *
 * The Problem:
 * When curvy routing is enabled, we modify routing preferences (avoid motorways,
 * prefer secondary/tertiary roads). This can produce routes that are:
 * - 3x longer than the optimal route
 * - Through unpaved roads that are dangerous for motorcycles
 * - Through residential areas with 30km/h speed limits
 * - Through roads that are seasonally closed
 * - Downgraded so far that they're actually worse than the standard route
 *
 * The Solution:
 * Before accepting a curvy route, compare it against a "baseline" route
 * (standard OsmAnd routing for motorcycle profile). If the curvy route is
 * significantly worse on any sanity metric, fall back to the baseline.
 *
 * Sanity metrics:
 * 1. Time penalty: curvy route must not take >2x baseline time
 * 2. Distance penalty: curvy route must not be >1.5x baseline distance
 * 3. Route quality floor: even with curvy preference, route must have
 *    reasonable average speed (>20 km/h average)
 * 4. Fun score minimum: if the "curvy" route has fun score < 20, it's
 *    not actually curvy, so we gained nothing from the penalty
 *
 * When fallback occurs:
 * - The standard route is used for navigation
 * - A notification is shown: "Curvy routing unavailable for this route"
 * - The event is logged for diagnostics
 * - User can override in settings (disable sanity guard)
 */
class RoutingSanityGuard(private val app: OsmandApplication) {

    companion object {
        private val LOG = PlatformUtil.getLog(RoutingSanityGuard::class.java)

        // Sanity thresholds
        const val MAX_TIME_PENALTY_RATIO = 2.0f       // Curvy route max 2x slower than baseline
        const val MAX_DISTANCE_PENALTY_RATIO = 1.5f    // Curvy route max 1.5x longer than baseline
        const val MIN_AVERAGE_SPEED_KMH = 20f          // Even curvy routes should average > 20 km/h
        const val MIN_FUN_SCORE_FOR_PENALTY = 20        // If fun score < 20, penalty not justified

        // Fun score thresholds for recommendations
        const val FUN_SCORE_NOT_WORTH_IT = 15          // Below this, curvy preference adds nothing
        const val FUN_SCORE_MARGINAL = 30               // Marginal improvement
        const val FUN_SCORE_GREAT = 50                   // Definitely worth the detour
    }

    /**
     * Result of sanity check comparison.
     */
    data class SanityCheckResult(
        val isAcceptable: Boolean,
        val timePenaltyRatio: Float,
        val distancePenaltyRatio: Float,
        val curvyAvgSpeedKmh: Float,
        val curvyFunScore: Int,
        val baselineFunScore: Int,
        val fallbackReason: String?,
        val recommendation: RouteRecommendation
    )

    enum class RouteRecommendation(val description: String) {
        USE_CURVY("Curvy route is good - enjoy the ride!"),
        MARGINAL_IMPROVEMENT("Curvy route adds slight detour for moderate fun"),
        NOT_WORTH_THE_PENALTY("Curvy route is too slow/long for the fun gained"),
        USE_BASELINE("Falling back to standard route - curvy route failed sanity check"),
        CURVY_NOT_ACTUALLY_CURVY("Curvy preference produced a route that isn't actually curvy")
    }

    private val curvyRoadRouter = CurvyRoadRouter()
    private val motorcycleRoutingHelper = MotorcycleRoutingHelper(app)

    /**
     * Check if a curvy route is acceptable compared to a baseline route.
     *
     * @param curvyRoute The route calculated with curvy road preferences
     * @param baselineRoute The route calculated with standard preferences (nullable if not available)
     * @return SanityCheckResult with accept/reject decision and detailed metrics
     */
    fun checkRouteSanity(
        curvyRoute: RouteCalculationResult,
        baselineRoute: RouteCalculationResult?
    ): SanityCheckResult {
        val curvyStats = motorcycleRoutingHelper.analyzeRouteCurviness(curvyRoute)
        val curvyFunScore = curvyRoadRouter.calculateFunScore(curvyStats)

        // Calculate curvy route metrics
        val curvyDistanceM = curvyRoute.wholeRouteDistance
        val curvyTimeSec = curvyRoute.routingTime.toFloat()
        val curvyAvgSpeedKmh = if (curvyTimeSec > 0)
            (curvyDistanceM / curvyTimeSec) * 3.6f else 0f

        var timePenaltyRatio = 1.0f
        var distancePenaltyRatio = 1.0f
        var baselineFunScore = 0

        // Compare against baseline if available
        if (baselineRoute != null && baselineRoute.isCalculated) {
            val baselineDistanceM = baselineRoute.wholeRouteDistance
            val baselineTimeSec = baselineRoute.routingTime.toFloat()
            val baselineStats = motorcycleRoutingHelper.analyzeRouteCurviness(baselineRoute)
            baselineFunScore = curvyRoadRouter.calculateFunScore(baselineStats)

            if (baselineTimeSec > 0) {
                timePenaltyRatio = curvyTimeSec / baselineTimeSec
            }
            if (baselineDistanceM > 0) {
                distancePenaltyRatio = curvyDistanceM.toFloat() / baselineDistanceM.toFloat()
            }
        }

        // Determine recommendation
        val recommendation = determineRecommendation(
            timePenaltyRatio, distancePenaltyRatio,
            curvyAvgSpeedKmh, curvyFunScore, baselineFunScore
        )

        // Check if route passes sanity
        val (isAcceptable, fallbackReason) = evaluateSanity(
            timePenaltyRatio, distancePenaltyRatio,
            curvyAvgSpeedKmh, curvyFunScore
        )

        if (!isAcceptable) {
            LOG.warn("RoutingSanityGuard: Curvy route REJECTED - $fallbackReason " +
                "(time=${"%.1f".format(timePenaltyRatio)}x, dist=${"%.1f".format(distancePenaltyRatio)}x, " +
                "avgSpeed=${"%.0f".format(curvyAvgSpeedKmh)}km/h, funScore=$curvyFunScore)")
        } else {
            LOG.info("RoutingSanityGuard: Curvy route ACCEPTED " +
                "(time=${"%.1f".format(timePenaltyRatio)}x, dist=${"%.1f".format(distancePenaltyRatio)}x, " +
                "avgSpeed=${"%.0f".format(curvyAvgSpeedKmh)}km/h, funScore=$curvyFunScore)")
        }

        return SanityCheckResult(
            isAcceptable = isAcceptable,
            timePenaltyRatio = timePenaltyRatio,
            distancePenaltyRatio = distancePenaltyRatio,
            curvyAvgSpeedKmh = curvyAvgSpeedKmh,
            curvyFunScore = curvyFunScore,
            baselineFunScore = baselineFunScore,
            fallbackReason = fallbackReason,
            recommendation = recommendation
        )
    }

    /**
     * Quick sanity check without a baseline route comparison.
     * Uses absolute thresholds only.
     */
    fun quickSanityCheck(curvyRoute: RouteCalculationResult): SanityCheckResult {
        return checkRouteSanity(curvyRoute, null)
    }

    /**
     * Determine route recommendation based on all metrics.
     */
    private fun determineRecommendation(
        timePenalty: Float, distancePenalty: Float,
        avgSpeedKmh: Float, funScore: Int, baselineFunScore: Int
    ): RouteRecommendation {
        // Route is not actually curvy
        if (funScore < FUN_SCORE_NOT_WORTH_IT) {
            return RouteRecommendation.CURVY_NOT_ACTUALLY_CURVY
        }

        // Fails basic sanity
        if (timePenalty > MAX_TIME_PENALTY_RATIO || distancePenalty > MAX_DISTANCE_PENALTY_RATIO) {
            return RouteRecommendation.NOT_WORTH_THE_PENALTY
        }

        // Great fun score with acceptable penalty
        if (funScore >= FUN_SCORE_GREAT && timePenalty < 1.5f) {
            return RouteRecommendation.USE_CURVY
        }

        // Marginal improvement
        if (funScore >= FUN_SCORE_MARGINAL || (funScore - baselineFunScore) > 15) {
            return RouteRecommendation.MARGINAL_IMPROVEMENT
        }

        // Default: use curvy but note it's not great
        return RouteRecommendation.MARGINAL_IMPROVEMENT
    }

    /**
     * Evaluate whether the curvy route passes sanity checks.
     *
     * Returns (isAcceptable, fallbackReason)
     */
    private fun evaluateSanity(
        timePenalty: Float, distancePenalty: Float,
        avgSpeedKmh: Float, funScore: Int
    ): Pair<Boolean, String?> {
        // Check time penalty
        if (timePenalty > MAX_TIME_PENALTY_RATIO) {
            return Pair(false, "Time penalty too high: ${"%.1f".format(timePenalty)}x " +
                "(max ${MAX_TIME_PENALTY_RATIO}x)")
        }

        // Check distance penalty
        if (distancePenalty > MAX_DISTANCE_PENALTY_RATIO) {
            return Pair(false, "Distance penalty too high: ${"%.1f".format(distancePenalty)}x " +
                "(max ${MAX_DISTANCE_PENALTY_RATIO}x)")
        }

        // Check average speed floor
        if (avgSpeedKmh > 0 && avgSpeedKmh < MIN_AVERAGE_SPEED_KMH) {
            return Pair(false, "Average speed too low: ${"%.0f".format(avgSpeedKmh)} km/h " +
                "(min ${MIN_AVERAGE_SPEED_KMH} km/h) - route may include unsuitable roads")
        }

        // Check if the route is actually curvy enough to justify any penalty
        if (timePenalty > 1.2f && funScore < MIN_FUN_SCORE_FOR_PENALTY) {
            return Pair(false, "Route not curvy enough to justify penalty: " +
                "funScore=$funScore < $MIN_FUN_SCORE_FOR_PENALTY")
        }

        return Pair(true, null)
    }

    /**
     * Get a human-readable summary of the sanity check result.
     */
    fun getSanitySummary(result: SanityCheckResult): String {
        val sb = StringBuilder()
        sb.append("Route Quality: ${result.recommendation.name}\n")
        sb.append("Recommendation: ${result.recommendation.description}\n")

        if (result.timePenaltyRatio > 1.0f) {
            sb.append("Time: ${"%.1f".format(result.timePenaltyRatio)}x baseline\n")
        }
        if (result.distancePenaltyRatio > 1.0f) {
            sb.append("Distance: ${"%.1f".format(result.distancePenaltyRatio)}x baseline\n")
        }

        sb.append("Fun Score: ${result.curvyFunScore}/100\n")
        sb.append("Avg Speed: ${"%.0f".format(result.curvyAvgSpeedKmh)} km/h\n")

        if (!result.isAcceptable) {
            sb.append("\nWARNING: ${result.fallbackReason}\n")
            sb.append("Falling back to standard route.")
        }

        return sb.toString()
    }
}
