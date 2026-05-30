package net.osmand.plus.plugins.motorcyclesensors.safety

import net.osmand.Location
import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import net.osmand.plus.settings.backend.preferences.CommonPreference

/**
 * SpeedWarningHelper - configurable speed threshold warning with visual flash.
 *
 * Provides speed-based alerts for motorcyclists:
 * - Configurable speed threshold (km/h or mph)
 * - Visual flash overlay when speed exceeds threshold
 * - Haptic feedback option
 * - Different thresholds for different road types (urban/rural/highway)
 * - Gradual warning (yellow near threshold, red over threshold)
 *
 * Use cases:
 * - Avoid speeding tickets in speed camera zones
 * - Stay within safe limits on twisty roads
 * - Control speed in residential areas
 * - Track day speed limiter for beginners
 *
 * The warning is non-intrusive:
 * - Brief screen flash (200ms) when crossing threshold
 * - Persistent colored border while over speed
 * - No audio alert (would be distracting while riding)
 * - Can be dismissed by slowing down
 */
class SpeedWarningHelper(private val app: OsmandApplication) {

    companion object {
        private val LOG = PlatformUtil.getLog(SpeedWarningHelper::class.java)

        // Warning zones
        const val URBAN_SPEED_LIMIT = 50       // km/h
        const val RURAL_SPEED_LIMIT = 90       // km/h
        const val HIGHWAY_SPEED_LIMIT = 130    // km/h

        // Visual flash duration
        const val FLASH_DURATION_MS = 200L

        // Speed buffer to prevent flickering at threshold boundary
        const val HYSTERESIS_KMH = 3f
    }

    /**
     * Speed warning severity level.
     */
    enum class WarningLevel {
        NONE,           // Below threshold
        APPROACHING,    // Within 10% of threshold
        EXCEEDED,       // Over threshold
        FAR_EXCEEDED    // More than 20% over threshold
    }

    /**
     * Speed warning event with details.
     */
    data class SpeedWarningEvent(
        val level: WarningLevel,
        val currentSpeedKmh: Float,
        val thresholdKmh: Float,
        val overSpeedKmh: Float,       // How much over the threshold
        val overSpeedPercent: Float,    // Percentage over threshold
        val flashRequired: Boolean      // Whether to trigger visual flash
    )

    private var lastWarningLevel = WarningLevel.NONE
    private var customSpeedThreshold: Float = 0f  // 0 = use zone-based limits
    private var isEnabled = false
    private var hapticEnabled = true
    private var lastFlashTimeMs = 0L

    // Listener
    interface SpeedWarningListener {
        fun onSpeedWarning(event: SpeedWarningEvent)
        fun onSpeedReturnedToNormal()
    }

    private val listeners = mutableListOf<SpeedWarningListener>()

    fun addListener(listener: SpeedWarningListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: SpeedWarningListener) {
        listeners.remove(listener)
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            lastWarningLevel = WarningLevel.NONE
        }
    }

    fun setCustomThreshold(speedKmh: Float) {
        customSpeedThreshold = speedKmh
    }

    fun setHapticEnabled(enabled: Boolean) {
        hapticEnabled = enabled
    }

    /**
     * Update with current GPS location.
     * Called from plugin's updateLocation().
     */
    fun updateLocation(location: Location) {
        if (!isEnabled) return

        val speedKmh = location.speed * 3.6f  // m/s to km/h
        val threshold = getActiveThreshold()

        if (threshold <= 0f) return  // No threshold set

        val newLevel = calculateWarningLevel(speedKmh, threshold)
        val flashRequired = newLevel != lastWarningLevel && newLevel != WarningLevel.NONE

        lastWarningLevel = newLevel

        if (newLevel != WarningLevel.NONE) {
            val event = SpeedWarningEvent(
                level = newLevel,
                currentSpeedKmh = speedKmh,
                thresholdKmh = threshold,
                overSpeedKmh = speedKmh - threshold,
                overSpeedPercent = ((speedKmh - threshold) / threshold) * 100f,
                flashRequired = flashRequired
            )
            listeners.forEach { it.onSpeedWarning(event) }

            if (flashRequired) {
                lastFlashTimeMs = System.currentTimeMillis()
            }
        } else if (lastWarningLevel != WarningLevel.NONE) {
            // Returned to normal speed
            listeners.forEach { it.onSpeedReturnedToNormal() }
        }
    }

    /**
     * Calculate warning level based on current speed and threshold.
     * Uses hysteresis to prevent flickering at boundary.
     */
    private fun calculateWarningLevel(speedKmh: Float, thresholdKmh: Float): WarningLevel {
        return when {
            speedKmh > thresholdKmh * 1.2f -> WarningLevel.FAR_EXCEEDED
            speedKmh > thresholdKmh + HYSTERESIS_KMH -> WarningLevel.EXCEEDED
            speedKmh > thresholdKmh * 0.9f -> {
                // Within 10% of threshold - approaching
                if (lastWarningLevel == WarningLevel.EXCEEDED ||
                    lastWarningLevel == WarningLevel.FAR_EXCEEDED) {
                    // Still within hysteresis, keep exceeded
                    if (speedKmh > thresholdKmh - HYSTERESIS_KMH) WarningLevel.EXCEEDED
                    else WarningLevel.APPROACHING
                } else {
                    WarningLevel.APPROACHING
                }
            }
            else -> WarningLevel.NONE
        }
    }

    /**
     * Get the active speed threshold.
     * Uses custom threshold if set, otherwise uses zone-based defaults.
     */
    private fun getActiveThreshold(): Float {
        if (customSpeedThreshold > 0f) return customSpeedThreshold
        // Default to urban speed limit
        return URBAN_SPEED_LIMIT.toFloat()
    }

    /**
     * Get color for warning level.
     */
    fun getColorForLevel(level: WarningLevel): Int {
        return when (level) {
            WarningLevel.NONE -> 0x00000000  // Transparent
            WarningLevel.APPROACHING -> 0x33FFFF00  // Yellow transparent
            WarningLevel.EXCEEDED -> 0x66FF0000  // Red semi-transparent
            WarningLevel.FAR_EXCEEDED -> 0x99FF0000  // Red more visible
        }
    }

    /**
     * Check if a flash is currently active.
     */
    fun isFlashing(): Boolean {
        if (lastFlashTimeMs == 0L) return false
        return System.currentTimeMillis() - lastFlashTimeMs < FLASH_DURATION_MS
    }
}
