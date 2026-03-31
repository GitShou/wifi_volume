/**
 * 接続中の Bluetooth 端末を表す、シンプルなデータ型です。
 *
 * 役割:
 * - 端末名と MAC アドレスをセットで持つ
 * - 端末名が空のときでも表示できるよう [displayName] を提供する
 *
 * 関係する主なファイル:
 * - [BluetoothStateResolver]: 実際に端末一覧を集めてこの型に変換する
 * - [MainActivity]: 条件追加ダイアログで表示名を使う
 * - [VolumeProfile.kt]: Bluetooth 条件の一致判定でアドレスを比較する
 *
 * Android に不慣れな人向けの見方:
 * - このファイル自体に処理はほとんどない
 * - 「Bluetooth 端末1件ぶんの箱」と考えると分かりやすい
 */
package com.example.wifi_volume.bluetooth

data class ConnectedBluetoothDevice(
    val name: String?,
    val address: String,
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: address
}
