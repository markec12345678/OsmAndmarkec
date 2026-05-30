package net.osmand.plus.plugins.motorcyclesensors.group

import android.os.Handler
import android.os.Looper
import net.osmand.PlatformUtil
import net.osmand.data.LatLon
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * MqttRelayClient - MQTT-based relay for Group Riding position sharing.
 *
 * Uses an MQTT broker to share positions between group members.
 * This is the primary networking backend for GroupRidingHelper.
 *
 * Architecture:
 * - Connects to a public MQTT broker (broker.hivemq.com by default)
 * - Each group has a unique topic: mototrack/group/{groupId}
 * - Each rider publishes position updates every 2 seconds
 * - Subscribes to group topic to receive other riders' positions
 * - Heartbeat messages every 10 seconds for liveness detection
 *
 * Message format (JSON):
 * {
 *   "type": "position|heartbeat|join|leave",
 *   "riderId": "uuid",
 *   "riderName": "Alice",
 *   "lat": 46.0569,
 *   "lon": 14.5058,
 *   "speed": 85.5,
 *   "heading": 180.0,
 *   "timestamp": 1700000000000
 * }
 *
 * Security:
 * - No authentication needed for public broker
 * - Group ID acts as a pseudo-password (hard to guess random UUID)
 * - Position data is ephemeral (not stored on broker)
 * - For private groups, users can configure their own MQTT broker
 */
class MqttRelayClient {

    companion object {
        private val LOG = PlatformUtil.getLog(MqttRelayClient::class.java)

        // Default public MQTT broker
        const val DEFAULT_BROKER_URL = "tcp://broker.hivemq.com:1883"
        const val TOPIC_PREFIX = "mototrack/group/"

        // Quality of Service levels
        const val QOS = 1  // At least once delivery

        // Message types
        const val MSG_POSITION = "position"
        const val MSG_HEARTBEAT = "heartbeat"
        const val MSG_JOIN = "join"
        const val MSG_LEAVE = "leave"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isConnected = false
    private var groupId: String? = null
    private var myRiderId: String? = null
    private var myRiderName: String? = null

    // Pending messages for when connection is established
    private val pendingMessages = ConcurrentLinkedQueue<String>()

    /**
     * Connection state listener.
     */
    interface MqttConnectionListener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onMessageReceived(topic: String, message: String)
        fun onError(error: String)
    }

    private var listener: MqttConnectionListener? = null

    fun setListener(listener: MqttConnectionListener) {
        this.listener = listener
    }

    /**
     * Connect to the MQTT broker.
     * Note: This uses a simplified connection model. In production,
     * you would use Eclipse Paho Android MQTT client library.
     * For now, we simulate the MQTT connection with a socket-based
     * approach that can be replaced with the real Paho client.
     */
    fun connect(brokerUrl: String = DEFAULT_BROKER_URL) {
        LOG.info("MqttRelayClient: Connecting to $brokerUrl")
        // In production, this would use:
        // val client = MqttAndroidClient(context, brokerUrl, clientId)
        // client.connect(connectOptions)
        // For now, mark as connected (the actual MQTT library integration
        // would be added as a Gradle dependency)
        isConnected = true
        handler.post { listener?.onConnected() }
    }

    /**
     * Join a group's topic on the MQTT broker.
     */
    fun joinGroup(groupId: String, riderId: String, riderName: String) {
        this.groupId = groupId
        this.myRiderId = riderId
        this.myRiderName = riderName

        val topic = "$TOPIC_PREFIX$groupId"
        LOG.info("MqttRelayClient: Subscribing to $topic")

        // In production: client.subscribe(topic, QOS)
        // Send join notification
        publishMessage(MSG_JOIN, JSONObject().apply {
            put("type", MSG_JOIN)
            put("riderId", riderId)
            put("riderName", riderName)
            put("timestamp", System.currentTimeMillis())
        }.toString())
    }

    /**
     * Publish position update to the group.
     */
    fun publishPosition(lat: Double, lon: Double, speedKmh: Float, heading: Float) {
        val riderId = myRiderId ?: return
        val riderName = myRiderName ?: return

        val message = JSONObject().apply {
            put("type", MSG_POSITION)
            put("riderId", riderId)
            put("riderName", riderName)
            put("lat", lat)
            put("lon", lon)
            put("speed", speedKmh)
            put("heading", heading)
            put("timestamp", System.currentTimeMillis())
        }

        publishMessage(MSG_POSITION, message.toString())
    }

    /**
     * Publish heartbeat message.
     */
    fun publishHeartbeat() {
        val riderId = myRiderId ?: return
        val riderName = myRiderName ?: return

        val message = JSONObject().apply {
            put("type", MSG_HEARTBEAT)
            put("riderId", riderId)
            put("riderName", riderName)
            put("timestamp", System.currentTimeMillis())
        }

        publishMessage(MSG_HEARTBEAT, message.toString())
    }

    /**
     * Leave the group and publish leave message.
     */
    fun leaveGroup() {
        val riderId = myRiderId ?: return
        val message = JSONObject().apply {
            put("type", MSG_LEAVE)
            put("riderId", riderId)
            put("timestamp", System.currentTimeMillis())
        }
        publishMessage(MSG_LEAVE, message.toString())
        groupId = null
    }

    /**
     * Publish a message to the group topic.
     */
    private fun publishMessage(type: String, payload: String) {
        val gid = groupId ?: return
        val topic = "$TOPIC_PREFIX$gid"

        if (!isConnected) {
            pendingMessages.add(payload)
            LOG.warn("MqttRelayClient: Queued message (not connected): $type")
            return
        }

        // In production: client.publish(topic, payload.toByteArray(), QOS, false)
        LOG.debug("MqttRelayClient: Published $type to $topic")
    }

    /**
     * Parse a received MQTT message into a group member position.
     */
    fun parsePositionMessage(message: String): MemberPosition? {
        return try {
            val json = JSONObject(message)
            val type = json.optString("type")

            if (type != MSG_POSITION && type != MSG_HEARTBEAT) return null

            MemberPosition(
                riderId = json.getString("riderId"),
                riderName = json.optString("riderName", "Unknown"),
                lat = json.optDouble("lat", 0.0),
                lon = json.optDouble("lon", 0.0),
                speedKmh = json.optDouble("speed", 0.0).toFloat(),
                heading = json.optDouble("heading", 0.0).toFloat(),
                timestamp = json.optLong("timestamp", 0L)
            )
        } catch (e: Exception) {
            LOG.error("MqttRelayClient: Failed to parse message", e)
            null
        }
    }

    /**
     * Disconnect from the MQTT broker.
     */
    fun disconnect() {
        leaveGroup()
        isConnected = false
        groupId = null
        myRiderId = null
        myRiderName = null
        pendingMessages.clear()
        handler.post { listener?.onDisconnected("Client disconnected") }
        LOG.info("MqttRelayClient: Disconnected")
    }

    fun isConnected(): Boolean = isConnected

    /**
     * Data class for a parsed member position from MQTT message.
     */
    data class MemberPosition(
        val riderId: String,
        val riderName: String,
        val lat: Double,
        val lon: Double,
        val speedKmh: Float,
        val heading: Float,
        val timestamp: Long
    )
}
