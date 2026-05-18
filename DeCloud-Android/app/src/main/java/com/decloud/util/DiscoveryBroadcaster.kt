package com.decloud.util

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Broadcasts the server's presence on the network via UDP
 * This allows PC clients to automatically discover the phone
 * without needing to manually enter IP address
 */
class DiscoveryBroadcaster {

    companion object {
        private const val TAG = "DiscoveryBroadcaster"
        const val DISCOVERY_PORT = 8081  // UDP port for discovery
        const val BROADCAST_INTERVAL_MS = 1000L  // Broadcast every 1 second
        const val PROTOCOL_PREFIX = "DECLOUD"
    }

    private var broadcastJob: Job? = null
    private var socket: DatagramSocket? = null
    private var isRunning = false

    /**
     * Start broadcasting server presence
     * @param serverIp The IP address of the HTTP server
     * @param serverPort The port of the HTTP server
     */
    fun startBroadcasting(serverIp: String, serverPort: Int) {
        if (isRunning) {
            Log.d(TAG, "Already broadcasting")
            return
        }

        if (serverIp == "0.0.0.0" || serverIp.isBlank()) {
            Log.w(TAG, "Cannot broadcast with invalid IP: $serverIp")
            return
        }

        isRunning = true

        broadcastJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                    reuseAddress = true
                }

                // Message format: DECLOUD|IP|PORT|DEVICE_NAME
                val deviceName = android.os.Build.MODEL.replace("|", "-")
                val message = "$PROTOCOL_PREFIX|$serverIp|$serverPort|$deviceName"
                val messageBytes = message.toByteArray(Charsets.UTF_8)

                Log.d(TAG, "Starting broadcast: $message (no internet required)")

                while (isActive && isRunning) {
                    try {
                        // Method 1: Broadcast to 255.255.255.255 (global broadcast)
                        try {
                            val broadcastAddress = InetAddress.getByName("255.255.255.255")
                            val packet = DatagramPacket(
                                messageBytes,
                                messageBytes.size,
                                broadcastAddress,
                                DISCOVERY_PORT
                            )
                            socket?.send(packet)
                        } catch (e: Exception) {
                            Log.w(TAG, "Global broadcast failed: ${e.message}")
                        }

                        // Method 2: Subnet broadcast (more reliable without internet)
                        broadcastToSubnet(serverIp, messageBytes)

                        // Method 3: Common hotspot subnet broadcasts
                        broadcastToCommonSubnets(messageBytes)

                    } catch (e: Exception) {
                        Log.e(TAG, "Broadcast error: ${e.message}")
                    }

                    delay(BROADCAST_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start broadcasting: ${e.message}")
            }
        }
    }

    /**
     * Broadcast to common hotspot subnets (works without internet)
     */
    private fun broadcastToCommonSubnets(messageBytes: ByteArray) {
        val commonSubnets = listOf(
            "192.168.43.255",  // Android hotspot
            "192.168.49.255",  // Some Android devices
            "192.168.137.255", // Windows ICS
            "172.20.10.255"    // iOS hotspot
        )

        for (subnet in commonSubnets) {
            try {
                val address = InetAddress.getByName(subnet)
                val packet = DatagramPacket(
                    messageBytes,
                    messageBytes.size,
                    address,
                    DISCOVERY_PORT
                )
                socket?.send(packet)
            } catch (e: Exception) {
                // Ignore - not all subnets will be reachable
            }
        }
    }

    /**
     * Broadcast to the specific subnet (e.g., 192.168.43.255)
     */
    private fun broadcastToSubnet(serverIp: String, messageBytes: ByteArray) {
        try {
            // Get subnet broadcast address (e.g., 192.168.43.255)
            val parts = serverIp.split(".")
            if (parts.size == 4) {
                val subnetBroadcast = "${parts[0]}.${parts[1]}.${parts[2]}.255"
                val subnetAddress = InetAddress.getByName(subnetBroadcast)
                val packet = DatagramPacket(
                    messageBytes,
                    messageBytes.size,
                    subnetAddress,
                    DISCOVERY_PORT
                )
                socket?.send(packet)
            }
        } catch (e: Exception) {
            // Ignore subnet broadcast errors
        }
    }

    /**
     * Stop broadcasting
     */
    fun stopBroadcasting() {
        isRunning = false
        broadcastJob?.cancel()
        broadcastJob = null

        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        socket = null

        Log.d(TAG, "Broadcasting stopped")
    }
}
