package com.example.wifi_volume.data

import com.example.wifi_volume.model.AppSettings
import com.example.wifi_volume.model.ReapplyMode
import com.example.wifi_volume.model.RingerModeOption
import com.example.wifi_volume.model.RuleCondition
import com.example.wifi_volume.model.RuleConditionType
import com.example.wifi_volume.model.RuleConfig
import com.example.wifi_volume.model.VolumeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonCodecTest {
    @Test
    fun `encode and decode preserves settings`() {
        val settings = AppSettings(
            rules = listOf(
                RuleConfig(
                    id = "wifi",
                    name = "家",
                    priority = 1,
                    conditions = listOf(
                        RuleCondition(
                            type = RuleConditionType.WIFI_SSID,
                            value = "HomeWifi",
                            label = "Wi-Fi: HomeWifi",
                        ),
                    ),
                    volumeProfile = sampleProfile(RingerModeOption.VIBRATE),
                ),
                RuleConfig(
                    id = "fallback",
                    name = "その他",
                    priority = 2,
                    conditions = emptyList(),
                    volumeProfile = sampleProfile(RingerModeOption.NORMAL),
                    isFallback = true,
                ),
            ),
            reapplyMode = ReapplyMode.ALWAYS,
            notifyOnRuleChange = true,
        )

        val restored = SettingsJsonCodec.decode(SettingsJsonCodec.encode(settings))

        assertEquals(settings.reapplyMode, restored.reapplyMode)
        assertTrue(restored.notifyOnRuleChange)
        assertEquals(2, restored.rules.size)
        assertEquals("家", restored.rules.first().name)
        assertEquals("HomeWifi", restored.rules.first().conditions.first().value)
        assertTrue(restored.rules.last().isFallback)
    }

    @Test
    fun `legacy fallback payload is still decoded as fallback`() {
        val rawJson = """
            {
              "reapplyMode":"ON_CHANGE_ONLY",
              "notifyOnRuleChange":false,
              "rules":[
                {
                  "id":"legacy-fallback",
                  "priority":3,
                  "media":5,
                  "ring":4,
                  "notification":4,
                  "alarm":6,
                  "ringerMode":"NORMAL",
                  "conditionType":"MOBILE_DEFAULT",
                  "conditionValue":"",
                  "conditionLabel":"モバイル通信 / その他"
                }
              ]
            }
        """.trimIndent()

        val restored = SettingsJsonCodec.decode(rawJson)

        assertEquals(1, restored.rules.size)
        assertTrue(restored.rules.first().isFallback)
        assertEquals("その他", restored.rules.first().name)
        assertFalse(restored.rules.first().conditions.any())
    }

    private fun sampleProfile(ringerMode: RingerModeOption): VolumeProfile {
        return VolumeProfile(
            media = 5,
            ring = 4,
            notification = 4,
            alarm = 6,
            ringerMode = ringerMode,
        )
    }
}
