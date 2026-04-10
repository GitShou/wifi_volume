/**
 * アプリ全体で使う「設定データの型」と「判定ルール」をまとめたファイルです。
 *
 * 役割:
 * - 音量設定そのものを表す [VolumeProfile] を定義する
 * - 1つの設定カードを表す [RuleConfig] を定義する
 * - Wi-Fi / Bluetooth 条件をどう判定するかを [RuleEvaluator] にまとめる
 *
 * 関係する主なファイル:
 * - [MainActivity]: ここで定義した型を画面編集用のデータとして使う
 * - [SettingsRepository]: ここで定義した型を JSON として保存・復元する
 * - [ConnectionMonitorService]: [RuleEvaluator] を使って「今どの設定を適用すべきか」を決める
 *
 * Android に不慣れな人向けの見方:
 * - このファイルは「画面」ではなく「アプリが扱うデータの設計図」
 * - 挙動がおかしい時に、どの条件でどの設定が選ばれるかを確認したいならここを見る
 */
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
    fun description(): String {
        return when (type) {
            RuleConditionType.WIFI_ANY -> "任意の Wi-Fi 接続"
            RuleConditionType.WIFI_SSID -> "特定の Wi-Fi"
            RuleConditionType.BLUETOOTH_ANY -> "任意の Bluetooth 接続"
            RuleConditionType.BLUETOOTH_DEVICE -> "特定の Bluetooth 端末"
        }
    }
}

data class RuleConfig(
    val id: String,
    val name: String,
    val priority: Int,
    val conditions: List<RuleCondition>,
    val volumeProfile: VolumeProfile,
    val isFallback: Boolean = false,
)

data class AppSettings(
    val rules: List<RuleConfig>,
    val reapplyMode: ReapplyMode,
    val notifyOnRuleChange: Boolean,
) {
    fun sortedRules(): List<RuleConfig> {
        return rules.sortedWith(
            compareBy<RuleConfig> { it.isFallback }
                .thenBy { it.priority },
        )
    }

    fun findRule(ruleId: String?): RuleConfig? {
        return rules.firstOrNull { it.id == ruleId }
    }

    fun fallbackRule(): RuleConfig {
        return rules.first { it.isFallback }
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

data class ConditionRetentionState(
    val lastMatchedAtByCondition: Map<String, Long> = emptyMap(),
)

class RuleEvaluator {
    fun resolveActiveRule(settings: AppSettings, deviceState: DeviceState): ResolvedRule {
        settings.sortedRules().forEach { rule ->
            if (rule.isFallback) {
                return@forEach
            }
            val matchedCondition = rule.conditions.firstOrNull { matches(it, deviceState) }
            if (matchedCondition != null) {
                return ResolvedRule(
                    rule = rule,
                    matchedLabel = matchedCondition.label,
                    displayLabel = rule.name.ifBlank { matchedCondition.label },
                )
            }
        }
        return ResolvedRule(
            rule = settings.fallbackRule(),
            matchedLabel = FALLBACK_LABEL,
            displayLabel = settings.fallbackRule().name.ifBlank { FALLBACK_LABEL },
        )
    }

    private fun matches(condition: RuleCondition, deviceState: DeviceState): Boolean {
        return when (condition.type) {
            RuleConditionType.WIFI_ANY -> deviceState.connectionState == ConnectionState.WIFI
            RuleConditionType.WIFI_SSID ->
                deviceState.connectionState == ConnectionState.WIFI &&
                    condition.value != null &&
                    condition.value == deviceState.currentWifiSsid
            RuleConditionType.BLUETOOTH_ANY -> deviceState.connectedBluetoothDevices.isNotEmpty()
            RuleConditionType.BLUETOOTH_DEVICE ->
                condition.value != null &&
                    deviceState.connectedBluetoothDevices.any { it.address == condition.value }
        }
    }

    data class ResolvedRule(
        val rule: RuleConfig,
        val matchedLabel: String,
        val displayLabel: String,
    )

    companion object {
        const val FALLBACK_LABEL = "その他"
    }
}

class ConditionStateRetentionPolicy(
    private val holdMs: Long,
) {
    fun resolveActiveRule(
        settings: AppSettings,
        deviceState: DeviceState,
        state: ConditionRetentionState,
        nowMs: Long,
    ): RetainedResolution {
        val nextMatchedAtByCondition = state.lastMatchedAtByCondition
            .filterValues { lastMatchedAt -> nowMs - lastMatchedAt <= holdMs }
            .toMutableMap()
        val effectiveMatchByCondition = mutableMapOf<String, Boolean>()

        settings.sortedRules()
            .filterNot { it.isFallback }
            .forEach { rule ->
                rule.conditions.forEach { condition ->
                    val conditionKey = condition.key()
                    effectiveMatchByCondition[conditionKey] = when (evaluateCondition(condition, deviceState)) {
                        ConditionMatchResult.MATCHED -> {
                            nextMatchedAtByCondition[conditionKey] = nowMs
                            true
                        }

                        ConditionMatchResult.UNKNOWN ->
                            nextMatchedAtByCondition[conditionKey]
                                ?.let { lastMatchedAt -> nowMs - lastMatchedAt <= holdMs }
                                ?: false

                        ConditionMatchResult.NOT_MATCHED -> false
                    }
                }
            }

        settings.sortedRules().forEach { rule ->
            if (rule.isFallback) {
                return@forEach
            }
            val matchedCondition = rule.conditions.firstOrNull { condition ->
                effectiveMatchByCondition[condition.key()] == true
            }

            if (matchedCondition != null) {
                return RetainedResolution(
                    resolvedRule = RuleEvaluator.ResolvedRule(
                        rule = rule,
                        matchedLabel = matchedCondition.label,
                        displayLabel = rule.name.ifBlank { matchedCondition.label },
                    ),
                    nextState = ConditionRetentionState(nextMatchedAtByCondition.toMap()),
                )
            }
        }

        val fallbackRule = settings.fallbackRule()
        return RetainedResolution(
            resolvedRule = RuleEvaluator.ResolvedRule(
                rule = fallbackRule,
                matchedLabel = RuleEvaluator.FALLBACK_LABEL,
                displayLabel = fallbackRule.name.ifBlank { RuleEvaluator.FALLBACK_LABEL },
            ),
            nextState = ConditionRetentionState(nextMatchedAtByCondition.toMap()),
        )
    }

    private fun evaluateCondition(
        condition: RuleCondition,
        deviceState: DeviceState,
    ): ConditionMatchResult {
        return when (condition.type) {
            RuleConditionType.WIFI_ANY -> {
                if (deviceState.connectionState == ConnectionState.WIFI) {
                    ConditionMatchResult.MATCHED
                } else {
                    ConditionMatchResult.NOT_MATCHED
                }
            }

            RuleConditionType.WIFI_SSID -> when {
                deviceState.connectionState != ConnectionState.WIFI -> ConditionMatchResult.NOT_MATCHED
                deviceState.currentWifiSsid == null -> ConditionMatchResult.UNKNOWN
                condition.value == deviceState.currentWifiSsid -> ConditionMatchResult.MATCHED
                else -> ConditionMatchResult.NOT_MATCHED
            }

            RuleConditionType.BLUETOOTH_ANY -> {
                if (deviceState.connectedBluetoothDevices.isNotEmpty()) {
                    ConditionMatchResult.MATCHED
                } else {
                    ConditionMatchResult.NOT_MATCHED
                }
            }

            RuleConditionType.BLUETOOTH_DEVICE -> {
                if (condition.value != null &&
                    deviceState.connectedBluetoothDevices.any { it.address == condition.value }
                ) {
                    ConditionMatchResult.MATCHED
                } else {
                    ConditionMatchResult.NOT_MATCHED
                }
            }
        }
    }

    private fun RuleCondition.key(): String {
        return "${type.name}:${value.orEmpty()}"
    }

    data class RetainedResolution(
        val resolvedRule: RuleEvaluator.ResolvedRule,
        val nextState: ConditionRetentionState,
    )

    private enum class ConditionMatchResult {
        MATCHED,
        NOT_MATCHED,
        UNKNOWN,
    }
}
