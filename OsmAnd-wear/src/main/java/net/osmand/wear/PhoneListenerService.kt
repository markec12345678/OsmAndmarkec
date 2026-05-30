package net.osmand.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import net.osmand.wear.SensorDataStore.KEY_LATERAL_G
import net.osmand.wear.SensorDataStore.KEY_LEAN_ANGLE
import net.osmand.wear.SensorDataStore.KEY_LEAN_DIRECTION
import net.osmand.wear.SensorDataStore.KEY_LONGITUDINAL_G
import net.osmand.wear.SensorDataStore.KEY_MAX_G
import net.osmand.wear.SensorDataStore.KEY_MAX_LEAN
import net.osmand.wear.SensorDataStore.KEY_SPEED_KMH
import net.osmand.wear.SensorDataStore.KEY_TIMESTAMP
import net.osmand.wear.SensorDataStore.KEY_TOTAL_G
import net.osmand.wear.SensorDataStore.SENSOR_DATA_PATH

/**
 * PhoneListenerService - receives sensor data from the phone app.
 *
 * Runs on the Wear OS watch and listens for:
 * 1. Data changes at /motorcycle/sensors (regular sensor updates)
 * 2. Messages at /motorcycle/crash (instant crash alerts)
 *
 * When sensor data arrives:
 * - Stores it in SensorDataStore (singleton)
 * - Broadcasts an intent for MainActivity to update UI
 * - Triggers complication updates if active
 *
 * When crash alert arrives:
 * - Starts CrashAlertActivity on the watch
 * - Vibrates and shows full-screen warning
 */
class PhoneListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneListenerService"

        // Broadcast action for UI updates
        const val ACTION_SENSOR_UPDATE = "net.osmand.wear.SENSOR_UPDATE"
        const val ACTION_CRASH_ALERT = "net.osmand.wear.CRASH_ALERT"
        const val EXTRA_CRASH_GFORCE = "crash_gforce"
        const val EXTRA_CRASH_LAT = "crash_lat"
        const val EXTRA_CRASH_LON = "crash_lon"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val dataItem = event.dataItem
                if (dataItem.uri.path == SENSOR_DATA_PATH) {
                    val dataMap = DataMapItem.fromDataItem(dataItem).dataMap

                    // Update sensor data store
                    val store = SensorDataStore.getInstance()
                    store.leanAngleDeg = dataMap.getFloat(KEY_LEAN_ANGLE, 0f)
                    store.leanDirection = dataMap.getString(KEY_LEAN_DIRECTION, "")
                    store.totalG = dataMap.getFloat(KEY_TOTAL_G, 0f)
                    store.lateralG = dataMap.getFloat(KEY_LATERAL_G, 0f)
                    store.longitudinalG = dataMap.getFloat(KEY_LONGITUDINAL_G, 0f)
                    store.speedKmh = dataMap.getFloat(KEY_SPEED_KMH, 0f)
                    store.maxLean = dataMap.getFloat(KEY_MAX_LEAN, 0f)
                    store.maxG = dataMap.getFloat(KEY_MAX_G, 0f)
                    store.lastUpdateMs = dataMap.getLong(KEY_TIMESTAMP, 0L)
                    store.isConnected = true

                    Log.d(TAG, "Sensor update: lean=${"%.1f".format(store.leanAngleDeg)} deg, " +
                        "G=${"%.2f".format(store.totalG)}, speed=${"%.0f".format(store.speedKmh)} km/h")

                    // Broadcast to UI
                    val intent = Intent(ACTION_SENSOR_UPDATE)
                    intent.setPackage(packageName)
                    sendBroadcast(intent)
                }
            } else if (event.type == DataEvent.TYPE_DELETED) {
                val dataItem = event.dataItem
                if (dataItem.uri.path == SENSOR_DATA_PATH) {
                    SensorDataStore.getInstance().isConnected = false
                    Log.w(TAG, "Phone disconnected - sensor data deleted")
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: com.google.android.gms.wearable.MessageEvent) {
        if (messageEvent.path == "/motorcycle/crash") {
            Log.w(TAG, "CRASH ALERT received from phone!")

            try {
                val json = org.json.JSONObject(String(messageEvent.data))
                val gForce = json.getDouble("crash_gforce").toFloat()
                val lat = json.getDouble("crash_lat")
                val lon = json.getDouble("crash_lon")

                // Update store
                val store = SensorDataStore.getInstance()
                store.isCrashActive = true
                store.crashGForce = gForce
                store.crashLat = lat
                store.crashLon = lon

                // Broadcast crash alert
                val intent = Intent(ACTION_CRASH_ALERT)
                intent.putExtra(EXTRA_CRASH_GFORCE, gForce)
                intent.putExtra(EXTRA_CRASH_LAT, lat)
                intent.putExtra(EXTRA_CRASH_LON, lon)
                intent.setPackage(packageName)
                sendBroadcast(intent)

                // Launch crash alert activity
                val crashIntent = Intent(this, CrashAlertActivity::class.java)
                crashIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                crashIntent.putExtra(EXTRA_CRASH_GFORCE, gForce)
                crashIntent.putExtra(EXTRA_CRASH_LAT, lat)
                crashIntent.putExtra(EXTRA_CRASH_LON, lon)
                startActivity(crashIntent)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse crash alert data", e)
            }
        }
    }
}
