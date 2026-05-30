package net.osmand.plus.plugins.motorcyclesensors.obd2

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
import java.io.OutputStream
import java.util.UUID

/**
 * OBD2Helper - Bluetooth OBD2 adapter connection and PID data parsing.
 *
 * Connects to an ELM327-compatible Bluetooth OBD2 adapter and reads
 * motorcycle-specific PIDs from the engine ECU:
 * - RPM (PID 0C)
 * - Engine coolant temperature (PID 05)
 * - Throttle position (PID 11)
 * - Engine load (PID 04)
 * - Vehicle speed (PID 0D)
 * - Fuel level (PID 2F)
 * - Intake air temperature (PID 0F)
 * - Manifold pressure (PID 0B)
 * - DTC status (PID 01)
 *
 * Connection flow:
 * 1. Scan for paired Bluetooth devices with "OBD" or "ELM" in the name
 * 2. Connect via RFCOMM to the SPP UUID
 * 3. Initialize ELM327 with AT commands (reset, echo off, protocol auto)
 * 4. Poll PIDs in rotation at ~2 Hz (configurable)
 * 5. Parse ELM327 responses and update OBD2DataStore
 *
 * Design decisions:
 * - RFCOMM/SPP over BLE GATT for maximum ELM327 compatibility
 *   (most cheap OBD2 adapters use classic Bluetooth SPP)
 * - Sequential PID polling (not concurrent) to avoid adapter buffer overflow
 * - 500ms timeout per command to handle unresponsive adapters
 * - Background thread with Handler for periodic polling
 */
class OBD2Helper(private val context: Context) {

    companion object {
        private val LOG = PlatformUtil.getLog(OBD2Helper::class.java)

        // Standard SPP UUID for ELM327
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Polling interval
        const val POLL_INTERVAL_MS = 500L  // 2 Hz

        // Command timeout
        const val COMMAND_TIMEOUT_MS = 500L

        // ELM327 AT commands for initialization
        val INIT_COMMANDS = listOf(
            "ATZ",       // Reset
            "ATE0",      // Echo off
            "ATL0",      // Linefeeds off
            "ATS0",      // Spaces off
            "ATH0",      // Headers off
            "ATSP0"      // Auto protocol
        )

        // PIDs to poll in rotation
        val POLL_PIDS = listOf("01 0C", "01 05", "01 11", "01 04", "01 0D", "01 2F", "01 0F", "01 0B", "01 01")
    }

    private val dataStore = OBD2DataStore.getInstance()
    private val handler = Handler(Looper.getMainLooper())

    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private var isConnecting = false
    private var isInitialized = false
    private var currentPidIndex = 0
    private var pollRunnable: Runnable? = null

    private var consecutiveErrors = 0
    private val MAX_CONSECUTIVE_ERRORS = 5
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 3
    private var lastConnectedDevice: BluetoothDevice? = null

    private var connectionListener: OBD2ConnectionListener? = null

    /**
     * Connection state listener interface.
     */
    interface OBD2ConnectionListener {
        fun onConnected(deviceName: String)
        fun onDisconnected(reason: String)
        fun onConnectionFailed(error: String)
        fun onDataReceived(pid: String, value: Any)
    }

    fun setConnectionListener(listener: OBD2ConnectionListener) {
        connectionListener = listener
    }

    /**
     * Get paired Bluetooth devices that look like OBD2 adapters.
     * Filters by name containing "OBD", "ELM", "V-Link", or "CARSCANNER".
     */
    @SuppressLint("MissingPermission")
    fun getOBD2Devices(): List<BluetoothDevice> {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return emptyList()

        if (!adapter.isEnabled) return emptyList()

        return adapter.bondedDevices.filter { device ->
            val name = device.name?.uppercase() ?: ""
            name.contains("OBD") || name.contains("ELM") ||
                name.contains("V-LINK") || name.contains("CARSCANNER") ||
                name.contains("KW909") || name.contains("VIECAR")
        }
    }

    /**
     * Connect to a specific OBD2 Bluetooth device.
     * Runs connection on a background thread.
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (isConnecting || dataStore.isConnected) return

        isConnecting = true
        lastConnectedDevice = device
        Thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                bluetoothSocket = socket
                outputStream = socket.outputStream
                inputStream = socket.inputStream

                dataStore.setConnectionState(true, device.name ?: "OBD2", device.address)
                LOG.info("OBD2Helper: Connected to ${device.name}")

                // Initialize ELM327
                if (initializeELM327()) {
                    isInitialized = true
                    reconnectAttempts = 0
                    consecutiveErrors = 0
                    handler.post {
                        connectionListener?.onConnected(device.name ?: "OBD2")
                        startPolling()
                    }
                } else {
                    disconnect("ELM327 initialization failed")
                    handler.post {
                        connectionListener?.onConnectionFailed("ELM327 initialization failed")
                    }
                }
            } catch (e: IOException) {
                LOG.error("OBD2Helper: Connection failed", e)
                disconnect("Connection failed: ${e.message}")
                handler.post {
                    connectionListener?.onConnectionFailed(e.message ?: "Connection failed")
                }
            } finally {
                isConnecting = false
            }
        }.start()
    }

    /**
     * Disconnect from OBD2 adapter and stop polling.
     */
    fun disconnect(reason: String = "User disconnected") {
        stopPolling()
        isInitialized = false

        try {
            outputStream?.close()
        } catch (_: Exception) {}
        try {
            inputStream?.close()
        } catch (_: Exception) {}
        try {
            bluetoothSocket?.close()
        } catch (_: Exception) {}

        bluetoothSocket = null
        outputStream = null
        inputStream = null

        val wasConnected = dataStore.isConnected
        dataStore.setConnectionState(false)

        if (wasConnected) {
            LOG.info("OBD2Helper: Disconnected - $reason")
            handler.post {
                connectionListener?.onDisconnected(reason)
            }
        }
    }

    /**
     * Attempt to reconnect to the last OBD2 device.
     */
    private fun attemptReconnect() {
        stopPolling()
        val device = lastConnectedDevice
        if (device == null || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts = 0
            disconnect("Max reconnection attempts reached")
            return
        }
        reconnectAttempts++
        LOG.info("OBD2Helper: Reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS")
        // Close current connection
        try { outputStream?.close() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: Exception) {}
        try { bluetoothSocket?.close() } catch (_: Exception) {}
        bluetoothSocket = null
        outputStream = null
        inputStream = null
        dataStore.setConnectionState(false)

        // Try to reconnect after a delay
        handler.postDelayed({
            connect(device)
            if (dataStore.isConnected) {
                reconnectAttempts = 0
                consecutiveErrors = 0
            }
        }, 2000L)  // 2 second delay between reconnect attempts
    }

    /**
     * Initialize ELM327 with AT commands.
     */
    private fun initializeELM327(): Boolean {
        for (cmd in INIT_COMMANDS) {
            val response = sendCommand(cmd)
            if (response == null) {
                LOG.warn("OBD2Helper: No response to $cmd")
                // ATZ may timeout on some adapters, continue
                if (cmd != "ATZ") return false
            }
            Thread.sleep(200)  // Wait between init commands
        }
        LOG.info("OBD2Helper: ELM327 initialized successfully")
        return true
    }

    /**
     * Start periodic PID polling.
     */
    private fun startPolling() {
        stopPolling()
        currentPidIndex = 0

        pollRunnable = object : Runnable {
            override fun run() {
                if (!dataStore.isConnected || !isInitialized) return

                val pid = POLL_PIDS[currentPidIndex]
                val response = sendCommand(pid)

                if (response != null) {
                    consecutiveErrors = 0  // Reset error counter on success
                    val parsedValue = parsePIDResponse(pid, response)
                    if (parsedValue != null) {
                        val pidCode = pid.substring(3).trim()  // "01 0C" -> "0C"
                        dataStore.updateData(pidCode, parsedValue)
                        handler.post {
                            connectionListener?.onDataReceived(pidCode, parsedValue)
                        }
                    }
                } else {
                    consecutiveErrors++
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        LOG.warn("OBD2Helper: $consecutiveErrors consecutive errors, attempting reconnect")
                        attemptReconnect()
                        return  // Stop polling, reconnect will restart it
                    }
                }

                // Rotate to next PID
                currentPidIndex = (currentPidIndex + 1) % POLL_PIDS.size
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

        handler.post(pollRunnable!!)
    }

    /**
     * Stop periodic PID polling.
     */
    private fun stopPolling() {
        pollRunnable?.let {
            handler.removeCallbacks(it)
            pollRunnable = null
        }
    }

    /**
     * Send a command to ELM327 and read the response.
     */
    private fun sendCommand(command: String): String? {
        val os = outputStream ?: return null
        val `is` = inputStream ?: return null

        return try {
            os.write("$command\r".toByteArray())
            os.flush()

            // Read response with timeout
            val buffer = StringBuilder()
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < COMMAND_TIMEOUT_MS) {
                if (`is`.available() > 0) {
                    val b = `is`.read()
                    if (b == '>') break  // ELM327 prompt character
                    if (b > 0x0D && b.toChar() != ' ') {
                        buffer.append(b.toChar())
                    }
                } else {
                    Thread.sleep(10)
                }
            }

            val result = buffer.toString().trim()
            if (result.isEmpty()) null else result
        } catch (e: Exception) {
            LOG.error("OBD2Helper: Command '$command' failed", e)
            null
        }
    }

    /**
     * Parse ELM327 response for a specific PID.
     * Returns the parsed value or null if parsing fails.
     */
    private fun parsePIDResponse(pid: String, response: String): Any? {
        return try {
            // Remove any "4100" style prefix (mode 41 response + PID)
            val cleaned = response.replace(" ", "").uppercase()

            when {
                pid.endsWith("0C") -> parseRPM(cleaned)
                pid.endsWith("05") -> parseTemperature(cleaned)
                pid.endsWith("11") -> parsePercentage(cleaned)
                pid.endsWith("04") -> parsePercentage(cleaned)
                pid.endsWith("0D") -> parseSpeed(cleaned)
                pid.endsWith("2F") -> parseFuelLevel(cleaned)
                pid.endsWith("0F") -> parseTemperature(cleaned)
                pid.endsWith("0B") -> parsePressure(cleaned)
                pid.endsWith("01") -> parseDTCStatus(cleaned)
                else -> null
            }
        } catch (e: Exception) {
            LOG.debug("OBD2Helper: Failed to parse PID $pid response: $response")
            null
        }
    }

    /**
     * Parse RPM from PID 0C response.
     * Formula: ((A * 256) + B) / 4
     */
    private fun parseRPM(data: String): Int? {
        // Find the data bytes after the PID echo
        val hexData = extractDataBytes(data, 4) ?: return null
        val a = hexData.substring(0, 2).toInt(16)
        val b = hexData.substring(2, 4).toInt(16)
        return ((a * 256) + b) / 4
    }

    /**
     * Parse temperature from PID 05 or 0F response.
     * Formula: A - 40
     */
    private fun parseTemperature(data: String): Int? {
        val hexData = extractDataBytes(data, 2) ?: return null
        val a = hexData.substring(0, 2).toInt(16)
        return a - 40
    }

    /**
     * Parse percentage from PID 04 or 11 response.
     * Formula: (A * 100) / 255
     */
    private fun parsePercentage(data: String): Float? {
        val hexData = extractDataBytes(data, 2) ?: return null
        val a = hexData.substring(0, 2).toInt(16)
        return (a * 100f) / 255f
    }

    /**
     * Parse speed from PID 0D response.
     * Formula: A (direct km/h)
     */
    private fun parseSpeed(data: String): Float? {
        val hexData = extractDataBytes(data, 2) ?: return null
        val a = hexData.substring(0, 2).toInt(16)
        return a.toFloat()
    }

    /**
     * Parse fuel level from PID 2F response.
     * Formula: (A * 100) / 255
     */
    private fun parseFuelLevel(data: String): Float? {
        val hexData = extractDataBytes(data, 2) ?: return null
        val a = hexData.substring(0, 2).toInt(16)
        return (a * 100f) / 255f
    }

    /**
     * Parse manifold pressure from PID 0B response.
     * Formula: A (direct kPa)
     */
    private fun parsePressure(data: String): Float? {
        val hexData = extractDataBytes(data, 2) ?: return null
        val a = hexData.substring(0, 2).toInt(16)
        return a.toFloat()
    }

    /**
     * Parse DTC status from PID 01 response.
     */
    private fun parseDTCStatus(data: String): DTCStatus? {
        val hexData = extractDataBytes(data, 4) ?: return null
        val a = hexData.substring(0, 2).toInt(16)
        val hasCheckEngine = (a and 0x80) != 0
        val count = a and 0x7F
        return DTCStatus(hasCheckEngine, count)
    }

    /**
     * Extract the relevant data bytes from a raw ELM327 response.
     * Skips the mode+PID header bytes.
     */
    private fun extractDataBytes(data: String, expectedLength: Int): String? {
        // ELM327 response format: 41 XX DD DD... (mode 01 response)
        // Find "41" in the data and extract bytes after PID
        val mode41Index = data.indexOf("41")
        if (mode41Index < 0) return null

        val afterHeader = data.substring(mode41Index + 4)  // Skip "41XX"
        return if (afterHeader.length >= expectedLength) afterHeader else null
    }

    /**
     * Check if Bluetooth is available and enabled.
     */
    @SuppressLint("MissingPermission")
    fun isBluetoothAvailable(): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return false
        return adapter.isEnabled
    }

    /**
     * Get current connection status.
     */
    fun isConnected(): Boolean = dataStore.isConnected
}
