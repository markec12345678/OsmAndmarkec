package net.osmand.plus.plugins.motorcyclesensors.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Helper class that reads raw accelerometer and gyroscope data from device sensors.
 * Provides filtered sensor data to LeanAngleCalculator and GForceCalculator.
 *
 * This is a standalone sensor reader separate from OsmAndLocationProvider's compass logic,
 * because we need raw sensor values for lean angle / G-force computation, not just heading.
 */
class DeviceSensorHelper(private val app: OsmandApplication) : SensorEventListener {

    companion object {
        private val LOG = PlatformUtil.getLog(DeviceSensorHelper::class.java)
        private const val SENSOR_UPDATE_INTERVAL_MS = 20L // ~50Hz for smooth tracking
        private const val GRAVITY_EARTH = 9.80665f // m/s^2
    }

    private val sensorManager: SensorManager =
        app.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var rotationVector: Sensor? = null
    private var gameRotationVector: Sensor? = null

    private var isRegistered = false
    private var lastSensorUpdateTime = 0L

    // Raw sensor data
    private val accelerometerData = FloatArray(3)
    private val gyroscopeData = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Filtered accelerometer data (low-pass filter to remove vibrations)
    private val filteredAccel = FloatArray(3)
    private val filteredGyro = FloatArray(3)

    // Low-pass filter coefficient (0 = no filter, 1 = full filter)
    private var alphaAccel = 0.8f
    private var alphaGyro = 0.7f

    // Timestamp for gyroscope integration
    private var lastGyroTimestampNs: Long = 0

    // Listeners for sensor data updates
    private val listeners = CopyOnWriteArrayList<SensorDataListener>()

    // Background executor for sensor processing
    private val sensorExecutor = Executors.newSingleThreadExecutor()

    interface SensorDataListener {
        fun onSensorDataUpdated(
            accelX: Float, accelY: Float, accelZ: Float,
            gyroX: Float, gyroY: Float, gyroZ: Float,
            roll: Float, pitch: Float, yaw: Float,
            timestamp: Long
        )
    }

    /**
     * Check if the device has the required sensors for motorcycle tracking
     */
    fun hasRequiredSensors(): Boolean {
        return hasAccelerometer() && (hasGyroscope() || hasRotationVector())
    }

    fun hasAccelerometer(): Boolean {
        return sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    }

    fun hasGyroscope(): Boolean {
        return sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    }

    fun hasRotationVector(): Boolean {
        return sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
                sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null
    }

    /**
     * Register sensor listeners. Call when the motorcycle profile is active and plugin is enabled.
     */
    fun registerListeners() {
        if (isRegistered) return

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        gameRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        var registered = false

        // Register accelerometer - required for G-force
        accelerometer?.let {
            registered = sensorManager.registerListener(
                this, it, SensorManager.SENSOR_DELAY_GAME
            ) || registered
        }

        // Register gyroscope - required for lean angle
        gyroscope?.let {
            registered = sensorManager.registerListener(
                this, it, SensorManager.SENSOR_DELAY_GAME
            ) || registered
        }

        // Register rotation vector as fallback for orientation
        // Prefer game rotation vector (no magnetometer drift)
        val rvSensor = gameRotationVector ?: rotationVector
        rvSensor?.let {
            registered = sensorManager.registerListener(
                this, it, SensorManager.SENSOR_DELAY_GAME
            ) || registered
        }

        isRegistered = registered
        lastGyroTimestampNs = 0
        LOG.info("MotorcycleSensors: registerListeners() - registered=$registered")
    }

    /**
     * Unregister sensor listeners. Call when plugin is disabled or app goes to background.
     */
    fun unregisterListeners() {
        if (!isRegistered) return
        sensorManager.unregisterListener(this)
        isRegistered = false
        lastGyroTimestampNs = 0
        LOG.info("MotorcycleSensors: unregisterListeners()")
    }

    fun addListener(listener: SensorDataListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: SensorDataListener) {
        listeners.remove(listener)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Throttle updates
        val now = SystemClock.elapsedRealtime()
        if (now - lastSensorUpdateTime < SENSOR_UPDATE_INTERVAL_MS) return
        lastSensorUpdateTime = now

        val sensorType = event.sensor.type
        val values = event.values.clone()

        // Process on background thread
        sensorExecutor.execute {
            processSensorEvent(sensorType, values, event.timestamp)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    private fun processSensorEvent(sensorType: Int, values: FloatArray, timestampNs: Long) {
        when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Apply low-pass filter to reduce vibration noise
                filteredAccel[0] = alphaAccel * filteredAccel[0] + (1 - alphaAccel) * values[0]
                filteredAccel[1] = alphaAccel * filteredAccel[1] + (1 - alphaAccel) * values[1]
                filteredAccel[2] = alphaAccel * filteredAccel[2] + (1 - alphaAccel) * values[2]
                System.arraycopy(values, 0, accelerometerData, 0, 3)
            }
            Sensor.TYPE_GYROSCOPE -> {
                // Apply low-pass filter
                filteredGyro[0] = alphaGyro * filteredGyro[0] + (1 - alphaGyro) * values[0]
                filteredGyro[1] = alphaGyro * filteredGyro[1] + (1 - alphaGyro) * values[1]
                filteredGyro[2] = alphaGyro * filteredGyro[2] + (1 - alphaGyro) * values[2]
                System.arraycopy(values, 0, gyroscopeData, 0, 3)
                lastGyroTimestampNs = timestampNs
            }
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                // Calculate orientation from rotation vector
                SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
            }
        }

        // Notify listeners with filtered data
        notifyListeners(timestampNs)
    }

    private fun notifyListeners(timestampNs: Long) {
        val roll = orientationAngles[2] // Roll angle (lean)
        val pitch = orientationAngles[1] // Pitch angle
        val yaw = orientationAngles[0] // Yaw (heading)

        for (listener in listeners) {
            listener.onSensorDataUpdated(
                filteredAccel[0], filteredAccel[1], filteredAccel[2],
                filteredGyro[0], filteredGyro[1], filteredGyro[2],
                roll, pitch, yaw,
                timestampNs
            )
        }
    }

    /**
     * Get current filtered accelerometer magnitude (total G-force)
     */
    fun getFilteredAccelMagnitude(): Float {
        val x = filteredAccel[0]
        val y = filteredAccel[1]
        val z = filteredAccel[2]
        return Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
    }

    /**
     * Set the low-pass filter coefficient for accelerometer data.
     * Higher values = smoother but slower response.
     * Typical: 0.7-0.9 for motorcycle use
     */
    fun setAccelFilterCoefficient(alpha: Float) {
        alphaAccel = alpha.coerceIn(0f, 1f)
    }

    fun setGyroFilterCoefficient(alpha: Float) {
        alphaGyro = alpha.coerceIn(0f, 1f)
    }

    fun isRunning(): Boolean = isRegistered

    fun destroy() {
        unregisterListeners()
        listeners.clear()
        sensorExecutor.shutdown()
    }
}
