/**
 * アプリ全体で使う Logcat 出力をまとめるヘルパーです。
 *
 * 役割:
 * - ログタグの形式をそろえる
 * - 画面、サービス、接続判定、保存処理などのログを追いやすくする
 *
 * 関係する主なファイル:
 * - [MainActivity]: 画面起動、権限要求、保存処理の流れを記録する
 * - [ConnectionMonitorService]: 常駐監視の開始、イベント受信、再評価を記録する
 * - [EvaluateAndApplyRuleUseCase]: どの設定が選ばれたかを記録する
 *
 * Android に不慣れな人向けの見方:
 * - Android 開発では `println` より Logcat を使うのが一般的
 * - バグ調査では「いつ」「どの状態で」「どの処理が走ったか」をこのログで追う
 */
package com.example.wifi_volume.log

import android.util.Log

object AppLog {
    private const val TAG_PREFIX = "WifiVolume"

    fun d(area: String, message: String) {
        logFallback { Log.d(tag(area), message) }
    }

    fun i(area: String, message: String) {
        logFallback { Log.i(tag(area), message) }
    }

    fun w(area: String, message: String) {
        logFallback { Log.w(tag(area), message) }
    }

    fun w(area: String, message: String, throwable: Throwable) {
        logFallback { Log.w(tag(area), message, throwable) }
    }

    fun e(area: String, message: String, throwable: Throwable? = null) {
        logFallback { Log.e(tag(area), message, throwable) }
    }

    private fun tag(area: String): String {
        return "$TAG_PREFIX/$area"
    }

    private fun logFallback(block: () -> Unit) {
        runCatching(block).onFailure {
            // JVM unit test では android.util.Log がスタブ実装なので落ちることがある。
            // その場合はログ出力自体を諦めてテスト継続を優先する。
        }
    }
}
