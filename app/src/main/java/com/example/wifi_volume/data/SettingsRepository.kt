package com.example.wifi_volume.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.wifi_volume.model.ActiveRuleState
import com.example.wifi_volume.model.AppSettings
import com.example.wifi_volume.model.ReapplyMode
import com.example.wifi_volume.model.RingerModeOption
import com.example.wifi_volume.model.RuleCondition
import com.example.wifi_volume.model.RuleConditionType
import com.example.wifi_volume.model.RuleConfig
import com.example.wifi_volume.model.VolumeProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "volume_settings")

class SettingsRepository(private val context: Context) {
    private val dataStore = context.dataStore

    val activeRuleStateFlow: Flow<ActiveRuleState> =
        dataStore.data.map { preferences ->
            ActiveRuleState(
                ruleId = preferences[Keys.ACTIVE_RULE_ID],
                label = preferences[Keys.ACTIVE_RULE_LABEL],
            )
        }

    suspend fun ensureInitialized(defaultProfile: VolumeProfile) {
        val preferences = dataStore.data.first()
        if (preferences.contains(Keys.SETTINGS_JSON)) {
            return
        }
        saveSettings(migrateOrCreateDefaults(preferences, defaultProfile))
    }

    suspend fun getSettings(): AppSettings? {
        val preferences = dataStore.data.first()
        val rawJson = preferences[Keys.SETTINGS_JSON] ?: return null
        return decodeSettings(rawJson)
    }

    suspend fun saveSettings(settings: AppSettings) {
        dataStore.edit { preferences ->
            preferences[Keys.SETTINGS_JSON] = encodeSettings(settings)
        }
    }

    suspend fun getActiveRuleId(): String? {
        val preferences = dataStore.data.first()
        return preferences[Keys.ACTIVE_RULE_ID]
    }

    suspend fun setActiveRuleId(ruleId: String) {
        dataStore.edit { preferences ->
            preferences[Keys.ACTIVE_RULE_ID] = ruleId
        }
    }

    suspend fun setActiveRuleState(rule: RuleConfig) {
        dataStore.edit { preferences ->
            preferences[Keys.ACTIVE_RULE_ID] = rule.id
            preferences[Keys.ACTIVE_RULE_LABEL] = rule.condition.label
        }
    }

    private fun migrateOrCreateDefaults(
        preferences: Preferences,
        defaultProfile: VolumeProfile,
    ): AppSettings {
        val bluetoothProfile = readLegacyProfile(
            mediaKey = LegacyKeys.BLUETOOTH_MEDIA,
            ringKey = LegacyKeys.BLUETOOTH_RING,
            notificationKey = LegacyKeys.BLUETOOTH_NOTIFICATION,
            alarmKey = LegacyKeys.BLUETOOTH_ALARM,
            ringerModeKey = LegacyKeys.BLUETOOTH_RINGER_MODE,
            preferences = preferences,
        ) ?: defaultProfile
        val wifiProfile = readLegacyProfile(
            mediaKey = LegacyKeys.WIFI_MEDIA,
            ringKey = LegacyKeys.WIFI_RING,
            notificationKey = LegacyKeys.WIFI_NOTIFICATION,
            alarmKey = LegacyKeys.WIFI_ALARM,
            ringerModeKey = LegacyKeys.WIFI_RINGER_MODE,
            preferences = preferences,
        ) ?: defaultProfile
        val mobileProfile = readLegacyProfile(
            mediaKey = LegacyKeys.MOBILE_MEDIA,
            ringKey = LegacyKeys.MOBILE_RING,
            notificationKey = LegacyKeys.MOBILE_NOTIFICATION,
            alarmKey = LegacyKeys.MOBILE_ALARM,
            ringerModeKey = LegacyKeys.MOBILE_RINGER_MODE,
            preferences = preferences,
        ) ?: defaultProfile

        return AppSettings(
            rules = listOf(
                createRule(
                    condition = RuleCondition(
                        type = RuleConditionType.BLUETOOTH_ANY,
                        label = LABEL_BLUETOOTH_ANY,
                    ),
                    priority = preferences[LegacyKeys.BLUETOOTH_PRIORITY] ?: DEFAULT_BLUETOOTH_PRIORITY,
                    profile = bluetoothProfile,
                ),
                createRule(
                    condition = RuleCondition(
                        type = RuleConditionType.WIFI_ANY,
                        label = LABEL_WIFI_ANY,
                    ),
                    priority = preferences[LegacyKeys.WIFI_PRIORITY] ?: DEFAULT_WIFI_PRIORITY,
                    profile = wifiProfile,
                ),
                createRule(
                    condition = RuleCondition(
                        type = RuleConditionType.MOBILE_DEFAULT,
                        label = LABEL_MOBILE_DEFAULT,
                    ),
                    priority = preferences[LegacyKeys.MOBILE_PRIORITY] ?: DEFAULT_MOBILE_PRIORITY,
                    profile = mobileProfile,
                ),
            ),
            reapplyMode = decodeReapplyMode(preferences[LegacyKeys.REAPPLY_MODE]),
        )
    }

    private fun createRule(
        condition: RuleCondition,
        priority: Int,
        profile: VolumeProfile,
    ): RuleConfig {
        return RuleConfig(
            id = UUID.randomUUID().toString(),
            condition = condition,
            priority = priority,
            volumeProfile = profile,
        )
    }

    private fun readLegacyProfile(
        mediaKey: Preferences.Key<Int>,
        ringKey: Preferences.Key<Int>,
        notificationKey: Preferences.Key<Int>,
        alarmKey: Preferences.Key<Int>,
        ringerModeKey: Preferences.Key<String>,
        preferences: Preferences,
    ): VolumeProfile? {
        if (!preferences.contains(mediaKey) ||
            !preferences.contains(ringKey) ||
            !preferences.contains(notificationKey) ||
            !preferences.contains(alarmKey) ||
            !preferences.contains(ringerModeKey)
        ) {
            return null
        }

        return VolumeProfile(
            media = preferences[mediaKey] ?: 0,
            ring = preferences[ringKey] ?: 0,
            notification = preferences[notificationKey] ?: 0,
            alarm = preferences[alarmKey] ?: 0,
            ringerMode = decodeRingerMode(preferences[ringerModeKey]),
        )
    }

    private fun encodeSettings(settings: AppSettings): String {
        return JSONObject().apply {
            put("reapplyMode", settings.reapplyMode.name)
            put(
                "rules",
                JSONArray().apply {
                    settings.rules.forEach { rule ->
                        put(
                            JSONObject().apply {
                                put("id", rule.id)
                                put("conditionType", rule.condition.type.name)
                                put("conditionValue", rule.condition.value)
                                put("conditionLabel", rule.condition.label)
                                put("priority", rule.priority)
                                put("media", rule.volumeProfile.media)
                                put("ring", rule.volumeProfile.ring)
                                put("notification", rule.volumeProfile.notification)
                                put("alarm", rule.volumeProfile.alarm)
                                put("ringerMode", rule.volumeProfile.ringerMode.name)
                            },
                        )
                    }
                },
            )
        }.toString()
    }

    private fun decodeSettings(rawJson: String): AppSettings {
        val root = JSONObject(rawJson)
        val rulesArray = root.getJSONArray("rules")
        val rules = buildList {
            for (index in 0 until rulesArray.length()) {
                val ruleObject = rulesArray.getJSONObject(index)
                add(
                    RuleConfig(
                        id = ruleObject.getString("id"),
                        condition = decodeCondition(ruleObject),
                        priority = ruleObject.getInt("priority"),
                        volumeProfile = VolumeProfile(
                            media = ruleObject.getInt("media"),
                            ring = ruleObject.getInt("ring"),
                            notification = ruleObject.getInt("notification"),
                            alarm = ruleObject.getInt("alarm"),
                            ringerMode = decodeRingerMode(ruleObject.getString("ringerMode")),
                        ),
                    ),
                )
            }
        }

        return AppSettings(
            rules = rules,
            reapplyMode = decodeReapplyMode(root.optString("reapplyMode")),
        )
    }

    private fun decodeCondition(ruleObject: JSONObject): RuleCondition {
        val type = RuleConditionType.valueOf(ruleObject.getString("conditionType"))
        val value = ruleObject.optString("conditionValue").takeIf { it.isNotBlank() }
        val savedLabel = ruleObject.getString("conditionLabel")

        val normalizedLabel = when (type) {
            RuleConditionType.MOBILE_DEFAULT -> LABEL_MOBILE_DEFAULT
            RuleConditionType.WIFI_ANY -> savedLabel
            RuleConditionType.WIFI_SSID -> savedLabel
            RuleConditionType.BLUETOOTH_ANY -> savedLabel
            RuleConditionType.BLUETOOTH_DEVICE -> savedLabel
        }

        return RuleCondition(
            type = type,
            value = value,
            label = normalizedLabel,
        )
    }

    private fun decodeRingerMode(rawValue: String?): RingerModeOption {
        return runCatching { rawValue?.let(RingerModeOption::valueOf) }.getOrNull()
            ?: RingerModeOption.NORMAL
    }

    private fun decodeReapplyMode(rawValue: String?): ReapplyMode {
        return runCatching { rawValue?.let(ReapplyMode::valueOf) }.getOrNull()
            ?: ReapplyMode.ON_CHANGE_ONLY
    }

    private object Keys {
        val SETTINGS_JSON = stringPreferencesKey("settings_json")
        val ACTIVE_RULE_ID = stringPreferencesKey("active_rule_id")
        val ACTIVE_RULE_LABEL = stringPreferencesKey("active_rule_label")
    }

    private object LegacyKeys {
        val BLUETOOTH_MEDIA = intPreferencesKey("bluetooth_media")
        val BLUETOOTH_RING = intPreferencesKey("bluetooth_ring")
        val BLUETOOTH_NOTIFICATION = intPreferencesKey("bluetooth_notification")
        val BLUETOOTH_ALARM = intPreferencesKey("bluetooth_alarm")
        val BLUETOOTH_RINGER_MODE = stringPreferencesKey("bluetooth_ringer_mode")
        val BLUETOOTH_PRIORITY = intPreferencesKey("bluetooth_priority")
        val WIFI_MEDIA = intPreferencesKey("wifi_media")
        val WIFI_RING = intPreferencesKey("wifi_ring")
        val WIFI_NOTIFICATION = intPreferencesKey("wifi_notification")
        val WIFI_ALARM = intPreferencesKey("wifi_alarm")
        val WIFI_RINGER_MODE = stringPreferencesKey("wifi_ringer_mode")
        val WIFI_PRIORITY = intPreferencesKey("wifi_priority")
        val MOBILE_MEDIA = intPreferencesKey("mobile_media")
        val MOBILE_RING = intPreferencesKey("mobile_ring")
        val MOBILE_NOTIFICATION = intPreferencesKey("mobile_notification")
        val MOBILE_ALARM = intPreferencesKey("mobile_alarm")
        val MOBILE_RINGER_MODE = stringPreferencesKey("mobile_ringer_mode")
        val MOBILE_PRIORITY = intPreferencesKey("mobile_priority")
        val REAPPLY_MODE = stringPreferencesKey("reapply_mode")
    }

    private companion object {
        const val DEFAULT_BLUETOOTH_PRIORITY = 1
        const val DEFAULT_WIFI_PRIORITY = 2
        const val DEFAULT_MOBILE_PRIORITY = 3

        const val LABEL_BLUETOOTH_ANY = "Bluetooth接続時"
        const val LABEL_WIFI_ANY = "Wi-Fi通信時"
        const val LABEL_MOBILE_DEFAULT = "その他"
    }
}
