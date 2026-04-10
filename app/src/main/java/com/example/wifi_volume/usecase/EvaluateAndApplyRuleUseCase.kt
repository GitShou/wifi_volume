/**
 * 監視サービスから呼ばれる「現在の接続状況を評価して、必要なら音量設定を適用する」処理本体です。
 *
 * 役割:
 * - 現在の端末状態を取得する
 * - 保存済み設定の中から、いま適用すべき設定を選ぶ
 * - 再適用が必要なら音量設定を反映する
 * - 適用中設定の保存と、必要時の通知を行う
 *
 * 関係する主なファイル:
 * - [ConnectionMonitorService]: Android のイベントを受け取って、この UseCase を呼び出す
 * - [VolumeProfile.kt]: [RuleEvaluator] と [WifiRuleRetentionPolicy] を使って適用ルールを決める
 * - [SettingsRepository]: 保存済み設定と適用中状態の保存先
 *
 * Android に不慣れな人向けの見方:
 * - 「アプリとして何をするか」をまとめた中間層
 * - Android の API を直接触らず、interface 越しに外部機能を使う
 * - そのため WSL 上の単体テストで振る舞いを確認しやすい
 */
package com.example.wifi_volume.usecase

import com.example.wifi_volume.log.AppLog
import com.example.wifi_volume.model.ActiveRuleState
import com.example.wifi_volume.model.AppSettings
import com.example.wifi_volume.model.ConditionRetentionState
import com.example.wifi_volume.model.ConditionStateRetentionPolicy
import com.example.wifi_volume.model.DeviceState
import com.example.wifi_volume.model.ReapplyMode
import com.example.wifi_volume.model.VolumeProfile

interface DeviceStateProvider {
    suspend fun getCurrentDeviceState(): DeviceState
}

interface SettingsStore {
    suspend fun getSettings(): AppSettings?

    suspend fun getActiveRuleState(): ActiveRuleState

    suspend fun setActiveRuleState(ruleId: String, label: String)
}

interface VolumeProfileApplier {
    fun applyProfile(profile: VolumeProfile)
}

interface RuleChangeNotifier {
    fun notifyRuleChanged(ruleLabel: String)
}

fun interface ElapsedTimeProvider {
    fun nowMs(): Long
}

data class EvaluateAndApplyRuleResult(
    val ruleId: String,
    val displayLabel: String,
    val appliedProfile: Boolean,
    val notifiedRuleChange: Boolean,
)

class EvaluateAndApplyRuleUseCase(
    private val deviceStateProvider: DeviceStateProvider,
    private val settingsStore: SettingsStore,
    private val volumeProfileApplier: VolumeProfileApplier,
    private val ruleChangeNotifier: RuleChangeNotifier,
    private val elapsedTimeProvider: ElapsedTimeProvider,
    private val conditionStateRetentionPolicy: ConditionStateRetentionPolicy,
) {
    private var conditionRetentionState = ConditionRetentionState()

    suspend fun execute(): EvaluateAndApplyRuleResult? {
        val settings = settingsStore.getSettings() ?: return null
        val previousActiveRuleState = settingsStore.getActiveRuleState()
        val deviceState = deviceStateProvider.getCurrentDeviceState()
        val nowMs = elapsedTimeProvider.nowMs()
        AppLog.d(
            LOG_AREA,
            "execute: prevRule=${previousActiveRuleState.ruleId} connection=${deviceState.connectionState} ssid=${deviceState.currentWifiSsid} bt=${deviceState.connectedBluetoothDevices.map { it.displayName }}",
        )
        val retainedResolution = conditionStateRetentionPolicy.resolveActiveRule(
            settings = settings,
            deviceState = deviceState,
            state = conditionRetentionState,
            nowMs = nowMs,
        )
        val resolvedRule = retainedResolution.resolvedRule
        conditionRetentionState = retainedResolution.nextState

        val shouldApply = when (settings.reapplyMode) {
            ReapplyMode.ALWAYS -> true
            ReapplyMode.ON_CHANGE_ONLY -> previousActiveRuleState.ruleId != resolvedRule.rule.id
        }
        AppLog.d(
            LOG_AREA,
            "execute: resolved=${resolvedRule.displayLabel} shouldApply=$shouldApply notifyEnabled=${settings.notifyOnRuleChange} retentionKeys=${conditionRetentionState.lastMatchedAtByCondition.keys}",
        )

        if (shouldApply) {
            volumeProfileApplier.applyProfile(resolvedRule.rule.volumeProfile)
        }
        settingsStore.setActiveRuleState(resolvedRule.rule.id, resolvedRule.displayLabel)

        val shouldNotify = settings.notifyOnRuleChange &&
            previousActiveRuleState.ruleId != null &&
            previousActiveRuleState.ruleId != resolvedRule.rule.id
        if (shouldNotify) {
            ruleChangeNotifier.notifyRuleChanged(resolvedRule.displayLabel)
        }

        return EvaluateAndApplyRuleResult(
            ruleId = resolvedRule.rule.id,
            displayLabel = resolvedRule.displayLabel,
            appliedProfile = shouldApply,
            notifiedRuleChange = shouldNotify,
        )
    }

    private companion object {
        private const val LOG_AREA = "EvaluateRuleUseCase"
    }
}
