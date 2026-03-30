package com.example.wifi_volume.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
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
            return emptyList()
        }

        val devicesByAddress = linkedMapOf<String, ConnectedBluetoothDevice>()
        connectedProfiles().forEach { profile ->
            fetchConnectedDevices(adapter, profile).forEach { device ->
                devicesByAddress.putIfAbsent(
                    device.address,
                    ConnectedBluetoothDevice(
                        name = runCatching { device.name }.getOrNull(),
                        address = device.address,
                    ),
                )
            }
        }
        return devicesByAddress.values.toList()
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
}
