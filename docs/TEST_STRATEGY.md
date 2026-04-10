# wifi_volume テスト戦略

このドキュメントは、このプロジェクトでテストを増やしていくときの基準をまとめたものです。

目的は次の 4 つです。

1. `WSL` で回せるテストを最大化して、Codex の実装後に CLI で即検証できるようにする
2. `Windows` が必要なテストだけを明確に切り出し、実機中心で運用する
3. 通常利用データを壊さないように、実機テストを本番アプリと別領域で実行する
4. 実行コマンドを固定して、自動化しやすい運用にする

## 1. このプロジェクトでのレイヤー分担

```text
+------------------------------+  WSL
| JVM Local Test               |
| - pure model / usecase       |
| - JSON codec                 |
| - Robolectric の軽量UI検証   |
+---------------+--------------+
                |
                v
+------------------------------+  Windows 実機
| Instrumented Test            |
| - connectedE2eAndroidTest    |
| - MainActivity 操作          |
| - Service / Notification     |
| - permission / lifecycle     |
| - Wi-Fi / Bluetooth 周辺     |
+---------------+--------------+
                |
                v
+------------------------------+  Windows 実機
| Manual / Exploratory         |
| - 実機差分                   |
| - BT機器実接続               |
| - 権限ダイアログの最終確認   |
+------------------------------+
```

## 2. 一般的な Android テスト構成と、このプロジェクトでの採用方針

Android では大きく次の 3 層に分けるのが扱いやすいです。

- `Local unit test`
  - JVM 上で実行する
  - 速く、CI と Codex の自動実行に向く
- `Robolectric test`
  - JVM 上で Android `Context` や `resources` を伴うテストを補完する
  - エミュレーターなしで UI の一部や永続化の薄い結合を確認できる
- `Instrumented test`
  - 主に実機で実行する
  - 権限、Service、通知、ライフサイクル、OS 連携の最終確認に使う

このプロジェクトでは、次の方針を採用します。

- まず `WSL` の JVM テストで `model` / `usecase` / `data codec` を厚くする
- Android 依存が少しだけある部分は `Robolectric` で `WSL` に残す
- `MainActivity`、`ConnectionMonitorService`、権限、通知、接続イベントは `Windows` の instrumented test に寄せる
- instrumented test は `e2e` buildType の別 `applicationId` で実行し、本番データ領域を使わない

## 3. テスト用アプリ領域の分離

今回の最終方針では、通常利用アプリと実機テスト用アプリを分けます。

```text
+----------------------------------------------+
| 通常利用                                     |
| applicationId = com.example.wifi_volume      |
| DataStore / アプリデータ = 本番領域          |
+----------------------+-----------------------+
                       |
                       | 実機テスト時だけ切替
                       v
+----------------------------------------------+
| 実機テスト                                   |
| buildType = e2e                              |
| applicationId = com.example.wifi_volume.e2e  |
| DataStore / アプリデータ = テスト専用領域    |
+----------------------------------------------+
```

この方式を採る理由は次です。

- 通常利用データを退避・削除・復元する方式は `DataStore` の同一プロセス状態保持と相性が悪い
- `DataStore` 名を変えるより、`applicationId` 単位でアプリ領域ごと分離する方が設計として自然
- `assembleDebug` は引き続き既存アプリへ上書きできる
- `connectedE2eAndroidTest` だけがテスト専用アプリを使う

あわせて次を前提にします。

- `testBuildType = "e2e"`
- `Android Test Orchestrator`
- `testInstrumentationRunnerArguments["clearPackageData"] = "true"`

## 4. レイヤー別の対象

### 4-1. WSL で自動化する対象

対象:

- `app/src/test/java/com/example/wifi_volume/model`
  - ルール優先順位
  - fallback 判定
  - Wi-Fi / Bluetooth 条件評価
  - 一時的な SSID 不明時の保持ロジック
- `app/src/test/java/com/example/wifi_volume/usecase`
  - 適用判定
  - 再適用モード
  - 通知発火条件
- `app/src/test/java/com/example/wifi_volume/data`
  - JSON encode / decode
  - legacy 形式の復元
- `Robolectric`
  - `resources` 利用
  - `Context` を要する軽量テスト
  - `SettingsRepository` 初期化や `MainActivity` の最小表示確認の追加候補

追加優先度が高いテスト:

1. `SettingsRepository` の初期化と migration
2. `EvaluateAndApplyRuleUseCase` の異常系
3. `ConditionStateRetentionPolicy` の境界値
4. `RuleEvaluator` の複合条件ケース

### 4-2. Windows で自動化する対象

対象:

- `app/src/androidTest`
  - `MainActivity` の保存操作
  - タブ切替と入力 UI
  - 権限ボタン押下からの導線
  - `ConnectionMonitorService` 起動と通知表示
  - foreground service 維持
  - `BroadcastReceiver` / `NetworkCallback` を契機にした再評価の流れ

追加優先度が高いテスト:

1. `MainActivity` で設定保存後に一覧へ反映されること
2. 通知 ON 時にルール変更通知が表示されること
3. `ConnectionMonitorService` 起動時に foreground notification が出ること
4. 権限未付与時の設定画面誘導

運用上の前提:

- `androidTest` は `e2e` アプリ領域で実行する
- 既存ユーザー設定を前提にしない
- テスト結果は `test-logs/windows` の UTF-8 ログで確認する

### 4-3. Windows 手動確認に残す対象

対象:

- 実際の Wi-Fi SSID 切替
- 実 Bluetooth 機器との接続切替
- OEM 差分が出やすい権限挙動
- 通知チャネルやバックグラウンド制限の端末差

## 5. 実行コマンド

### 5-1. WSL

`/mnt/d/project/android/scripts/test-wsl-unit.sh`

デフォルトでは `testDebugUnitTest` を実行します。

```bash
bash scripts/test-wsl-unit.sh
```

特定タスクを渡すこともできます。

```bash
bash scripts/test-wsl-unit.sh testDebugUnitTest
```

### 5-2. Windows

`/mnt/d/project/android/scripts/test-windows-instrumented.ps1`

接続済み実機を優先し、`connectedE2eAndroidTest` を実行します。

```powershell
.\scripts\test-windows-instrumented.ps1
```

通常利用アプリを実機へ上書きする場合:

```powershell
.\gradlew.bat assembleDebug --no-daemon --console=plain
adb -s <device-id> install -r .\app\build\outputs\apk\debug\app-debug.apk
```

テスト用アプリを手動で入れたい場合:

```powershell
.\gradlew.bat assembleE2e --no-daemon --console=plain
adb -s <device-id> install -r .\app\build\outputs\apk\e2e\app-e2e.apk
```

## 6. 自動化ポリシー

- Codex 実装後の既定確認は `WSL` の `testDebugUnitTest`
- PR 前の自動確認は最低でも次の 2 本
  - `WSL`: `testDebugUnitTest`
  - `Windows`: 実機接続での `connectedE2eAndroidTest`
- `androidTest` は件数を絞り、重い検証を詰め込みすぎない
- ビジネスルールを `androidTest` に持ち込まず、できる限り `usecase` / `model` 側へ押し戻す

## 7. 今回入れた基盤

- `app/build.gradle.kts`
  - `Robolectric` を導入
  - local unit test で Android resources を利用可能化
  - `e2e` buildType を追加
  - `testBuildType = "e2e"` を設定
  - `Android Test Orchestrator` を導入
- `app/src/test/java/com/example/wifi_volume/AndroidResourceSmokeTest.kt`
  - Robolectric が WSL の JVM テストで動くことを確認する smoke test
- `scripts/test-wsl-unit.sh`
  - WSL 用の標準実行スクリプト
- `scripts/test-windows-instrumented.ps1`
  - Windows 実機用の標準実行スクリプト
  - 既定で `connectedE2eAndroidTest` を実行
  - UTF-8 ログを `test-logs/windows` に保存
