package net.osmand.plus.plugins.motorcyclesensors.widgets

import android.view.View
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.motorcyclesensors.obd2.OBD2DataStore
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.widgets.SimpleWidget

/**
 * Widget that displays OBD2 engine data on the map screen.
 *
 * Supports multiple display modes via WidgetType:
 * - MOTORCYCLE_OBD2_RPM: Engine RPM
 * - MOTORCYCLE_OBD2_GEAR: Current gear position
 * - MOTORCYCLE_OBD2_TEMP: Engine coolant temperature
 * - MOTORCYCLE_OBD2_THROTTLE: Throttle position percentage
 *
 * Color-coded:
 *   RPM: White (normal) → Yellow (high) → Red (redline)
 *   Temp: White (normal) → Yellow (warm) → Red (overheating)
 *   Throttle: White → Yellow (high) → Red (wide open)
 *
 * Requires Bluetooth OBD2 adapter connection via OBD2Helper.
 */
class OBD2Widget(
    mapActivity: MapActivity,
    widgetType: WidgetType,
    customId: String?,
    widgetsPanel: WidgetsPanel?
) : SimpleWidget(mapActivity, widgetType, customId, widgetsPanel) {

    private val dataStore = OBD2DataStore.getInstance()

    init {
        when (widgetType) {
            WidgetType.MOTORCYCLE_OBD2_RPM -> {
                setText(null)
                setSmallText("RPM")
            }
            WidgetType.MOTORCYCLE_OBD2_GEAR -> {
                setText(null)
                setSmallText("GEAR")
            }
            WidgetType.MOTORCYCLE_OBD2_TEMP -> {
                setText(null)
                setSmallText("°C")
            }
            WidgetType.MOTORCYCLE_OBD2_THROTTLE -> {
                setText(null)
                setSmallText("%")
            }
            else -> {
                setText(null)
                setSmallText("")
            }
        }
    }

    override fun updateInfo(view: View) {
        if (dataStore.isStale() || !dataStore.isConnected) {
            when (widgetType) {
                WidgetType.MOTORCYCLE_OBD2_RPM -> {
                    setText("--")
                    setSmallText("RPM")
                }
                WidgetType.MOTORCYCLE_OBD2_GEAR -> {
                    setText("--")
                    setSmallText("GEAR")
                }
                WidgetType.MOTORCYCLE_OBD2_TEMP -> {
                    setText("--")
                    setSmallText("°C")
                }
                WidgetType.MOTORCYCLE_OBD2_THROTTLE -> {
                    setText("--")
                    setSmallText("%")
                }
                else -> {}
            }
            return
        }

        when (widgetType) {
            WidgetType.MOTORCYCLE_OBD2_RPM -> updateRPM()
            WidgetType.MOTORCYCLE_OBD2_GEAR -> updateGear()
            WidgetType.MOTORCYCLE_OBD2_TEMP -> updateTemp()
            WidgetType.MOTORCYCLE_OBD2_THROTTLE -> updateThrottle()
            else -> {}
        }
    }

    private fun updateRPM() {
        val rpm = dataStore.rpm
        setText(rpm.toString())
        setSmallText("RPM")
    }

    private fun updateGear() {
        val gear = dataStore.calculateGear(dataStore.rpm, dataStore.speedKmh)
        dataStore.currentGear = gear
        setText(dataStore.getGearDisplay())
        setSmallText("GEAR")
    }

    private fun updateTemp() {
        val temp = dataStore.engineCoolantTemp
        setText(temp.toString())
        setSmallText("°C")
    }

    private fun updateThrottle() {
        val throttle = dataStore.throttlePosition
        setText(String.format("%.0f", throttle))
        setSmallText("%")
    }

    override fun isMetricSystemDepended(): Boolean = false

    override fun getAdditionalState(): Int = 0
}
