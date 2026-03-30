package com.example.wifi_volume.model

import android.media.AudioManager
import com.example.wifi_volume.bluetooth.ConnectedBluetoothDevice
import com.example.wifi_volume.network.ConnectionState

enum class RingerModeOption(val audioManagerValue: Int) {
    NORMAL(AudioManager.RINGER_MODE_NORMAL),
    VIBRATE(AudioManager.RINGER_MODE_VIBRATE),
    SILENT(AudioManager.RINGER_MODE_SILENT),
    ;

    companion object {
        fun fromAudioManagerValue(value: Int): RingerModeOption {
            return entries.firstOrNull { it.audioManagerValue == value } ?: NORMAL
        }
    }
}

data class VolumeProfile(
    val media: Int,
    val ring: Int,
    val notification: Int,
    val alarm: Int,
    val ringerMode: RingerModeOption,
)

enum class ReapplyMode {
    ALWAYS,
    ON_CHANGE_ONLY,
}

enum class RuleConditionType {
    MOBILE_DEFAULT,
    WIFI_ANY,
    WIFI_SSID,
    BLUETOOTH_ANY,
    BLUETOOTH_DEVICE,
}

data class RuleCondition(
    val type: RuleConditionType,
    val value: String? = null,
    val label: String,
) {
    fun canDelete(): Boolean {
        return type != RuleConditionType.MOBILE_DEFAULT
    }

    fun description(): String {
        return when (type) {
            RuleConditionType.MOBILE_DEFAULT -> "その他"
            RuleConditionType.WIFI_ANY -> "任意の Wi-Fi 接続"
            RuleConditionType.WIFI_SSID -> "特定の Wi-Fi"
            RuleConditionType.BLUETOOTH_ANY -> "任意の Bluetooth 接続"
            RuleConditionType.BLUETOOTH_DEVICE -> "特定の Bluetooth 端末"
        }
    }
}

data class RuleConfig(
    val id: String,
    val condition: RuleCondition,
    val priority: Int,
    val volumeProfile: VolumeProfile,
)

data class AppSettings(
    val rules: List<RuleConfig>,
    val reapplyMode: ReapplyMode,
) {
    fun sortedRules(): List<RuleConfig> {
        return rules.sortedWith(
            compareBy<RuleConfig> { it.condition.type == RuleConditionType.MOBILE_DEFAULT }
                .thenBy { it.priority },
        )
    }

    fun findRule(ruleId: String?): RuleConfig? {
        return rules.firstOrNull { it.id == ruleId }
    }

    fun mobileRule(): RuleConfig {
        return rules.first { it.condition.type == RuleConditionType.MOBILE_DEFAULT }
    }
}

data class ActiveRuleState(
    val ruleId: String?,
    val label: String?,
)

data class VolumeLimits(
    val mediaMax: Int,
    val ringMax: Int,
    val notificationMax: Int,
    val alarmMax: Int,
)

data class DeviceState(
    val connectionState: ConnectionState,
    val currentWifiSsid: String?,
    val connectedBluetoothDevices: List<ConnectedBluetoothDevice>,
)

class RuleEvaluator {
    fun resolveActiveRule(settings: AppSettings, deviceState: DeviceState): RuleConfig {
        return settings.sortedRules().firstOrNull { matches(it, deviceState) } ?: settings.mobileRule()
    }

    private fun matches(rule: RuleConfig, deviceState: DeviceState): Boolean {
        return when (rule.condition.type) {
            RuleConditionType.MOBILE_DEFAULT -> true
            RuleConditionType.WIFI_ANY -> deviceState.connectionState == ConnectionState.WIFI
            RuleConditionType.WIFI_SSID ->
                deviceState.connectionState == ConnectionState.WIFI &&
                    rule.condition.value != null &&
                    rule.condition.value == deviceState.currentWifiSsid
            RuleConditionType.BLUETOOTH_ANY -> deviceState.connectedBluetoothDevices.isNotEmpty()
            RuleConditionType.BLUETOOTH_DEVICE ->
                rule.condition.value != null &&
                    deviceState.connectedBluetoothDevices.any { it.address == rule.condition.value }
        }
    }
}
