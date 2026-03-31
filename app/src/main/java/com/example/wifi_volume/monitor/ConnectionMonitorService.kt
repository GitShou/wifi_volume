/**
 * 画面を閉じた後も動き続ける常駐監視サービスです。
 *
 * 役割:
 * - Wi-Fi / Bluetooth の接続変化を受け取る
 * - 保存済みルールと現在の接続状況を照合する
 * - 必要なら [VolumeController] で音量を変更し、通知表示も更新する
 *
 * 関係する主なファイル:
 * - [MainActivity]: アプリ起動時にこのサービスを開始する
 * - [SettingsRepository]: 保存済み設定の読み出しと、適用中設定の共有に使う
 * - [ConnectionStateResolver], [BluetoothStateResolver]: 現在の接続状況を取得する
 * - [VolumeProfile.kt]: [RuleEvaluator] を使って適用対象を決める
 *
 * Android に不慣れな人向けの見方:
 * - 「自動切替が実際に動く場所」はこのファイル
 * - 画面の設定を保存しただけでは自動では動かず、このサービスが監視して初めて切替が起きる
 */
package com.example.wifi_volume.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.wifi_volume.MainActivity
import com.example.wifi_volume.R
import com.example.wifi_volume.audio.VolumeController
import com.example.wifi_volume.bluetooth.BluetoothStateResolver
import com.example.wifi_volume.data.SettingsRepository
import com.example.wifi_volume.model.DeviceState
import com.example.wifi_volume.model.ReapplyMode
import com.example.wifi_volume.model.RuleConditionType
import com.example.wifi_volume.model.RuleConfig
import com.example.wifi_volume.model.RuleEvaluator
import com.example.wifi_volume.model.RuleEvaluator.ResolvedRule
import com.example.wifi_volume.network.ConnectionStateResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ConnectionMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var volumeController: VolumeController
    private lateinit var connectionStateResolver: ConnectionStateResolver
    private lateinit var bluetoothStateResolver: BluetoothStateResolver
    private val ruleEvaluator = RuleEvaluator()
    private var pendingEvaluationJob: Job? = null
    private var watchdogJob: Job? = null

    private var isNetworkCallbackRegistered = false
    private var isBluetoothReceiverRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            requestRuleEvaluation()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            requestRuleEvaluation()
        }

        override fun onLost(network: Network) {
            requestRuleEvaluation()
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothAdapter.ACTION_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                -> requestRuleEvaluation()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
        volumeController = VolumeController(applicationContext)
        connectionStateResolver = ConnectionStateResolver(applicationContext)
        bluetoothStateResolver = BluetoothStateResolver(applicationContext)

        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_initializing)),
        )
        serviceScope.launch {
            settingsRepository.activeRuleStateFlow.collectLatest { activeRuleState ->
                val message = activeRuleState.label?.let {
                    getString(R.string.notification_active_rule, it)
                } ?: getString(R.string.notification_initializing)
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(message))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isNetworkCallbackRegistered) {
            connectionStateResolver.connectivityManager.registerDefaultNetworkCallback(networkCallback)
            isNetworkCallbackRegistered = true
        }
        if (!isBluetoothReceiverRegistered) {
            registerReceiver(
                bluetoothReceiver,
                IntentFilter().apply {
                    addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                },
                RECEIVER_NOT_EXPORTED,
            )
            isBluetoothReceiverRegistered = true
        }
        if (watchdogJob == null) {
            watchdogJob = serviceScope.launch {
                while (isActive) {
                    delay(WATCHDOG_INTERVAL_MS)
                    requestRuleEvaluation(immediate = true)
                }
            }
        }

        requestRuleEvaluation(immediate = true)
        return START_STICKY
    }

    override fun onDestroy() {
        if (isNetworkCallbackRegistered) {
            connectionStateResolver.connectivityManager.unregisterNetworkCallback(networkCallback)
        }
        if (isBluetoothReceiverRegistered) {
            unregisterReceiver(bluetoothReceiver)
        }
        pendingEvaluationJob?.cancel()
        watchdogJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun requestRuleEvaluation(immediate: Boolean = false) {
        pendingEvaluationJob?.cancel()
        pendingEvaluationJob = serviceScope.launch {
            if (!immediate) {
                delay(EVENT_STABILIZE_DELAY_MS)
            }
            val settings = settingsRepository.getSettings() ?: return@launch
            val previousActiveRuleId = settingsRepository.getActiveRuleId()
            val connectionSnapshot = connectionStateResolver.resolveSnapshot()
            val deviceState = DeviceState(
                connectionState = connectionSnapshot.connectionState,
                currentWifiSsid = connectionSnapshot.currentWifiSsid,
                connectedBluetoothDevices = bluetoothStateResolver.getConnectedDevices(),
            )
            val activeRule = ruleEvaluator.resolveActiveRule(
                settings = settings,
                deviceState = deviceState,
            )
            val resolvedRule = retainPreviousWifiRuleIfNeeded(
                activeRule = activeRule,
                previousActiveRuleId = previousActiveRuleId,
                settings = settings,
                deviceState = deviceState,
            )
            val shouldApply = when (settings.reapplyMode) {
                ReapplyMode.ALWAYS -> true
                ReapplyMode.ON_CHANGE_ONLY -> settingsRepository.getActiveRuleId() != resolvedRule.rule.id
            }

            if (shouldApply) {
                volumeController.applyProfile(resolvedRule.rule.volumeProfile)
            }
            settingsRepository.setActiveRuleState(resolvedRule.rule.id, resolvedRule.displayLabel)
            if (settings.notifyOnRuleChange &&
                previousActiveRuleId != null &&
                previousActiveRuleId != resolvedRule.rule.id
            ) {
                showRuleChangeNotification(resolvedRule.displayLabel)
            }
        }
    }

    private fun retainPreviousWifiRuleIfNeeded(
        activeRule: ResolvedRule,
        previousActiveRuleId: String?,
        settings: com.example.wifi_volume.model.AppSettings,
        deviceState: DeviceState,
    ): ResolvedRule {
        if (!activeRule.rule.isFallback) {
            return activeRule
        }
        if (deviceState.connectionState != com.example.wifi_volume.network.ConnectionState.WIFI) {
            return activeRule
        }
        if (deviceState.currentWifiSsid != null) {
            return activeRule
        }

        val previousRule = settings.findRule(previousActiveRuleId)
            ?.takeIf(::hasWifiSpecificCondition)
            ?: return activeRule

        val fallbackLabel = previousRule.name.ifBlank {
            previousRule.conditions.firstOrNull { it.type == RuleConditionType.WIFI_SSID }?.label
                ?: activeRule.displayLabel
        }
        return ResolvedRule(
            rule = previousRule,
            matchedLabel = fallbackLabel,
            displayLabel = fallbackLabel,
        )
    }

    private fun hasWifiSpecificCondition(rule: RuleConfig): Boolean {
        return !rule.isFallback && rule.conditions.any { it.type == RuleConditionType.WIFI_SSID }
    }

    private fun buildNotification(message: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val monitorChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        val changeChannel = NotificationChannel(
            CHANGE_NOTIFICATION_CHANNEL_ID,
            getString(R.string.change_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        notificationManager.createNotificationChannels(listOf(monitorChannel, changeChannel))
    }

    private fun showRuleChangeNotification(ruleLabel: String) {
        getSystemService(NotificationManager::class.java).notify(
            CHANGE_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANGE_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.change_notification_title))
                .setContentText(getString(R.string.change_notification_message, ruleLabel))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val NOTIFICATION_CHANNEL_ID = "connection_monitor"
        const val NOTIFICATION_ID = 1001
        const val CHANGE_NOTIFICATION_CHANNEL_ID = "rule_change"
        const val CHANGE_NOTIFICATION_ID = 1002
        const val EVENT_STABILIZE_DELAY_MS = 1200L
        const val WATCHDOG_INTERVAL_MS = 10_000L
    }
}
