package net.osmand.plus.plugins.motorcyclesensors

import android.app.Activity
import android.graphics.drawable.Drawable
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.github.mikephil.charting.charts.LineChart
import net.osmand.Location
import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.charts.GPXDataSetAxisType
import net.osmand.plus.charts.GPXDataSetType
import net.osmand.plus.charts.OrderedLineDataSet
import net.osmand.plus.plugins.OsmandPlugin
import net.osmand.plus.plugins.motorcyclesensors.sensors.DeviceSensorHelper
import net.osmand.plus.plugins.motorcyclesensors.sensors.GForceCalculator
import net.osmand.plus.plugins.motorcyclesensors.sensors.GForceData
import net.osmand.plus.plugins.motorcyclesensors.sensors.LeanAngleCalculator
import net.osmand.plus.plugins.motorcyclesensors.recording.MotorcycleSensorRecorder
import net.osmand.plus.plugins.motorcyclesensors.widgets.GForceWidget
import net.osmand.plus.plugins.motorcyclesensors.widgets.LeanAngleWidget
import net.osmand.plus.settings.backend.ApplicationMode
import net.osmand.plus.settings.backend.OsmandSettings
import net.osmand.plus.settings.backend.preferences.CommonPreference
import net.osmand.plus.settings.enums.ScreenLayoutMode
import net.osmand.plus.views.mapwidgets.MapWidgetInfo
import net.osmand.plus.views.mapwidgets.WidgetInfoCreator
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.widgets.MapWidget
import net.osmand.plus.widgets.ctxmenu.ContextMenuAdapter
import net.osmand.plus.widgets.ctxmenu.data.ContextMenuItem
import net.osmand.shared.gpx.GpxTrackAnalysis
import net.osmand.util.Algorithms
import org.json.JSONException
import org.json.JSONObject

/**
 * Motorcycle Sensors Plugin for OsmAnd
 *
 * Provides real-time motorcycle telemetry using device built-in sensors:
 * - Lean angle (using gyroscope + accelerometer fusion)
 * - G-force (using accelerometer with gravity compensation)
 * - Recording of sensor data to GPX track extensions
 * - Post-ride analysis with charts
 *
 * This is the FIRST open-source motorcycle lean angle + G-force plugin!
 *
 * Plugin ID: osmand.motorcycle.sensors
 */
class MotorcycleSensorsPlugin(app: OsmandApplication) : OsmandPlugin(app),
    DeviceSensorHelper.SensorDataListener {

    companion object {
        private val LOG = PlatformUtil.getLog(MotorcycleSensorsPlugin::class.java)
        const val PLUGIN_ID = "osmand.motorcycle.sensors"

        // GPX extension keys
        const val GPX_EXTENSION_LEAN_ANGLE = "lean_angle"
        const val GPX_EXTENSION_LATERAL_G = "lateral_g"
        const val GPX_EXTENSION_LONGITUDINAL_G = "longitudinal_g"
        const val GPX_EXTENSION_TOTAL_G = "total_g"
    }

    private val settings: OsmandSettings = app.settings

    // Sensor helpers
    val sensorHelper = DeviceSensorHelper(app)
    val leanAngleCalculator = LeanAngleCalculator()
    val gForceCalculator = GForceCalculator()
    val sensorRecorder = MotorcycleSensorRecorder(app)

    // Latest computed values
    var lastLeanAngleDeg: Float = 0f
        private set
    var lastGForceData: GForceData? = null
        private set

    // Preferences
    val RECORD_SENSOR_DATA = registerBooleanPreference("motorcycle_record_sensor_data", true)
        .makeProfile().cache() as CommonPreference<Boolean>

    val SHOW_LEAN_ANGLE_WIDGET = registerBooleanPreference("motorcycle_show_lean_angle", true)
        .makeProfile().cache() as CommonPreference<Boolean>

    val SHOW_GFORCE_WIDGET = registerBooleanPreference("motorcycle_show_gforce", true)
        .makeProfile().cache() as CommonPreference<Boolean>

    val ACCEL_FILTER_COEFFICIENT = registerFloatPreference("motorcycle_accel_filter", 0.8f)
        .makeProfile().cache() as CommonPreference<Float>

    val GYRO_FILTER_COEFFICIENT = registerFloatPreference("motorcycle_gyro_filter", 0.7f)
        .makeProfile().cache() as CommonPreference<Float>

    private var mapActivity: MapActivity? = null

    override fun getId(): String = PLUGIN_ID

    override fun getName(): String = app.getString(R.string.motorcycle_sensors_plugin_name)

    override fun getDescription(linksEnabled: Boolean): CharSequence =
        app.getString(R.string.motorcycle_sensors_plugin_description)

    override fun getLogoResourceId(): Int = R.drawable.ic_action_motorcycle_dark

    override fun getAssetResourceImage(): Drawable? =
        app.uiUtilities.getIcon(R.drawable.ic_action_motorcycle_dark)

    override fun isEnableByDefault(): Boolean = false

    override fun init(app: OsmandApplication, activity: Activity?): Boolean {
        if (sensorHelper.hasRequiredSensors()) {
            sensorHelper.addListener(this)
            sensorHelper.setAccelFilterCoefficient(ACCEL_FILTER_COEFFICIENT.get())
            sensorHelper.setGyroFilterCoefficient(GYRO_FILTER_COEFFICIENT.get())
            LOG.info("MotorcycleSensorsPlugin initialized - device has required sensors")
        } else {
            LOG.warn("MotorcycleSensorsPlugin: Device lacks required sensors (accelerometer + gyroscope)")
        }
        return true
    }

    override fun disable(app: OsmandApplication) {
        super.disable(app)
        sensorHelper.unregisterListeners()
        sensorRecorder.stopRecording()
        LOG.info("MotorcycleSensorsPlugin disabled")
    }

    // ===== Sensor Data Listener =====

    override fun onSensorDataUpdated(
        accelX: Float, accelY: Float, accelZ: Float,
        gyroX: Float, gyroY: Float, gyroZ: Float,
        roll: Float, pitch: Float, yaw: Float,
        timestamp: Long
    ) {
        // Calculate lean angle
        lastLeanAngleDeg = leanAngleCalculator.calculateLeanAngle(
            accelX, accelY, accelZ, gyroX, roll, timestamp
        )

        // Calculate G-force
        lastGForceData = gForceCalculator.calculateGForce(
            accelX, accelY, accelZ, roll, pitch
        )

        // Record to GPX if trip recording is active
        if (RECORD_SENSOR_DATA.get() && sensorRecorder.isRecording) {
            sensorRecorder.recordSensorData(
                leanAngleDeg = lastLeanAngleDeg,
                gForceData = lastGForceData,
                timestamp = timestamp
            )
        }
    }

    // ===== Plugin Lifecycle =====

    override fun mapActivityCreate(activity: MapActivity) {
        mapActivity = activity
    }

    override fun mapActivityResume(activity: MapActivity) {
        mapActivity = activity
        if (isActive && sensorHelper.hasRequiredSensors()) {
            sensorHelper.registerListeners()
        }
    }

    override fun mapActivityPause(activity: MapActivity) {
        sensorHelper.unregisterListeners()
    }

    override fun mapActivityDestroy(activity: MapActivity) {
        sensorHelper.unregisterListeners()
        sensorHelper.destroy()
        mapActivity = null
    }

    // ===== Widgets =====

    override fun createWidgets(
        mapActivity: MapActivity,
        widgetsInfos: MutableList<MapWidgetInfo>,
        appMode: ApplicationMode,
        layoutMode: ScreenLayoutMode?
    ) {
        val creator = WidgetInfoCreator(app, appMode, layoutMode)

        // Lean Angle Widget
        val leanAngleWidget = LeanAngleWidget(
            mapActivity, WidgetType.MOTORCYCLE_LEAN_ANGLE, null, WidgetsPanel.RIGHT
        )
        widgetsInfos.add(creator.createWidgetInfo(leanAngleWidget))

        // Total G-Force Widget
        val gForceWidget = GForceWidget(
            mapActivity, WidgetType.MOTORCYCLE_GFORCE, null, WidgetsPanel.RIGHT
        )
        widgetsInfos.add(creator.createWidgetInfo(gForceWidget))

        // Lateral G-Force Widget
        val lateralGWidget = GForceWidget(
            mapActivity, WidgetType.MOTORCYCLE_GFORCE_LATERAL, null, WidgetsPanel.RIGHT
        )
        widgetsInfos.add(creator.createWidgetInfo(lateralGWidget))

        // Longitudinal G-Force Widget
        val longitudinalGWidget = GForceWidget(
            mapActivity, WidgetType.MOTORCYCLE_GFORCE_LONGITUDINAL, null, WidgetsPanel.RIGHT
        )
        widgetsInfos.add(creator.createWidgetInfo(longitudinalGWidget))
    }

    override fun createMapWidgetForParams(
        mapActivity: MapActivity,
        widgetType: WidgetType,
        customId: String?,
        widgetsPanel: WidgetsPanel?
    ): MapWidget? {
        return when (widgetType) {
            WidgetType.MOTORCYCLE_LEAN_ANGLE ->
                LeanAngleWidget(mapActivity, widgetType, customId, widgetsPanel)
            WidgetType.MOTORCYCLE_GFORCE,
            WidgetType.MOTORCYCLE_GFORCE_LATERAL,
            WidgetType.MOTORCYCLE_GFORCE_LONGITUDINAL ->
                GForceWidget(mapActivity, widgetType, customId, widgetsPanel)
            else -> null
        }
    }

    // ===== GPX Track Recording =====

    override fun attachAdditionalInfoToRecordedTrack(
        location: @NonNull Location,
        json: @NonNull JSONObject
    ) {
        if (!isActive || !RECORD_SENSOR_DATA.get()) return

        try {
            val leanAngle = lastLeanAngleDeg
            val gForce = lastGForceData

            if (Math.abs(leanAngle) > 0.1f) {
                json.put(GPX_EXTENSION_LEAN_ANGLE, String.format("%.1f", leanAngle))
            }

            gForce?.let { g ->
                if (g.totalG > 0.05f) {
                    json.put(GPX_EXTENSION_TOTAL_G, String.format("%.3f", g.totalG))
                    json.put(GPX_EXTENSION_LATERAL_G, String.format("%.3f", g.lateralG))
                    json.put(GPX_EXTENSION_LONGITUDINAL_G, String.format("%.3f", g.longitudinalG))
                }
            }
        } catch (e: JSONException) {
            LOG.error("Error attaching motorcycle sensor data to track", e)
        }
    }

    // ===== Menu Items =====

    override fun registerOptionsMenuItems(
        mapActivity: MapActivity,
        helper: ContextMenuAdapter
    ) {
        // Future: Add motorcycle-specific menu items
    }

    // ===== Added App Modes =====

    override fun getAddedAppModes(): List<ApplicationMode> {
        return listOf(ApplicationMode.MOTORCYCLE)
    }

    // ===== Charts / Analysis =====

    override fun getAvailableGPXDataSetTypes(
        analysis: @NonNull GpxTrackAnalysis,
        availableTypes: @NonNull MutableList<GPXDataSetType>
    ) {
        // Add motorcycle sensor chart types if data exists
        val hasLeanAngleData = analysis.analysisExtensionFields?.contains(GPX_EXTENSION_LEAN_ANGLE) == true
        val hasGForceData = analysis.analysisExtensionFields?.contains(GPX_EXTENSION_TOTAL_G) == true

        if (hasLeanAngleData) {
            availableTypes.add(GPXDataSetType.MOTORCYCLE_LEAN_ANGLE)
        }
        if (hasGForceData) {
            availableTypes.add(GPXDataSetType.MOTORCYCLE_GFORCE)
        }
    }

    override fun getOrderedLineDataSet(
        chart: @NonNull LineChart,
        analysis: @NonNull GpxTrackAnalysis,
        graphType: @NonNull GPXDataSetType,
        axisType: @NonNull GPXDataSetAxisType,
        calcWithoutGaps: Boolean,
        useRightAxis: Boolean
    ): OrderedLineDataSet? {
        // TODO: Implement chart data creation for lean angle and G-force graphs
        return null
    }

    /**
     * Check if this device supports motorcycle sensor features
     */
    fun isDeviceSupported(): Boolean = sensorHelper.hasRequiredSensors()

    /**
     * Start sensor recording for current trip
     */
    fun startRecording() {
        sensorRecorder.startRecording()
        if (isActive && !sensorHelper.isRunning()) {
            sensorHelper.registerListeners()
        }
    }

    /**
     * Stop sensor recording for current trip
     */
    fun stopRecording() {
        sensorRecorder.stopRecording()
    }

    /**
     * Reset peak values for current ride
     */
    fun resetRideData() {
        leanAngleCalculator.reset()
        gForceCalculator.resetPeaks()
        sensorRecorder.reset()
    }
}
