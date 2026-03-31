/**
 * 実際の音量読み取りと音量変更を担当するファイルです。
 *
 * 役割:
 * - 端末の現在音量を読み取って [VolumeProfile] に変換する
 * - 各ストリームの最大値を取得して UI のスライダー範囲に渡す
 * - 保存済み [VolumeProfile] を端末へ適用する
 *
 * 関係する主なファイル:
 * - [MainActivity]: 初期値表示、保存直後の即時適用、スライダー上限取得で使う
 * - [ConnectionMonitorService]: 自動切替で選ばれた設定を反映する
 * - [VolumeProfile.kt]: 入出力に使う音量データ型の定義元
 *
 * Android に不慣れな人向けの見方:
 * - 「音量そのものを触る場所」はこのファイルだけ
 * - UI や監視処理から AudioManager を直接触らせないように分離している
 */
package com.example.wifi_volume.audio

import android.content.Context
import android.media.AudioManager
import com.example.wifi_volume.model.RingerModeOption
import com.example.wifi_volume.model.VolumeLimits
import com.example.wifi_volume.model.VolumeProfile

class VolumeController(context: Context) {
    private val audioManager =
        context.getSystemService(AudioManager::class.java)
            ?: error("AudioManager is not available")

    fun readCurrentProfile(): VolumeProfile {
        return VolumeProfile(
            media = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
            ring = audioManager.getStreamVolume(AudioManager.STREAM_RING),
            notification = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
            alarm = audioManager.getStreamVolume(AudioManager.STREAM_ALARM),
            ringerMode = RingerModeOption.fromAudioManagerValue(audioManager.ringerMode),
        )
    }

    fun readLimits(): VolumeLimits {
        return VolumeLimits(
            mediaMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            ringMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING),
            notificationMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION),
            alarmMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
        )
    }

    fun applyProfile(profile: VolumeProfile) {
        setStreamVolume(AudioManager.STREAM_MUSIC, profile.media)
        setStreamVolume(AudioManager.STREAM_RING, profile.ring)
        setStreamVolume(AudioManager.STREAM_NOTIFICATION, profile.notification)
        setStreamVolume(AudioManager.STREAM_ALARM, profile.alarm)
        runCatching {
            audioManager.ringerMode = profile.ringerMode.audioManagerValue
        }
    }

    private fun setStreamVolume(streamType: Int, value: Int) {
        val boundedValue = value.coerceIn(0, audioManager.getStreamMaxVolume(streamType))
        runCatching {
            audioManager.setStreamVolume(streamType, boundedValue, 0)
        }
    }
}
