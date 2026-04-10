/**
 * 現在接続中の Bluetooth 端末一覧を取得するファイルです。
 *
 * 役割:
 * - Bluetooth の接続権限があるかを確認する
 * - ヘッドホンなど、現在つながっている端末一覧を取得する
 * - 条件追加や自動判定で使いやすい形に整える
 *
 * 関係する主なファイル:
 * - [MainActivity]: 条件追加ダイアログで接続中端末を候補として表示する
 * - [ConnectionMonitorService]: 自動切替時に Bluetooth 条件へ一致するか判定する
 * - [ConnectedBluetoothDevice]: 取得した端末情報を UI / 判定で扱いやすくする型
 *
 * Android に不慣れな人向けの見方:
 * - 「Bluetooth 接続中の端末を知りたい」ときの入口
 * - Android の Bluetooth API は profile ごとに取得方法が違うため、その面倒さをここに閉じ込めている
 */
package com.example.wifi_volume.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.wifi_volume.log.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class BluetoothStateResolver(context: Context) {
    private val bluetoothManager =
        context.getSystemService(BluetoothManager::class.java)
            ?: error("BluetoothManager is not available")
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val appContext = context.applicationContext

    suspend fun getConnectedDevices(): List<ConnectedBluetoothDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        if (!hasBluetoothConnectPermission()) {
            AppLog.w(LOG_AREA, "getConnectedDevices: missing BLUETOOTH_CONNECT permission")
            return emptyList()
        }

        val devicesByAddress = linkedMapOf<String, ConnectedBluetoothDevice>()
        connectedProfiles().forEach { profile ->
            fetchConnectedDevicesWithTimeout(adapter, profile).forEach { device ->
                devicesByAddress.putIfAbsent(
                    device.address,
                    ConnectedBluetoothDevice(
                        name = runCatching { device.name }.getOrNull(),
                        address = device.address,
                    ),
                )
            }
        }
        return devicesByAddress.values.toList().also { devices ->
            AppLog.d(LOG_AREA, "getConnectedDevices: devices=${devices.map { it.displayName }}")
        }
    }

    suspend fun isAnyDeviceConnected(): Boolean {
        return getConnectedDevices().isNotEmpty()
    }

    fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun fetchConnectedDevicesWithTimeout(
        adapter: BluetoothAdapter,
        profile: Int,
    ): List<android.bluetooth.BluetoothDevice> {
        return withTimeoutOrNull(PROFILE_PROXY_TIMEOUT_MS) {
            fetchConnectedDevices(adapter, profile)
        } ?: emptyList<android.bluetooth.BluetoothDevice>().also {
            AppLog.w(LOG_AREA, "fetchConnectedDevicesWithTimeout: timeout profile=$profile")
        }
    }

    private suspend fun fetchConnectedDevices(
        adapter: BluetoothAdapter,
        profile: Int,
    ): List<android.bluetooth.BluetoothDevice> {
        return suspendCancellableCoroutine { continuation ->
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                    val devices = runCatching { proxy.connectedDevices }.getOrDefault(emptyList())
                    runCatching { adapter.closeProfileProxy(profileId, proxy) }
                    if (continuation.isActive) {
                        continuation.resume(devices)
                    }
                }

                override fun onServiceDisconnected(profileId: Int) = Unit
            }

            val started = runCatching {
                adapter.getProfileProxy(appContext, listener, profile)
            }.getOrDefault(false)

            if (!started && continuation.isActive) {
                continuation.resume(emptyList())
            }
        }
    }

    private fun connectedProfiles(): List<Int> {
        return listOf(
            BluetoothProfile.A2DP,
            BluetoothProfile.HEADSET,
            BluetoothProfile.HEARING_AID,
            BluetoothProfile.LE_AUDIO,
        )
    }

    private companion object {
        private const val LOG_AREA = "BluetoothState"
        const val PROFILE_PROXY_TIMEOUT_MS = 1_500L
    }
}
