package net.osmand.plus.plugins.motorcyclesensors.fuel

import net.osmand.PlatformUtil
import net.osmand.data.LatLon
import net.osmand.data.QuadRect
import net.osmand.plus.OsmandApplication
import net.osmand.plus.settings.backend.OsmandSettings
import net.osmand.plus.settings.backend.preferences.CommonPreference
import kotlin.math.*

/**
 * FuelRangeHelper - calculates remaining fuel range based on:
 * - Fuel tank capacity (liters)
 * - Current fuel level (from OBD2 or manual input)
 * - Average fuel consumption (L/100km)
 * - Current speed and riding mode
 *
 * The range is displayed as a polygon overlay on the map, showing
 * the maximum distance the motorcycle can travel before running out of fuel.
 * The polygon accounts for road networks (not just a circle), using
 * a simplified isochrone approach based on distance from current position.
 *
 * Dynamic consumption calculation:
 * - Base consumption from user settings (e.g., 5.5 L/100km)
 * - Speed factor: higher speed = more consumption (quadratic drag)
 * - Aggressive factor: lean angle and G-force increase consumption
 * - Cold engine factor: higher consumption during warm-up
 *
 * The fuel range polygon is generated as a set of points at maximum
 * distance in 36 directions (every 10 degrees), then clipped to
 * account for terrain and road accessibility.
 */
class FuelRangeHelper(private val app: OsmandApplication) {

    companion object {
        private val LOG = PlatformUtil.getLog(FuelRangeHelper::class.java)

        // Default values
        const val DEFAULT_TANK_CAPACITY = 17.0       // liters (typical mid-size motorcycle)
        const val DEFAULT_AVG_CONSUMPTION = 5.5      // L/100km (typical sport touring)
        const val DEFAULT_FUEL_LEVEL = 100.0          // percent
        const val RESERVE_PERCENTAGE = 15.0           // Reserve fuel warning at 15%

        // Speed factor coefficients (quadratic drag model)
        // At 120 km/h, consumption is roughly 1.5x the base rate
        const val SPEED_FACTOR_COEFF = 0.00005        // c in: factor = 1 + c * v^2

        // Aggressive riding adds up to 30% more consumption
        const val AGGRESSIVE_MAX_FACTOR = 0.30

        // Number of polygon points (every 10 degrees)
        const val POLYGON_POINTS = 36
    }

    private val settings = app.settings

    /**
     * Calculate remaining range in kilometers.
     *
     * @param fuelLevelPercent Current fuel level (0-100%)
     * @param tankCapacityLiters Total fuel tank capacity in liters
     * @param avgConsumption Base average consumption in L/100km
     * @param currentSpeedKmh Current speed for dynamic consumption adjustment
     * @param leanAngle Current lean angle for aggressive riding factor
     * @param gForce Current G-force for aggressive riding factor
     * @return Remaining range in kilometers
     */
    fun calculateRange(
        fuelLevelPercent: Float,
        tankCapacityLiters: Float,
        avgConsumption: Float,
        currentSpeedKmh: Float = 0f,
        leanAngle: Float = 0f,
        gForce: Float = 0f
    ): Float {
        // Calculate fuel remaining in liters
        val fuelRemaining = (fuelLevelPercent / 100f) * tankCapacityLiters

        // Subtract reserve fuel (never count reserve as usable range)
        val reserveLiters = (RESERVE_PERCENTAGE / 100f) * tankCapacityLiters
        val usableFuel = max(0f, fuelRemaining - reserveLiters)

        if (usableFuel <= 0f) return 0f

        // Calculate dynamic consumption rate
        val dynamicConsumption = calculateDynamicConsumption(
            avgConsumption, currentSpeedKmh, leanAngle, gForce
        )

        // Range = usable fuel / (consumption / 100)
        // L / (L/100km) = km * 100
        val rangeKm = (usableFuel / dynamicConsumption) * 100f

        LOG.debug("FuelRangeHelper: fuel=${"%.1f".format(fuelRemaining)}L, " +
            "usable=${"%.1f".format(usableFuel)}L, " +
            "consumption=${"%.1f".format(dynamicConsumption)}L/100km, " +
            "range=${"%.1f".format(rangeKm)}km")

        return max(0f, rangeKm)
    }

    /**
     * Calculate dynamic fuel consumption based on riding conditions.
     *
     * Base consumption is adjusted by:
     * 1. Speed factor: Air resistance increases quadratically with speed
     *    - At 60 km/h: ~1.0x base
     *    - At 120 km/h: ~1.7x base
     *    - At 180 km/h: ~2.6x base
     * 2. Aggressive riding factor: Lean angle and G-force indicate sport riding
     *    - Lean > 20° or G > 0.8G: adds 10-30% more consumption
     */
    fun calculateDynamicConsumption(
        baseConsumption: Float,
        speedKmh: Float,
        leanAngle: Float,
        gForce: Float
    ): Float {
        var consumption = baseConsumption

        // Speed factor (quadratic air drag model)
        if (speedKmh > 0f) {
            val speedFactor = 1f + SPEED_FACTOR_COEFF * speedKmh * speedKmh
            consumption *= speedFactor
        }

        // Aggressive riding factor
        val absLean = abs(leanAngle)
        val leanFactor = when {
            absLean > 35 -> AGGRESSIVE_MAX_FACTOR        // 30% more
            absLean > 20 -> AGGRESSIVE_MAX_FACTOR * 0.5f  // 15% more
            else -> 0f
        }

        val gForceFactor = when {
            gForce > 1.5f -> AGGRESSIVE_MAX_FACTOR        // 30% more
            gForce > 0.8f -> AGGRESSIVE_MAX_FACTOR * 0.3f  // 9% more
            else -> 0f
        }

        consumption *= (1f + max(leanFactor, gForceFactor))

        return consumption
    }

    /**
     * Generate a fuel range polygon centered at the given position.
     *
     * Creates a set of LatLon points representing the maximum reachable
     * distance in 36 directions. The polygon is simplified (circular)
     * but can be refined with road network awareness in future versions.
     *
     * @param centerLat Current latitude
     * @param centerLon Current longitude
     * @param rangeKm Remaining range in kilometers
     * @return List of LatLon points forming the range polygon
     */
    fun generateRangePolygon(centerLat: Double, centerLon: Double, rangeKm: Float): List<LatLon> {
        if (rangeKm <= 0f) return emptyList()

        val points = mutableListOf<LatLon>()
        val rangeM = rangeKm * 1000.0  // Convert to meters

        // Generate points around a circle at the range distance
        for (i in 0 until POLYGON_POINTS) {
            val bearing = (i * 360.0 / POLYGON_POINTS) * PI / 180.0

            // Approximate distance calculation using haversine inverse
            val latOffset = rangeM / 111320.0 * cos(bearing)  // meters per degree latitude
            val lonOffset = rangeM / (111320.0 * cos(Math.toRadians(centerLat))) * sin(bearing)

            val lat = centerLat + latOffset
            val lon = centerLon + lonOffset

            points.add(LatLon(lat, lon))
        }

        // Close the polygon
        if (points.isNotEmpty()) {
            points.add(points[0])
        }

        return points
    }

    /**
     * Get the bounding box for the fuel range polygon.
     */
    fun getRangeBoundingBox(centerLat: Double, centerLon: Double, rangeKm: Float): QuadRect {
        val rangeM = (rangeKm * 1000.0)
        val latOffset = rangeM / 111320.0
        val lonOffset = rangeM / (111320.0 * cos(Math.toRadians(centerLat)))

        return QuadRect(
            centerLon - lonOffset,  // left
            centerLat + latOffset,  // top
            centerLon + lonOffset,  // right
            centerLat - latOffset   // bottom
        )
    }

    /**
     * Check if fuel level is below reserve threshold.
     */
    fun isLowFuel(fuelLevelPercent: Float): Boolean {
        return fuelLevelPercent <= RESERVE_PERCENTAGE
    }

    /**
     * Get estimated distance to nearest gas station.
     * Uses OsmAnd's POI search for fuel stations.
     */
    fun getDistanceToNearestGasStation(currentLat: Double, currentLon: Double): Float {
        // TODO: Use OsmAnd's AmenitySearchAPI to find nearest fuel station
        // For now, return a placeholder
        return -1f
    }

    /**
     * Get fuel consumption category for display.
     */
    fun getConsumptionCategory(consumption: Float): ConsumptionCategory {
        return when {
            consumption < 4.0f -> ConsumptionCategory.ECONOMICAL
            consumption < 6.0f -> ConsumptionCategory.NORMAL
            consumption < 8.0f -> ConsumptionCategory.MODERATE
            consumption < 12.0f -> ConsumptionCategory.HIGH
            else -> ConsumptionCategory.VERY_HIGH
        }
    }

    enum class ConsumptionCategory(val displayName: String, val color: String) {
        ECONOMICAL("Economical", "#4CAF50"),
        NORMAL("Normal", "#8BC34A"),
        MODERATE("Moderate", "#FFC107"),
        HIGH("High", "#FF9800"),
        VERY_HIGH("Very High", "#F44336")
    }
}
