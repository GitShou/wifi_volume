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
    private lateinit var rulesContainer: LinearLayout
    private lateinit var volumeSettingsContent: LinearLayout
    private lateinit var globalSettingsContent: LinearLayout

    private lateinit var volumeLimits: VolumeLimits
    private lateinit var ringerModeAdapter: ArrayAdapter<CharSequence>

    private val editableRules = mutableListOf<RuleConfig>()
    private val ruleViews = linkedMapOf<String, RuleCardViews>()
    private var currentActiveRuleId: String? = null

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
        rulesContainer = findViewById(R.id.rulesContainer)
        volumeSettingsContent = findViewById(R.id.volumeSettingsContent)
        globalSettingsContent = findViewById(R.id.globalSettingsContent)

        ringerModeAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.ringer_mode_options,
            android.R.layout.simple_spinner_item,
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        findViewById<MaterialButton>(R.id.addConditionButton).setOnClickListener {
            syncEditableRulesFromViews()
            showAddConditionDialog()
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
    }

    private fun initializeSettings() {
        lifecycleScope.launch {
            val currentProfile = volumeController.readCurrentProfile()
            settingsRepository.ensureInitialized(currentProfile)
            val settings = settingsRepository.getSettings() ?: return@launch
            editableRules.clear()
            editableRules.addAll(settings.sortedRules())
            reapplySwitch.isChecked = settings.reapplyMode == ReapplyMode.ALWAYS
            renderRules()
            applyStatus(resolveActiveRule(settings).condition.label)
        }
    }

    private fun observeActiveRule() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.activeRuleStateFlow.collect { activeRuleState ->
                    val settings = settingsRepository.getSettings()
                    currentActiveRuleId = activeRuleState.ruleId
                    val label = activeRuleState.label
                        ?: settings?.findRule(activeRuleState.ruleId)?.condition?.label
                        ?: settings?.let { resolveActiveRule(it).condition.label }
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
                mediaSlider = cardView.findViewById(R.id.mediaSlider),
                ringSlider = cardView.findViewById(R.id.ringSlider),
                notificationSlider = cardView.findViewById(R.id.notificationSlider),
                alarmSlider = cardView.findViewById(R.id.alarmSlider),
                ringerModeSpinner = cardView.findViewById(R.id.ringerModeSpinner),
                deleteButton = cardView.findViewById(R.id.deleteRuleButton),
            )
            bindRuleCard(holder, rule)
            rulesContainer.addView(cardView)
            ruleViews[rule.id] = holder
        }
        updateRuleHighlights()
    }

    private fun bindRuleCard(holder: RuleCardViews, rule: RuleConfig) {
        holder.titleText.text = rule.condition.label
        holder.descriptionText.text = when (rule.condition.type) {
            RuleConditionType.MOBILE_DEFAULT -> getString(R.string.rule_description_mobile)
            RuleConditionType.WIFI_ANY -> getString(R.string.rule_description_wifi_any)
            RuleConditionType.WIFI_SSID -> getString(R.string.rule_description_wifi_ssid)
            RuleConditionType.BLUETOOTH_ANY -> getString(R.string.rule_description_bluetooth_any)
            RuleConditionType.BLUETOOTH_DEVICE -> getString(R.string.rule_description_bluetooth_device)
        }
        holder.ringerModeSpinner.adapter = ringerModeAdapter

        if (rule.condition.type == RuleConditionType.MOBILE_DEFAULT) {
            holder.priorityInputLayout.visibility = View.GONE
        } else {
            holder.priorityInputLayout.visibility = View.VISIBLE
            holder.priorityEditText.setText(rule.priority.toString())
        }

        configureSlider(holder.mediaSlider, volumeLimits.mediaMax, rule.volumeProfile.media)
        configureSlider(holder.ringSlider, volumeLimits.ringMax, rule.volumeProfile.ring)
        configureSlider(
            holder.notificationSlider,
            volumeLimits.notificationMax,
            rule.volumeProfile.notification,
        )
        configureSlider(holder.alarmSlider, volumeLimits.alarmMax, rule.volumeProfile.alarm)
        holder.ringerModeSpinner.setSelection(rule.volumeProfile.ringerMode.ordinal)

        if (rule.condition.canDelete()) {
            holder.deleteButton.visibility = View.VISIBLE
            holder.deleteButton.setOnClickListener {
                syncEditableRulesFromViews()
                showDeleteRuleDialog(rule.id)
            }
        } else {
            holder.deleteButton.visibility = View.GONE
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
                        compareBy<RuleConfig> { it.condition.type == RuleConditionType.MOBILE_DEFAULT }
                            .thenBy { it.priority },
                    ),
                    reapplyMode = if (reapplySwitch.isChecked) {
                        ReapplyMode.ALWAYS
                    } else {
                        ReapplyMode.ON_CHANGE_ONLY
                    },
                )
                settingsRepository.saveSettings(settings)

                val activeRule = resolveActiveRule(settings)
                volumeController.applyProfile(activeRule.volumeProfile)
                settingsRepository.setActiveRuleState(activeRule)

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
            .filterNot { it.condition.type == RuleConditionType.MOBILE_DEFAULT }
            .map { it.priority }
        if (priorities.any { it < 1 }) {
            throw IllegalArgumentException(getString(R.string.toast_priority_invalid))
        }
        if (priorities.distinct().size != priorities.size) {
            throw IllegalArgumentException(getString(R.string.toast_priority_duplicate))
        }
        if (rules.count { it.condition.type == RuleConditionType.MOBILE_DEFAULT } != 1) {
            throw IllegalArgumentException(getString(R.string.toast_error))
        }
    }

    private fun showAddConditionDialog() {
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
                    addRule(candidates[which].condition)
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun buildAddableConditions(deviceState: DeviceState): List<AddConditionCandidate> {
        val existingRules = editableRules.toList()
        val candidates = mutableListOf<AddConditionCandidate>()

        if (deviceState.connectionState == ConnectionState.WIFI &&
            existingRules.none { it.condition.type == RuleConditionType.WIFI_ANY }
        ) {
            candidates += AddConditionCandidate(
                condition = RuleCondition(
                    type = RuleConditionType.WIFI_ANY,
                    label = getString(R.string.rule_label_wifi_any),
                ),
            )
        }

        deviceState.currentWifiSsid
            ?.takeIf { ssid ->
                existingRules.none {
                    it.condition.type == RuleConditionType.WIFI_SSID && it.condition.value == ssid
                }
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

        if (deviceState.connectedBluetoothDevices.isNotEmpty() &&
            existingRules.none { it.condition.type == RuleConditionType.BLUETOOTH_ANY }
        ) {
            candidates += AddConditionCandidate(
                condition = RuleCondition(
                    type = RuleConditionType.BLUETOOTH_ANY,
                    label = getString(R.string.rule_label_bluetooth_any),
                ),
            )
        }

        deviceState.connectedBluetoothDevices.forEach { device ->
            if (existingRules.none {
                    it.condition.type == RuleConditionType.BLUETOOTH_DEVICE &&
                        it.condition.value == device.address
                }
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

    private fun addRule(condition: RuleCondition) {
        val nextPriority = (
            editableRules
                .filterNot { it.condition.type == RuleConditionType.MOBILE_DEFAULT }
                .maxOfOrNull { it.priority } ?: 0
            ) + 1
        editableRules += RuleConfig(
            id = UUID.randomUUID().toString(),
            condition = condition,
            priority = nextPriority,
            volumeProfile = volumeController.readCurrentProfile(),
        )
        renderRules()
    }

    private fun showDeleteRuleDialog(ruleId: String) {
        val rule = editableRules.firstOrNull { it.id == ruleId } ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_rule_title)
            .setMessage(getString(R.string.dialog_delete_rule_message, rule.condition.label))
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                editableRules.removeAll { it.id == ruleId }
                renderRules()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private suspend fun resolveActiveRule(settings: AppSettings): RuleConfig {
        return ruleEvaluator.resolveActiveRule(settings, currentDeviceState())
    }

    private fun normalizeMobileRulePriority(rules: MutableList<RuleConfig>) {
        val mobileRuleIndex = rules.indexOfFirst { it.condition.type == RuleConditionType.MOBILE_DEFAULT }
        if (mobileRuleIndex == -1) {
            return
        }
        val fallbackPriority = (
            rules.filterNot { it.condition.type == RuleConditionType.MOBILE_DEFAULT }
                .maxOfOrNull { it.priority } ?: 0
            ) + 1
        rules[mobileRuleIndex] = rules[mobileRuleIndex].copy(priority = fallbackPriority)
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
        val mediaSlider: Slider,
        val ringSlider: Slider,
        val notificationSlider: Slider,
        val alarmSlider: Slider,
        val ringerModeSpinner: Spinner,
        val deleteButton: MaterialButton,
    )

    private data class AddConditionCandidate(
        val condition: RuleCondition,
    )

    private companion object {
        const val TAB_VOLUME_SETTINGS = 0
        const val TAB_GLOBAL_SETTINGS = 1
    }
}
