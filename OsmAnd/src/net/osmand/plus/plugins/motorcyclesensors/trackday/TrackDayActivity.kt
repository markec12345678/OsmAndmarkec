package net.osmand.plus.plugins.motorcyclesensors.trackday

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import net.osmand.PlatformUtil
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.plugins.motorcyclesensors.MotorcycleSensorsPlugin

/**
 * TrackDayActivity - full-screen UI for Track Day lap timing.
 *
 * Provides a dedicated interface for track day sessions with:
 * - Start/Stop session control
 * - Real-time lap timer with millisecond precision
 * - Current lap time display
 * - Best lap time with delta indicator
 * - Last lap time
 * - Sector split times
 * - Lap counter
 * - Max lean angle and G-force per lap
 * - Consistency score
 *
 * The UI is optimized for outdoor visibility:
 * - Large white text on black background
 * - High contrast color coding (green for best, red for slow)
 * - Minimal distractions during riding
 *
 * Usage:
 * 1. Position yourself at the start/finish line
 * 2. Press "START SESSION" to begin
 * 3. Cross the start/finish line to trigger lap detection
 * 4. Review lap times and sector splits in real-time
 * 5. Press "STOP SESSION" to end
 *
 * The activity receives GPS updates from the plugin's TrackDayHelper
 * which handles all lap detection logic.
 */
class TrackDayActivity : Activity() {

    companion object {
        private val LOG = PlatformUtil.getLog(TrackDayActivity::class.java)
        const val ACTION_LAP_COMPLETED = "net.osmand.motorcycle.LAP_COMPLETED"
        const val ACTION_SECTOR_COMPLETED = "net.osmand.motorcycle.SECTOR_COMPLETED"
        const val ACTION_SESSION_STATE_CHANGED = "net.osmand.motorcycle.SESSION_STATE_CHANGED"
        const val EXTRA_LAP_TIME_MS = "lap_time_ms"
        const val EXTRA_LAP_NUMBER = "lap_number"
        const val EXTRA_BEST_LAP_MS = "best_lap_ms"
        const val EXTRA_SECTOR_NUMBER = "sector_number"
        const val EXTRA_SECTOR_TIME_MS = "sector_time_ms"
        const val EXTRA_MAX_LEAN = "max_lean"
        const val EXTRA_MAX_G = "max_g"
        const val EXTRA_STATE = "session_state"
    }

    private lateinit var sessionStateText: TextView
    private lateinit var currentLapTimer: TextView
    private lateinit var lapCounter: TextView
    private lateinit var bestLapTime: TextView
    private lateinit var lastLapTime: TextView
    private lateinit var deltaDisplay: TextView
    private lateinit var sector1Time: TextView
    private lateinit var sector2Time: TextView
    private lateinit var sector3Time: TextView
    private lateinit var maxLeanDisplay: TextView
    private lateinit var maxGDisplay: TextView
    private lateinit var consistencyDisplay: TextView
    private lateinit var startStopButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var timerRunning = false

    private val plugin: MotorcycleSensorsPlugin? by lazy {
        PluginsHelper.getPlugin(MotorcycleSensorsPlugin::class.java)
    }

    private val trackDayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_LAP_COMPLETED -> handleLapCompleted(intent)
                ACTION_SECTOR_COMPLETED -> handleSectorCompleted(intent)
                ACTION_SESSION_STATE_CHANGED -> handleStateChanged(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Use our custom layout
        setContentView(
            resources.getIdentifier("track_day_activity", "layout", packageName)
        )

        // Keep screen on during track day
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        initViews()
        setupStartStopButton()
        registerReceiver()

        // Update initial state
        updateSessionState()
    }

    private fun initViews() {
        sessionStateText = findViewById(
            resources.getIdentifier("session_state", "id", packageName)
        )
        currentLapTimer = findViewById(
            resources.getIdentifier("current_lap_timer", "id", packageName)
        )
        lapCounter = findViewById(
            resources.getIdentifier("lap_counter", "id", packageName)
        )
        bestLapTime = findViewById(
            resources.getIdentifier("best_lap_time", "id", packageName)
        )
        lastLapTime = findViewById(
            resources.getIdentifier("last_lap_time", "id", packageName)
        )
        deltaDisplay = findViewById(
            resources.getIdentifier("delta_display", "id", packageName)
        )
        sector1Time = findViewById(
            resources.getIdentifier("sector1_time", "id", packageName)
        )
        sector2Time = findViewById(
            resources.getIdentifier("sector2_time", "id", packageName)
        )
        sector3Time = findViewById(
            resources.getIdentifier("sector3_time", "id", packageName)
        )
        maxLeanDisplay = findViewById(
            resources.getIdentifier("max_lean_display", "id", packageName)
        )
        maxGDisplay = findViewById(
            resources.getIdentifier("max_g_display", "id", packageName)
        )
        consistencyDisplay = findViewById(
            resources.getIdentifier("consistency_display", "id", packageName)
        )
        startStopButton = findViewById(
            resources.getIdentifier("start_stop_button", "id", packageName)
        )
    }

    private fun setupStartStopButton() {
        startStopButton.setOnClickListener {
            val td = plugin?.trackDay ?: return@setOnClickListener
            when (td.getState()) {
                TrackDayHelper.SessionState.IDLE -> {
                    td.startSession()
                    startLapTimer()
                    updateSessionState()
                }
                TrackDayHelper.SessionState.WAITING,
                TrackDayHelper.SessionState.RACING -> {
                    td.stopSession()
                    stopLapTimer()
                    updateSessionState()
                }
            }
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_LAP_COMPLETED)
            addAction(ACTION_SECTOR_COMPLETED)
            addAction(ACTION_SESSION_STATE_CHANGED)
        }
        registerReceiver(trackDayReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLapTimer()
        try {
            unregisterReceiver(trackDayReceiver)
        } catch (_: Exception) {}
    }

    // ===== Timer =====

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            val td = plugin?.trackDay ?: return
            val elapsed = td.getCurrentLapElapsedMs()
            currentLapTimer.text = formatLapTime(elapsed)
            handler.postDelayed(this, 50)  // Update at 20 Hz for smooth display
        }
    }

    private fun startLapTimer() {
        timerRunning = true
        handler.post(timerRunnable)
    }

    private fun stopLapTimer() {
        timerRunning = false
        handler.removeCallbacks(timerRunnable)
    }

    // ===== Event Handlers =====

    private fun handleLapCompleted(intent: Intent) {
        val lapTimeMs = intent.getLongExtra(EXTRA_LAP_TIME_MS, 0)
        val lapNumber = intent.getIntExtra(EXTRA_LAP_NUMBER, 0)
        val bestLapMs = intent.getLongExtra(EXTRA_BEST_LAP_MS, 0)
        val maxLean = intent.getFloatExtra(EXTRA_MAX_LEAN, 0f)
        val maxG = intent.getFloatExtra(EXTRA_MAX_G, 0f)

        // Update lap counter
        lapCounter.text = "LAP $lapNumber"

        // Update last lap time
        lastLapTime.text = formatLapTime(lapTimeMs)
        lastLapTime.setTextColor(Color.WHITE)

        // Update best lap time
        bestLapTime.text = formatLapTime(bestLapMs)
        bestLapTime.setTextColor(Color.parseColor("#4CAF50"))  // Green

        // Calculate delta from best
        val delta = lapTimeMs - bestLapMs
        if (delta > 0) {
            deltaDisplay.text = "+${formatLapTime(delta)}"
            deltaDisplay.setTextColor(Color.parseColor("#F44336"))  // Red (slower)
        } else if (delta == 0L) {
            deltaDisplay.text = "BEST"
            deltaDisplay.setTextColor(Color.parseColor("#4CAF50"))  // Green (new best)
            lastLapTime.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            deltaDisplay.text = formatLapTime(delta)
            deltaDisplay.setTextColor(Color.parseColor("#4CAF50"))  // Green (faster)
        }

        // Update max lean and G
        maxLeanDisplay.text = "${"%.0f".format(Math.abs(maxLean))}°"
        maxGDisplay.text = "${"%.2f".format(maxG)}G"

        // Update consistency score
        val td = plugin?.trackDay
        if (td != null) {
            val consistency = td.getConsistencyScore()
            consistencyDisplay.text = "${consistency}/100"
            consistencyDisplay.setTextColor(when {
                consistency > 80 -> Color.parseColor("#4CAF50")
                consistency > 60 -> Color.parseColor("#FFC107")
                else -> Color.parseColor("#F44336")
            })
        }

        // Restart lap timer
        startLapTimer()
    }

    private fun handleSectorCompleted(intent: Intent) {
        val sectorNum = intent.getIntExtra(EXTRA_SECTOR_NUMBER, 0)
        val sectorTimeMs = intent.getLongExtra(EXTRA_SECTOR_TIME_MS, 0)

        val formatted = formatLapTime(sectorTimeMs)
        when (sectorNum) {
            1 -> sector1Time.text = "S1: $formatted"
            2 -> sector2Time.text = "S2: $formatted"
            3 -> sector3Time.text = "S3: $formatted"
        }
    }

    private fun handleStateChanged(intent: Intent) {
        updateSessionState()
    }

    private fun updateSessionState() {
        val td = plugin?.trackDay ?: return
        when (td.getState()) {
            TrackDayHelper.SessionState.IDLE -> {
                sessionStateText.text = "READY"
                sessionStateText.setTextColor(Color.parseColor("#80FFFFFF"))
                startStopButton.text = "START SESSION"
                currentLapTimer.text = "0:00.000"
            }
            TrackDayHelper.SessionState.WAITING -> {
                sessionStateText.text = "WAITING FOR START LINE"
                sessionStateText.setTextColor(Color.parseColor("#FFC107"))
                startStopButton.text = "STOP SESSION"
            }
            TrackDayHelper.SessionState.RACING -> {
                sessionStateText.text = "RACING"
                sessionStateText.setTextColor(Color.parseColor("#4CAF50"))
                startStopButton.text = "STOP SESSION"
            }
        }
    }

    // ===== Formatting =====

    /**
     * Format lap time in M:SS.mmm format.
     */
    private fun formatLapTime(timeMs: Long): String {
        val minutes = timeMs / 60000
        val seconds = (timeMs % 60000) / 1000
        val millis = timeMs % 1000
        return String.format("%d:%02d.%03d", minutes, seconds, millis)
    }
}
