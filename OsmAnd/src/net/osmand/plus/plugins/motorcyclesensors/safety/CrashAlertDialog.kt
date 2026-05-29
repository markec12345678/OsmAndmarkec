package net.osmand.plus.plugins.motorcyclesensors.safety

import android.app.Activity
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import net.osmand.PlatformUtil
import net.osmand.plus.R
import net.osmand.plus.base.BaseOsmAndDialogFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash Alert Dialog - Full-screen warning with countdown timer.
 *
 * When a crash is detected, this dialog appears with:
 * 1. Full-screen red warning
 * 2. Location coordinates
 * 3. Impact G-force and rotation data
 * 4. 10-second countdown timer
 * 5. Large "I'M OK" cancel button
 *
 * If the countdown reaches zero:
 * - The event is logged to the crash event log
 * - NO SMS is sent (that's a future feature requiring permissions)
 * - The dialog dismisses with "event_logged" status
 *
 * Design decisions:
 * - Full-screen + wake lock = impossible to miss
 * - Large touch target for "I'M OK" = easy to hit with gloves
 * - Countdown = prevents accidental dismissal without attention
 * - Event logging only = no side effects until permissions are granted
 * - No network calls = works even in areas with no signal
 */
class CrashAlertDialog : BaseOsmAndDialogFragment() {

    companion object {
        private val LOG = PlatformUtil.getLog(CrashAlertDialog::class.java)
        const val TAG = "CrashAlertDialog"

        // Argument keys
        const val ARG_GFORCE = "gforce_at_impact"
        const val ARG_ROTATION = "rotation_at_impact"
        const val ARG_LAT = "crash_lat"
        const val ARG_LON = "crash_lon"
        const val ARG_SPEED_MS = "crash_speed_ms"
        const val ARG_TIMESTAMP = "crash_timestamp"

        // Countdown duration
        const val COUNTDOWN_SECONDS = 10

        fun newInstance(
            gForceAtImpact: Float,
            rotationAtImpact: Float,
            location: Location?,
            speedMs: Float
        ): CrashAlertDialog {
            val dialog = CrashAlertDialog()
            val args = Bundle()
            args.putFloat(ARG_GFORCE, gForceAtImpact)
            args.putFloat(ARG_ROTATION, rotationAtImpact)
            args.putDouble(ARG_LAT, location?.latitude ?: 0.0)
            args.putDouble(ARG_LON, location?.longitude ?: 0.0)
            args.putFloat(ARG_SPEED_MS, speedMs)
            args.putLong(ARG_TIMESTAMP, System.currentTimeMillis())
            dialog.arguments = args
            return dialog
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var countdownRemaining = COUNTDOWN_SECONDS
    private var userResponded = false
    private var crashEventLogged = false

    // Crash event log listener
    interface CrashEventListener {
        fun onCrashEventLogged(event: CrashEvent)
        fun onCrashCancelled(event: CrashEvent)
    }

    private var eventListener: CrashEventListener? = null

    fun setCrashEventListener(listener: CrashEventListener) {
        eventListener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full-screen dialog
        setStyle(STYLE_NO_FRAME, R.style.Theme_OsmandLight_FullScreen)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Keep screen on and bright
        activity?.window?.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // Populate crash data
        val args = requireArguments()
        val gForce = args.getFloat(ARG_GFORCE, 0f)
        val rotation = args.getFloat(ARG_ROTATION, 0f)
        val lat = args.getDouble(ARG_LAT, 0.0)
        val lon = args.getDouble(ARG_LON, 0.0)
        val speedMs = args.getFloat(ARG_SPEED_MS, 0f)
        val timestamp = args.getLong(ARG_TIMESTAMP, 0L)

        view.findViewById<TextView>(R.id.crash_gforce_value)?.text =
            String.format("%.1f G", gForce)
        view.findViewById<TextView>(R.id.crash_rotation_value)?.text =
            String.format("%.1f rad/s", rotation)
        view.findViewById<TextView>(R.id.crash_speed_value)?.text =
            String.format("%.0f km/h", speedMs * 3.6f)
        view.findViewById<TextView>(R.id.crash_location_value)?.text =
            if (lat != 0.0 || lon != 0.0)
                String.format("%.5f, %.5f", lat, lon)
            else
                "Unknown"
        view.findViewById<TextView>(R.id.crash_time_value)?.text =
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

        // Setup countdown
        updateCountdownDisplay()

        // "I'M OK" button
        view.findViewById<Button>(R.id.crash_im_ok_button)?.setOnClickListener {
            userResponded = true
            handler.removeCallbacksAndMessages(null)
            logCrashEvent(gForce, rotation, lat, lon, speedMs, timestamp, cancelled = true)
            dismiss()
        }

        // Start countdown
        startCountdown(gForce, rotation, lat, lon, speedMs, timestamp)
    }

    private fun startCountdown(
        gForce: Float, rotation: Float,
        lat: Double, lon: Double,
        speedMs: Float, timestamp: Long
    ) {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (userResponded) return

                countdownRemaining--
                updateCountdownDisplay()

                if (countdownRemaining <= 0) {
                    // Countdown expired - log event
                    logCrashEvent(gForce, rotation, lat, lon, speedMs, timestamp, cancelled = false)
                    dismiss()
                } else {
                    handler.postDelayed(this, 1000L)
                }
            }
        }, 1000L)
    }

    private fun updateCountdownDisplay() {
        view?.let { v ->
            v.findViewById<TextView>(R.id.crash_countdown_text)?.text =
                countdownRemaining.toString()

            v.findViewById<ProgressBar>(R.id.crash_countdown_progress)?.let { bar ->
                bar.max = COUNTDOWN_SECONDS
                bar.progress = countdownRemaining
            }
        }
    }

    /**
     * Log a crash event to the persistent crash event log.
     * This is the ONLY side effect of the countdown reaching zero.
     * No SMS, no calls, no network requests.
     */
    private fun logCrashEvent(
        gForce: Float, rotation: Float,
        lat: Double, lon: Double,
        speedMs: Float, timestamp: Long,
        cancelled: Boolean
    ) {
        if (crashEventLogged) return
        crashEventLogged = true

        val event = CrashEvent(
            timestampMs = timestamp,
            gForceAtImpact = gForce,
            rotationAtImpact = rotation,
            latitude = lat,
            longitude = lon,
            speedAtImpactMs = speedMs,
            userCancelled = cancelled,
            countdownCompleted = !cancelled
        )

        // Persist to crash event log (SharedPreferences for now)
        CrashEventLog.logEvent(requireContext(), event)

        if (cancelled) {
            LOG.info("CrashAlert: User confirmed OK - event logged as false alarm")
            eventListener?.onCrashCancelled(event)
        } else {
            LOG.warn("CrashAlert: Countdown expired - crash event logged (no emergency response yet)")
            eventListener?.onCrashEventLogged(event)
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    override fun getLayoutId(): Int = R.layout.crash_alert_dialog
}
