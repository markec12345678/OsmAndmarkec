package net.osmand.wear

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * CrashAlertActivity - full-screen crash alert on the watch.
 *
 * When the phone detects a crash and sends an alert:
 * - Watch vibrates (system notification)
 * - Full-screen red warning appears
 * - Shows impact G-force
 * - Shows location (if available)
 * - Dismisses automatically after 30 seconds
 *
 * The user can tap to dismiss.
 */
class CrashAlertActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val sensorData = SensorDataStore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_alert)

        // Keep screen on
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        // Populate crash data
        val gForce = intent.getFloatExtra(PhoneListenerService.EXTRA_CRASH_GFORCE, 0f)
        val lat = intent.getDoubleExtra(PhoneListenerService.EXTRA_CRASH_LAT, 0.0)
        val lon = intent.getDoubleExtra(PhoneListenerService.EXTRA_CRASH_LON, 0.0)

        findViewById<TextView>(R.id.crash_gforce_value)?.text = "${"%.1f".format(gForce)}G"

        if (lat != 0.0 || lon != 0.0) {
            findViewById<TextView>(R.id.crash_location_value)?.text =
                "${"%.4f".format(lat)}, ${"%.4f".format(lon)}"
        }

        // Tap anywhere to dismiss
        findViewById<View>(R.id.crash_alert_root)?.setOnClickListener {
            sensorData.resetCrash()
            finish()
        }

        // Auto-dismiss after 30 seconds
        handler.postDelayed({
            sensorData.resetCrash()
            finish()
        }, 30000L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
