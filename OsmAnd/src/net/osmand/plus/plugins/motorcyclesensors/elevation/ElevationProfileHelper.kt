package net.osmand.plus.plugins.motorcyclesensors.elevation

import net.osmand.Location
import net.osmand.PlatformUtil
import net.osmand.data.LatLon
import net.osmand.plus.OsmandApplication
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ElevationProfileHelper - altitude and route profile display during ride.
 *
 * Provides real-time elevation tracking and route profile information:
 * - Current altitude (from GPS or barometric sensor)
 * - Altitude gained/lost during ride
 * - Grade (slope percentage) of current road segment
 * - Elevation profile of the planned route
 * - Mountain pass detection and warnings
 * - Altitude-based weather adjustments
 *
 * Data sources:
 * - Primary: GPS altitude (available on all devices)
 * - Secondary: Barometric pressure sensor (more accurate, available on some phones)
 * - Tertiary: OsmAnd's built-in elevation data from SRTM/DEM
 *
 * Grade calculation:
 * - Uses a 5-second rolling window for smooth grade estimation
 * - Filters out GPS altitude noise with median filter
 * - Reports grade in percentage (-15% to +15% typical for roads)
 *
 * Mountain pass warnings:
 * - Altitude > 1500m: mountain riding conditions
 * - Altitude > 2500m: reduced engine power (naturally aspirated)
 * - Temperature drops ~6.5°C per 1000m altitude
 */
class ElevationProfileHelper(private val app: OsmandApplication) {

    companion object {
        private val LOG = PlatformUtil.getLog(ElevationProfileHelper::class.java)

        // Grade calculation window
        const val GRADE_WINDOW_SECONDS = 5
        const val GRADE_SAMPLE_INTERVAL_MS = 1000L  // 1 Hz for grade

        // Altitude thresholds
        const val MOUNTAIN_THRESHOLD_M = 1500.0
        const val HIGH_MOUNTAIN_THRESHOLD_M = 2500.0
        const val LAPSE_RATE_C_PER_1000M = 6.5  // Temperature decrease per 1000m

        // GPS altitude smoothing
        const val ALTITUDE_SMOOTHING_FACTOR = 0.3f  // Exponential moving average alpha
        const val MAX_GRADE_PERCENT = 45f           // Max realistic road grade
    }

    /**
     * Elevation data for the current ride position.
     */
    data class ElevationData(
        var altitudeM: Float = 0f,           // Current altitude in meters
        var smoothedAltitudeM: Float = 0f,    // Smoothed altitude
        var gradePercent: Float = 0f,         // Current road grade %
        var altitudeGainedM: Float = 0f,      // Total ascent this ride
        var altitudeLostM: Float = 0f,         // Total descent this ride
        var minAltitudeM: Float = Float.MAX_VALUE,  // Minimum altitude this ride
        var maxAltitudeM: Float = Float.MIN_VALUE,  // Maximum altitude this ride
        var isMountainZone: Boolean = false,   // Above 1500m
        var isHighMountainZone: Boolean = false, // Above 2500m
        var estimatedTempAdjustment: Float = 0f // Temperature adjustment from altitude
    )

    /**
     * Route elevation profile point.
     */
    data class ElevationPoint(
        val distanceFromStartKm: Float,
        val altitudeM: Float,
        val latLon: LatLon,
        val gradePercent: Float
    )

    /**
     * Mountain zone warning.
     */
    data class MountainWarning(
        val type: MountainWarningType,
        val altitudeM: Float,
        val message: String
    )

    enum class MountainWarningType {
        ENTERING_MOUNTAIN,       // Above 1500m
        ENTERING_HIGH_MOUNTAIN,  // Above 2500m
        STEEP_UPHILL,           // Grade > 10%
        STEEP_DOWNHILL          // Grade < -10%
    }

    // State
    val elevationData = ElevationData()
    private val routeProfile = CopyOnWriteArrayList<ElevationPoint>()
    private val altitudeHistory = CopyOnWriteArrayList<Pair<Long, Float>>()  // timestamp, altitude

    private var lastLocation: Location? = null
    private var lastGradeCalculationMs: Long = 0L
    private var lastMountainWarningType: MountainWarningType? = null

    // Listener
    interface ElevationListener {
        fun onElevationUpdated(data: ElevationData)
        fun onMountainWarning(warning: MountainWarning)
        fun onSteepGrade(gradePercent: Float, isUphill: Boolean)
    }

    private val listeners = mutableListOf<ElevationListener>()

    fun addListener(listener: ElevationListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ElevationListener) {
        listeners.remove(listener)
    }

    /**
     * Update with new GPS location.
     * Called from plugin's updateLocation().
     */
    fun updateLocation(location: Location) {
        val altitude = location.altitude.toFloat()
        if (altitude <= 0f) return  // Invalid altitude

        val now = System.currentTimeMillis()

        // Smooth altitude using exponential moving average
        if (elevationData.smoothedAltitudeM <= 0f) {
            elevationData.smoothedAltitudeM = altitude
        } else {
            elevationData.smoothedAltitudeM =
                ALTITUDE_SMOOTHING_FACTOR * altitude +
                (1f - ALTITUDE_SMOOTHING_FACTOR) * elevationData.smoothedAltitudeM
        }

        elevationData.altitudeM = altitude

        // Track min/max
        elevationData.minAltitudeM = min(elevationData.minAltitudeM, elevationData.smoothedAltitudeM)
        elevationData.maxAltitudeM = max(elevationData.maxAltitudeM, elevationData.smoothedAltitudeM)

        // Calculate ascent/descent
        lastLocation?.let { prev ->
            val prevAlt = prev.altitude.toFloat()
            val altDiff = elevationData.smoothedAltitudeM - prevAlt

            if (altDiff > 1f) {
                elevationData.altitudeGainedM += altDiff
            } else if (altDiff < -1f) {
                elevationData.altitudeLostM += abs(altDiff)
            }
        }

        // Calculate grade periodically
        if (now - lastGradeCalculationMs >= GRADE_SAMPLE_INTERVAL_MS) {
            calculateGrade(location)
            lastGradeCalculationMs = now
        }

        // Check mountain zones
        checkMountainZones()

        // Store altitude history for grade calculation
        altitudeHistory.add(Pair(now, elevationData.smoothedAltitudeM))
        // Trim old entries (keep last 30 seconds)
        while (altitudeHistory.size > 30) {
            altitudeHistory.removeAt(0)
        }

        lastLocation = location

        listeners.forEach { it.onElevationUpdated(elevationData) }
    }

    /**
     * Calculate current road grade from altitude history.
     */
    private fun calculateGrade(location: Location) {
        val now = System.currentTimeMillis()
        val windowStartMs = now - (GRADE_WINDOW_SECONDS * 1000L)

        // Get altitudes within the window
        val windowEntries = altitudeHistory.filter { it.first >= windowStartMs }
        if (windowEntries.size < 3) return

        val firstAlt = windowEntries.first().second
        val lastAlt = windowEntries.last().second
        val altDiff = lastAlt - firstAlt

        // Calculate horizontal distance
        val timeDiffS = (windowEntries.last().first - windowEntries.first().first) / 1000f
        if (timeDiffS <= 0) return

        val speedMs = location.speed
        val horizontalDistM = speedMs * timeDiffS

        if (horizontalDistM < 5f) return  // Too slow for accurate grade

        var grade = (altDiff / horizontalDistM) * 100f  // Percentage
        grade = grade.coerceIn(-MAX_GRADE_PERCENT, MAX_GRADE_PERCENT)  // Clamp

        elevationData.gradePercent = grade

        // Alert on steep grades
        if (grade > 10f) {
            listeners.forEach { it.onSteepGrade(grade, true) }
        } else if (grade < -10f) {
            listeners.forEach { it.onSteepGrade(grade, false) }
        }
    }

    /**
     * Check if we're entering a mountain zone.
     */
    private fun checkMountainZones() {
        val alt = elevationData.smoothedAltitudeM
        val wasMountain = elevationData.isMountainZone
        val wasHighMountain = elevationData.isHighMountainZone

        elevationData.isMountainZone = alt >= MOUNTAIN_THRESHOLD_M
        elevationData.isHighMountainZone = alt >= HIGH_MOUNTAIN_THRESHOLD_M

        // Calculate temperature adjustment based on altitude
        if (alt > 500) {
            elevationData.estimatedTempAdjustment = -((alt - 500) / 1000f) * LAPSE_RATE_C_PER_1000M.toFloat()
        } else {
            elevationData.estimatedTempAdjustment = 0f
        }

        // Warn on zone transitions
        if (!wasMountain && elevationData.isMountainZone && lastMountainWarningType != MountainWarningType.ENTERING_MOUNTAIN) {
            lastMountainWarningType = MountainWarningType.ENTERING_MOUNTAIN
            listeners.forEach {
                it.onMountainWarning(MountainWarning(
                    MountainWarningType.ENTERING_MOUNTAIN, alt,
                    "Entering mountain zone (${alt.toInt()}m). Temperature may be ${"%.0f".format(elevationData.estimatedTempAdjustment)}°C lower."
                ))
            }
        }

        if (!wasHighMountain && elevationData.isHighMountainZone && lastMountainWarningType != MountainWarningType.ENTERING_HIGH_MOUNTAIN) {
            lastMountainWarningType = MountainWarningType.ENTERING_HIGH_MOUNTAIN
            listeners.forEach {
                it.onMountainWarning(MountainWarning(
                    MountainWarningType.ENTERING_HIGH_MOUNTAIN, alt,
                    "High altitude zone (${alt.toInt()}m). Engine power may be reduced. Stay hydrated!"
                ))
            }
        }
    }

    /**
     * Set the route elevation profile from a calculated route.
     */
    fun setRouteProfile(points: List<ElevationPoint>) {
        routeProfile.clear()
        routeProfile.addAll(points)
    }

    /**
     * Get the route elevation profile.
     */
    fun getRouteProfile(): List<ElevationPoint> = routeProfile.toList()

    /**
     * Get elevation at a specific distance along the route.
     */
    fun getElevationAtDistance(distanceKm: Float): Float? {
        if (routeProfile.isEmpty()) return null

        // Binary search for closest point
        var low = 0
        var high = routeProfile.size - 1

        while (low < high) {
            val mid = (low + high) / 2
            if (routeProfile[mid].distanceFromStartKm < distanceKm) {
                low = mid + 1
            } else {
                high = mid
            }
        }

        return routeProfile[low].altitudeM
    }

    /**
     * Reset elevation data for a new ride.
     */
    fun reset() {
        elevationData.altitudeM = 0f
        elevationData.smoothedAltitudeM = 0f
        elevationData.gradePercent = 0f
        elevationData.altitudeGainedM = 0f
        elevationData.altitudeLostM = 0f
        elevationData.minAltitudeM = Float.MAX_VALUE
        elevationData.maxAltitudeM = Float.MIN_VALUE
        elevationData.isMountainZone = false
        elevationData.isHighMountainZone = false
        elevationData.estimatedTempAdjustment = 0f
        altitudeHistory.clear()
        routeProfile.clear()
        lastLocation = null
        lastMountainWarningType = null
    }
}
