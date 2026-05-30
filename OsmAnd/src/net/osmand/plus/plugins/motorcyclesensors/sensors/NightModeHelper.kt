package net.osmand.plus.plugins.motorcyclesensors.sensors

import android.graphics.Color
import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication

/**
 * NightModeHelper - auto-dimmed widget styling for night riding.
 *
 * When riding at night, bright white widgets are dangerous because:
 * 1. They reduce night vision (takes 30+ minutes to fully adapt)
 * 2. They cause glare on helmet visors
 * 3. They distract from the road
 *
 * This helper provides:
 * - Automatic detection of night time (sunset/sunrise or manual)
 * - Red-colored numbers (preserves night vision like aviation instruments)
 * - Dimmed backgrounds and borders
 * - Reduced widget update frequency (less distraction)
 *
 * Night vision science:
 * - Red light at wavelengths > 620nm doesn't bleach rhodopsin
 * - This is why military/aviation instruments use red
 * - Our red color: #FF1744 (vivid red, clearly readable)
 * - Dimmed background: #1A000000 (almost transparent dark)
 * - Dimmed border: #33FFFFFF (very subtle white)
 *
 * Auto-detection:
 * - Uses OsmAnd's built-in day/night mode detection
 * - Checks sunset/sunrise times from location
 * - Manual override available in settings
 */
class NightModeHelper(private val app: OsmandApplication) {

    companion object {
        private val LOG = PlatformUtil.getLog(NightModeHelper::class.java)

        // Night mode colors
        const val NIGHT_TEXT_COLOR = 0xFFFF1744.toInt()       // Vivid red
        const val NIGHT_TEXT_COLOR_DIM = 0xFFD50000.toInt()   // Darker red for less important values
        const val NIGHT_BACKGROUND = 0x1A000000.toInt()       // Almost transparent dark
        const val NIGHT_BORDER = 0x33FFFFFF.toInt()           // Very subtle white border
        const val NIGHT_WARNING_COLOR = 0xFFFF6D00.toInt()    // Orange for warnings
        const val NIGHT_DANGER_COLOR = 0xFFFF1744.toInt()     // Red for danger (same as text, bright)

        // Day mode colors (for comparison)
        const val DAY_TEXT_COLOR = Color.WHITE
        const val DAY_WARNING_COLOR = 0xFFFFC107.toInt()      // Amber
        const val DAY_DANGER_COLOR = 0xFFF44336.toInt()       // Red
    }

    enum class NightMode {
        DAY,            // Full brightness, white/normal colors
        TWILIGHT,       // Transition: slightly dimmed
        NIGHT           // Red numbers, dimmed everything
    }

    private var currentMode = NightMode.DAY
    private var forceNightMode = false
    private var isEnabled = true

    /**
     * Listener for night mode changes.
     */
    interface NightModeListener {
        fun onNightModeChanged(mode: NightMode)
    }

    private val listeners = mutableListOf<NightModeListener>()

    fun addListener(listener: NightModeListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: NightModeListener) {
        listeners.remove(listener)
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            setMode(NightMode.DAY)
        }
    }

    fun setForceNightMode(force: Boolean) {
        forceNightMode = force
        updateMode()
    }

    /**
     * Update night mode based on current conditions.
     * Called periodically or when OsmAnd's day/night mode changes.
     */
    fun updateMode() {
        if (!isEnabled) return

        val newMode = when {
            forceNightMode -> NightMode.NIGHT
            isCurrentlyNight() -> NightMode.NIGHT
            isCurrentlyTwilight() -> NightMode.TWILIGHT
            else -> NightMode.DAY
        }

        if (newMode != currentMode) {
            currentMode = newMode
            LOG.info("NightModeHelper: Mode changed to $currentMode")
            listeners.forEach { it.onNightModeChanged(currentMode) }
        }
    }

    /**
     * Check if it's currently night based on OsmAnd's day/night helper.
     */
    private fun isCurrentlyNight(): Boolean {
        return app.daynightHelper.isNightMode(
            net.osmand.plus.settings.enums.ThemeUsageContext.APP
        )
    }

    /**
     * Check if it's twilight (within 30 minutes of sunrise/sunset).
     * Uses a simplified check based on OsmAnd's night mode transition.
     */
    private fun isCurrentlyTwilight(): Boolean {
        // Simplified: if OsmAnd is transitioning, we're in twilight
        // In practice, we'd check actual sunrise/sunset times
        return false  // Will be enhanced with actual sun position calculation
    }

    /**
     * Get the text color for the current mode.
     */
    fun getTextColor(): Int {
        return when (currentMode) {
            NightMode.DAY -> DAY_TEXT_COLOR
            NightMode.TWILIGHT -> NIGHT_TEXT_COLOR_DIM
            NightMode.NIGHT -> NIGHT_TEXT_COLOR
        }
    }

    /**
     * Get the warning color for the current mode.
     */
    fun getWarningColor(): Int {
        return when (currentMode) {
            NightMode.DAY -> DAY_WARNING_COLOR
            NightMode.TWILIGHT -> NIGHT_WARNING_COLOR
            NightMode.NIGHT -> NIGHT_WARNING_COLOR
        }
    }

    /**
     * Get the danger color for the current mode.
     */
    fun getDangerColor(): Int {
        return when (currentMode) {
            NightMode.DAY -> DAY_DANGER_COLOR
            NightMode.TWILIGHT -> NIGHT_DANGER_COLOR
            NightMode.NIGHT -> NIGHT_DANGER_COLOR
        }
    }

    /**
     * Get the background color for the current mode.
     */
    fun getBackgroundColor(): Int {
        return when (currentMode) {
            NightMode.DAY -> Color.TRANSPARENT
            NightMode.TWILIGHT -> NIGHT_BACKGROUND
            NightMode.NIGHT -> NIGHT_BACKGROUND
        }
    }

    /**
     * Get the border color for the current mode.
     */
    fun getBorderColor(): Int {
        return when (currentMode) {
            NightMode.DAY -> Color.TRANSPARENT
            NightMode.TWILIGHT -> NIGHT_BORDER
            NightMode.NIGHT -> NIGHT_BORDER
        }
    }

    /**
     * Get the widget update interval for the current mode.
     * Night mode uses slower updates to reduce distraction.
     */
    fun getUpdateIntervalMs(): Long {
        return when (currentMode) {
            NightMode.DAY -> 100L       // 10 Hz
            NightMode.TWILIGHT -> 200L  // 5 Hz
            NightMode.NIGHT -> 500L     // 2 Hz (less distraction)
        }
    }

    fun getCurrentMode(): NightMode = currentMode

    fun isNightMode(): Boolean = currentMode == NightMode.NIGHT

    private fun setMode(mode: NightMode) {
        if (currentMode != mode) {
            currentMode = mode
            listeners.forEach { it.onNightModeChanged(mode) }
        }
    }
}
