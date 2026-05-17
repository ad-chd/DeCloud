package com.decloud.util

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth RFCOMM server for phone discovery.
 * When a PC connects via Bluetooth, this server sends the same discovery
 * message as UDP: DECLOUD|IP|PORT|DEVICE_NAME
 *
 * This provides a fallback discovery mechanism when WiFi/UDP discovery
 * fails (e.g., different subnets, firewall blocking UDP broadcasts).
 */
class BluetoothDiscoveryServer {

    companion object {
        private const val TAG = "BTDiscoveryServer"
        private const val SERVICE_NAME = "DeCloudDiscovery"
        val SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    }

    private var serverSocket: BluetoothServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile
    private var isRunning = false

    private var serverIp: String = ""
    private var serverPort: Int = 0

    /**
     * Start the Bluetooth RFCOMM discovery server.
     * @param ip The HTTP server IP address to advertise
     * @param port The HTTP server port to advertise
     */
    fun start(ip: String, port: Int) {
        if (isRunning) {
            Log.d(TAG, "Already running")
            return
        }

        serverIp = ip
        serverPort = port

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Log.w(TAG, "No Bluetooth adapter available")
            return
        }

        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled, skipping BT discovery server")
            return
        }

        try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
            isRunning = true

            acceptThread = Thread({
                Log.d(TAG, "Listening for BT connections (UUID=$SERVICE_UUID)")
                while (isRunning) {
                    var clientSocket: BluetoothSocket? = null
                    try {
                        clientSocket = serverSocket?.accept() // Blocks until connection
                        if (clientSocket != null) {
                            handleConnection(clientSocket)
                        }
                    } catch (e: IOException) {
                        if (isRunning) {
                            Log.e(TAG, "Accept failed: ${e.message}")
                        }
                        // If not running, this is expected (socket closed during stop)
                    }
                }
            }, "BT-Discovery-Accept").also { it.isDaemon = true; it.start() }

            Log.d(TAG, "Started: advertising $serverIp:$serverPort")
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth permission denied: ${e.message}")
            isRunning = false
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create server socket: ${e.message}")
            isRunning = false
        }
    }

    private fun handleConnection(socket: BluetoothSocket) {
        try {
            val deviceName = android.os.Build.MODEL.replace("|", "-")
            val message = "DECLOUD|$serverIp|$serverPort|$deviceName\n"
            socket.outputStream.write(message.toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()
            Log.d(TAG, "Sent discovery info to ${socket.remoteDevice?.address}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send discovery info: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (e: IOException) {
                // Ignore
            }
        }
    }

    /**
     * Stop the Bluetooth discovery server.
     */
    fun stop() {
        isRunning = false

        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // Ignore
        }
        serverSocket = null

        acceptThread?.interrupt()
        acceptThread = null

        Log.d(TAG, "Stopped")
    }
}
