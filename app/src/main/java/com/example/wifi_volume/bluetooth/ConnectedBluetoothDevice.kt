package com.example.wifi_volume.bluetooth

data class ConnectedBluetoothDevice(
    val name: String?,
    val address: String,
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: address
}
