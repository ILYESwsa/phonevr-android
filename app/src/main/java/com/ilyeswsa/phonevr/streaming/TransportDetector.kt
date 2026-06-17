package com.ilyeswsa.phonevr.streaming

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Detects whether to use USB (ADB tunnel via 127.0.0.1) or WiFi transport.
 *
 * USB mode:
 *   The PC companion runs:
 *     adb forward tcp:6000 tcp:6000   (tracking: phone→PC)
 *     adb forward tcp:6002 tcp:6002   (controllers: phone→PC)
 *     adb reverse tcp:6001 tcp:6001   (video: PC→phone)
 *   So the phone connects everything to 127.0.0.1.
 *
 * WiFi mode:
 *   The phone connects to the PC's local IP directly.
 *
 * Auto-detect: try connecting to 127.0.0.1:6000.
 *   If it succeeds → ADB tunnel is active → USB mode.
 *   If it fails    → use the IP from the input field → WiFi mode.
 */
object TransportDetector {

    enum class Transport { USB, WIFI }

    data class TransportConfig(
        val transport: Transport,
        val ip: String,           // IP to connect to
        val trackingPort: Int = 6000,
        val videoPort: Int    = 6001,
        val controllerPort: Int = 6002
    )

    /**
     * Auto-detects transport mode.
     * @param manualIp The IP the user typed in (used for WiFi fallback).
     * @param timeoutMs How long to try ADB probe (default 300ms).
     */
    suspend fun detect(manualIp: String, timeoutMs: Int = 300): TransportConfig =
        withContext(Dispatchers.IO) {
            if (probeAdbTunnel(timeoutMs)) {
                TransportConfig(Transport.USB, "127.0.0.1")
            } else {
                TransportConfig(Transport.WIFI, manualIp)
            }
        }

    /**
     * Probe 127.0.0.1:6000 — if the ADB forward is active the PC driver is
     * listening there and the connection will succeed immediately.
     */
    private fun probeAdbTunnel(timeoutMs: Int): Boolean {
        return try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress("127.0.0.1", 6000), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Gets the device's WiFi IP address (useful to display in the UI
     * so the user knows what to type into the PC companion).
     */
    fun getWifiIp(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        if (ip == 0) return null
        return String.format("%d.%d.%d.%d",
            ip and 0xff, (ip shr 8) and 0xff,
            (ip shr 16) and 0xff, (ip shr 24) and 0xff)
    }
}
