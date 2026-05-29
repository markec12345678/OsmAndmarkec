package net.osmand.plus.plugins.motorcyclesensors.widgets

import android.view.View
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.motorcyclesensors.MotorcycleSensorsPlugin
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.widgets.SimpleWidget
import kotlin.math.abs

/**
 * Widget that displays the current G-force on the motorcycle.
 *
 * Shows total G-force magnitude by default.
 * Can be configured to show:
 *   - Total G-force (MOTORCYCLE_GFORCE)
 *   - Lateral G-force (MOTORCYCLE_GFORCE_LATERAL)
 *   - Longitudinal G-force (MOTORCYCLE_GFORCE_LONGITUDINAL)
 *
 * Color-coded:
 *   - Green: 0-0.5G (cruising)
 *   - Yellow: 0.5-1.0G (spirited riding)
 *   - Red: 1.0G+ (aggressive/track)
 */
class GForceWidget(
    mapActivity: MapActivity,
    widgetType: WidgetType,
    customId: String?,
    widgetsPanel: WidgetsPanel?
) : SimpleWidget(mapActivity, widgetType, customId, widgetsPanel) {

    private var lastTotalG = 0f

    init {
        setText(null)
        setSmallText("G")
    }

    override fun updateInfo(view: View) {
        val plugin = app.pluginsHelper.getPlugin(MotorcycleSensorsPlugin::class.java)
        if (plugin == null || !plugin.isActive) {
            setText("—")
            setSmallText("G")
            return
        }

        val gForceData = plugin.lastGForceData
        val totalG = gForceData?.totalG ?: 0f

        // Only update if changed significantly
        if (abs(totalG - lastTotalG) < 0.01f && lastTotalG != 0f) return
        lastTotalG = totalG

        // Format display based on widget type
        when (widgetType) {
            WidgetType.MOTORCYCLE_GFORCE_LATERAL -> {
                val lateralG = gForceData?.lateralG ?: 0f
                val absG = abs(lateralG)
                val direction = if (lateralG >= 0) "R" else "L"
                setText(String.format("%.2f", absG))
                setSmallText("G $direction")
            }
            WidgetType.MOTORCYCLE_GFORCE_LONGITUDINAL -> {
                val longG = gForceData?.longitudinalG ?: 0f
                val absG = abs(longG)
                val direction = if (longG >= 0) "ACC" else "BRK"
                setText(String.format("%.2f", absG))
                setSmallText("G $direction")
            }
            else -> {
                // Total G-force
                setText(String.format("%.2f", totalG))
                setSmallText("G")
            }
        }
    }

    override fun isMetricSystemDepended(): Boolean = false

    override fun getAdditionalState(): Int = 0
}
