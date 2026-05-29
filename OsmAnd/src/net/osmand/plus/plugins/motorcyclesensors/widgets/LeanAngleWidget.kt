package net.osmand.plus.plugins.motorcyclesensors.widgets

import android.view.View
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.motorcyclesensors.MotorcycleSensorsPlugin
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.widgets.SimpleWidget
import kotlin.math.abs

/**
 * Widget that displays the current motorcycle lean angle on the map screen.
 *
 * Shows lean angle in degrees with direction indicator (L/R).
 * Color-coded:
 *   - Green: 0-20° (safe/normal)
 *   - Yellow: 20-40° (moderate)
 *   - Red: 40°+ (aggressive/track territory)
 *
 * This is the FIRST open-source lean angle widget for motorcyclists!
 */
class LeanAngleWidget(
    mapActivity: MapActivity,
    widgetType: WidgetType,
    customId: String?,
    widgetsPanel: WidgetsPanel?
) : SimpleWidget(mapActivity, widgetType, customId, widgetsPanel) {

    private var lastLeanAngle = 0f

    init {
        setText(null)
        setSmallText("°")
    }

    override fun updateInfo(view: View) {
        val plugin = app.pluginsHelper.getPlugin(MotorcycleSensorsPlugin::class.java)
        if (plugin == null || !plugin.isActive) {
            setText("—")
            setSmallText("°")
            return
        }

        val leanAngle = plugin.lastLeanAngleDeg
        val absLean = abs(leanAngle)

        // Only update if changed significantly
        if (abs(leanAngle - lastLeanAngle) < 0.3f && lastLeanAngle != 0f) return
        lastLeanAngle = leanAngle

        // Format the display
        val direction = if (leanAngle >= 0) "R" else "L"
        val displayText = if (absLean < 0.5f) {
            "0"
        } else {
            String.format("%.0f", absLean)
        }
        val smallText = if (absLean < 0.5f) "°" else "° $direction"

        setText(displayText)
        setSmallText(smallText)
    }

    override fun isMetricSystemDepended(): Boolean = false

    override fun getAdditionalState(): Int = 0
}
