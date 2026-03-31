/**
 * このアプリのメイン画面です。
 *
 * 役割:
 * - 画面に設定カードや全体設定タブを表示する
 * - ユーザーが編集した内容を [SettingsRepository] に保存する
 * - 保存後に現在の接続状況を見て、すぐに [VolumeController] で音量を反映する
 *
 * 関係する主なファイル:
 * - [SettingsRepository]: 設定の保存先。画面の入力値は最終的にここへ保存される
 * - [ConnectionMonitorService]: 画面を閉じた後も接続状況を監視して自動切替する
 * - [ConnectionStateResolver], [BluetoothStateResolver]: いま何に接続しているかを取得する
 * - [activity_main.xml], [item_rule_card.xml], [item_condition_row.xml]: この画面の見た目を定義する
 *
 * Android に不慣れな人向けの見方:
 * - 「画面の入口」はまずこのファイル
 * - 「保存や監視の実処理」は別ファイルに分けている
 * - そのため、UI の動きで迷ったらこのファイルから追うのが一番分かりやすい
 */
package com.example.wifi_volume

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.example.wifi_volume.audio.VolumeController
import com.example.wifi_volume.bluetooth.BluetoothStateResolver
import com.example.wifi_volume.data.SettingsRepository
import com.example.wifi_volume.model.AppSettings
import com.example.wifi_volume.model.DeviceState
import com.example.wifi_volume.model.ReapplyMode
import com.example.wifi_volume.model.RingerModeOption
import com.example.wifi_volume.model.RuleCondition
import com.example.wifi_volume.model.RuleConditionType
import com.example.wifi_volume.model.RuleConfig
import com.example.wifi_volume.model.RuleEvaluator
import com.example.wifi_volume.model.RuleEvaluator.ResolvedRule
import com.example.wifi_volume.model.VolumeLimits
import com.example.wifi_volume.model.VolumeProfile
import com.example.wifi_volume.monitor.ConnectionMonitorService
import com.example.wifi_volume.network.ConnectionState
import com.example.wifi_volume.network.ConnectionStateResolver
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var volumeController: VolumeController
    private lateinit var connectionStateResolver: ConnectionStateResolver
    private lateinit var bluetoothStateResolver: BluetoothStateResolver
    private val ruleEvaluator = RuleEvaluator()

    private lateinit var statusText: TextView
    private lateinit var reapplySwitch: MaterialSwitch
    private lateinit var ruleChangeNotificationSwitch: MaterialSwitch
    private lateinit var rulesContainer: LinearLayout
    private lateinit var volumeSettingsContent: LinearLayout
    private lateinit var globalSettingsContent: LinearLayout
    private lateinit var deleteSettingButton: MaterialButton

    private lateinit var volumeLimits: VolumeLimits
    private lateinit var ringerModeAdapter: ArrayAdapter<CharSequence>

    private val editableRules = mutableListOf<RuleConfig>()
    private val ruleViews = linkedMapOf<String, RuleCardViews>()
    private var currentActiveRuleId: String? = null
    private var isDeleteMode = false

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val deniedPermissions = result.filterValues { granted -> !granted }.keys
            when {
                deniedPermissions.isEmpty() -> {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_permissions_granted),
                        Toast.LENGTH_SHORT,
                    ).show()
                }

                deniedPermissions.any { permission ->
                    shouldHandleInApp(permission) && !shouldShowRequestPermissionRationale(permission)
                } -> {
                    showPermissionSettingsDialog()
                }

                else -> {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_permissions_missing),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        settingsRepository = SettingsRepository(applicationContext)
        volumeController = VolumeController(applicationContext)
        connectionStateResolver = ConnectionStateResolver(applicationContext)
        bluetoothStateResolver = BluetoothStateResolver(applicationContext)

        volumeLimits = volumeController.readLimits()
        bindViews()
        initializeSettings()
        observeActiveRule()
        requestPermissionsIfNeeded()
        startMonitoringService()
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        reapplySwitch = findViewById(R.id.reapplySwitch)
        ruleChangeNotificationSwitch = findViewById(R.id.ruleChangeNotificationSwitch)
        rulesContainer = findViewById(R.id.rulesContainer)
        volumeSettingsContent = findViewById(R.id.volumeSettingsContent)
        globalSettingsContent = findViewById(R.id.globalSettingsContent)
        deleteSettingButton = findViewById(R.id.deleteSettingButton)

        ringerModeAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.ringer_mode_options,
            android.R.layout.simple_spinner_item,
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        findViewById<MaterialButton>(R.id.addConditionButton).setOnClickListener {
            syncEditableRulesFromViews()
            setDeleteMode(false)
            showAddSettingDialog()
        }
        deleteSettingButton.setOnClickListener {
            syncEditableRulesFromViews()
            setDeleteMode(!isDeleteMode)
        }
        findViewById<MaterialButton>(R.id.permissionsButton).setOnClickListener {
            requestPermissionsIfNeeded(showGrantedMessage = true)
        }
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            saveAndApplySettings()
        }
        findViewById<TabLayout>(R.id.settingsTabLayout).apply {
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    showTab(tab.position)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
            getTabAt(TAB_VOLUME_SETTINGS)?.select()
        }
        showTab(TAB_VOLUME_SETTINGS)
        updateDeleteModeUi()
    }

    private fun initializeSettings() {
        lifecycleScope.launch {
            val currentProfile = volumeController.readCurrentProfile()
            settingsRepository.ensureInitialized(currentProfile)
            val settings = settingsRepository.getSettings() ?: return@launch
            editableRules.clear()
            editableRules.addAll(settings.sortedRules())
            reapplySwitch.isChecked = settings.reapplyMode == ReapplyMode.ALWAYS
            ruleChangeNotificationSwitch.isChecked = settings.notifyOnRuleChange
            renderRules()
            applyStatus(resolveActiveRule(settings).displayLabel)
        }
    }

    private fun observeActiveRule() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.activeRuleStateFlow.collect { activeRuleState ->
                    val settings = settingsRepository.getSettings()
                    currentActiveRuleId = activeRuleState.ruleId
                    val label = activeRuleState.label
                        ?: settings?.findRule(activeRuleState.ruleId)?.name?.takeIf { it.isNotBlank() }
                        ?: settings?.let { resolveActiveRule(it).displayLabel }
                        ?: getString(R.string.status_initializing)
                    applyStatus(label)
                    updateRuleHighlights()
                }
            }
        }
    }

    private fun renderRules() {
        rulesContainer.removeAllViews()
        ruleViews.clear()

        editableRules.sortedBy { it.priority }.forEach { rule ->
            val cardView = LayoutInflater.from(this).inflate(
                R.layout.item_rule_card,
                rulesContainer,
                false,
            )
            val holder = RuleCardViews(
                root = cardView as MaterialCardView,
                titleText = cardView.findViewById(R.id.ruleTitleText),
                descriptionText = cardView.findViewById(R.id.ruleDescriptionText),
                priorityInputLayout = cardView.findViewById(R.id.priorityInputLayout),
                priorityEditText = cardView.findViewById(R.id.priorityEditText),
                conditionsContainer = cardView.findViewById(R.id.conditionsContainer),
                addConditionButton = cardView.findViewById(R.id.addConditionToRuleButton),
                mediaSlider = cardView.findViewById(R.id.mediaSlider),
                ringSlider = cardView.findViewById(R.id.ringSlider),
                notificationSlider = cardView.findViewById(R.id.notificationSlider),
                alarmSlider = cardView.findViewById(R.id.alarmSlider),
                ringerModeSpinner = cardView.findViewById(R.id.ringerModeSpinner),
                renameButton = cardView.findViewById(R.id.renameRuleButton),
            )
            bindRuleCard(holder, rule)
            rulesContainer.addView(cardView)
            ruleViews[rule.id] = holder
        }
        updateRuleHighlights()
    }

    private fun bindRuleCard(holder: RuleCardViews, rule: RuleConfig) {
        holder.titleText.text = rule.name
        holder.descriptionText.text = if (rule.isFallback) {
            getString(R.string.rule_description_mobile)
        } else if (rule.conditions.isEmpty()) {
            getString(R.string.rule_description_empty_conditions)
        } else {
            getString(R.string.rule_description_or)
        }
        holder.ringerModeSpinner.adapter = ringerModeAdapter

        if (rule.isFallback) {
            holder.priorityInputLayout.visibility = View.GONE
            holder.addConditionButton.visibility = View.GONE
        } else {
            holder.priorityInputLayout.visibility = View.VISIBLE
            holder.priorityEditText.setText(rule.priority.toString())
            holder.addConditionButton.visibility = View.VISIBLE
            holder.addConditionButton.setOnClickListener {
                syncEditableRulesFromViews()
                showAddConditionDialog(rule.id)
            }
        }
        renderConditions(holder.conditionsContainer, rule)

        configureSlider(holder.mediaSlider, volumeLimits.mediaMax, rule.volumeProfile.media)
        configureSlider(holder.ringSlider, volumeLimits.ringMax, rule.volumeProfile.ring)
        configureSlider(
            holder.notificationSlider,
            volumeLimits.notificationMax,
            rule.volumeProfile.notification,
        )
        configureSlider(holder.alarmSlider, volumeLimits.alarmMax, rule.volumeProfile.alarm)
        holder.ringerModeSpinner.setSelection(rule.volumeProfile.ringerMode.ordinal)

        holder.renameButton.visibility = if (rule.isFallback) View.GONE else View.VISIBLE
        holder.renameButton.setOnClickListener {
            syncEditableRulesFromViews()
            if (isDeleteMode) {
                confirmDeleteSetting(rule.id)
            } else {
                showRenameSettingDialog(rule.id)
            }
        }
        if (isDeleteMode) {
            holder.renameButton.text = getString(R.string.action_delete)
            holder.renameButton.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.delete_button_red)
            holder.renameButton.setTextColor(ContextCompat.getColor(this, R.color.white))
        } else {
            holder.renameButton.text = getString(R.string.action_rename_setting)
        }
    }

    private fun configureSlider(slider: Slider, maxValue: Int, currentValue: Int) {
        slider.valueFrom = 0f
        slider.valueTo = maxValue.toFloat()
        slider.stepSize = 1f
        slider.value = currentValue.coerceIn(0, maxValue).toFloat()
    }

    private fun saveAndApplySettings() {
        lifecycleScope.launch {
            runCatching {
                syncEditableRulesFromViews()
                validateRules(editableRules)
                normalizeMobileRulePriority(editableRules)

                val settings = AppSettings(
                    rules = editableRules.sortedWith(
                        compareBy<RuleConfig> { it.isFallback }
                            .thenBy { it.priority },
                    ),
                    reapplyMode = if (reapplySwitch.isChecked) {
                        ReapplyMode.ALWAYS
                    } else {
                        ReapplyMode.ON_CHANGE_ONLY
                    },
                    notifyOnRuleChange = ruleChangeNotificationSwitch.isChecked,
                )
                settingsRepository.saveSettings(settings)

                val activeRule = resolveActiveRule(settings)
                volumeController.applyProfile(activeRule.rule.volumeProfile)
                settingsRepository.setActiveRuleState(activeRule.rule.id, activeRule.displayLabel)

                editableRules.clear()
                editableRules.addAll(settings.sortedRules())
                renderRules()
            }.onSuccess {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_saved),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure {
                Toast.makeText(
                    this@MainActivity,
                    it.message ?: getString(R.string.toast_error),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun syncEditableRulesFromViews() {
        if (ruleViews.isEmpty()) {
            return
        }

        val updatedRules = editableRules.map { existingRule ->
            val holder = ruleViews[existingRule.id] ?: return@map existingRule
            existingRule.copy(
                priority = holder.priorityEditText.text?.toString()?.toIntOrNull() ?: existingRule.priority,
                volumeProfile = VolumeProfile(
                    media = holder.mediaSlider.value.toInt(),
                    ring = holder.ringSlider.value.toInt(),
                    notification = holder.notificationSlider.value.toInt(),
                    alarm = holder.alarmSlider.value.toInt(),
                    ringerMode = RingerModeOption.entries.getOrElse(
                        holder.ringerModeSpinner.selectedItemPosition,
                    ) { RingerModeOption.NORMAL },
                ),
            )
        }

        editableRules.clear()
        editableRules.addAll(updatedRules)
    }

    private fun validateRules(rules: List<RuleConfig>) {
        val priorities = rules
            .filterNot { it.isFallback }
            .map { it.priority }
        if (priorities.any { it < 1 }) {
            throw IllegalArgumentException(getString(R.string.toast_priority_invalid))
        }
        if (priorities.distinct().size != priorities.size) {
            throw IllegalArgumentException(getString(R.string.toast_priority_duplicate))
        }
        if (rules.count { it.isFallback } != 1) {
            throw IllegalArgumentException(getString(R.string.toast_error))
        }
    }

    private fun showAddSettingDialog() {
        val inputLayout = TextInputLayout(this).apply {
            hint = getString(R.string.dialog_add_setting_hint)
        }
        val editText = TextInputEditText(inputLayout.context)
        inputLayout.addView(editText)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_add_setting_title)
            .setView(inputLayout)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                syncEditableRulesFromViews()
                addEmptyRule(editText.text?.toString()?.trim().orEmpty())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showRenameSettingDialog(ruleId: String) {
        val rule = editableRules.firstOrNull { it.id == ruleId } ?: return
        val inputLayout = TextInputLayout(this).apply {
            hint = getString(R.string.dialog_add_setting_hint)
        }
        val editText = TextInputEditText(inputLayout.context).apply {
            setText(rule.name)
            setSelection(rule.name.length)
        }
        inputLayout.addView(editText)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_rename_setting_title)
            .setView(inputLayout)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val index = editableRules.indexOfFirst { it.id == ruleId }
                if (index != -1) {
                    editableRules[index] = editableRules[index].copy(
                        name = editText.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
                            ?: editableRules[index].name,
                    )
                    renderRules()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showAddConditionDialog(targetRuleId: String) {
        lifecycleScope.launch {
            val deviceState = currentDeviceState()
            val candidates = buildAddableConditions(deviceState)
            if (candidates.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_add_condition_empty),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.dialog_add_condition_title)
                .setItems(candidates.map { it.condition.label }.toTypedArray()) { _, which ->
                    addConditionToRule(targetRuleId, candidates[which].condition)
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun buildAddableConditions(deviceState: DeviceState): List<AddConditionCandidate> {
        val existingConditionKeys = editableRules
            .flatMap { it.conditions }
            .map(::conditionKey)
            .toSet()
        val candidates = mutableListOf<AddConditionCandidate>()

        deviceState.currentWifiSsid
            ?.takeIf { ssid ->
                conditionKey(RuleCondition(type = RuleConditionType.WIFI_SSID, value = ssid, label = "")) !in existingConditionKeys
            }
            ?.let { ssid ->
                candidates += AddConditionCandidate(
                    condition = RuleCondition(
                        type = RuleConditionType.WIFI_SSID,
                        value = ssid,
                        label = "Wi-Fi: $ssid",
                    ),
                )
            }

        deviceState.connectedBluetoothDevices.forEach { device ->
            if (conditionKey(
                    RuleCondition(
                        type = RuleConditionType.BLUETOOTH_DEVICE,
                        value = device.address,
                        label = "",
                    ),
                ) !in existingConditionKeys
            ) {
                candidates += AddConditionCandidate(
                    condition = RuleCondition(
                        type = RuleConditionType.BLUETOOTH_DEVICE,
                        value = device.address,
                        label = "Bluetooth: ${device.displayName}",
                    ),
                )
            }
        }

        return candidates
    }

    private fun addEmptyRule(inputName: String) {
        val shiftedRules = editableRules.map { rule ->
            if (rule.isFallback) {
                rule
            } else {
                rule.copy(priority = rule.priority + 1)
            }
        }
        editableRules.clear()
        editableRules.addAll(shiftedRules)
        editableRules += RuleConfig(
            id = UUID.randomUUID().toString(),
            name = inputName.ifBlank { nextDefaultRuleName() },
            priority = 1,
            conditions = emptyList(),
            volumeProfile = volumeController.readCurrentProfile(),
        )
        renderRules()
    }

    private fun addConditionToRule(ruleId: String, condition: RuleCondition) {
        val index = editableRules.indexOfFirst { it.id == ruleId }
        if (index == -1) {
            return
        }
        val rule = editableRules[index]
        editableRules[index] = rule.copy(conditions = rule.conditions + condition)
        renderRules()
    }

    private fun confirmDeleteSetting(ruleId: String) {
        val rule = editableRules.firstOrNull { it.id == ruleId } ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_setting_title)
            .setMessage(getString(R.string.dialog_delete_setting_message, rule.name))
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                editableRules.removeAll { it.id == ruleId }
                if (editableRules.none { !it.isFallback }) {
                    setDeleteMode(false)
                }
                renderRules()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun setDeleteMode(enabled: Boolean) {
        if (isDeleteMode == enabled) {
            return
        }
        isDeleteMode = enabled
        updateDeleteModeUi()
        renderRules()
    }

    private fun updateDeleteModeUi() {
        deleteSettingButton.text = if (isDeleteMode) {
            getString(R.string.action_finish_delete_mode)
        } else {
            getString(R.string.action_delete_setting)
        }
    }

    private fun renderConditions(container: LinearLayout, rule: RuleConfig) {
        container.removeAllViews()
        if (rule.isFallback) {
            return
        }
        rule.conditions.forEach { condition ->
            val row = LayoutInflater.from(this).inflate(
                R.layout.item_condition_row,
                container,
                false,
            )
            row.findViewById<TextView>(R.id.conditionText).text = condition.label
            row.findViewById<MaterialButton>(R.id.deleteConditionButton).setOnClickListener {
                syncEditableRulesFromViews()
                showDeleteConditionDialog(rule.id, condition)
            }
            container.addView(row)
        }
    }

    private fun showDeleteConditionDialog(ruleId: String, condition: RuleCondition) {
        val rule = editableRules.firstOrNull { it.id == ruleId } ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_rule_title)
            .setMessage(getString(R.string.dialog_delete_rule_message, condition.label))
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val index = editableRules.indexOfFirst { it.id == ruleId }
                if (index != -1) {
                    editableRules[index] = rule.copy(
                        conditions = rule.conditions.filterNot { conditionKey(it) == conditionKey(condition) },
                    )
                }
                renderRules()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private suspend fun resolveActiveRule(settings: AppSettings): ResolvedRule {
        return ruleEvaluator.resolveActiveRule(settings, currentDeviceState())
    }

    private fun normalizeMobileRulePriority(rules: MutableList<RuleConfig>) {
        val mobileRuleIndex = rules.indexOfFirst { it.isFallback }
        if (mobileRuleIndex == -1) {
            return
        }
        val fallbackPriority = (
            rules.filterNot { it.isFallback }
                .maxOfOrNull { it.priority } ?: 0
            ) + 1
        rules[mobileRuleIndex] = rules[mobileRuleIndex].copy(priority = fallbackPriority)
    }

    private fun conditionKey(condition: RuleCondition): String {
        return "${condition.type.name}:${condition.value.orEmpty()}"
    }

    private fun nextDefaultRuleName(): String {
        val maxIndex = editableRules.mapNotNull { rule ->
            DEFAULT_RULE_NAME_REGEX.matchEntire(rule.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }.maxOrNull() ?: 0
        return "設定${maxIndex + 1}"
    }

    private suspend fun currentDeviceState(): DeviceState {
        val connectionSnapshot = connectionStateResolver.resolveSnapshot()
        return DeviceState(
            connectionState = connectionSnapshot.connectionState,
            currentWifiSsid = connectionSnapshot.currentWifiSsid,
            connectedBluetoothDevices = bluetoothStateResolver.getConnectedDevices(),
        )
    }

    private fun applyStatus(label: String) {
        statusText.text = getString(R.string.status_active_rule, label)
    }

    private fun showTab(position: Int) {
        volumeSettingsContent.visibility = if (position == TAB_VOLUME_SETTINGS) {
            View.VISIBLE
        } else {
            View.GONE
        }
        globalSettingsContent.visibility = if (position == TAB_GLOBAL_SETTINGS) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun updateRuleHighlights() {
        val activeStrokeColor = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimary,
            0,
        )
        ruleViews.forEach { (ruleId, holder) ->
            val isActive = ruleId == currentActiveRuleId
            holder.root.strokeColor = activeStrokeColor
            holder.root.strokeWidth = if (isActive) {
                resources.getDimensionPixelSize(R.dimen.active_rule_stroke_width)
            } else {
                0
            }
            holder.root.alpha = if (isActive) 1f else 0.92f
        }
    }

    private fun requestPermissionsIfNeeded(showGrantedMessage: Boolean = false) {
        val permissions = missingPermissions()
        if (permissions.isNotEmpty()) {
            permissionsLauncher.launch(permissions.toTypedArray())
        } else if (showGrantedMessage) {
            Toast.makeText(
                this,
                getString(R.string.toast_permissions_granted),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun missingPermissions(): List<String> {
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun shouldHandleInApp(permission: String): Boolean {
        return when (permission) {
            Manifest.permission.BLUETOOTH_CONNECT -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            Manifest.permission.ACCESS_FINE_LOCATION -> true
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.POST_NOTIFICATIONS,
            -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            else -> false
        }
    }

    private fun showPermissionSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_permission_settings_title)
            .setMessage(R.string.dialog_permission_settings_message)
            .setPositiveButton(R.string.action_open_settings) { _, _ ->
                openAppSettings()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private fun startMonitoringService() {
        val intent = Intent(this, ConnectionMonitorService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private data class RuleCardViews(
        val root: MaterialCardView,
        val titleText: TextView,
        val descriptionText: TextView,
        val priorityInputLayout: TextInputLayout,
        val priorityEditText: TextInputEditText,
        val conditionsContainer: LinearLayout,
        val addConditionButton: MaterialButton,
        val mediaSlider: Slider,
        val ringSlider: Slider,
        val notificationSlider: Slider,
        val alarmSlider: Slider,
        val ringerModeSpinner: Spinner,
        val renameButton: MaterialButton,
    )

    private data class AddConditionCandidate(
        val condition: RuleCondition,
    )

    private companion object {
        const val TAB_VOLUME_SETTINGS = 0
        const val TAB_GLOBAL_SETTINGS = 1
        val DEFAULT_RULE_NAME_REGEX = Regex("^設定(\\d+)$")
    }
}
