package net.osmand.plus.plugins.motorcyclesensors.group

import net.osmand.PlatformUtil
import net.osmand.Location
import net.osmand.data.LatLon
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Group Riding - real-time position sharing between riders.
 *
 * Architecture:
 * - Peer-to-peer via WiFi Direct (no server needed for nearby groups)
 * - Optional relay server for long-distance groups
 * - Each rider broadcasts: location, speed, heading, name
 * - Map overlay shows all group members in real-time
 * - Automatic regrouping suggestions when rider falls behind
 *
 * Communication modes:
 * 1. WiFi Direct (P2P) - for riders within ~100m, no internet needed
 * 2. MQTT relay server - for riders at any distance, requires internet
 * 3. Bluetooth - backup for very close range
 *
 * Group structure:
 * - One rider creates a group (becomes host)
 * - Other riders join with a group code
 * - Host manages the group, can kick members
 * - All members see each other on the map
 *
 * Data protocol:
 * - Position update: every 2 seconds (configurable)
 * - Heartbeat: every 10 seconds (detect disconnections)
 * - Disconnect timeout: 30 seconds without heartbeat
 */
class GroupRidingHelper {

    companion object {
        private val LOG = PlatformUtil.getLog(GroupRidingHelper::class.java)

        // Update intervals
        const val POSITION_UPDATE_INTERVAL_MS = 2_000L     // 2 seconds
        const val HEARTBEAT_INTERVAL_MS = 10_000L          // 10 seconds
        const val DISCONNECT_TIMEOUT_MS = 30_000L          // 30 seconds

        // Proximity alerts
        const val REGROUP_DISTANCE_M = 500.0               // Alert when rider > 500m behind
        const val DANGER_DISTANCE_M = 50.0                 // Alert when riders too close
    }

    /**
     * A rider in the group.
     */
    data class GroupMember(
        val id: String,
        val name: String,
        val color: Int,                     // Display color on map
        var location: Location? = null,
        var speedKmh: Float = 0f,
        var heading: Float = 0f,
        var lastUpdateTime: Long = 0L,
        var lastHeartbeat: Long = 0L,
        var isOnline: Boolean = true,
        var distanceFromMe: Float = 0f      // Updated locally
    )

    /**
     * Group information.
     */
    data class RidingGroup(
        val id: String,
        val name: String,
        val hostId: String,
        val createdAt: Long,
        val inviteCode: String,             // Short code for joining
        val members: MutableList<GroupMember> = mutableListOf()
    ) {
        fun getMemberCount(): Int = members.size

        fun isHost(userId: String): Boolean = userId == hostId

        fun getOnlineMembers(): List<GroupMember> = members.filter { it.isOnline }
    }

    /**
     * Group riding session state.
     */
    enum class GroupState {
        IDLE,           // Not in a group
        CREATING,       // Creating a new group
        JOINING,        // Joining an existing group
        CONNECTED,      // Connected and sharing
        DISCONNECTED    // Lost connection
    }

    /**
     * Proximity alert types.
     */
    data class ProximityAlert(
        val type: AlertType,
        val member: GroupMember,
        val distance: Float,
        val message: String
    )

    enum class AlertType {
        MEMBER_FAR_BEHIND,      // Rider fell behind
        MEMBER_STOPPED,         // Rider stopped unexpectedly
        MEMBER_TOO_CLOSE,       // Riders dangerously close
        MEMBER_DISCONNECTED,    // Lost connection
        REGROUP_SUGGESTION      // Route regroup point suggestion
    }

    // State
    private var state = GroupState.IDLE
    private var currentGroup: RidingGroup? = null
    private var myId: String = UUID.randomUUID().toString()
    private var myName: String = "Rider"

    // Listeners
    interface GroupRidingListener {
        fun onGroupCreated(group: RidingGroup)
        fun onGroupJoined(group: RidingGroup)
        fun onMemberJoined(member: GroupMember)
        fun onMemberLeft(member: GroupMember)
        fun onMemberUpdated(member: GroupMember)
        fun onProximityAlert(alert: ProximityAlert)
        fun onGroupLeft()
        fun onError(message: String)
    }

    private val listeners = CopyOnWriteArrayList<GroupRidingListener>()

    fun addListener(listener: GroupRidingListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: GroupRidingListener) {
        listeners.remove(listener)
    }

    fun getState(): GroupState = state

    fun getCurrentGroup(): RidingGroup? = currentGroup

    /**
     * Create a new riding group.
     * @param groupName Display name for the group
     * @param riderName Your display name
     */
    fun createGroup(groupName: String, riderName: String) {
        myName = riderName
        val inviteCode = generateInviteCode()

        val me = GroupMember(
            id = myId,
            name = riderName,
            color = generateColor(0),
            lastHeartbeat = System.currentTimeMillis(),
            lastUpdateTime = System.currentTimeMillis(),
            isOnline = true
        )

        val group = RidingGroup(
            id = UUID.randomUUID().toString(),
            name = groupName,
            hostId = myId,
            createdAt = System.currentTimeMillis(),
            inviteCode = inviteCode,
            members = mutableListOf(me)
        )

        currentGroup = group
        state = GroupState.CONNECTED

        LOG.info("GroupRiding: Group created - '${group.name}' (code: $inviteCode)")
        listeners.forEach { it.onGroupCreated(group) }
    }

    /**
     * Join an existing riding group.
     * @param inviteCode The group's invite code
     * @param riderName Your display name
     */
    fun joinGroup(inviteCode: String, riderName: String) {
        myName = riderName
        state = GroupState.JOINING

        // In production, this would connect to the relay server or WiFi Direct host
        // For now, create a simulated group entry
        LOG.info("GroupRiding: Joining group with code $inviteCode...")
        // TODO: Implement actual P2P or MQTT connection
    }

    /**
     * Leave the current group.
     */
    fun leaveGroup() {
        val group = currentGroup ?: return
        state = GroupState.IDLE
        currentGroup = null

        LOG.info("GroupRiding: Left group '${group.name}'")
        listeners.forEach { it.onGroupLeft() }
    }

    /**
     * Update my position and broadcast to group.
     * Called from plugin's updateLocation().
     */
    fun updateMyLocation(location: Location) {
        if (state != GroupState.CONNECTED) return
        val group = currentGroup ?: return

        // Update my member entry
        val me = group.members.find { it.id == myId }
        me?.location = location
        me?.speedKmh = location.speed * 3.6f
        me?.heading = location.bearing
        me?.lastUpdateTime = System.currentTimeMillis()

        // Calculate distances to all members
        for (member in group.members) {
            if (member.id == myId) continue
            member.location?.let { memberLoc ->
                member.distanceFromMe = location.distanceTo(memberLoc)
            }
        }

        // Check proximity alerts
        checkProximityAlerts(group)

        // In production: broadcast position to other members
        // broadcastPosition(me)
    }

    /**
     * Update a remote member's position (received from network).
     */
    fun updateMemberPosition(memberId: String, location: Location, speedKmh: Float, heading: Float) {
        val group = currentGroup ?: return
        val member = group.members.find { it.id == memberId } ?: return

        member.location = location
        member.speedKmh = speedKmh
        member.heading = heading
        member.lastUpdateTime = System.currentTimeMillis()
        member.lastHeartbeat = System.currentTimeMillis()
        member.isOnline = true

        listeners.forEach { it.onMemberUpdated(member) }
    }

    /**
     * Check for proximity alerts between group members.
     */
    private fun checkProximityAlerts(group: RidingGroup) {
        for (member in group.members) {
            if (member.id == myId) continue
            if (!member.isOnline) continue

            val distance = member.distanceFromMe

            // Rider fell behind
            if (distance > REGROUP_DISTANCE_M) {
                listeners.forEach {
                    it.onProximityAlert(ProximityAlert(
                        AlertType.MEMBER_FAR_BEHIND, member, distance,
                        "${member.name} is ${"%.0f".format(distance)}m behind"
                    ))
                }
            }

            // Rider stopped unexpectedly
            if (member.speedKmh < 3f && member.distanceFromMe < REGROUP_DISTANCE_M) {
                listeners.forEach {
                    it.onProximityAlert(ProximityAlert(
                        AlertType.MEMBER_STOPPED, member, distance,
                        "${member.name} has stopped"
                    ))
                }
            }

            // Riders too close (dangerous)
            if (distance < DANGER_DISTANCE_M && distance > 0) {
                listeners.forEach {
                    it.onProximityAlert(ProximityAlert(
                        AlertType.MEMBER_TOO_CLOSE, member, distance,
                        "${member.name} is too close (${"%.0f".format(distance)}m)!"
                    ))
                }
            }

            // Check for disconnection
            if (System.currentTimeMillis() - member.lastHeartbeat > DISCONNECT_TIMEOUT_MS) {
                member.isOnline = false
                listeners.forEach {
                    it.onProximityAlert(ProximityAlert(
                        AlertType.MEMBER_DISCONNECTED, member, distance,
                        "${member.name} disconnected"
                    ))
                }
            }
        }
    }

    /**
     * Generate a short 6-digit invite code for group joining.
     */
    private fun generateInviteCode(): String {
        return "%06d".format((100000..999999).random())
    }

    /**
     * Generate a distinct color for each group member.
     */
    private fun generateColor(index: Int): Int {
        val colors = intArrayOf(
            0xFF2196F3.toInt(),  // Blue
            0xFF4CAF50.toInt(),  // Green
            0xFFFF9800.toInt(),  // Orange
            0xFF9C27B0.toInt(),  // Purple
            0xFFF44336.toInt(),  // Red
            0xFF00BCD4.toInt(),  // Cyan
            0xFFFFEB3B.toInt(),  // Yellow
            0xFFE91E63.toInt()   // Pink
        )
        return colors[index % colors.size]
    }

    /**
     * Get all group members' positions for map overlay.
     */
    fun getMemberPositions(): List<Pair<GroupMember, LatLon>> {
        val group = currentGroup ?: return emptyList()
        return group.members
            .filter { it.id != myId && it.isOnline && it.location != null }
            .map { member ->
                member to LatLon(member.location!!.latitude, member.location!!.longitude)
            }
    }
}
