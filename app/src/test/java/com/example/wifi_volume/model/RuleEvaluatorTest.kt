package com.example.wifi_volume.model

import com.example.wifi_volume.bluetooth.ConnectedBluetoothDevice
import com.example.wifi_volume.network.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleEvaluatorTest {
    private val evaluator = RuleEvaluator()

    @Test
    fun `higher priority matching rule is selected`() {
        val settings = AppSettings(
            rules = listOf(
                rule(
                    id = "wifi",
                    priority = 1,
                    name = "家",
                    conditions = listOf(
                        RuleCondition(
                            type = RuleConditionType.WIFI_SSID,
                            value = "HomeWifi",
                            label = "Wi-Fi: HomeWifi",
                        ),
                    ),
                ),
                rule(
                    id = "bt",
                    priority = 2,
                    name = "ヘッドホン",
                    conditions = listOf(
                        RuleCondition(
                            type = RuleConditionType.BLUETOOTH_DEVICE,
                            value = "AA:BB",
                            label = "Bluetooth: Headphones",
                        ),
                    ),
                ),
                fallbackRule(),
            ),
            reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
            notifyOnRuleChange = false,
        )

        val resolved = evaluator.resolveActiveRule(
            settings = settings,
            deviceState = DeviceState(
                connectionState = ConnectionState.WIFI,
                currentWifiSsid = "HomeWifi",
                connectedBluetoothDevices = listOf(
                    ConnectedBluetoothDevice(name = "Headphones", address = "AA:BB"),
                ),
            ),
        )

        assertEquals("wifi", resolved.rule.id)
        assertEquals("家", resolved.displayLabel)
    }

    @Test
    fun `conditions inside one rule are matched as or`() {
        val settings = AppSettings(
            rules = listOf(
                rule(
                    id = "mixed",
                    priority = 1,
                    name = "家またはヘッドホン",
                    conditions = listOf(
                        RuleCondition(
                            type = RuleConditionType.WIFI_SSID,
                            value = "HomeWifi",
                            label = "Wi-Fi: HomeWifi",
                        ),
                        RuleCondition(
                            type = RuleConditionType.BLUETOOTH_DEVICE,
                            value = "AA:BB",
                            label = "Bluetooth: Headphones",
                        ),
                    ),
                ),
                fallbackRule(),
            ),
            reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
            notifyOnRuleChange = false,
        )

        val resolved = evaluator.resolveActiveRule(
            settings = settings,
            deviceState = DeviceState(
                connectionState = ConnectionState.NON_WIFI,
                currentWifiSsid = null,
                connectedBluetoothDevices = listOf(
                    ConnectedBluetoothDevice(name = "Headphones", address = "AA:BB"),
                ),
            ),
        )

        assertEquals("mixed", resolved.rule.id)
    }

    @Test
    fun `fallback rule is selected when no condition matches`() {
        val settings = AppSettings(
            rules = listOf(
                rule(
                    id = "wifi",
                    priority = 1,
                    name = "家",
                    conditions = listOf(
                        RuleCondition(
                            type = RuleConditionType.WIFI_SSID,
                            value = "HomeWifi",
                            label = "Wi-Fi: HomeWifi",
                        ),
                    ),
                ),
                fallbackRule(),
            ),
            reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
            notifyOnRuleChange = false,
        )

        val resolved = evaluator.resolveActiveRule(
            settings = settings,
            deviceState = DeviceState(
                connectionState = ConnectionState.NON_WIFI,
                currentWifiSsid = null,
                connectedBluetoothDevices = emptyList(),
            ),
        )

        assertEquals("fallback", resolved.rule.id)
        assertEquals("その他", resolved.displayLabel)
    }

    @Test
    fun `wifi any condition matches any wifi connection`() {
        val settings = AppSettings(
            rules = listOf(
                rule(
                    id = "wifi-any",
                    priority = 1,
                    name = "Wi-Fi全般",
                    conditions = listOf(
                        RuleCondition(
                            type = RuleConditionType.WIFI_ANY,
                            label = "Wi-Fi通信時",
                        ),
                    ),
                ),
                fallbackRule(),
            ),
            reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
            notifyOnRuleChange = false,
        )

        val resolved = evaluator.resolveActiveRule(
            settings = settings,
            deviceState = DeviceState(
                connectionState = ConnectionState.WIFI,
                currentWifiSsid = null,
                connectedBluetoothDevices = emptyList(),
            ),
        )

        assertEquals("wifi-any", resolved.rule.id)
    }

    @Test
    fun `wifi ssid condition does not match different ssid`() {
        val settings = AppSettings(
            rules = listOf(
                rule(
                    id = "wifi-home",
                    priority = 1,
                    name = "家",
                    conditions = listOf(
                        RuleCondition(
                            type = RuleConditionType.WIFI_SSID,
                            value = "HomeWifi",
                            label = "Wi-Fi: HomeWifi",
                        ),
                    ),
                ),
                fallbackRule(),
            ),
            reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
            notifyOnRuleChange = false,
        )

        val resolved = evaluator.resolveActiveRule(
            settings = settings,
            deviceState = DeviceState(
                connectionState = ConnectionState.WIFI,
                currentWifiSsid = "CafeWifi",
                connectedBluetoothDevices = emptyList(),
            ),
        )

        assertEquals("fallback", resolved.rule.id)
    }

    @Test
    fun `bluetooth any condition matches when any bluetooth device is connected`() {
        val settings = AppSettings(
            rules = listOf(
                rule(
                    id = "bt-any",
                    priority = 1,
                    name = "Bluetooth全般",
                    conditions = listOf(
                        RuleCondition(
                            type = RuleConditionType.BLUETOOTH_ANY,
                            label = "Bluetooth接続時",
                        ),
                    ),
                ),
                fallbackRule(),
            ),
            reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
            notifyOnRuleChange = false,
        )

        val resolved = evaluator.resolveActiveRule(
            settings = settings,
            deviceState = DeviceState(
                connectionState = ConnectionState.NON_WIFI,
                currentWifiSsid = null,
                connectedBluetoothDevices = listOf(
                    ConnectedBluetoothDevice(name = "Speaker", address = "11:22"),
                ),
            ),
        )

        assertEquals("bt-any", resolved.rule.id)
    }

    @Test
    fun `bluetooth device condition does not match different address`() {
        val settings = AppSettings(
            rules = listOf(
                rule(
                    id = "bt-headphones",
                    priority = 1,
                    name = "ヘッドホン",
                    conditions = listOf(
                        RuleCondition(
                            type = RuleConditionType.BLUETOOTH_DEVICE,
                            value = "AA:BB",
                            label = "Bluetooth: Headphones",
                        ),
                    ),
                ),
                fallbackRule(),
            ),
            reapplyMode = ReapplyMode.ON_CHANGE_ONLY,
            notifyOnRuleChange = false,
        )

        val resolved = evaluator.resolveActiveRule(
            settings = settings,
            deviceState = DeviceState(
                connectionState = ConnectionState.NON_WIFI,
                currentWifiSsid = null,
                connectedBluetoothDevices = listOf(
                    ConnectedBluetoothDevice(name = "Car", address = "CC:DD"),
                ),
            ),
        )

        assertEquals("fallback", resolved.rule.id)
    }

    private fun rule(
        id: String,
        priority: Int,
        name: String,
        conditions: List<RuleCondition>,
    ): RuleConfig {
        return RuleConfig(
            id = id,
            name = name,
            priority = priority,
            conditions = conditions,
            volumeProfile = sampleProfile(),
        )
    }

    private fun fallbackRule(): RuleConfig {
        return RuleConfig(
            id = "fallback",
            name = "その他",
            priority = 99,
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
