/**
 * 現在のネットワーク状態を調べるファイルです。
 *
 * 役割:
 * - いま Wi-Fi か、それ以外かを判定する
 * - 可能なら現在接続中の Wi-Fi 名 (SSID) を取得する
 *
 * 関係する主なファイル:
 * - [MainActivity]: 条件追加ダイアログで「今つながっている Wi-Fi」を候補に出す時に使う
 * - [ConnectionMonitorService]: バックグラウンド監視で、いまどの設定に一致するか判定する時に使う
 * - [SettingsRepository]: ここ自体は保存をしないが、保存済みルールと照合する入力値を作る
 *
 * Android に不慣れな人向けの見方:
 * - 「接続中の Wi-Fi を知りたい」ときの入口
 * - Android では SSID 取得に権限や端末設定が関わるため、ここにその判定も集めている
 */
package com.example.wifi_volume.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.location.LocationManager
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
    private val wifiManager: WifiManager? =
        appContext.getSystemService(WifiManager::class.java)
    private val locationManager: LocationManager? =
        appContext.getSystemService(LocationManager::class.java)

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
        if (!hasWifiSsidPermissions()) {
            return null
        }

        val transportSsid = (capabilities.transportInfo as? WifiInfo)?.let(::sanitizeSsid)
        if (transportSsid != null) {
            return transportSsid
        }

        return wifiManager?.connectionInfo?.let(::sanitizeSsid)
    }

    private fun sanitizeSsid(wifiInfo: WifiInfo): String? {
        val rawSsid = runCatching { wifiInfo.ssid }.getOrNull()
        return rawSsid
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID }
    }

    private fun hasWifiSsidPermissions(): Boolean {
        if (locationManager?.isLocationEnabled == false) {
            return false
        }

        val hasLocationPermission = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) {
            return false
        }

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
