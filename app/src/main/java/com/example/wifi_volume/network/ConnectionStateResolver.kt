package com.example.wifi_volume.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import androidx.core.content.ContextCompat

enum class ConnectionState {
    WIFI,
    NON_WIFI,
}

data class ConnectionSnapshot(
    val connectionState: ConnectionState,
    val currentWifiSsid: String?,
)

class ConnectionStateResolver(context: Context) {
    private val appContext = context.applicationContext
    val connectivityManager: ConnectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
            ?: error("ConnectivityManager is not available")

    fun resolve(): ConnectionState {
        return resolveSnapshot().connectionState
    }

    fun resolveSnapshot(): ConnectionSnapshot {
        val activeNetwork = connectivityManager.activeNetwork
            ?: return ConnectionSnapshot(ConnectionState.NON_WIFI, null)
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return ConnectionSnapshot(ConnectionState.NON_WIFI, null)

        return if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            ConnectionSnapshot(
                connectionState = ConnectionState.WIFI,
                currentWifiSsid = readCurrentWifiSsid(capabilities),
            )
        } else {
            ConnectionSnapshot(ConnectionState.NON_WIFI, null)
        }
    }

    private fun readCurrentWifiSsid(capabilities: NetworkCapabilities): String? {
        if (!hasNearbyWifiPermission()) {
            return null
        }

        val transportInfo = capabilities.transportInfo as? WifiInfo ?: return null
        val rawSsid = runCatching { transportInfo.ssid }.getOrNull()
        return rawSsid
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID }
    }

    private fun hasNearbyWifiPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
