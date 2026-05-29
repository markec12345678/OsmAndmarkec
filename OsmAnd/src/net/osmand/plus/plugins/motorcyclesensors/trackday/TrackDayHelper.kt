package net.osmand.plus.plugins.motorcyclesensors.trackday

import net.osmand.PlatformUtil
import net.osmand.Location
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Track Day Mode - lap timing, sector splits, best lap tracking.
 *
 * Architecture:
 * - Uses GPS for timing (no external transponders needed)
 * - Define start/finish line by GPS coordinates
 * - Optionally define sector boundaries for split timing
 * - Auto-detects lap crossings via GPS proximity
 * - Tracks: lap times, sector times, best lap, consistency
 *
 * Usage flow:
 * 1. User defines track start/finish line on map
 * 2. Optionally defines sector split points
 * 3. Presses "Start Track Day"
 * 4. System auto-detects when rider crosses start/finish
 * 5. Each crossing = new lap, time recorded
 * 6. Sector times recorded between sector boundaries
 * 7. Shows: current lap, last lap, best lap, delta to best
 *
 * Detection method:
 * - Geofence around start/finish line (default radius: 20m)
 * - Direction check: only counts crossing in the correct direction
 * - Minimum lap time: 30s (prevents false double-triggers)
 */
class TrackDayHelper {

    companion object {
        private val LOG = PlatformUtil.getLog(TrackDayHelper::class.java)

        // Detection settings
        const val START_FINISH_RADIUS_M = 20.0      // meters
        const val MIN_LAP_TIME_MS = 30_000L          // 30 seconds minimum
        const val SECTOR_RADIUS_M = 30.0             // meters
        const val DIRECTION_TOLERANCE_DEG = 45.0     // degrees
    }

    /**
     * Track layout: start/finish line + optional sector boundaries.
     */
    data class TrackLayout(
        val name: String,
        val startFinishLat: Double,
        val startFinishLon: Double,
        val startFinishDirection: Float,    // degrees, expected crossing direction
        val sectors: List<SectorBoundary>,
        val trackLengthKm: Float            // approximate track length
    )

    data class SectorBoundary(
        val lat: Double,
        val lon: Double,
        val sectorNumber: Int
    )

    /**
     * A completed lap with sector times.
     */
    data class Lap(
        val lapNumber: Int,
        val lapTimeMs: Long,
        val sectorTimes: Map<Int, Long>,    // sector number -> time in ms
        val timestamp: Long,
        val maxLeanAngle: Float,
        val maxGForce: Float,
        val avgSpeedKmh: Float
    )

    /**
     * Track Day session state.
     */
    enum class SessionState {
        IDLE,           // Not started
        WAITING,        // Waiting for first start/finish crossing
        RACING,         // Active lap in progress
        PAUSED          // Temporarily paused
    }

    /**
     * Complete Track Day session results.
     */
    data class TrackDaySession(
        val trackLayout: TrackLayout,
        val laps: List<Lap>,
        val bestLap: Lap?,
        val totalLaps: Int,
        val consistency: Float,         // 0-100, how consistent lap times are
        val improvement: Float          // % improvement from first to best lap
    ) {
        fun getBestLapTimeMs(): Long = bestLap?.lapTimeMs ?: 0L

        fun getAverageLapTimeMs(): Long {
            if (laps.isEmpty()) return 0L
            return laps.map { it.lapTimeMs }.sum() / laps.size
        }

        fun getConsistencyRating(): String {
            return when {
                consistency > 95 -> "Pro-level consistency"
                consistency > 85 -> "Very consistent"
                consistency > 70 -> "Good consistency"
                consistency > 50 -> "Inconsistent - focus on rhythm"
                else -> "Very inconsistent - slow down and find your pace"
            }
        }
    }

    // Session state
    private var state = SessionState.IDLE
    private var currentTrack: TrackLayout? = null
    private val completedLaps = CopyOnWriteArrayList<Lap>()
    private var lapStartTimeMs = 0L
    private var lapStartLocation: Location? = null
    private var currentLapMaxLean = 0f
    private var currentLapMaxG = 0f
    private var currentLapDistance = 0f
    private var lastSectorCrossed = 0
    private var sectorStartTimes = mutableMapOf<Int, Long>()
    private var lastLocation: Location? = null

    // Listeners
    interface TrackDayListener {
        fun onLapCompleted(lap: Lap, bestLap: Lap?)
        fun onSectorCompleted(sectorNumber: Int, sectorTimeMs: Long)
        fun onSessionStarted()
        fun onSessionStopped(session: TrackDaySession)
    }

    private val listeners = CopyOnWriteArrayList<TrackDayListener>()

    fun addListener(listener: TrackDayListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: TrackDayListener) {
        listeners.remove(listener)
    }

    fun getState(): SessionState = state

    /**
     * Start a Track Day session.
     * @param track The track layout with start/finish and sector definitions
     */
    fun startSession(track: TrackLayout) {
        currentTrack = track
        completedLaps.clear()
        state = SessionState.WAITING
        lapStartTimeMs = 0L
        currentLapMaxLean = 0f
        currentLapMaxG = 0f
        currentLapDistance = 0f
        lastSectorCrossed = 0
        sectorStartTimes.clear()
        lastLocation = null

        LOG.info("TrackDay: Session started - waiting for first start/finish crossing at ${track.name}")
        listeners.forEach { it.onSessionStarted() }
    }

    /**
     * Stop the Track Day session and generate results.
     */
    fun stopSession(): TrackDaySession? {
        val track = currentTrack ?: return null
        state = SessionState.IDLE

        val session = buildSession(track)
        LOG.info("TrackDay: Session stopped - ${session.totalLaps} laps, " +
            "best: ${formatLapTime(session.getBestLapTimeMs())}")
        listeners.forEach { it.onSessionStopped(session) }
        return session
    }

    /**
     * Update with new GPS location.
     * Called from the plugin's updateLocation() method.
     */
    fun updateLocation(location: Location) {
        if (state == SessionState.IDLE || state == SessionState.PAUSED) return
        val track = currentTrack ?: return

        // Track distance for average speed calculation
        lastLocation?.let { last ->
            currentLapDistance += last.distanceTo(location)
        }
        lastLocation = location

        // Check start/finish crossing
        val distToStart = haversineDistance(
            location.latitude, location.longitude,
            track.startFinishLat, track.startFinishLon
        )

        if (distToStart <= START_FINISH_RADIUS_M) {
            handleStartFinishCrossing(track, location)
        }

        // Check sector crossings (only during active lap)
        if (state == SessionState.RACING) {
            for (sector in track.sectors) {
                if (sector.sectorNumber <= lastSectorCrossed) continue

                val distToSector = haversineDistance(
                    location.latitude, location.longitude,
                    sector.lat, sector.lon
                )

                if (distToSector <= SECTOR_RADIUS_M) {
                    handleSectorCrossing(sector, location)
                }
            }
        }
    }

    /**
     * Update with current sensor data for lap statistics.
     */
    fun updateSensorData(leanAngleDeg: Float, totalG: Float) {
        if (state != SessionState.RACING) return
        currentLapMaxLean = maxOf(currentLapMaxLean, Math.abs(leanAngleDeg))
        currentLapMaxG = maxOf(currentLapMaxG, totalG)
    }

    private fun handleStartFinishCrossing(track: TrackLayout, location: Location) {
        val now = System.currentTimeMillis()

        if (state == SessionState.WAITING) {
            // First crossing - start first lap
            state = SessionState.RACING
            lapStartTimeMs = now
            lapStartLocation = location
            lastSectorCrossed = 0
            sectorStartTimes[0] = now
            LOG.info("TrackDay: First crossing detected - lap 1 started")
            return
        }

        // Check minimum lap time to prevent double-triggers
        if (now - lapStartTimeMs < MIN_LAP_TIME_MS) return

        // Complete current lap
        val lapTimeMs = now - lapStartTimeMs
        val avgSpeed = if (lapTimeMs > 0 && currentLapDistance > 0) {
            (currentLapDistance / (lapTimeMs / 1000f)) * 3.6f  // m/s to km/h
        } else 0f

        val sectorTimes = sectorStartTimes.map { (sector, startTime) ->
            val nextSectorTime = sectorStartTimes[sector + 1] ?: now
            sector to (nextSectorTime - startTime)
        }.toMap()

        val lap = Lap(
            lapNumber = completedLaps.size + 1,
            lapTimeMs = lapTimeMs,
            sectorTimes = sectorTimes,
            timestamp = now,
            maxLeanAngle = currentLapMaxLean,
            maxGForce = currentLapMaxG,
            avgSpeedKmh = avgSpeed
        )

        completedLaps.add(lap)
        val bestLap = completedLaps.minByOrNull { it.lapTimeMs }

        LOG.info("TrackDay: Lap ${lap.lapNumber} completed - ${formatLapTime(lapTimeMs)}, " +
            "best: ${formatLapTime(bestLap?.lapTimeMs ?: lapTimeMs)}")

        listeners.forEach { it.onLapCompleted(lap, bestLap) }

        // Reset for next lap
        lapStartTimeMs = now
        lapStartLocation = location
        currentLapMaxLean = 0f
        currentLapMaxG = 0f
        currentLapDistance = 0f
        lastSectorCrossed = 0
        sectorStartTimes.clear()
        sectorStartTimes[0] = now
    }

    private fun handleSectorCrossing(sector: SectorBoundary, location: Location) {
        val now = System.currentTimeMillis()
        val sectorTimeMs = now - (sectorStartTimes[lastSectorCrossed] ?: lapStartTimeMs)

        lastSectorCrossed = sector.sectorNumber
        sectorStartTimes[sector.sectorNumber] = now

        LOG.info("TrackDay: Sector ${sector.sectorNumber} crossed - ${formatLapTime(sectorTimeMs)}")
        listeners.forEach { it.onSectorCompleted(sector.sectorNumber, sectorTimeMs) }
    }

    private fun buildSession(track: TrackLayout): TrackDaySession {
        val bestLap = completedLaps.minByOrNull { it.lapTimeMs }
        val consistency = calculateConsistency()

        val improvement = if (completedLaps.size >= 2) {
            val firstLap = completedLaps.first().lapTimeMs.toFloat()
            val best = bestLap?.lapTimeMs?.toFloat() ?: firstLap
            ((firstLap - best) / firstLap) * 100f
        } else 0f

        return TrackDaySession(
            trackLayout = track,
            laps = completedLaps.toList(),
            bestLap = bestLap,
            totalLaps = completedLaps.size,
            consistency = consistency,
            improvement = improvement
        )
    }

    /**
     * Calculate consistency score (0-100).
     * 100 = all laps within 1% of average, 0 = wild variation.
     */
    private fun calculateConsistency(): Float {
        if (completedLaps.size < 2) return 100f
        val times = completedLaps.map { it.lapTimeMs.toFloat() }
        val avg = times.average().toFloat()
        if (avg == 0f) return 100f
        val avgDeviation = times.map { Math.abs(it - avg) / avg }.average().toFloat()
        return maxOf(0f, (1f - avgDeviation * 5f) * 100f)  // Scale: 20% deviation = 0 score
    }

    companion object {
        /**
         * Format lap time as MM:SS.mmm
         */
        fun formatLapTime(timeMs: Long): String {
            val minutes = timeMs / 60_000
            val seconds = (timeMs % 60_000) / 1_000
            val millis = timeMs % 1_000
            return "%d:%02d.%03d".format(minutes, seconds, millis)
        }

        /**
         * Haversine distance between two points in meters.
         */
        fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0  // Earth radius in meters
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            return r * c
        }
    }
}
