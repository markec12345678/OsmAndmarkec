package net.osmand.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.wear.widget.SwipeDismissFrameLayout
import net.osmand.wear.PhoneListenerService.Companion.ACTION_CRASH_ALERT
import net.osmand.wear.PhoneListenerService.Companion.ACTION_SENSOR_UPDATE

/**
 * MainActivity - main watch screen showing motorcycle sensor data.
 *
 * Displays:
 * - Lean angle (large, prominent) with direction indicator
 * - Total G-force
 * - Speed
 * - Max lean and max G (peak values)
 * - Connection status indicator
 *
 * Layout adapts to round and square watches via WearableRecyclerView
 * and BoxInsetLayout. The display uses large, glanceable numbers
 * suitable for quick reading while riding.
 *
 * The activity registers a BroadcastReceiver to update the UI
 * whenever new sensor data arrives from PhoneListenerService.
 */
class MainActivity : ComponentActivity() {

    private lateinit var leanAngleText: TextView
    private lateinit var leanDirectionText: TextView
    private lateinit var gforceText: TextView
    private lateinit var speedText: TextView
    private lateinit var maxLeanText: TextView
    private lateinit var maxGText: TextView
    private lateinit var connectionStatus: View
    private lateinit var staleOverlay: View

    private val sensorData = SensorDataStore.getInstance()

    private val sensorUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SENSOR_UPDATE -> updateDisplay()
                ACTION_CRASH_ALERT -> launchCrashAlert(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Find views
        leanAngleText = findViewById(R.id.lean_angle_value)
        leanDirectionText = findViewById(R.id.lean_direction)
        gforceText = findViewById(R.id.gforce_value)
        speedText = findViewById(R.id.speed_value)
        maxLeanText = findViewById(R.id.max_lean_value)
        maxGText = findViewById(R.id.max_g_value)
        connectionStatus = findViewById(R.id.connection_status)
        staleOverlay = findViewById(R.id.stale_overlay)

        // Register for sensor updates
        val filter = IntentFilter().apply {
            addAction(ACTION_SENSOR_UPDATE)
            addAction(ACTION_CRASH_ALERT)
        }
        registerReceiver(sensorUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Initial display
        updateDisplay()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(sensorUpdateReceiver)
        } catch (_: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        updateDisplay()
    }

    /**
     * Update all display values from SensorDataStore.
     */
    private fun updateDisplay() {
        val data = sensorData

        // Lean angle - the primary display
        leanAngleText.text = if (data.isStale()) "--" else "${"%.0f".format(Math.abs(data.leanAngleDeg))}"
        leanDirectionText.text = if (data.leanAngleDeg >= 0) "R" else "L"

        // G-force
        gforceText.text = data.getGForceDisplay()

        // Speed
        speedText.text = data.getSpeedDisplay()

        // Peak values
        maxLeanText.text = "${"%.0f".format(data.maxLean)}${"$"}"
        maxGText.text = "${"%.1f".format(data.maxG)}G"

        // Connection indicator
        connectionStatus.visibility = if (data.isConnected && !data.isStale()) View.GONE else View.VISIBLE

        // Stale data overlay (dim when no data)
        staleOverlay.visibility = if (data.isStale()) View.VISIBLE else View.GONE

        // Color-code lean angle by intensity
        val leanColor = when {
            data.isStale() -> getColor(android.R.color.white)
            Math.abs(data.leanAngleDeg) > 45 -> getColor(R.color.lean_extreme)
            Math.abs(data.leanAngleDeg) > 30 -> getColor(R.color.lean_aggressive)
            Math.abs(data.leanAngleDeg) > 15 -> getColor(R.color.lean_moderate)
            else -> getColor(android.R.color.white)
        }
        leanAngleText.setTextColor(leanColor)

        // Color-code G-force
        val gColor = when {
            data.isStale() -> getColor(android.R.color.white)
            data.totalG > 2.0f -> getColor(R.color.gforce_high)
            data.totalG > 1.5f -> getColor(R.color.gforce_medium)
            else -> getColor(android.R.color.white)
        }
        gforceText.setTextColor(gColor)
    }

    /**
     * Launch crash alert activity when crash is detected.
     */
    private fun launchCrashAlert(intent: Intent) {
        val crashIntent = Intent(this, CrashAlertActivity::class.java)
        crashIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        crashIntent.putExtras(intent.extras ?: Bundle())
        startActivity(crashIntent)
    }
}
