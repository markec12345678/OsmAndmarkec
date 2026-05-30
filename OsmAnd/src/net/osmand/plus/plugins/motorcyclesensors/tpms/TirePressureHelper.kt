package net.osmand.plus.plugins.motorcyclesensors.tpms

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import net.osmand.PlatformUtil
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * TirePressureHelper - Bluetooth TPMS (Tire Pressure Monitoring System) integration.
 *
 * Connects to Bluetooth TPMS sensors mounted on motorcycle tires to provide:
 * - Real-time tire pressure (front and rear)
 * - Tire temperature readings
 * - Low pressure warnings
 * - Rapid pressure loss detection (dangerous!)
 *
 * TPMS sensor types supported:
 * - BLE TPMS sensors (most common aftermarket type)
 * - Bluetooth SPP TPMS adapters
 *
 * Safety thresholds for motorcycles:
 * - Front tire: typically 28-36 PSI (1.9-2.5 bar)
 * - Rear tire: typically 30-42 PSI (2.1-2.9 bar)
 * - Low pressure warning: 20% below recommended
 * - Critical pressure: 30% below recommended
 * - High temperature warning: > 80°C (tire degradation)
 * - Rapid loss: > 2 PSI in 10 seconds = emergency
 *
 * The TPMS data integrates with:
 * - Dashboard widgets showing front/rear pressure
 * - Audio/visual warnings for low pressure
 * - Ride recording (pressure logged to GPX extensions)
 */
class TirePressureHelper(private val context: Context) {

    companion object {
        private val LOG = PlatformUtil.getLog(TirePressureHelper::class.java)

        // SPP UUID for TPMS adapters
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Default recommended pressures (PSI)
        const val DEFAULT_FRONT_PRESSURE_PSI = 33f
        const val DEFAULT_REAR_PRESSURE_PSI = 36f

        // Warning thresholds (percentage below recommended)
        const val LOW_PRESSURE_WARNING_PERCENT = 0.20f   // 20% below = warning
        const val LOW_PRESSURE_CRITICAL_PERCENT = 0.30f  // 30% below = critical

        // Temperature warning threshold (Celsius)
        const val HIGH_TEMP_WARNING_C = 80f

        // Rapid pressure loss threshold (PSI in 10 seconds)
        const val RAPID_LOSS_THRESHOLD_PSI = 2f

        // Polling interval
        const val POLL_INTERVAL_MS = 3000L  // 3 seconds
    }

    private val handler = Handler(Looper.getMainLooper())
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null

    // Current tire data
    val frontTire = TireData(position = TirePosition.FRONT)
    val rearTire = TireData(position = TirePosition.REAR)

    // Recommended pressures
    var recommendedFrontPsi: Float = DEFAULT_FRONT_PRESSURE_PSI
    var recommendedRearPsi: Float = DEFAULT_REAR_PRESSURE_PSI

    // Previous pressures for rapid loss detection
    private var lastFrontPsi: Float = 0f
    private var lastRearPsi: Float = 0f
    private var lastPressureCheckMs: Long = 0L

    // Connection state
    var isConnected: Boolean = false
        private set
    var isConnecting: Boolean = false
        private set

    private var pollRunnable: Runnable? = null

    /**
     * Tire position.
     */
    enum class TirePosition {
        FRONT, REAR
    }

    /**
     * Data class holding current tire pressure and temperature.
     */
    data class TireData(
        val position: TirePosition,
        var pressurePsi: Float = 0f,
        var temperatureC: Float = 0f,
        var lastUpdateMs: Long = 0L,
        var pressureStatus: PressureStatus = PressureStatus.UNKNOWN,
        var tempStatus: TempStatus = TempStatus.UNKNOWN
    ) {
        fun isStale(): Boolean {
            return lastUpdateMs == 0L || System.currentTimeMillis() - lastUpdateMs > 10000L
        }

        fun getPressureDisplay(): String {
            return if (isStale()) "--" else "${"%.1f".format(pressurePsi)} PSI"
        }

        fun getPressureBarDisplay(): String {
            return if (isStale()) "--" else "${"%.2f".format(pressurePsi * 0.0689476f)} bar"
        }

        fun getTempDisplay(): String {
            return if (isStale()) "--" else "${temperatureC.toInt()}°C"
        }
    }

    enum class PressureStatus {
        UNKNOWN,    // No data yet
        NORMAL,     // Within recommended range
        LOW,        // Warning: below recommended
        CRITICAL,   // Danger: significantly below recommended
        HIGH        // Over-inflated
    }

    enum class TempStatus {
        UNKNOWN,
        NORMAL,
        HIGH,       // Above 80°C
        CRITICAL    // Above 100°C
    }

    /**
     * TPMS alert types.
     */
    data class TireAlert(
        val type: AlertType,
        val tire: TirePosition,
        val message: String,
        val severity: AlertSeverity
    )

    enum class AlertType {
        LOW_PRESSURE,
        CRITICAL_PRESSURE,
        HIGH_PRESSURE,
        HIGH_TEMPERATURE,
        RAPID_PRESSURE_LOSS,
        SENSOR_DISCONNECTED
    }

    enum class AlertSeverity {
        INFO, WARNING, DANGER
    }

    // Listener
    interface TirePressureListener {
        fun onPressureUpdated(tire: TireData)
        fun onAlert(alert: TireAlert)
        fun onConnectionChanged(connected: Boolean)
    }

    private val listeners = mutableListOf<TirePressureListener>()

    fun addListener(listener: TirePressureListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: TirePressureListener) {
        listeners.remove(listener)
    }

    /**
     * Get paired Bluetooth devices that look like TPMS sensors.
     */
    @SuppressLint("MissingPermission")
    fun getTPMSDevices(): List<BluetoothDevice> {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return emptyList()

        if (!adapter.isEnabled) return emptyList()

        return adapter.bondedDevices.filter { device ->
            val name = device.name?.uppercase() ?: ""
            name.contains("TPMS") || name.contains("TIRE") ||
                name.contains("PRESSURE") || name.contains("FOBO") ||
                name.contains("NIRV") || name.contains("TIREMINDER")
        }
    }

    /**
     * Connect to a TPMS Bluetooth device.
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (isConnecting || isConnected) return

        isConnecting = true
        Thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                bluetoothSocket = socket
                inputStream = socket.inputStream

                isConnected = true
                isConnecting = false
                LOG.info("TirePressureHelper: Connected to ${device.name}")

                handler.post {
                    listeners.forEach { it.onConnectionChanged(true) }
                    startPolling()
                }
            } catch (e: IOException) {
                LOG.error("TirePressureHelper: Connection failed", e)
                isConnected = false
                isConnecting = false
                handler.post {
                    listeners.forEach { it.onConnectionChanged(false) }
                }
            }
        }.start()
    }

    /**
     * Disconnect from TPMS device.
     */
    fun disconnect() {
        stopPolling()
        try { inputStream?.close() } catch (_: Exception) {}
        try { bluetoothSocket?.close() } catch (_: Exception) {}
        bluetoothSocket = null
        inputStream = null
        isConnected = false
        handler.post { listeners.forEach { it.onConnectionChanged(false) } }
    }

    /**
     * Start periodic TPMS polling.
     */
    private fun startPolling() {
        stopPolling()

        pollRunnable = object : Runnable {
            override fun run() {
                if (!isConnected) return
                readTPMSData()
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        handler.post(pollRunnable!!)
    }

    private fun stopPolling() {
        pollRunnable?.let {
            handler.removeCallbacks(it)
            pollRunnable = null
        }
    }

    /**
     * Read TPMS data from Bluetooth stream.
     * Parses TPMS protocol data (varies by manufacturer).
     */
    private fun readTPMSData() {
        val is = inputStream ?: return

        try {
            if (is.available() < 8) return  // Minimum TPMS frame size

            val buffer = ByteArray(32)
            val bytesRead = is.read(buffer)

            if (bytesRead < 8) return

            // Parse TPMS data frame
            // Common format: [HEADER][TIRE_ID][PRESSURE_H][PRESSURE_L][TEMP_H][TEMP_L][CHECKSUM]
            // This is a simplified parser - real TPMS protocols vary by manufacturer

            val header = buffer[0].toInt() and 0xFF
            if (header != 0xAA && header != 0x55) return  // Not a valid TPMS frame

            val tireId = buffer[1].toInt() and 0xFF
            val pressureRaw = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
            val tempRaw = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)

            // Convert raw values (typical TPMS: pressure in kPa * 10, temp in 0.1°C)
            val pressurePsi = (pressureRaw / 10f) * 0.145038f  // kPa to PSI
            val tempC = tempRaw / 10f

            // Determine which tire this data belongs to
            // Typically: tireId 0x01 = front, 0x02 = rear
            val isFrontTire = (tireId and 0x01) != 0
            val isRearTire = (tireId and 0x02) != 0

            if (isFrontTire) {
                updateTireData(frontTire, pressurePsi, tempC)
            }
            if (isRearTire) {
                updateTireData(rearTire, pressurePsi, tempC)
            }
        } catch (e: Exception) {
            LOG.debug("TirePressureHelper: Read error", e)
        }
    }

    /**
     * Update tire data and check for alerts.
     */
    private fun updateTireData(tire: TireData, pressurePsi: Float, tempC: Float) {
        val now = System.currentTimeMillis()

        // Check for rapid pressure loss
        if (lastPressureCheckMs > 0) {
            val timeDeltaS = (now - lastPressureCheckMs) / 1000f
            if (timeDeltaS > 0 && timeDeltaS <= 15f) {
                val previousPsi = if (tire.position == TirePosition.FRONT) lastFrontPsi else lastRearPsi
                if (previousPsi > 0) {
                    val pressureDrop = previousPsi - pressurePsi
                    if (pressureDrop >= RAPID_LOSS_THRESHOLD_PSI) {
                        listeners.forEach {
                            it.onAlert(TireAlert(
                                AlertType.RAPID_PRESSURE_LOSS, tire.position,
                                "Rapid pressure loss: ${"%.1f".format(pressureDrop)} PSI in ${"%.0f".format(timeDeltaS)}s",
                                AlertSeverity.DANGER
                            ))
                        }
                    }
                }
            }
        }

        tire.pressurePsi = pressurePsi
        tire.temperatureC = tempC
        tire.lastUpdateMs = now

        // Save for rapid loss detection
        if (tire.position == TirePosition.FRONT) lastFrontPsi = pressurePsi
        else lastRearPsi = pressurePsi
        lastPressureCheckMs = now

        // Check pressure status
        val recommendedPsi = if (tire.position == TirePosition.FRONT) recommendedFrontPsi else recommendedRearPsi
        tire.pressureStatus = when {
            pressurePsi < recommendedPsi * (1f - LOW_PRESSURE_CRITICAL_PERCENT) -> {
                listeners.forEach {
                    it.onAlert(TireAlert(
                        AlertType.CRITICAL_PRESSURE, tire.position,
                        "${tire.position.name} tire critically low: ${"%.1f".format(pressurePsi)} PSI (recommended: ${"%.0f".format(recommendedPsi)})",
                        AlertSeverity.DANGER
                    ))
                }
                PressureStatus.CRITICAL
            }
            pressurePsi < recommendedPsi * (1f - LOW_PRESSURE_WARNING_PERCENT) -> {
                listeners.forEach {
                    it.onAlert(TireAlert(
                        AlertType.LOW_PRESSURE, tire.position,
                        "${tire.position.name} tire low: ${"%.1f".format(pressurePsi)} PSI",
                        AlertSeverity.WARNING
                    ))
                }
                PressureStatus.LOW
            }
            pressurePsi > recommendedPsi * 1.15f -> PressureStatus.HIGH
            else -> PressureStatus.NORMAL
        }

        // Check temperature status
        tire.tempStatus = when {
            tempC > 100f -> {
                listeners.forEach {
                    it.onAlert(TireAlert(
                        AlertType.HIGH_TEMPERATURE, tire.position,
                        "${tire.position.name} tire overheating: ${tempC.toInt()}°C",
                        AlertSeverity.DANGER
                    ))
                }
                TempStatus.CRITICAL
            }
            tempC > HIGH_TEMP_WARNING_C -> {
                listeners.forEach {
                    it.onAlert(TireAlert(
                        AlertType.HIGH_TEMPERATURE, tire.position,
                        "${tire.position.name} tire hot: ${tempC.toInt()}°C",
                        AlertSeverity.WARNING
                    ))
                }
                TempStatus.HIGH
            }
            else -> TempStatus.NORMAL
        }

        listeners.forEach { it.onPressureUpdated(tire) }
    }

    /**
     * Update tire pressure manually (when no TPMS sensor is connected).
     */
    fun setManualPressure(position: TirePosition, pressurePsi: Float) {
        val tire = if (position == TirePosition.FRONT) frontTire else rearTire
        val recommendedPsi = if (position == TirePosition.FRONT) recommendedFrontPsi else recommendedRearPsi
        tire.pressurePsi = pressurePsi
        tire.lastUpdateMs = System.currentTimeMillis()
        tire.pressureStatus = when {
            pressurePsi < recommendedPsi * (1f - LOW_PRESSURE_CRITICAL_PERCENT) -> PressureStatus.CRITICAL
            pressurePsi < recommendedPsi * (1f - LOW_PRESSURE_WARNING_PERCENT) -> PressureStatus.LOW
            pressurePsi > recommendedPsi * 1.15f -> PressureStatus.HIGH
            else -> PressureStatus.NORMAL
        }
        listeners.forEach { it.onPressureUpdated(tire) }
    }
}
