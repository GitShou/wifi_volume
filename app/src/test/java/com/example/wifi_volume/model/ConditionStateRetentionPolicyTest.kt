package com.example.wifi_volume.model

import com.example.wifi_volume.bluetooth.ConnectedBluetoothDevice
import com.example.wifi_volume.network.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionStateRetentionPolicyTest {
    private val policy = ConditionStateRetentionPolicy(holdMs = 5_000L)

    @Test
    fun `wifi ssid condition is retained during short unknown period`() {
        val settings = AppSettings(
            rules = listOf(
                wifiRule(id = "wifi-home", priority = 1, ssid = "HomeWifi"),
                fallbackRule(id = "fallback", priority = 2),
            ),
            reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
            notifyOnRuleChange = false,
        )

        val first = policy.resolveActiveRule(
            settings = settings,
            deviceState = wifiState(ssid = "HomeWifi"),
            state = ConditionRetentionState(),
            nowMs = 1_000L,
        )
        val second = policy.resolveActiveRule(
            settings = settings,
            deviceState = wifiState(ssid = null),
            state = first.nextState,
            nowMs = 2_000L,
        )

        assertEquals("wifi-home", first.resolvedRule.rule.id)
        assertEquals("wifi-home", second.resolvedRule.rule.id)
    }

    @Test
    fun `all configured conditions are updated before choosing winner`() {
        val settings = AppSettings(
            rules = listOf(
                bluetoothRule(id = "headphones", priority = 1, address = "AA:BB"),
                wifiRule(id = "wifi-home", priority = 2, ssid = "HomeWifi"),
                fallbackRule(id = "fallback", priority = 3),
            ),
            reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
            notifyOnRuleChange = false,
        )

        val first = policy.resolveActiveRule(
            settings = settings,
            deviceState = DeviceState(
                connectionState = ConnectionState.WIFI,
                currentWifiSsid = "HomeWifi",
                connectedBluetoothDevices = listOf(
                    ConnectedBluetoothDevice(name = "Headphones", address = "AA:BB"),
                ),
            ),
            state = ConditionRetentionState(),
            nowMs = 1_000L,
        )

        assertEquals("headphones", first.resolvedRule.rule.id)
        assertTrue(first.nextState.lastMatchedAtByCondition.containsKey("WIFI_SSID:HomeWifi"))
        assertTrue(first.nextState.lastMatchedAtByCondition.containsKey("BLUETOOTH_DEVICE:AA:BB"))
    }

    @Test
    fun `retained wifi condition expires after hold window`() {
        val settings = AppSettings(
            rules = listOf(
                wifiRule(id = "wifi-home", priority = 1, ssid = "HomeWifi"),
                fallbackRule(id = "fallback", priority = 2),
            ),
            reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
            notifyOnRuleChange = false,
        )

        val first = policy.resolveActiveRule(
            settings = settings,
            deviceState = wifiState(ssid = "HomeWifi"),
            state = ConditionRetentionState(),
            nowMs = 1_000L,
        )
        val second = policy.resolveActiveRule(
            settings = settings,
            deviceState = wifiState(ssid = null),
            state = first.nextState,
            nowMs = 7_000L,
        )

        assertEquals("fallback", second.resolvedRule.rule.id)
    }

    @Test
    fun `unknown wifi ssid without history falls back immediately`() {
        val settings = AppSettings(
            rules = listOf(
                wifiRule(id = "wifi-home", priority = 1, ssid = "HomeWifi"),
                fallbackRule(id = "fallback", priority = 2),
            ),
            reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
            notifyOnRuleChange = false,
        )

        val result = policy.resolveActiveRule(
            settings = settings,
            deviceState = wifiState(ssid = null),
            state = ConditionRetentionState(),
            nowMs = 1_000L,
        )

        assertEquals("fallback", result.resolvedRule.rule.id)
    }

    @Test
    fun `wifi any condition matches even when ssid is unknown`() {
        val settings = AppSettings(
            rules = listOf(
                RuleConfig(
                    id = "wifi-any",
                    name = "Wi-Fi全般",
                    priority = 1,
                    conditions = listOf(
                        RuleCondition(
                            type = RuleConditionType.WIFI_ANY,
                            label = "Wi-Fi通信時",
                        ),
                    ),
                    volumeProfile = sampleProfile(),
                ),
                fallbackRule(id = "fallback", priority = 2),
            ),
            reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
            notifyOnRuleChange = false,
        )

        val result = policy.resolveActiveRule(
            settings = settings,
            deviceState = wifiState(ssid = null),
            state = ConditionRetentionState(),
            nowMs = 1_000L,
        )

        assertEquals("wifi-any", result.resolvedRule.rule.id)
    }

    @Test
    fun `bluetooth device history is not retained after disconnect`() {
        val settings = AppSettings(
            rules = listOf(
                bluetoothRule(id = "headphones", priority = 1, address = "AA:BB"),
                fallbackRule(id = "fallback", priority = 2),
            ),
            reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
            notifyOnRuleChange = false,
        )

        val first = policy.resolveActiveRule(
            settings = settings,
            deviceState = DeviceState(
                connectionState = ConnectionState.NON_WIFI,
                currentWifiSsid = null,
                connectedBluetoothDevices = listOf(
                    ConnectedBluetoothDevice(name = "Headphones", address = "AA:BB"),
                ),
            ),
            state = ConditionRetentionState(),
            nowMs = 1_000L,
        )
        val second = policy.resolveActiveRule(
            settings = settings,
            deviceState = DeviceState(
                connectionState = ConnectionState.NON_WIFI,
                currentWifiSsid = null,
                connectedBluetoothDevices = emptyList(),
            ),
            state = first.nextState,
            nowMs = 2_000L,
        )

        assertEquals("fallback", second.resolvedRule.rule.id)
    }

    @Test
    fun `higher priority retained wifi condition wins over lower priority bluetooth any`() {
        val settings = AppSettings(
            rules = listOf(
                wifiRule(id = "wifi-home", priority = 1, ssid = "HomeWifi"),
                RuleConfig(
                    id = "bt-any",
                    name = "Bluetooth全般",
                    priority = 2,
                    conditions = listOf(
                        RuleCondition(
                            type = RuleConditionType.BLUETOOTH_ANY,
                            label = "Bluetooth接続時",
                        ),
                    ),
                    volumeProfile = sampleProfile(),
                ),
                fallbackRule(id = "fallback", priority = 3),
            ),
            reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
            notifyOnRuleChange = false,
        )

        val first = policy.resolveActiveRule(
            settings = settings,
            deviceState = DeviceState(
                connectionState = ConnectionState.WIFI,
                currentWifiSsid = "HomeWifi",
                connectedBluetoothDevices = listOf(
                    ConnectedBluetoothDevice(name = "Headphones", address = "AA:BB"),
                ),
            ),
            state = ConditionRetentionState(),
            nowMs = 1_000L,
        )
        val second = policy.resolveActiveRule(
            settings = settings,
            deviceState = DeviceState(
                connectionState = ConnectionState.WIFI,
                currentWifiSsid = null,
                connectedBluetoothDevices = listOf(
                    ConnectedBluetoothDevice(name = "Headphones", address = "AA:BB"),
                ),
            ),
            state = first.nextState,
            nowMs = 2_000L,
        )

        assertEquals("wifi-home", second.resolvedRule.rule.id)
    }

    @Test
    fun `higher priority bluetooth device wins over lower priority wifi ssid when both match`() {
        val settings = AppSettings(
            rules = listOf(
                bluetoothRule(id = "headphones", priority = 1, address = "AA:BB"),
                wifiRule(id = "wifi-home", priority = 2, ssid = "HomeWifi"),
                fallbackRule(id = "fallback", priority = 3),
            ),
            reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
            notifyOnRuleChange = false,
        )

        val result = policy.resolveActiveRule(
            settings = settings,
            deviceState = DeviceState(
                connectionState = ConnectionState.WIFI,
                currentWifiSsid = "HomeWifi",
                connectedBluetoothDevices = listOf(
                    ConnectedBluetoothDevice(name = "Headphones", address = "AA:BB"),
                ),
            ),
            state = ConditionRetentionState(),
            nowMs = 1_000L,
        )

        assertEquals("headphones", result.resolvedRule.rule.id)
    }

    @Test
    fun `higher priority bluetooth device still wins while lower priority wifi ssid is retained`() {
        val settings = AppSettings(
            rules = listOf(
                bluetoothRule(id = "headphones", priority = 1, address = "AA:BB"),
                wifiRule(id = "wifi-home", priority = 2, ssid = "HomeWifi"),
                fallbackRule(id = "fallback", priority = 3),
            ),
            reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
            notifyOnRuleChange = false,
        )

        val first = policy.resolveActiveRule(
            settings = settings,
            deviceState = DeviceState(
                connectionState = ConnectionState.WIFI,
                currentWifiSsid = "HomeWifi",
                connectedBluetoothDevices = listOf(
                    ConnectedBluetoothDevice(name = "Headphones", address = "AA:BB"),
                ),
            ),
            state = ConditionRetentionState(),
            nowMs = 1_000L,
        )
        val second = policy.resolveActiveRule(
            settings = settings,
            deviceState = DeviceState(
                connectionState = ConnectionState.WIFI,
                currentWifiSsid = null,
                connectedBluetoothDevices = listOf(
                    ConnectedBluetoothDevice(name = "Headphones", address = "AA:BB"),
                ),
            ),
            state = first.nextState,
            nowMs = 2_000L,
        )

        assertEquals("headphones", second.resolvedRule.rule.id)
    }

    private fun wifiState(ssid: String?): DeviceState {
        return DeviceState(
            connectionState = ConnectionState.WIFI,
            currentWifiSsid = ssid,
            connectedBluetoothDevices = emptyList(),
        )
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
            media = 5,
            ring = 4,
            notification = 4,
            alarm = 6,
            ringerMode = RingerModeOption.NORMAL,
        )
    }
}
