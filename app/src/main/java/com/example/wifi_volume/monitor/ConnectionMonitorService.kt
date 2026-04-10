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
import com.example.wifi_volume.model.ConditionStateRetentionPolicy
import com.example.wifi_volume.log.AppLog
import com.example.wifi_volume.model.DeviceState
import com.example.wifi_volume.network.ConnectionStateResolver
import com.example.wifi_volume.usecase.DeviceStateProvider
import com.example.wifi_volume.usecase.ElapsedTimeProvider
import com.example.wifi_volume.usecase.EvaluateAndApplyRuleUseCase
import com.example.wifi_volume.usecase.RuleChangeNotifier
import com.example.wifi_volume.usecase.SettingsStore
import com.example.wifi_volume.usecase.VolumeProfileApplier
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
    private lateinit var evaluateAndApplyRuleUseCase: EvaluateAndApplyRuleUseCase
    private var pendingEvaluationJob: Job? = null
    private var watchdogJob: Job? = null

    private var isNetworkCallbackRegistered = false
    private var isBluetoothReceiverRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            AppLog.i(LOG_AREA, "networkCallback.onAvailable")
            requestRuleEvaluation()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            AppLog.i(LOG_AREA, "networkCallback.onCapabilitiesChanged")
            requestRuleEvaluation()
        }

        override fun onLost(network: Network) {
            AppLog.i(LOG_AREA, "networkCallback.onLost")
            requestRuleEvaluation()
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppLog.i(LOG_AREA, "bluetoothReceiver.onReceive action=${intent?.action}")
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
        AppLog.i(LOG_AREA, "onCreate")
        settingsRepository = SettingsRepository(applicationContext)
        volumeController = VolumeController(applicationContext)
        connectionStateResolver = ConnectionStateResolver(applicationContext)
        bluetoothStateResolver = BluetoothStateResolver(applicationContext)
        evaluateAndApplyRuleUseCase = EvaluateAndApplyRuleUseCase(
            deviceStateProvider = object : DeviceStateProvider {
                override suspend fun getCurrentDeviceState(): DeviceState {
                    val connectionSnapshot = connectionStateResolver.resolveSnapshot()
                    return DeviceState(
                        connectionState = connectionSnapshot.connectionState,
                        currentWifiSsid = connectionSnapshot.currentWifiSsid,
                        connectedBluetoothDevices = bluetoothStateResolver.getConnectedDevices(),
                    )
                }
            },
            settingsStore = object : SettingsStore {
                override suspend fun getSettings() = settingsRepository.getSettings()

                override suspend fun getActiveRuleState() = settingsRepository.getActiveRuleState()

                override suspend fun setActiveRuleState(ruleId: String, label: String) {
                    settingsRepository.setActiveRuleState(ruleId, label)
                }
            },
            volumeProfileApplier = object : VolumeProfileApplier {
                override fun applyProfile(profile: com.example.wifi_volume.model.VolumeProfile) {
                    volumeController.applyProfile(profile)
                }
            },
            ruleChangeNotifier = object : RuleChangeNotifier {
                override fun notifyRuleChanged(ruleLabel: String) {
                    showRuleChangeNotification(ruleLabel)
                }
            },
            elapsedTimeProvider = ElapsedTimeProvider {
                android.os.SystemClock.elapsedRealtime()
            },
            conditionStateRetentionPolicy = ConditionStateRetentionPolicy(
                WIFI_RULE_HOLD_MS,
            ),
        )

        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_initializing)),
        )
        serviceScope.launch {
            settingsRepository.activeRuleStateFlow.collectLatest { activeRuleState ->
                AppLog.d(LOG_AREA, "activeRuleStateFlow: ruleId=${activeRuleState.ruleId} label=${activeRuleState.label}")
                val message = activeRuleState.label?.let {
                    getString(R.string.notification_active_rule, it)
                } ?: getString(R.string.notification_initializing)
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(message))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i(LOG_AREA, "onStartCommand flags=$flags startId=$startId")
        if (!isNetworkCallbackRegistered) {
            connectionStateResolver.connectivityManager.registerDefaultNetworkCallback(networkCallback)
            isNetworkCallbackRegistered = true
            AppLog.i(LOG_AREA, "registered default network callback")
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
            AppLog.i(LOG_AREA, "registered bluetooth receiver")
        }
        if (watchdogJob == null) {
            watchdogJob = serviceScope.launch {
                while (isActive) {
                    delay(WATCHDOG_INTERVAL_MS)
                    AppLog.d(LOG_AREA, "watchdog tick")
                    requestRuleEvaluation(immediate = true)
                }
            }
            AppLog.i(LOG_AREA, "started watchdog")
        }

        requestRuleEvaluation(immediate = true)
        return START_STICKY
    }

    override fun onDestroy() {
        AppLog.w(LOG_AREA, "onDestroy")
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
        AppLog.d(LOG_AREA, "requestRuleEvaluation immediate=$immediate")
        pendingEvaluationJob?.cancel()
        pendingEvaluationJob = serviceScope.launch {
            if (!immediate) {
                delay(EVENT_STABILIZE_DELAY_MS)
            }
            runCatching {
                evaluateAndApplyRuleUseCase.execute()
            }.onSuccess { result ->
                AppLog.i(
                    LOG_AREA,
                    "evaluation result=${result?.displayLabel} applied=${result?.appliedProfile} notified=${result?.notifiedRuleChange}",
                )
            }.onFailure { error ->
                AppLog.e(LOG_AREA, "rule evaluation failed", error)
            }
        }
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
        AppLog.i(LOG_AREA, "showRuleChangeNotification label=$ruleLabel")
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
        private const val LOG_AREA = "MonitorService"
        const val NOTIFICATION_CHANNEL_ID = "connection_monitor"
        const val NOTIFICATION_ID = 1001
        const val CHANGE_NOTIFICATION_CHANNEL_ID = "rule_change"
        const val CHANGE_NOTIFICATION_ID = 1002
        const val EVENT_STABILIZE_DELAY_MS = 1200L
        const val WATCHDOG_INTERVAL_MS = 10_000L
        const val WIFI_RULE_HOLD_MS = 5_000L
    }
}
