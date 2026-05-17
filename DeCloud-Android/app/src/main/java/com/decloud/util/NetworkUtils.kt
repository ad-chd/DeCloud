package com.decloud.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Utility class for network operations
 * Supports both Wi-Fi Hotspot and USB Tethering modes
 */
object NetworkUtils {

    enum class ConnectionType {
        WIFI_HOTSPOT,
        USB_TETHERING,
        WIFI_LAN,
        NONE
    }

    data class ConnectionInfo(
        val type: ConnectionType,
        val ipAddress: String,
        val displayName: String
    )

    /**
     * Get the best available connection for file transfer
     * Priority: Wi-Fi Hotspot > USB Tethering > Wi-Fi LAN
     */
    fun getConnectionInfo(): ConnectionInfo {
        // Check Wi-Fi Hotspot first (fastest for direct connection)
        getHotspotIpAddress()?.let {
            return ConnectionInfo(
                type = ConnectionType.WIFI_HOTSPOT,
                ipAddress = it,
                displayName = "Wi-Fi Hotspot"
            )
        }

        // Check USB Tethering
        getUsbTetheringIpAddress()?.let {
            return ConnectionInfo(
                type = ConnectionType.USB_TETHERING,
                ipAddress = it,
                displayName = "USB Tethering"
            )
        }

        // Check Wi-Fi LAN (phone connected to router)
        getWifiLanIpAddress()?.let {
            return ConnectionInfo(
                type = ConnectionType.WIFI_LAN,
                ipAddress = it,
                displayName = "Wi-Fi"
            )
        }

        // Last resort: try ANY valid local IP (catches non-standard hotspot interfaces)
        getLocalIpAddress()?.let {
            android.util.Log.w("NetworkUtils", "Using fallback IP detection: $it")
            return ConnectionInfo(
                type = ConnectionType.WIFI_HOTSPOT,
                ipAddress = it,
                displayName = "Network"
            )
        }

        return ConnectionInfo(
            type = ConnectionType.NONE,
            ipAddress = "0.0.0.0",
            displayName = "Not Connected"
        )
    }

    /**
     * Get the device's IP address when Wi-Fi Hotspot is enabled
     * Works WITHOUT mobile data - purely local network detection
     */
    fun getHotspotIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val allIPs = mutableListOf<Triple<String, String, Boolean>>() // (interfaceName, ip, isExcluded)

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val name = networkInterface.name.lowercase()

                // Skip loopback and down interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                // Classify: is this definitely NOT a hotspot interface?
                val isExcluded = name.contains("dummy") ||  // Dummy interfaces
                        name.contains("rmnet") ||            // Mobile data (carrier)
                        name.contains("ccmni") ||            // MediaTek mobile data
                        name.contains("pdp") ||              // Older mobile data
                        name.startsWith("v4-") ||            // VPN (clat)
                        name.startsWith("tun") ||            // VPN tunnel
                        name.startsWith("ppp") ||            // PPP dial-up
                        name.contains("rndis") ||            // USB tethering (handled separately)
                        name.startsWith("usb")               // USB interfaces

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress ?: continue
                        if (!ip.startsWith("127.")) {
                            allIPs.add(Triple(name, ip, isExcluded))
                        }
                    }
                }
            }

            // Only consider non-excluded interfaces for hotspot detection
            val hotspotCandidates = allIPs.filter { !it.third }

            // Priority 1: Known hotspot gateway IPs
            for ((name, ip, _) in hotspotCandidates) {
                if (ip == "192.168.43.1" || ip == "192.168.49.1" ||
                    ip == "192.168.137.1" || ip == "172.20.10.1") {
                    android.util.Log.d("NetworkUtils", "Found hotspot IP: $ip on $name")
                    return ip
                }
            }

            // Priority 2: Known hotspot interface names with gateway IP
            for ((name, ip, _) in hotspotCandidates) {
                if (name.contains("ap") || name.contains("softap") ||
                    name == "swlan0" || name.contains("wlan")) {
                    if (ip.endsWith(".1") && isPrivateIp(ip)) {
                        android.util.Log.d("NetworkUtils", "Found hotspot by interface: $ip on $name")
                        return ip
                    }
                }
            }

            // Priority 3: Any gateway (.1) on a private range — on any non-excluded interface
            for ((name, ip, _) in hotspotCandidates) {
                if (ip.endsWith(".1") && isPrivateIp(ip)) {
                    android.util.Log.d("NetworkUtils", "Found likely hotspot: $ip on $name")
                    return ip
                }
            }

            // Priority 4: Any private IP on a hotspot-named interface (non-.1)
            for ((name, ip, _) in hotspotCandidates) {
                if ((name.contains("ap") || name.contains("softap") || name.contains("swlan")) &&
                    isPrivateIp(ip)) {
                    android.util.Log.d("NetworkUtils", "Found hotspot by interface (non-.1): $ip on $name")
                    return ip
                }
            }

            // Priority 5: ANY private IP on ANY non-excluded interface
            // This catches hotspots with unusual interface names or non-.1 IPs
            for ((name, ip, _) in hotspotCandidates) {
                if (isPrivateIp(ip)) {
                    android.util.Log.d("NetworkUtils", "Found hotspot by exclusion: $ip on $name")
                    return ip
                }
            }

            // Log all found IPs for debugging
            if (allIPs.isNotEmpty()) {
                android.util.Log.d("NetworkUtils", "All IPs found: ${allIPs.map { "(${it.first}, ${it.second}, excluded=${it.third})" }}")
            }

        } catch (e: Exception) {
            android.util.Log.e("NetworkUtils", "Error detecting hotspot: ${e.message}")
        }
        return null
    }

    private fun isPrivateIp(ip: String): Boolean {
        return ip.startsWith("192.168.") || ip.startsWith("10.") ||
                (ip.startsWith("172.") && run {
                    val second = ip.split(".").getOrNull(1)?.toIntOrNull() ?: 0
                    second in 16..31
                })
    }

    /**
     * Get the device's IP address on the USB tethering interface
     * USB tethering typically uses the "rndis" or "usb" interface
     */
    fun getUsbTetheringIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val name = networkInterface.name.lowercase()

                // USB tethering interfaces
                if (name.contains("rndis") || name.contains("usb") ||
                    name.startsWith("usb") || name == "rndis0") {

                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address is Inet4Address) {
                            return address.hostAddress
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
     * Get Wi-Fi LAN IP address (when connected to a router)
     */
    fun getWifiLanIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val name = networkInterface.name.lowercase()

                // Skip loopback and down interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                // Wi-Fi interface
                if (name.startsWith("wlan") || name.contains("wifi")) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address is Inet4Address) {
                            val ip = address.hostAddress ?: continue
                            // Exclude hotspot IPs
                            if (!ip.startsWith("192.168.43.") && !ip.startsWith("192.168.49.")) {
                                return ip
                            }
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
     * Get any local IP address (fallback) - works without internet
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null

            // Collect all valid IPs
            val validIPs = mutableListOf<String>()

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                // Skip loopback
                if (networkInterface.isLoopback) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        if (ip != null && !ip.startsWith("127.")) {
                            validIPs.add(ip)
                        }
                    }
                }
            }

            // Prefer 192.168.x.x addresses
            validIPs.find { it.startsWith("192.168.") }?.let { return it }

            // Then any private IP
            validIPs.find {
                it.startsWith("10.") || it.startsWith("172.")
            }?.let { return it }

            // Any IP
            return validIPs.firstOrNull()

        } catch (e: Exception) {
            android.util.Log.e("NetworkUtils", "Error getting local IP: ${e.message}")
        }
        return null
    }

    /**
     * Check if Wi-Fi Hotspot is enabled
     */
    fun isHotspotEnabled(): Boolean {
        return getHotspotIpAddress() != null
    }

    /**
     * Check if USB tethering is enabled
     */
    fun isUsbTetheringEnabled(): Boolean {
        return getUsbTetheringIpAddress() != null
    }

    /**
     * Get the best available IP address for the server
     */
    fun getServerIpAddress(): String {
        return getConnectionInfo().ipAddress
    }

    /**
     * Check if device has any valid connection for transfer
     */
    fun hasValidConnection(): Boolean {
        return getConnectionInfo().type != ConnectionType.NONE
    }

    /**
     * Get port number for the server
     */
    fun getServerPort(): Int = 64666

    /**
     * Get instructions for connecting based on current mode
     */
    fun getConnectionInstructions(type: ConnectionType): String {
        return when (type) {
            ConnectionType.WIFI_HOTSPOT ->
                "Connect your PC to this phone's Wi-Fi Hotspot"
            ConnectionType.USB_TETHERING ->
                "USB Tethering active. PC should be connected via USB."
            ConnectionType.WIFI_LAN ->
                "Make sure your PC is on the same Wi-Fi network"
            ConnectionType.NONE ->
                "Enable Wi-Fi Hotspot or USB Tethering"
        }
    }
}
