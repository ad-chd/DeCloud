package com.decloud.hotspot

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Manages Wi-Fi Hotspot detection and settings navigation.
 * Detects hotspot IP via NetworkInterface scanning, opens system settings
 * for the user to enable hotspot, and polls until network becomes available.
 */
class HotspotManager(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var pollingRunnable: Runnable? = null
    private var isPolling = false

    var onHotspotDetected: ((ipAddress: String) -> Unit)? = null

    /**
     * Open system hotspot/tethering settings.
     */
    fun openHotspotSettings() {
        try {
            val intent = Intent()
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            // Try tethering settings first (works on most phones)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                intent.action = Settings.ACTION_WIFI_SETTINGS
            } else {
                intent.action = "android.settings.TETHERING_SETTINGS"
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to wireless settings
            try {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    /**
     * Start polling every 1s to detect when a network becomes available.
     * Uses NetworkUtils exclusion-based detection. Polls indefinitely until
     * IP is found (fires onHotspotDetected) or stopPolling() is called.
     */
    fun startPollingForNetwork() {
        if (isPolling) return
        isPolling = true

        pollingRunnable = object : Runnable {
            override fun run() {
                if (!isPolling) return

                val connectionInfo = com.decloud.util.NetworkUtils.getConnectionInfo()
                if (connectionInfo.type != com.decloud.util.NetworkUtils.ConnectionType.NONE) {
                    isPolling = false
                    onHotspotDetected?.invoke(connectionInfo.ipAddress)
                    return
                }

                handler.postDelayed(this, 1000)
            }
        }

        handler.post(pollingRunnable!!)
    }

    fun stopPolling() {
        isPolling = false
        pollingRunnable?.let { handler.removeCallbacks(it) }
    }

    fun isHotspotActive(): Boolean = getHotspotIpAddress() != null

    /**
     * Get hotspot IP if active.
     * Scans ALL network interfaces for hotspot-like IPs.
     */
    fun getHotspotIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null

            // First pass: look for common hotspot IPs on any interface
            val interfaceList = interfaces.toList()
            for (networkInterface in interfaceList) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress ?: continue

                        // Common hotspot gateway IPs
                        if (ip == "192.168.43.1" ||
                            ip == "192.168.49.1" ||
                            ip.startsWith("192.168.43.") ||
                            ip.startsWith("192.168.49.")) {
                            return ip
                        }
                    }
                }
            }

            // Second pass: any private IP that looks like a gateway (.1)
            val interfaces2 = NetworkInterface.getNetworkInterfaces() ?: return null
            for (networkInterface in interfaces2.toList()) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress ?: continue

                        // Any .1 gateway IP (likely hotspot)
                        if (ip.endsWith(".1") && ip.startsWith("192.168.")) {
                            return ip
                        }
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Get any local IP address (fallback).
     */
    fun getAnyLocalIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (networkInterface in interfaces.toList()) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
