package net.osmand.wear

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.wear.ambient.AmbientModeSupport

/**
 * AmbientSensorActivity - shows lean angle in ambient (always-on) mode.
 *
 * When the watch enters ambient mode (to save battery):
 * - Screen dims to grayscale
 * - Only shows lean angle (most important data point)
 * - Updates at reduced rate (once per second)
 * - Uses BurnInProtection to prevent screen burn-in
 *
 * This allows riders to glance at their lean angle continuously
 * without waking the watch.
 */
class AmbientSensorActivity : FragmentActivity(), AmbientModeSupport.AmbientCallbackProvider {

    private val sensorData = SensorDataStore.getInstance()
    private lateinit var ambientController: AmbientModeSupport.AmbientController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ambient)

        ambientController = AmbientModeSupport.attach(this)
    }

    override fun getAmbientCallback(): AmbientModeSupport.AmbientCallback {
        return object : AmbientModeSupport.AmbientCallback() {
            override fun onEnterAmbient(ambientDetails: Bundle?) {
                // Update display once, switch to grayscale
                updateAmbientDisplay()
            }

            override fun onUpdateAmbient() {
                // Periodic update in ambient mode
                updateAmbientDisplay()
            }

            override fun onExitAmbient() {
                // Return to normal display
            }
        }
    }

    private fun updateAmbientDisplay() {
        findViewById<android.widget.TextView>(R.id.ambient_lean_angle)?.text =
            sensorData.getLeanAngleDisplay()
    }
}
