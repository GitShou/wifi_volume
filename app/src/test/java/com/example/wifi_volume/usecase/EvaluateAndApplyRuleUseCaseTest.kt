/**
 * [EvaluateAndApplyRuleUseCase] の単体テストです。
 *
 * 役割:
 * - 接続状態の評価から音量適用、通知判定までの流れを JVM 上で確認する
 * - Android 実装を使わず、Fake 実装だけで UseCase の振る舞いを確かめる
 *
 * 関係する主なファイル:
 * - [EvaluateAndApplyRuleUseCase]: テスト対象の本体
 * - [ConnectionMonitorService]: 本番ではこのサービスが UseCase を呼び出す
 * - [VolumeProfile.kt]: ルール判定と保持ポリシーの pure ロジック定義元
 *
 * Android に不慣れな人向けの見方:
 * - このテストは「実機なしでアプリの判断だけを確かめる」もの
 * - ここで通ると、少なくとも判定手順そのものは Android なしで検証できている
 */
package com.example.wifi_volume.usecase

import com.example.wifi_volume.bluetooth.ConnectedBluetoothDevice
import com.example.wifi_volume.model.ActiveRuleState
import com.example.wifi_volume.model.AppSettings
import com.example.wifi_volume.model.ConditionStateRetentionPolicy
import com.example.wifi_volume.model.DeviceState
import com.example.wifi_volume.model.ReapplyMode
import com.example.wifi_volume.model.RingerModeOption
import com.example.wifi_volume.model.RuleCondition
import com.example.wifi_volume.model.RuleConditionType
import com.example.wifi_volume.model.RuleConfig
import com.example.wifi_volume.model.VolumeProfile
import com.example.wifi_volume.network.ConnectionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluateAndApplyRuleUseCaseTest {
    @Test
    fun `matching rule is applied and active state is updated`() {
        val settingsStore = FakeSettingsStore(
            settings = AppSettings(
                rules = listOf(
                    wifiRule(id = "wifi-home", priority = 1, ssid = "HomeWifi"),
                    fallbackRule(id = "fallback", priority = 2),
                ),
                reapplyMode = ReapplyMode.ALWAYS,
                notifyOnRuleChange = false,
            ),
            activeRuleState = ActiveRuleState(ruleId = null, label = null),
        )
        val volumeApplier = FakeVolumeProfileApplier()
        val notifier = FakeRuleChangeNotifier()
        val useCase = EvaluateAndApplyRuleUseCase(
            deviceStateProvider = FakeDeviceStateProvider(
                DeviceState(
                    connectionState = ConnectionState.WIFI,
                    currentWifiSsid = "HomeWifi",
                    connectedBluetoothDevices = emptyList(),
                ),
            ),
            settingsStore = settingsStore,
            volumeProfileApplier = volumeApplier,
            ruleChangeNotifier = notifier,
            elapsedTimeProvider = FakeElapsedTimeProvider(1_000L),
            conditionStateRetentionPolicy = ConditionStateRetentionPolicy(5_000L),
        )

        val result = runBlocking { useCase.execute() }

        assertEquals("wifi-home", result?.ruleId)
        assertTrue(result?.appliedProfile == true)
        assertFalse(result?.notifiedRuleChange == true)
        assertEquals("wifi-home", settingsStore.activeRuleState.ruleId)
        assertEquals(1, volumeApplier.appliedProfiles.size)
        assertEquals(0, notifier.notifications.size)
    }

    @Test
    fun `on change only skips applying when same rule stays active`() {
        val settingsStore = FakeSettingsStore(
            settings = AppSettings(
                rules = listOf(
                    wifiRule(id = "wifi-home", priority = 1, ssid = "HomeWifi"),
                    fallbackRule(id = "fallback", priority = 2),
                ),
                reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
                notifyOnRuleChange = true,
            ),
            activeRuleState = ActiveRuleState(ruleId = "wifi-home", label = "家"),
        )
        val volumeApplier = FakeVolumeProfileApplier()
        val notifier = FakeRuleChangeNotifier()
        val useCase = EvaluateAndApplyRuleUseCase(
            deviceStateProvider = FakeDeviceStateProvider(
                DeviceState(
                    connectionState = ConnectionState.WIFI,
                    currentWifiSsid = "HomeWifi",
                    connectedBluetoothDevices = emptyList(),
                ),
            ),
            settingsStore = settingsStore,
            volumeProfileApplier = volumeApplier,
            ruleChangeNotifier = notifier,
            elapsedTimeProvider = FakeElapsedTimeProvider(1_000L),
            conditionStateRetentionPolicy = ConditionStateRetentionPolicy(5_000L),
        )

        val result = runBlocking { useCase.execute() }

        assertEquals("wifi-home", result?.ruleId)
        assertFalse(result?.appliedProfile == true)
        assertFalse(result?.notifiedRuleChange == true)
        assertTrue(volumeApplier.appliedProfiles.isEmpty())
        assertTrue(notifier.notifications.isEmpty())
    }

    @Test
    fun `rule change sends notification after active rule changes`() {
        val settingsStore = FakeSettingsStore(
            settings = AppSettings(
                rules = listOf(
                    bluetoothRule(id = "headphones", priority = 1, address = "AA:BB"),
                    fallbackRule(id = "fallback", priority = 2),
                ),
                reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
                notifyOnRuleChange = true,
            ),
            activeRuleState = ActiveRuleState(ruleId = "fallback", label = "その他"),
        )
        val volumeApplier = FakeVolumeProfileApplier()
        val notifier = FakeRuleChangeNotifier()
        val useCase = EvaluateAndApplyRuleUseCase(
            deviceStateProvider = FakeDeviceStateProvider(
                DeviceState(
                    connectionState = ConnectionState.NON_WIFI,
                    currentWifiSsid = null,
                    connectedBluetoothDevices = listOf(
                        ConnectedBluetoothDevice(name = "Headphones", address = "AA:BB"),
                    ),
                ),
            ),
            settingsStore = settingsStore,
            volumeProfileApplier = volumeApplier,
            ruleChangeNotifier = notifier,
            elapsedTimeProvider = FakeElapsedTimeProvider(1_000L),
            conditionStateRetentionPolicy = ConditionStateRetentionPolicy(5_000L),
        )

        val result = runBlocking { useCase.execute() }

        assertEquals("headphones", result?.ruleId)
        assertTrue(result?.appliedProfile == true)
        assertTrue(result?.notifiedRuleChange == true)
        assertEquals(listOf("ヘッドホン"), notifier.notifications)
    }

    @Test
    fun `previously observed wifi condition can win after bluetooth disconnect when ssid is temporarily unknown`() {
        val settingsStore = FakeSettingsStore(
            settings = AppSettings(
                rules = listOf(
                    bluetoothRule(id = "headphones", priority = 1, address = "AA:BB"),
                    wifiRule(id = "wifi-home", priority = 2, ssid = "HomeWifi"),
                    fallbackRule(id = "fallback", priority = 3),
                ),
                reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
                notifyOnRuleChange = false,
            ),
            activeRuleState = ActiveRuleState(ruleId = "headphones", label = "ヘッドホン"),
        )
        val useCase = EvaluateAndApplyRuleUseCase(
            deviceStateProvider = SequencedDeviceStateProvider(
                listOf(
                    DeviceState(
                        connectionState = ConnectionState.WIFI,
                        currentWifiSsid = "HomeWifi",
                        connectedBluetoothDevices = listOf(
                            ConnectedBluetoothDevice(name = "Headphones", address = "AA:BB"),
                        ),
                    ),
                    DeviceState(
                        connectionState = ConnectionState.WIFI,
                        currentWifiSsid = null,
                        connectedBluetoothDevices = emptyList(),
                    ),
                ),
            ),
            settingsStore = settingsStore,
            volumeProfileApplier = FakeVolumeProfileApplier(),
            ruleChangeNotifier = FakeRuleChangeNotifier(),
            elapsedTimeProvider = SequencedElapsedTimeProvider(listOf(1_000L, 2_000L)),
            conditionStateRetentionPolicy = ConditionStateRetentionPolicy(5_000L),
        )

        runBlocking { useCase.execute() }
        val result = runBlocking { useCase.execute() }

        assertEquals("wifi-home", result?.ruleId)
    }

    private fun wifiRule(id: String, priority: Int, ssid: String): RuleConfig {
        return RuleConfig(
            id = id,
            name = "家",
            priority = priority,
            conditions = listOf(
                RuleCondition(
                    type = RuleConditionType.WIFI_SSID,
                    value = ssid,
                    label = "Wi-Fi: $ssid",
                ),
            ),
            volumeProfile = sampleProfile(),
        )
    }

    private fun bluetoothRule(id: String, priority: Int, address: String): RuleConfig {
        return RuleConfig(
            id = id,
            name = "ヘッドホン",
            priority = priority,
            conditions = listOf(
                RuleCondition(
                    type = RuleConditionType.BLUETOOTH_DEVICE,
                    value = address,
                    label = "Bluetooth: Headphones",
                ),
            ),
            volumeProfile = sampleProfile(),
        )
    }

    private fun fallbackRule(id: String, priority: Int): RuleConfig {
        return RuleConfig(
            id = id,
            name = "その他",
            priority = priority,
            conditions = emptyList(),
            volumeProfile = sampleProfile(),
            isFallback = true,
        )
    }

    private fun sampleProfile(): VolumeProfile {
        return VolumeProfile(
            media = 4,
            ring = 3,
            notification = 3,
            alarm = 5,
            ringerMode = RingerModeOption.NORMAL,
        )
    }
}

private class FakeDeviceStateProvider(
    private val deviceState: DeviceState,
) : DeviceStateProvider {
    override suspend fun getCurrentDeviceState(): DeviceState = deviceState
}

private class FakeSettingsStore(
    private val settings: AppSettings?,
    activeRuleState: ActiveRuleState,
) : SettingsStore {
    var activeRuleState: ActiveRuleState = activeRuleState
        private set

    override suspend fun getSettings(): AppSettings? = settings

    override suspend fun getActiveRuleState(): ActiveRuleState = activeRuleState

    override suspend fun setActiveRuleState(ruleId: String, label: String) {
        activeRuleState = ActiveRuleState(ruleId = ruleId, label = label)
    }
}

private class FakeVolumeProfileApplier : VolumeProfileApplier {
    val appliedProfiles = mutableListOf<VolumeProfile>()

    override fun applyProfile(profile: VolumeProfile) {
        appliedProfiles += profile
    }
}

private class FakeRuleChangeNotifier : RuleChangeNotifier {
    val notifications = mutableListOf<String>()

    override fun notifyRuleChanged(ruleLabel: String) {
        notifications += ruleLabel
    }
}

private class FakeElapsedTimeProvider(
    private val nowMs: Long,
) : ElapsedTimeProvider {
    override fun nowMs(): Long = nowMs
}

private class SequencedDeviceStateProvider(
    private val deviceStates: List<DeviceState>,
) : DeviceStateProvider {
    private var index = 0

    override suspend fun getCurrentDeviceState(): DeviceState {
        val value = deviceStates.getOrElse(index) { deviceStates.last() }
        index += 1
        return value
    }
}

private class SequencedElapsedTimeProvider(
    private val values: List<Long>,
) : ElapsedTimeProvider {
    private var index = 0

    override fun nowMs(): Long {
        val value = values.getOrElse(index) { values.last() }
        index += 1
        return value
    }
}
