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
import net.osmand.plus.charts.ChartUtils
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
import net.osmand.plus.plugins.motorcyclesensors.routing.CurvyRoadRouter
import net.osmand.plus.plugins.motorcyclesensors.routing.MotorcycleRoutingHelper
import net.osmand.plus.plugins.motorcyclesensors.routing.RouteCurvinessStats
import net.osmand.plus.plugins.motorcyclesensors.safety.CrashAlertDialog
import net.osmand.plus.plugins.motorcyclesensors.safety.CrashDetectionHelper
import net.osmand.plus.plugins.motorcyclesensors.safety.CrashEventLog
import net.osmand.plus.plugins.motorcyclesensors.instrumentation.SensorDiagnosticsHelper
import net.osmand.plus.plugins.motorcyclesensors.calibration.SensorCalibrationHelper
import net.osmand.plus.plugins.motorcyclesensors.routing.RoutingSanityGuard
import net.osmand.plus.plugins.motorcyclesensors.weather.WeatherRoutingHelper
import net.osmand.plus.plugins.motorcyclesensors.trackday.TrackDayHelper
import net.osmand.plus.plugins.motorcyclesensors.group.GroupRidingHelper
import net.osmand.plus.routing.RoutingHelper
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
    val curvyRoadRouter = CurvyRoadRouter()
    val routingHelper = MotorcycleRoutingHelper(app)
    val crashDetection = CrashDetectionHelper()

    // Production-grade instrumentation
    val diagnostics = SensorDiagnosticsHelper()
    val calibration = SensorCalibrationHelper(app.settings)
    val routingSanityGuard = RoutingSanityGuard(app)

    // Future features (architecture ready)
    val weatherRouting = WeatherRoutingHelper()
    val trackDay = TrackDayHelper()
    val groupRiding = GroupRidingHelper()

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

    val PREFER_CURVY_ROADS = registerBooleanPreference("motorcycle_curvy_roads", true)
        .makeProfile().cache() as CommonPreference<Boolean>

    val AVOID_MOTORWAY = registerBooleanPreference("motorcycle_avoid_motorway", true)
        .makeProfile().cache() as CommonPreference<Boolean>

    val CRASH_DETECTION_ENABLED = registerBooleanPreference("motorcycle_crash_detection", true)
        .makeProfile().cache() as CommonPreference<Boolean>

    val AUTO_START_RECORDING = registerBooleanPreference("motorcycle_auto_start_recording", true)
        .makeProfile().cache() as CommonPreference<Boolean>

    val CRASH_SENSITIVITY = registerIntPreference("motorcycle_crash_sensitivity", 2)
        .makeProfile().cache() as CommonPreference<Int>

    val EMERGENCY_CONTACT_NUMBER = registerStringPreference("motorcycle_emergency_contact", "")
        .makeProfile().cache() as CommonPreference<String>

    val EMERGENCY_CONTACT_2 = registerStringPreference("motorcycle_emergency_contact_2", "")
        .makeProfile().cache() as CommonPreference<String>

    val EMERGENCY_CONTACT_3 = registerStringPreference("motorcycle_emergency_contact_3", "")
        .makeProfile().cache() as CommonPreference<String>

    /**
     * Get all configured emergency contact numbers (non-empty only).
     */
    fun getEmergencyContacts(): List<String> {
        return listOf(
            EMERGENCY_CONTACT_NUMBER.get(),
            EMERGENCY_CONTACT_2.get(),
            EMERGENCY_CONTACT_3.get()
        ).filter { !it.isNullOrEmpty() }
    }

    // Last route curviness stats (updated when new route is calculated)
    var lastRouteCurvinessStats: RouteCurvinessStats? = null
        private set

    private var mapActivity: MapActivity? = null

    override fun getId(): String = PLUGIN_ID

    override fun getName(): String = app.getString(R.string.motorcycle_sensors_plugin_name)

    override fun getDescription(linksEnabled: Boolean): CharSequence =
        app.getString(R.string.motorcycle_sensors_plugin_description)

    override fun getLogoResourceId(): Int = R.drawable.ic_action_motorcycle_dark

    override fun getAssetResourceImage(): Drawable? =
        app.uiUtilities.getIcon(R.drawable.ic_action_motorcycle_dark)

    override fun isEnableByDefault(): Boolean = false

    override fun getSettingsScreenType(): net.osmand.plus.settings.fragments.SettingsScreenType {
        return net.osmand.plus.settings.fragments.SettingsScreenType.MOTORCYCLE_SENSORS_SETTINGS
    }

    override fun init(app: OsmandApplication, activity: Activity?): Boolean {
        if (sensorHelper.hasRequiredSensors()) {
            sensorHelper.addListener(this)
            sensorHelper.setAccelFilterCoefficient(ACCEL_FILTER_COEFFICIENT.get())
            sensorHelper.setGyroFilterCoefficient(GYRO_FILTER_COEFFICIENT.get())
            LOG.info("MotorcycleSensorsPlugin initialized - device has required sensors")
        } else {
            LOG.warn("MotorcycleSensorsPlugin: Device lacks required sensors (accelerometer + gyroscope)")
        }
        crashDetection.setSensitivity(CRASH_SENSITIVITY.get())

        // Load saved calibration
        calibration.loadCalibrationFromPreferences()

        // Setup crash detection listener for UI flow
        crashDetection.addListener(object : CrashDetectionHelper.CrashDetectionListener {
            override fun onCrashDetected(location: Location?, gForceAtImpact: Float, rotationRateAtImpact: Float) {
                handleCrashDetected(location, gForceAtImpact, rotationRateAtImpact)
            }
            override fun onPotentialCrash(gForce: Float, reason: String) {
                diagnostics.logEvent("POTENTIAL_CRASH", reason, mapOf("gForce" to gForce))
            }
        })

        // Apply motorcycle routing preferences on init
        applyMotorcycleRoutingPrefs()
        return true
    }

    override fun disable(app: OsmandApplication) {
        super.disable(app)
        sensorHelper.unregisterListeners()
        sensorRecorder.stopRecording()
        crashDetection.destroy()
        // Stop diagnostics and save report if collecting
        if (diagnostics.isCollectingVisible) {
            diagnostics.stopCollection()
        }
        LOG.info("MotorcycleSensorsPlugin disabled")
    }

    // ===== Sensor Data Listener =====

    override fun onSensorDataUpdated(
        accelX: Float, accelY: Float, accelZ: Float,
        gyroX: Float, gyroY: Float, gyroZ: Float,
        roll: Float, pitch: Float, yaw: Float,
        timestamp: Long
    ) {
        // Apply gyro bias correction from calibration
        val correctedGyro = calibration.correctGyroBias(gyroX, gyroY, gyroZ)

        // Calculate lean angle with corrected gyro
        lastLeanAngleDeg = leanAngleCalculator.calculateLeanAngle(
            accelX, accelY, accelZ, correctedGyro[0], roll, timestamp
        )

        // Apply lean bias correction from calibration
        lastLeanAngleDeg = calibration.correctLeanAngle(lastLeanAngleDeg)

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

        // Feed sensor data to crash detection
        if (isActive && CRASH_DETECTION_ENABLED.get()) {
            crashDetection.updateSensorData(
                accelX, accelY, accelZ,
                correctedGyro[0], correctedGyro[1], correctedGyro[2],
                timestamp
            )
        }

        // Feed data to calibration if calibrating
        if (calibration.getState() == SensorCalibrationHelper.CalibrationState.COLLECTING) {
            calibration.updateSensorData(
                accelX, accelY, accelZ,
                gyroX, gyroY, gyroZ,
                roll, pitch, timestamp
            )
        }

        // Feed data to diagnostics
        diagnostics.updateSensorData(
            accelX, accelY, accelZ,
            gyroX, gyroY, gyroZ,
            lastLeanAngleDeg,
            Math.toDegrees(roll.toDouble()).toFloat(),
            leanAngleCalculator.getCurrentLeanAngle() + calibration.getCalibration().leanBiasDeg, // uncorrected for comparison
            leanAngleCalculator.getGyroIntegratedDeg(),
            timestamp
        )
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
        crashDetection.destroy()
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
        // Check if motorcycle sensor data exists in the track's point attributes
        val pointAttributes = analysis.pointAttributes
        var hasLeanAngle = false
        var hasTotalG = false

        for (attr in pointAttributes) {
            if (!attr.leanAngle.isNaN()) hasLeanAngle = true
            if (!attr.totalG.isNaN()) hasTotalG = true
            if (hasLeanAngle && hasTotalG) break
        }

        if (hasLeanAngle) {
            availableTypes.add(GPXDataSetType.MOTORCYCLE_LEAN_ANGLE)
        }
        if (hasTotalG) {
            availableTypes.add(GPXDataSetType.MOTORCYCLE_TOTAL_G)
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
        if (graphType != GPXDataSetType.MOTORCYCLE_LEAN_ANGLE && graphType != GPXDataSetType.MOTORCYCLE_TOTAL_G) {
            return null
        }

        val divX = ChartUtils.getDivX(app, chart, analysis, axisType, calcWithoutGaps)
        val mainUnitY = graphType.getMainUnitY(app)

        val textColor = net.osmand.plus.utils.ColorUtilities.getColor(app, graphType.textColorId)
        val yAxis = ChartUtils.getYAxis(chart, textColor, useRightAxis)
        yAxis.granularity = 1f
        yAxis.resetAxisMinimum()

        val dataKey = graphType.dataKey
        val values = ChartUtils.getPointAttributeValues(
            dataKey, analysis.pointAttributes, axisType, divX,
            Float.NaN, Float.NaN, calcWithoutGaps
        )

        if (values.isEmpty()) return null

        val dataSet = OrderedLineDataSet(values, "", graphType, axisType, !useRightAxis)

        // Calculate priority based on max absolute value
        val maxVal = values.maxOfOrNull { Math.abs(it.y) } ?: 0f
        dataSet.priority = maxVal
        dataSet.divX = divX
        dataSet.units = mainUnitY

        val formatY = if (graphType == GPXDataSetType.MOTORCYCLE_LEAN_ANGLE) {
            "{0,number,0.#} "
        } else {
            "{0,number,0.00} "
        }
        yAxis.valueFormatter = com.github.mikephil.charting.formatter.IAxisValueFormatter { value, axis ->
            String.format(formatY.trimEnd() + mainUnitY, value)
        }

        val nightMode = app.daynightHelper.isNightMode(net.osmand.plus.settings.enums.ThemeUsageContext.APP)
        val color = net.osmand.plus.utils.ColorUtilities.getColor(app, graphType.fillColorId)
        ChartUtils.setupDataSet(app, dataSet, color, color, true, false, useRightAxis, nightMode)

        return dataSet
    }

    /**
     * Auto-start sensor recording when MOTORCYCLE mode is active and trip recording starts.
     * Also feeds location data to crash detection.
     */
    override fun updateLocation(location: Location) {
        // Auto-start recording when in motorcycle mode with active plugin
        if (isActive && RECORD_SENSOR_DATA.get() && AUTO_START_RECORDING.get() && !sensorRecorder.isRecording) {
            val isMotorcycleMode = settings.applicationMode == ApplicationMode.MOTORCYCLE
            if (isMotorcycleMode && location.speed > 0.5f) {
                startRecording()
            }
        }

        // Feed location to crash detection
        if (isActive && CRASH_DETECTION_ENABLED.get()) {
            crashDetection.updateLocation(location)
        }

        // Feed GPS to calibration
        if (calibration.getState() == SensorCalibrationHelper.CalibrationState.COLLECTING) {
            calibration.updateGpsSpeed(location.speed)
        }

        // Feed GPS to diagnostics
        diagnostics.updateGpsData(location)

        // Feed GPS to Track Day mode
        if (trackDay.getState() == TrackDayHelper.SessionState.RACING ||
            trackDay.getState() == TrackDayHelper.SessionState.WAITING) {
            trackDay.updateLocation(location)
        }

        // Feed GPS to Group Riding
        if (groupRiding.getState() == GroupRidingHelper.GroupState.CONNECTED) {
            groupRiding.updateMyLocation(location)
        }
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
        diagnostics.startCollection()
        if (isActive && !sensorHelper.isRunning()) {
            sensorHelper.registerListeners()
        }
    }

    /**
     * Stop sensor recording for current trip
     */
    fun stopRecording() {
        sensorRecorder.stopRecording()
        // Stop diagnostics and write report
        diagnostics.stopCollection()
        // Show ride summary if we have a map activity
        mapActivity?.let { activity ->
            if (sensorRecorder.getRideStatistics().dataPointCount > 0) {
                val dialog = MotorcycleRideSummaryDialog()
                dialog.show(activity.supportFragmentManager)
            }
        }
    }

    /**
     * Reset peak values for current ride
     */
    fun resetRideData() {
        leanAngleCalculator.reset()
        gForceCalculator.resetPeaks()
        sensorRecorder.reset()
    }

    /**
     * Get route curviness stats for the current route
     */
    fun getRouteCurvinessStats(): RouteCurvinessStats? {
        val osmandRoutingHelper = app.routingHelper
        val route = osmandRoutingHelper?.route ?: return lastRouteCurvinessStats
        return routingHelper.analyzeRouteCurviness(route)
    }

    // ===== Routing Integration =====

    /**
     * Called when OsmAnd finishes calculating a new route.
     *
     * We use this to:
     * 1. Analyze route curviness and compute Fun Score
     * 2. Sync motorcycle routing preferences with OsmAnd routing parameters
     */
    override fun newRouteIsCalculated(newRoute: Boolean) {
        if (!isActive) return

        val osmandRoutingHelper = app.routingHelper
        val route = osmandRoutingHelper?.route
        if (route != null && route.isCalculated) {
            // Analyze route curviness
            lastRouteCurvinessStats = routingHelper.analyzeRouteCurviness(route)
            lastRouteCurvinessStats?.let { stats ->
                val funScore = curvyRoadRouter.calculateFunScore(stats)
                LOG.info("Route calculated - Curviness: ${stats.classification.displayName}, " +
                    "Fun Score: $funScore/100, " +
                    "Corners: ${stats.totalCorners}, " +
                    "Corners/km: ${"%.1f".format(stats.cornersPerKm)}")
            }

            // Run routing sanity guard when curvy routing is enabled
            if (PREFER_CURVY_ROADS.get()) {
                val sanityResult = routingSanityGuard.quickSanityCheck(route)
                if (!sanityResult.isAcceptable) {
                    LOG.warn("RoutingSanityGuard: Curvy route failed sanity - ${sanityResult.fallbackReason}")
                    diagnostics.logEvent("ROUTING_SANITY_FAIL", sanityResult.fallbackReason ?: "Unknown reason")
                    // Note: We don't automatically fallback here because that would require
                    // recalculating the route. The sanity result is available for UI to act on.
                }
            }
        }
    }

    /**
     * Apply motorcycle-specific routing preferences when in MOTORCYCLE mode.
     *
     * This syncs plugin preferences (PREFER_CURVY_ROADS, AVOID_MOTORWAY)
     * with OsmAnd's native routing parameters via OsmandSettings.
     *
     * OsmAnd's RouteProvider.initOsmAndRoutingConfig() reads routing parameters
     * from OsmandSettings and passes them to GeneralRouter. By setting the
     * appropriate routing boolean properties, we influence route calculation.
     */
    fun applyMotorcycleRoutingPrefs() {
        if (!isActive) return
        val mode = ApplicationMode.MOTORCYCLE

        // Sync AVOID_MOTORWAY with OsmAnd's native routing parameter
        val avoidMotorwayPref = settings.getCustomRoutingBooleanProperty(
            net.osmand.router.GeneralRouter.AVOID_MOTORWAY, false)
        if (AVOID_MOTORWAY.get()) {
            avoidMotorwayPref.setModeValue(mode, true)
        }

        // Sync PREFER_CURVY_ROADS: use "short_way" preference to prefer
        // shorter (typically more winding) routes over fast straight highways.
        // This is the key trick: "short_way" mode in OsmAnd prefers distance
        // over speed, which naturally favors curvy secondary roads.
        val shortWayPref = settings.getCustomRoutingBooleanProperty(
            net.osmand.router.GeneralRouter.USE_SHORTEST_WAY, false)
        if (PREFER_CURVY_ROADS.get()) {
            // When curvy roads is ON, disable fast route mode (prefer shorter/winding routes)
            settings.FAST_ROUTE_MODE.setModeValue(mode, false)
        } else {
            // When curvy roads is OFF, use normal fast routing
            settings.FAST_ROUTE_MODE.setModeValue(mode, true)
        }
    }

    /**
     * Check if crash has been detected
     */
    fun isCrashDetected(): Boolean = crashDetection.isCrashDetected()

    /**
     * Acknowledge crash (user is okay)
     */
    fun acknowledgeCrash() {
        crashDetection.reset()
    }

    // ===== Crash Alert UI Flow =====

    /**
     * Handle crash detection by showing the full-screen crash alert dialog.
     *
     * This is the ONLY response to a crash detection:
     * 1. Show full-screen red warning
     * 2. Start 10-second countdown
     * 3. If user presses "I'M OK" -> log as false alarm, reset
     * 4. If countdown expires -> log as confirmed crash event
     * NO SMS, NO emergency calls - that requires explicit user permissions.
     */
    private fun handleCrashDetected(
        location: Location?,
        gForceAtImpact: Float,
        rotationRateAtImpact: Float
    ) {
        LOG.error("CRASH DETECTED - Showing alert dialog (G=${"%.2f".format(gForceAtImpact)}, " +
            "rot=${"%.2f".format(rotationRateAtImpact)}rad/s)")

        // Log to diagnostics
        diagnostics.logEvent("CRASH_DETECTED",
            "G=${"%.2f".format(gForceAtImpact)}, rot=${"%.2f".format(rotationRateAtImpact)}rad/s",
            mapOf("gForce" to gForceAtImpact, "rotation" to rotationRateAtImpact))

        // Show crash alert dialog on UI thread
        mapActivity?.runOnUiThread {
            mapActivity?.let { activity ->
                val speedMs = location?.speed ?: 0f
                val dialog = CrashAlertDialog.newInstance(
                    gForceAtImpact, rotationRateAtImpact, location, speedMs
                )
                dialog.setCrashEventListener(object : CrashAlertDialog.CrashEventListener {
                    override fun onCrashEventLogged(event: net.osmand.plus.plugins.motorcyclesensors.safety.CrashEventLog.CrashEvent) {
                        LOG.warn("Crash countdown expired - event logged")
                        diagnostics.logEvent("CRASH_COUNTDOWN_EXPIRED", "User did not respond")
                    }
                    override fun onCrashCancelled(event: net.osmand.plus.plugins.motorcyclesensors.safety.CrashEventLog.CrashEvent) {
                        LOG.info("Crash alert cancelled - user is OK")
                        diagnostics.logCrashFalseTrigger(
                            "User cancelled countdown",
                            gForceAtImpact, rotationRateAtImpact
                        )
                        crashDetection.reset()
                    }
                })

                try {
                    dialog.show(activity.supportFragmentManager, CrashAlertDialog.TAG)
                } catch (e: Exception) {
                    LOG.error("Failed to show crash alert dialog", e)
                }
            }
        }
    }

    // ===== Calibration API =====

    /**
     * Start sensor calibration ride.
     */
    fun startCalibration(): net.osmand.plus.plugins.motorcyclesensors.calibration.CalibrationResult {
        return calibration.startCalibration()
    }

    /**
     * Get calibration progress (0.0 to 1.0).
     */
    fun getCalibrationProgress(): Float = calibration.getCalibrationProgress()

    /**
     * Get current calibration data.
     */
    fun getCalibrationData() = calibration.getCalibration()

    // ===== Routing Sanity API =====

    /**
     * Get the last routing sanity check result.
     */
    var lastSanityCheckResult: net.osmand.plus.plugins.motorcyclesensors.routing.RoutingSanityGuard.SanityCheckResult? = null
        private set

    /**
     * Get crash event statistics.
     */
    fun getCrashStatistics(): net.osmand.plus.plugins.motorcyclesensors.safety.CrashStatistics {
        return CrashEventLog.getStatistics(app)
    }
}
