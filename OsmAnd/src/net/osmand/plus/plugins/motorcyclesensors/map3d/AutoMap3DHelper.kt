package net.osmand.plus.plugins.motorcyclesensors.map3d

import net.osmand.PlatformUtil
import net.osmand.Location
import net.osmand.plus.OsmandApplication
import net.osmand.plus.settings.backend.OsmandSettings

/**
 * Auto 3D Map Mode - automatically switches between 2D and 3D perspective based on riding conditions.
 *
 * Design rationale:
 * - When riding at speed (>20 km/h), 3D perspective provides better spatial awareness
 *   for upcoming turns, elevation changes, and road curvature
 * - When stopped or very slow, 2D top-down view is more useful for navigation overview
 * - Smooth transitions prevent jarring map jumps
 * - Configurable speed threshold and elevation angle for personal preference
 *
 * How it works:
 * - Monitors GPS speed via plugin's updateLocation()
 * - When speed crosses the threshold:
 *   - Above threshold: sets elevation angle to configured 3D angle (default 45 degrees)
 *   - Below threshold (and stopped for >5s): returns to 2D (90 degrees top-down)
 * - Uses OsmAnd's existing map tilt system (OsmandMapTileView elevation angle)
 * - Does NOT interfere with manual 3D toggle (Map3DButton)
 *
 * Integration with OsmAnd 3D system:
 * - Uses OsmandSettings.ELEVATION_ANGLE preference
 * - Respects USE_OPENGL_RENDER setting (3D requires OpenGL renderer)
 * - Works with existing hillshade/terrain/slope overlays
 * - Compatible with 3D buildings and relief mode
 */
class AutoMap3DHelper(private val app: OsmandApplication) {

    companion object {
        private val LOG = PlatformUtil.getLog(AutoMap3DHelper::class.java)

        // Default configuration
        const val DEFAULT_SPEED_THRESHOLD_KMH = 20.0f
        const val DEFAULT_3D_ELEVATION_ANGLE = 45      // degrees (10=min tilt, 90=top-down)
        const val DEFAULT_2D_ELEVATION_ANGLE = 90       // top-down
        const val STOP_DELAY_MS = 5_000L                 // Wait 5s before switching to 2D
    }

    /**
     * Auto 3D mode state.
     */
    enum class Auto3DState {
        DISABLED,       // Feature turned off
        IDLE_2D,        // Speed below threshold, 2D view
        RIDING_3D,      // Speed above threshold, 3D view
        TRANSITIONING   // Switching between modes
    }

    // Current state
    private var state = Auto3DState.DISABLED
    private var stoppedSinceMs = 0L
    private var wasAutoSwitched = false      // Track if WE switched (vs user manual)

    // Configuration (from plugin preferences)
    var isEnabled = false
    var speedThresholdKmh = DEFAULT_SPEED_THRESHOLD_KMH
    var elevationAngle3D = DEFAULT_3D_ELEVATION_ANGLE

    /**
     * Enable or disable auto 3D mode.
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (enabled) {
            state = Auto3DState.IDLE_2D
            LOG.info("AutoMap3D: Enabled (threshold: ${speedThresholdKmh} km/h, angle: ${elevationAngle3D} deg)")
        } else {
            // If we auto-switched to 3D, return to 2D when disabling
            if (wasAutoSwitched) {
                restore2DView()
            }
            state = Auto3DState.DISABLED
            LOG.info("AutoMap3D: Disabled")
        }
    }

    /**
     * Update with new GPS location data.
     * Called from the plugin's updateLocation() method.
     *
     * @param location Current GPS location with speed
     */
    fun updateLocation(location: Location) {
        if (!isEnabled) return

        val speedKmh = location.speed * 3.6f  // m/s to km/h

        when (state) {
            Auto3DState.IDLE_2D -> {
                // Check if we should switch to 3D
                if (speedKmh >= speedThresholdKmh) {
                    switchTo3D()
                    state = Auto3DState.RIDING_3D
                }
            }
            Auto3DState.RIDING_3D -> {
                // Check if we should switch back to 2D
                if (speedKmh < speedThresholdKmh) {
                    if (stoppedSinceMs == 0L) {
                        stoppedSinceMs = System.currentTimeMillis()
                    }
                    // Only switch to 2D after delay (prevents flickering at threshold)
                    if (System.currentTimeMillis() - stoppedSinceMs >= STOP_DELAY_MS) {
                        switchTo2D()
                        state = Auto3DState.IDLE_2D
                        stoppedSinceMs = 0L
                    }
                } else {
                    // Still moving fast, reset stop timer
                    stoppedSinceMs = 0L
                }
            }
            else -> { /* DISABLED or TRANSITIONING - do nothing */ }
        }
    }

    /**
     * Switch map to 3D perspective mode.
     * Sets the elevation angle to the configured 3D angle.
     */
    private fun switchTo3D() {
        if (!is3DSupported()) {
            LOG.warn("AutoMap3D: 3D not supported on this device/renderer")
            return
        }

        val settings = app.settings
        val currentAngle = settings.ELEVATION_ANGLE.get()

        // Don't switch if user manually set a different 3D angle
        if (currentAngle < DEFAULT_2D_ELEVATION_ANGLE && !wasAutoSwitched) {
            LOG.info("AutoMap3D: User already in 3D mode (${currentAngle} deg) - not overriding")
            return
        }

        settings.ELEVATION_ANGLE.set(elevationAngle3D)
        wasAutoSwitched = true

        LOG.info("AutoMap3D: Switched to 3D (${elevationAngle3D} deg) - speed above ${speedThresholdKmh} km/h")
    }

    /**
     * Switch map back to 2D top-down view.
     */
    private fun switchTo2D() {
        if (!wasAutoSwitched) return  // Don't restore if we didn't auto-switch

        val settings = app.settings
        settings.ELEVATION_ANGLE.set(DEFAULT_2D_ELEVATION_ANGLE)
        wasAutoSwitched = false

        LOG.info("AutoMap3D: Switched to 2D (top-down) - speed below ${speedThresholdKmh} km/h")
    }

    /**
     * Restore 2D view when disabling the feature.
     */
    private fun restore2DView() {
        val settings = app.settings
        settings.ELEVATION_ANGLE.set(DEFAULT_2D_ELEVATION_ANGLE)
        wasAutoSwitched = false
    }

    /**
     * Check if 3D rendering is supported on this device.
     * 3D tilt requires the OpenGL renderer to be active.
     */
    private fun is3DSupported(): Boolean {
        return app.useOpenGlRenderer()
    }

    /**
     * Notify that the user manually changed the elevation angle.
     * This prevents auto-mode from overriding the user's manual tilt.
     */
    fun onManualElevationChange(newAngle: Int) {
        if (!isEnabled) return

        // If user manually changed angle while we were in auto-3D mode,
        // respect their choice and stop auto-switching until next cycle
        if (wasAutoSwitched && newAngle != elevationAngle3D) {
            wasAutoSwitched = false
            LOG.info("AutoMap3D: User manually changed elevation to ${newAngle} deg - respecting manual override")
        }
    }

    /**
     * Get current auto 3D state for UI display.
     */
    fun getState(): Auto3DState = state

    /**
     * Get a human-readable status string.
     */
    fun getStatusText(): String {
        return when (state) {
            Auto3DState.DISABLED -> "Auto 3D: Off"
            Auto3DState.IDLE_2D -> "Auto 3D: 2D (stopped)"
            Auto3DState.RIDING_3D -> "Auto 3D: 3D (riding)"
            Auto3DState.TRANSITIONING -> "Auto 3D: Switching..."
        }
    }
}
